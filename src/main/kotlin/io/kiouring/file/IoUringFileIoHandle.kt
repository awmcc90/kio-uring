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
import java.nio.file.Paths
import java.nio.file.attribute.FileAttribute
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.isDirectory
import kotlin.properties.Delegates
import org.apache.logging.log4j.kotlin.Logging

class IoUringFileIoHandle(
    val path: Path,
    private val ioEventLoop: IoEventLoop,
    private val flags: Int,
    private val mode: Int
) : IoUringIoHandle {

    private val contextRegistry = AsyncOpRegistry()

    private var state: State = State.INITIALIZING

    private var closeSubmitted: Boolean = false
    private val closeFuture: AtomicReference<UncancellableFuture<Int>?> = AtomicReference(null)
    private var openFuture: UncancellableFuture<IoUringFileIoHandle>? = null

    private lateinit var ioRegistration: IoRegistration
    private var fd by Delegates.notNull<Int>()

    val isAnonymous: Boolean = (flags and NativeConstants.OpenFlags.TMPFILE) == NativeConstants.OpenFlags.TMPFILE
    val isDirectory: Boolean = !isAnonymous && ((flags and NativeConstants.OpenFlags.DIRECTORY) == NativeConstants.OpenFlags.DIRECTORY)

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

    // NOTE: This should never throw and if it does that's a bug
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

            if (contextRegistry.isFull()) {
                return SyscallFuture().apply {
                    fail(IllegalStateException("Context registry is full"))
                }
            }

            // We know this can't throw due to the isFull check
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

    private fun open(pathCStr: ByteBuf): UncancellableFuture<IoUringFileIoHandle> {
        openFuture?.let { return it }

        val proxy = UncancellableFuture<IoUringFileIoHandle>()
        openFuture = proxy

        withEventLoop {
            if (isClosed()) {
                proxy.completeExceptionally(IOException("IoUringFileIoHandle is $state (op=open)"))
                return@withEventLoop
            }

            state = State.OPENING

            val f = submitOnLoop(opCode = NativeConstants.IoRingOp.OPENAT) { ctx ->
                IoUringIoOps(
                    ctx.op, 0, 0, -1,
                    0L, pathCStr.memoryAddress(), mode, flags,
                    ctx.id, 0, 0, 0, 0L
                )
            }

            f.onComplete { res, err ->
                if (err != null) {
                    state = State.INITIALIZED
                    proxy.completeExceptionally(err)
                } else {
                    fd = res
                    state = State.OPEN
                    proxy.complete(this)
                }
            }
        }

        return proxy
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

    fun unlinkAsync(): SyscallFuture {
        val proxy = SyscallFuture()
        val pathCStr = OpenHelpers.cStr(path)
        val f = submitOnLoop(opCode = NativeConstants.IoRingOp.UNLINKAT) { ctx ->
            IoUringIoOps(
                ctx.op, 0, 0, -1,
                0L, pathCStr.memoryAddress(), 0,
                if (isDirectory) NativeConstants.AtFlags.AT_REMOVEDIR else 0,
                ctx.id, 0, 0, 0, 0L
            )
        }
        f.onComplete { res, err ->
            pathCStr.release()
            if (err != null) proxy.fail(err)
            else proxy.complete(res)
        }
        return proxy
    }

    private fun submitCancel(uringId: Long) {
        if (contextRegistry.isFull()) {
            logger.warn { "Registry full; cannot submit cancellation for $uringId" }
            return
        }

        submitOnLoop(opCode = NativeConstants.IoRingOp.ASYNC_CANCEL) { ctx ->
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

        submitOnLoop(opCode = NativeConstants.IoRingOp.ASYNC_CANCEL) { ctx ->
            IoUringIoOps(
                ctx.op, 0, 0, fd,
                0L, 0L, 0,
                NativeConstants.AsyncCancelFlags.ALL or NativeConstants.AsyncCancelFlags.FD,
                ctx.id, 0, 0, 0, 0L
            )
        }
    }

    private fun submitCloseIfReady() {
        if (state != State.CLOSING || !contextRegistry.isEmpty() || closeSubmitted) return

        val ops = IoUringIoOps(
            NativeConstants.IoRingOp.CLOSE, 0, 0, fd,
            0L, 0L, 0, 0,
            Short.MAX_VALUE, 0, 0, 0, 0L,
        )

        try {
            val uringId = ioRegistration.submit(ops)
            if (uringId == -1L) throw IOException("Failed to submit close")
            closeSubmitted = true
        } catch (t: Throwable) {
            closeFuture.get()?.completeExceptionally(t)
            state = State.CLOSED
        }

        ioRegistration.cancel()
    }

    override fun handle(ioRegistration: IoRegistration, ioEvent: IoEvent) {
        val event = ioEvent as IoUringIoEvent

        if (event.data() == Short.MAX_VALUE && event.opcode() == NativeConstants.IoRingOp.CLOSE) {
            val res = event.res()
            val f = closeFuture.get() ?: run {
                logger.error {
                    "Received close event completion but close future isn't set. This should not happen."
                }
                return
            }

            if (res < 0) f.completeExceptionally(IOException("Close failed: $res"))
            else f.complete(res)

            return
        }

        contextRegistry.complete(event)
        submitCloseIfReady()
    }

    fun closeAsync(): CompletableFuture<Int> {
        closeFuture.get()?.let { return it }

        val promise = UncancellableFuture<Int>()
        if (closeFuture.compareAndSet(null, promise)) {
            withEventLoop {
                stuckOpsCleanerTask?.cancel(false)
                stuckOpsCleanerTask = null

                if (state != State.CLOSING && state != State.CLOSED) {
                    state = State.CLOSING
                    if (this::ioRegistration.isInitialized && ioRegistration.isValid) {
                        submitCancelAll()
                        submitCloseIfReady()
                    } else {
                        state = State.CLOSED
                        promise.complete(0)
                    }
                } else {
                    promise.complete(0)
                }
            }
            return promise
        }

        return closeFuture.get()!!
    }

    @Throws(Exception::class)
    override fun close() {
        closeAsync()
    }

    private enum class State {
        INITIALIZING,
        INITIALIZED,
        OPENING,
        OPEN,
        CLOSING,
        CLOSED,
    }

    companion object : Logging {
        private const val OP_TIMEOUT_NS = 30_000_000_000L

        private fun open(
            path: Path,
            ioEventLoop: IoEventLoop,
            flags: Int,
            mode: Int,
        ): CompletableFuture<IoUringFileIoHandle> {
            val future = UncancellableFuture<IoUringFileIoHandle>()
            val ioUringIoFileHandle = IoUringFileIoHandle(path, ioEventLoop,flags, mode)
            ioEventLoop
                .register(ioUringIoFileHandle)
                .addListener {
                    if (!it.isSuccess) {
                        future.completeExceptionally(it.cause())
                        return@addListener
                    }

                    val pathCStr = OpenHelpers.cStr(path)
                    try {
                        ioUringIoFileHandle
                            .init(it.get() as IoRegistration)
                            .open(pathCStr)
                            .handle { res, err ->
                                pathCStr.release()
                                if (err != null) future.completeExceptionally(err)
                                else future.complete(res)
                            }
                    } catch (t: Throwable) {
                        pathCStr.release()
                        future.completeExceptionally(t)
                    }
                }
            return future
        }

        @JvmStatic
        fun open(
            path: Path,
            ioEventLoop: IoEventLoop,
            options: Array<out OpenOption>,
            attrs: Array<out FileAttribute<*>> = emptyArray(),
        ): CompletableFuture<IoUringFileIoHandle> {
            require(!path.isDirectory()) { "file is directory" }
            require(ioEventLoop.isCompatible(IoUringIoHandle::class.java)) {
                "ioEventLoop is not compatible with IoUringIoHandle"
            }

            val flags = OpenHelpers.openFlags(options)
            val mode = OpenHelpers.fileMode(attrs)

            return open(path, ioEventLoop, flags, mode)
        }

        // Creates an anonymous temp file
        @JvmStatic
        fun createTempFile(
            ioEventLoop: IoEventLoop,
            options: Array<out OpenOption> = emptyArray(),
            attrs: Array<out FileAttribute<*>> = emptyArray(),
        ): CompletableFuture<IoUringFileIoHandle> {
            require(ioEventLoop.isCompatible(IoUringIoHandle::class.java)) {
                "ioEventLoop is not compatible with IoUringIoHandle"
            }

            val tmpDir = Paths.get(System.getProperty("java.io.tmpdir"))
            val userFlags = OpenHelpers.openFlags(options)

            // Enforce mandatory flags (must have O_TMPFILE)
            val hasWrite = (userFlags and NativeConstants.OpenFlags.WRONLY != 0) ||
                    (userFlags and NativeConstants.OpenFlags.RDWR != 0)

            val mandatoryAccess = if (hasWrite) 0 else NativeConstants.OpenFlags.RDWR

            val finalFlags = userFlags or NativeConstants.OpenFlags.TMPFILE or mandatoryAccess

            var mode = OpenHelpers.fileMode(attrs)
            if (mode == NativeConstants.FileMode.DEFAULT_FILE && attrs.isEmpty()) {
                mode = NativeConstants.FileMode.S_IRUSR or NativeConstants.FileMode.S_IWUSR
            }

            return open(tmpDir, ioEventLoop, finalFlags, mode)
        }
    }
}
