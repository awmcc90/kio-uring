package io.kiouring.file

import io.netty.buffer.ByteBuf
import io.netty.channel.IoEvent
import io.netty.channel.IoEventLoop
import io.netty.channel.IoRegistration
import io.netty.channel.unix.IovArray
import io.netty.channel.uring.IoUringIoEvent
import io.netty.channel.uring.IoUringIoHandle
import io.netty.channel.uring.IoUringIoOps
import io.netty.util.concurrent.ScheduledFuture
import java.io.IOException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.properties.Delegates
import org.apache.logging.log4j.kotlin.Logging

class IoUringFileIoHandle(
    private val ioEventLoop: IoEventLoop,
) : IoUringIoHandle {

    private var state: State = State.INITIALIZING
    private var closeSubmitted: Boolean = false
    private var openFuture: UncancellableFuture<IoUringFileIoHandle>? = null
    private val contextRegistry = AsyncOpRegistry()

    private lateinit var ioRegistration: IoRegistration
    private var fd by Delegates.notNull<Int>()

    private var stuckOpsCleanerTask: ScheduledFuture<*>? = null

    init {
        stuckOpsCleanerTask = ioEventLoop.scheduleAtFixedRate(
            this::checkStuckOps,
            1,
            1,
            TimeUnit.SECONDS,
        )
    }

    fun init(ioRegistration: IoRegistration): IoUringFileIoHandle {
        check(ioRegistration.isValid) { "IoRegistration is not valid" }
        this.ioRegistration = ioRegistration
        this.state = State.INITIALIZED
        return this
    }

    private fun isClosed() = state == State.CLOSING || state == State.CLOSED

    private fun checkStuckOps() {
        val stuckOps = contextRegistry.findStuckOps(OP_TIMEOUT_NS)

        if (stuckOps.isEmpty()) return

        logger.warn("Found ${stuckOps.size} stuck operations. Attempting cleanup.")

        for (ctx in stuckOps) {
            if (ctx.uringId == -1L) {
                // Never submitted to kernel (stuck in a queue? initialization?); safe to force release
                logger.warn("Force releasing unsubmitted op: ${ctx.id}")
                contextRegistry.release(ctx, TimeoutException("Op never submitted"))
            } else {
                // Kernel has it (something happened that shouldn't)
                logger.warn("Attempting to cancel stuck op: ${ctx.id}")
                submitCancel(ctx.id.toLong())
            }
        }
    }

    private inline fun withEventLoop(crossinline block: () -> Unit) {
        if (ioEventLoop.inEventLoop()) block() else ioEventLoop.execute { block() }
    }

    // Submit must happen on the event loop because contextLookup is not thread-safe.
    // If called off loop we schedule the submission and bridge completion into a result future.
    private inline fun internalSubmit(
        ctx: AsyncOpContext,
        opFactory: (ctx: AsyncOpContext) -> IoUringIoOps,
    ): SyscallFuture {
        if (isClosed()) {
            contextRegistry.release(ctx, IOException("Handle is closed"))
            return ctx.future
        }

        if (!ioRegistration.isValid) {
            contextRegistry.release(ctx, IllegalStateException("Registration is invalid"))
            return ctx.future
        }

        try {
            ctx.uringId = ioRegistration.submit(opFactory(ctx))
            if (ctx.uringId == -1L) {
                contextRegistry.release(ctx, IOException("io_uring submission failed (ring full?)"))
            }
        } catch (t: Throwable) {
            contextRegistry.release(ctx, t)
        }

        return ctx.future
    }

    private inline fun submitOnLoop(
        opCode: Byte,
        crossinline opFactory: (ctx: AsyncOpContext) -> IoUringIoOps,
    ): SyscallFuture {
        if (ioEventLoop.inEventLoop()) {
            if (isClosed()) {
                return SyscallFuture().apply {
                    fail(IOException("Handle is closed"))
                }
            }

            val ctx = contextRegistry.next(opCode)
            return internalSubmit(ctx, opFactory)
        }

        val proxy = SyscallFuture()
        ioEventLoop.execute {
            try {
                if (isClosed()) {
                    proxy.fail(IOException("Handle is closed"))
                    return@execute
                }

                val ctx = contextRegistry.next(opCode)
                internalSubmit(ctx, opFactory)
                    .onComplete { res, err ->
                        if (err != null) proxy.fail(err)
                        else proxy.complete(res)
                    }
            } catch (t: Throwable) {
                proxy.fail(t)
            }
        }

        return proxy
    }

    private fun open(pathCStr: ByteBuf, flags: Int): UncancellableFuture<IoUringFileIoHandle> {
        openFuture?.let { return it }

        val f = UncancellableFuture<IoUringFileIoHandle>()
        openFuture = f

        withEventLoop {
            if (isClosed()) {
                f.completeExceptionally(IOException("IoUringFileIoHandle is $state (op=open)"))
                return@withEventLoop
            }

            state = State.OPENING

            val openFuture = submitOnLoop(opCode = NativeConstants.IoRingOp.OPENAT) { ctx ->
                IoUringIoOps(
                    ctx.op, 0, 0, -1,
                    0L, pathCStr.memoryAddress(), 0, flags,
                    ctx.id, 0, 0, 0, 0L
                )
            }

            openFuture.onComplete { res, err ->
                if (err != null) {
                    state = State.INITIALIZED
                    f.completeExceptionally(err)
                } else {
                    fd = res
                    state = State.OPEN
                    f.complete(this)
                }
            }
        }

        return f
    }

    fun writeAsync(buffer: ByteBuf, offset: Long, dsync: Boolean = false): SyscallFuture =
        submitOnLoop(opCode = NativeConstants.IoRingOp.WRITE) { ctx ->
            IoUringIoOps(
                ctx.op, 0, 0, fd,
                offset, buffer.memoryAddress(), buffer.readableBytes(),
                if (dsync) NativeConstants.RwFlags.DSYNC else 0,
                ctx.id, 0, 0, 0, 0L
            )
        }

    fun readAsync(buffer: ByteBuf, offset: Long): SyscallFuture =
        submitOnLoop(opCode = NativeConstants.IoRingOp.READ) { ctx ->
            IoUringIoOps(
                ctx.op, 0, 0, fd,
                offset, buffer.memoryAddress(), buffer.writableBytes(), 0,
                ctx.id, 0, 0, 0, 0L
            )
        }

    fun readvAsync(iovArray: IovArray, offset: Long): SyscallFuture =
        submitOnLoop(opCode = NativeConstants.IoRingOp.READV) { ctx ->
            IoUringIoOps(
                ctx.op, 0, 0, fd,
                offset, iovArray.memoryAddress(0), iovArray.count(), 0,
                ctx.id, 0, 0, 0, 0L
            )
        }

    fun writevAsync(iovArray: IovArray, offset: Long): SyscallFuture =
        submitOnLoop(opCode = NativeConstants.IoRingOp.WRITEV) { ctx ->
            IoUringIoOps(
                ctx.op, 0, 0, fd,
                offset, iovArray.memoryAddress(0), iovArray.count(), 0,
                ctx.id, 0, 0, 0, 0L
            )
        }

    fun fsyncAsync(isSyncData: Boolean, len: Int, offset: Long): SyscallFuture =
        submitOnLoop(opCode = NativeConstants.IoRingOp.FSYNC) { ctx ->
            IoUringIoOps(
                ctx.op, 0, 0, fd,
                offset, 0L, len,
                if (isSyncData) NativeConstants.FsyncFlags.DATASYNC else 0,
                ctx.id, 0, 0, 0, 0L
            )
        }

    private fun submitCancel(uringId: Long) {
        if (contextRegistry.isFull()) {
            logger.warn { "Registry full; cannot submit cancellation for $uringId" }
            return
        }

        submitOnLoop(NativeConstants.IoRingOp.ASYNC_CANCEL) { ctx ->
            IoUringIoOps(
                ctx.op, 0, 0, -1,
                0L, uringId, 0, 0,
                ctx.id, 0, 0, 0, 0L
            )
        }
    }

    private fun submitCancelAll() {
        if (contextRegistry.isEmpty()) {
            return
        }

        if (contextRegistry.isFull()) {
            logger.warn("Registry full; cannot submit cancel all operation")
            return
        }

        submitOnLoop(NativeConstants.IoRingOp.ASYNC_CANCEL) { ctx ->
            IoUringIoOps(
                ctx.op, 0, 0, fd,
                0L, 0L, 0,
                NativeConstants.AsyncCancelFlags.ALL or NativeConstants.AsyncCancelFlags.FD,
                ctx.id, 0, 0, 0, 0L
            )
        }
    }

    private fun submitCloseIfReady() {
        if (closeSubmitted || !contextRegistry.isEmpty() || state != State.CLOSING) return
        closeSubmitted = true

        val ops = IoUringIoOps(
            NativeConstants.IoRingOp.CLOSE, 0, 0, fd,
            0L, 0L, 0, 0,
            0, 0, 0, 0, 0L,
        )

        try {
            ioRegistration.submit(ops)
        } catch (t: Throwable) {
            logger.error("Failed to submit close operation", t)
            // Allow past this to force CLOSED state and registration cancellation
        }

        state = State.CLOSED

        // Safe to cancel after we submit close
        ioRegistration.cancel()
    }

    override fun handle(ioRegistration: IoRegistration, ioEvent: IoEvent) {
        val event = ioEvent as IoUringIoEvent

        contextRegistry.complete(event)

        // Check closing state
        submitCloseIfReady()
    }

    private enum class State {
        INITIALIZING,
        INITIALIZED,
        OPENING,
        OPEN,
        CLOSING,
        CLOSED,
    }

    @Throws(Exception::class)
    override fun close() {
        withEventLoop {
            stuckOpsCleanerTask?.cancel(false)
            stuckOpsCleanerTask = null

            when (state) {
                State.CLOSING, State.CLOSED -> return@withEventLoop
                else -> {
                    state = State.CLOSING
                    if (this::ioRegistration.isInitialized && ioRegistration.isValid) {
                        submitCancelAll()
                        submitCloseIfReady()
                    } else {
                        state = State.CLOSED
                    }
                }
            }
        }
    }

    companion object : Logging {
        private const val OP_TIMEOUT_NS = 30_000_000_000L

        @JvmStatic
        fun open(path: Path, ioEventLoop: IoEventLoop, vararg options: OpenOption): CompletableFuture<IoUringFileIoHandle> {
            require(!path.isDirectory()) { "file is directory" }
            require(path.exists()) { "file is not exists" }
            require(ioEventLoop.isCompatible(IoUringIoHandle::class.java)) {
                "ioEventLoop is not compatible with IoUringIoHandle"
            }

            val future = UncancellableFuture<IoUringFileIoHandle>()
            val ioUringIoFileHandle = IoUringFileIoHandle(ioEventLoop)
            ioEventLoop
                .register(ioUringIoFileHandle)
                .addListener {
                    if (!it.isSuccess) {
                        future.completeExceptionally(it.cause())
                        return@addListener
                    }

                    val pathCStr = OpenHelpers.cStr(path)
                    ioUringIoFileHandle
                        .init(it.get() as IoRegistration)
                        .open(pathCStr, OpenHelpers.openFlags(options))
                        .whenComplete { result: IoUringFileIoHandle, t: Throwable? ->
                            pathCStr.release()

                            if (t != null) {
                                future.completeExceptionally(t)
                                return@whenComplete
                            }

                            future.complete(result)
                        }
                }
            return future
        }
    }
}
