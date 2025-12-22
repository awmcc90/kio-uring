package io.kiouring

import io.netty.buffer.ByteBuf
import io.netty.channel.IoEvent
import io.netty.channel.IoEventLoop
import io.netty.channel.IoRegistration
import io.netty.channel.unix.IovArray
import io.netty.channel.uring.IoUringIoEvent
import io.netty.channel.uring.IoUringIoHandle
import io.netty.channel.uring.IoUringIoOps
import org.apache.logging.log4j.kotlin.Logging
import java.io.IOException
import java.util.concurrent.CompletableFuture
import kotlin.properties.Delegates

class IoUringFileIoHandle(
    private val ioEventLoop: IoEventLoop,
) : IoUringIoHandle {

    private var state: State = State.INITIALIZING
    private var closeSubmitted: Boolean = false
    private var openFuture: CompletableFuture<IoUringFileIoHandle>? = null
    private val contextLookup = AsyncOpRegistry()

    private lateinit var ioRegistration: IoRegistration
    private var fd by Delegates.notNull<Int>()

    fun init(ioRegistration: IoRegistration): IoUringFileIoHandle {
        check(ioRegistration.isValid) { "IoRegistration is not valid" }
        this.ioRegistration = ioRegistration
        return this
    }

    private fun withEventLoop(block: () -> Unit) {
        if (ioEventLoop.inEventLoop()) block() else ioEventLoop.execute { block() }
    }

    // Submit must happen on the event loop because contextLookup is not thread-safe.
    // If called off loop we schedule the submission and bridge completion into a result future.
    private fun internalSubmit(
        opName: String,
        opCode: Byte,
        opFactory: (ctx: AsyncOpContext) -> IoUringIoOps,
    ): CompletableFuture<Int> {
        if (state == State.CLOSING || state == State.CLOSED) {
            return CompletableFuture.failedFuture(
                IOException(
                    "IoUringFileIoHandle is $state (op=$opName)"
                )
            )
        }

        if (!ioRegistration.isValid) {
            return CompletableFuture.failedFuture(
                IllegalStateException(
                    "ioRegistration is not valid for $opName"
                )
            )
        }

        val ctx = contextLookup.next(opCode)
        try {
            ctx.uringId = ioRegistration.submit(opFactory(ctx))
            if (ctx.uringId == -1L) {
                return CompletableFuture.failedFuture(
                    IOException(
                        "io_uring submission failed for $opName"
                    )
                )
            }
        } catch (e: Throwable) {
            return CompletableFuture.failedFuture(e)
        }

        return ctx.future
    }

    private fun submitOnLoop(
        opName: String,
        opCode: Byte,
        opFactory: (ctx: AsyncOpContext) -> IoUringIoOps,
    ): CompletableFuture<Int> {
        if (ioEventLoop.inEventLoop()) {
            return internalSubmit(opName, opCode, opFactory)
        }

        val bridge = CompletableFuture<Int>()
        ioEventLoop.execute {
            try {
                internalSubmit(opName, opCode, opFactory)
                    .whenComplete { res: Int, t: Throwable? ->
                        if (t != null) bridge.completeExceptionally(t)
                        else bridge.complete(res)
                    }
            } catch (e: Throwable) {
                bridge.completeExceptionally(e)
            }
        }
        return bridge
    }

    fun open(pathCStr: ByteBuf, flags: Int): CompletableFuture<IoUringFileIoHandle> {
        // Fast-path: return existing open future if already called
        openFuture?.let { return it }

        val f = CompletableFuture<IoUringFileIoHandle>()
        openFuture = f

        withEventLoop {
            if (state == State.CLOSING || state == State.CLOSED) {
                f.completeExceptionally(
                    IOException(
                        "IoUringFileIoHandle is $state (op=open)"
                    )
                )
                return@withEventLoop
            }

            if (state == State.INITIALIZING) state = State.INITIALIZED
            state = State.OPENING

            val openFuture = submitOnLoop(
                opName = "open",
                opCode = Constant.IORING_OP_OPENAT,
            ) { ctx ->
                IoUringIoOps(
                    ctx.op, 0, 0, -1,
                    0L, pathCStr.memoryAddress(), 0, flags,
                    ctx.id, 0, 0, 0, 0L
                )
            }

            openFuture.whenComplete { res: Int, t: Throwable? ->
                if (t != null) {
                    state = State.INITIALIZED
                    f.completeExceptionally(t)
                    return@whenComplete
                }
                if (res < 0) {
                    state = State.INITIALIZED
                    f.completeExceptionally(IOException("open failed (res=$res)"))
                    return@whenComplete
                }
                fd = res
                state = State.OPEN
                f.complete(this)
            }
        }

        return f
    }

    fun writeAsync(buffer: ByteBuf, offset: Long): CompletableFuture<Int> =
        submitOnLoop(opName = "write", opCode = Constant.IORING_OP_WRITE) { ctx ->
            IoUringIoOps(
                ctx.op, 0, 0, fd,
                offset, buffer.memoryAddress(), buffer.readableBytes(), 0,
                ctx.id, 0, 0, 0, 0L
            )
        }

    fun readAsync(buffer: ByteBuf, offset: Long): CompletableFuture<Int> =
        submitOnLoop(opName = "read", opCode = Constant.IORING_OP_READ) { ctx ->
            IoUringIoOps(
                ctx.op, 0, 0, fd,
                offset, buffer.memoryAddress(), buffer.writableBytes(), 0,
                ctx.id, 0, 0, 0, 0L
            )
        }

    fun readvAsync(iovArray: IovArray, offset: Long): CompletableFuture<Int> =
        submitOnLoop(opName = "readv", opCode = Constant.IORING_OP_READV) { ctx ->
            IoUringIoOps(
                ctx.op, 0, 0, fd,
                offset, iovArray.memoryAddress(0), iovArray.count(), 0,
                ctx.id, 0, 0, 0, 0L
            )
        }

    fun writevAsync(iovArray: IovArray, offset: Long): CompletableFuture<Int> =
        submitOnLoop(opName = "writev", opCode = Constant.IORING_OP_WRITEV) { ctx ->
            IoUringIoOps(
                ctx.op, 0, 0, fd,
                offset, iovArray.memoryAddress(0), iovArray.count(), 0,
                ctx.id, 0, 0, 0, 0L
            )
        }

    fun fsyncAsync(isSyncData: Boolean, len: Int, offset: Long): CompletableFuture<Int> =
        submitOnLoop(opName = "fsync", opCode = Constant.IORING_OP_FSYNC) { ctx ->
            IoUringIoOps(
                ctx.op, 0, 0, fd,
                offset, 0L, len, if (isSyncData) Constant.IORING_FSYNC_DATASYNC else 0,
                ctx.id, 0, 0, 0, 0L
            )
        }

    private fun submitCloseIfReady() {
        if (closeSubmitted) return
        if (!contextLookup.isEmpty()) return
        if (state != State.CLOSING) return

        closeSubmitted = true
        val ops = IoUringIoOps(
            Constant.IORING_OP_CLOSE, 0, 0, fd,
            0L, 0L, 0, 0,
            0, 0, 0, 0, 0L,
        )
        ioRegistration.submit(ops)
        state = State.CLOSED

        // Safe to cancel after we submit close
        ioRegistration.cancel()
    }

    private fun submitCancelAll() {
        contextLookup.forEachIndexed { i, it ->
            val ops = IoUringIoOps(
                Constant.IORING_OP_ASYNC_CANCEL, 0, 0, -1,
                0, it.uringId, 0, 0,
                i.toShort(), 0, 0, 0, 0
            )
            ioRegistration.submit(ops)
        }
    }

    override fun handle(ioRegistration: IoRegistration, ioEvent: IoEvent) {
        val event = ioEvent as IoUringIoEvent

        contextLookup.complete(event)

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

    private companion object : Logging
}
