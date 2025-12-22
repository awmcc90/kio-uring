package io.kiouring

import io.netty.buffer.ByteBuf
import io.netty.channel.IoEventLoop
import io.netty.channel.IoRegistration
import io.netty.channel.unix.IovArray
import io.netty.channel.uring.IoUringIoHandle
import java.nio.file.OpenOption
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class IoUringFile private constructor(
    private val ioUringIoHandle: IoUringFileIoHandle,
) : AutoCloseable {

    private fun ensureBuffer(buffer: ByteBuf, read: Boolean) {
        require(buffer.hasMemoryAddress()) { "Buffer is not direct" }
        require(if (read) buffer.isWritable else buffer.isReadable) { "Invalid buffer" }
    }

    fun readAsync(byteBuf: ByteBuf, offset: Long): CompletableFuture<Int> {
        ensureBuffer(byteBuf, true)
        return ioUringIoHandle.readAsync(byteBuf.retain(), offset)
            .whenComplete { res: Int, t: Throwable? ->
                byteBuf.release()
                if (t == null) byteBuf.writerIndex(byteBuf.writerIndex() + res)
            }
    }

    fun readvAsync(offset: Long, buffers: Array<out ByteBuf>): CompletableFuture<Int> {
        val iovArray = createSafeIovArray(buffers, true)
        return ioUringIoHandle.readvAsync(iovArray, offset)
            .whenComplete { res: Int, t: Throwable? ->
                iovArray.release()
                if (t == null) progressBuffers(buffers, res, isRead = true)
                buffers.forEach { it.release() }
            }
    }

    fun writeAsync(byteBuf: ByteBuf, offset: Long): CompletableFuture<Int> {
        ensureBuffer(byteBuf, false)
        return ioUringIoHandle.writeAsync(byteBuf.retain(), offset)
            .whenComplete { res: Int, t: Throwable? ->
                byteBuf.release()
                if (t == null) byteBuf.readerIndex(byteBuf.readerIndex() + res)
            }
    }

    fun writevAsync(offset: Long, buffers: Array<out ByteBuf>): CompletableFuture<Int> {
        val iovArray = createSafeIovArray(buffers, false)
        return ioUringIoHandle.writevAsync(iovArray, offset)
            .whenComplete { res: Int, t: Throwable? ->
                iovArray.release()
                if (t == null) progressBuffers(buffers, res, isRead = false)
                buffers.forEach { it.release() }
            }
    }

    fun fsync(isSyncData: Boolean = false, len: Int = 0, offset: Long = 0): CompletableFuture<Int> {
        return ioUringIoHandle.fsyncAsync(isSyncData, len, offset)
    }

    private fun createSafeIovArray(buffers: Array<out ByteBuf>, isRead: Boolean): IovArray {
        val len = buffers.size
        require(len > 0) { "Buffers empty" }

        val iov = IovArray(buffers.size)
        for (i in 0 until len) {
            val b = buffers[i]
            require(b.hasMemoryAddress()) { "Not direct" }

            if (isRead) iov.add(b, b.writerIndex(), b.writableBytes())
            else iov.add(b, b.readerIndex(), b.readableBytes())

            b.retain()
        }
        return iov
    }

    private fun progressBuffers(buffers: Array<out ByteBuf>, syscallResult: Int, isRead: Boolean) {
        var remaining = syscallResult
        val iterator = buffers.iterator()
        while (iterator.hasNext() && remaining > 0) {
            val buf = iterator.next()
            val progress = if (isRead) {
                buf.writableBytes().coerceAtMost(remaining).also {
                    buf.writerIndex(buf.writerIndex() + it)
                }
            } else {
                buf.readableBytes().coerceAtMost(remaining).also {
                    buf.readerIndex(buf.readerIndex() + it)
                }
            }
            remaining -= progress
        }
    }

    @Throws(Exception::class)
    override fun close() {
        ioUringIoHandle.close()
    }

    companion object {
        fun open(path: Path, ioEventLoop: IoEventLoop, vararg options: OpenOption): CompletableFuture<IoUringFile> {
            require(!path.isDirectory()) { "file is directory" }
            require(path.exists()) { "file is not exists" }
            require(ioEventLoop.isCompatible(IoUringIoHandle::class.java)) {
                "ioEventLoop is not compatible with IoUringIoHandle"
            }

            val future = CompletableFuture<IoUringFile>()
            val ioUringIoFileHandle = IoUringFileIoHandle(ioEventLoop)
            ioEventLoop
                .register(ioUringIoFileHandle)
                .addListener {
                    if (!it.isSuccess) {
                        future.completeExceptionally(it.cause())
                        return@addListener
                    }

                    val pathCStr = path.cstr()
                    ioUringIoFileHandle
                        .init(it.get() as IoRegistration)
                        .open(pathCStr, openFlags(*options))
                        .whenComplete { result: IoUringFileIoHandle, t: Throwable? ->
                            pathCStr.release()

                            if (t != null) {
                                future.completeExceptionally(t)
                                return@whenComplete
                            }

                            future.complete(IoUringFile(result))
                        }
                }
            return future
        }
    }
}
