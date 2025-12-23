package io.kiouring.file

import io.netty.buffer.ByteBuf
import io.netty.channel.IoEventLoop
import io.netty.channel.unix.IovArray
import java.io.IOException
import java.lang.AutoCloseable
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import java.util.concurrent.CompletableFuture

class IoUringFile private constructor(
    private val ioUringIoHandle: IoUringFileIoHandle,
) : AutoCloseable {

    private fun ensureBuffer(buffer: ByteBuf, read: Boolean) {
        require(buffer.hasMemoryAddress()) { "Buffer is not direct" }
        require(if (read) buffer.isWritable else buffer.isReadable) { "Invalid buffer" }
    }

    fun readAsync(byteBuf: ByteBuf, offset: Long): CompletableFuture<Int> {
        ensureBuffer(byteBuf, true)
        val promise = UncancellableFuture<Int>()
        ioUringIoHandle.readAsync(byteBuf.retain(), offset)
            .onComplete { res, err ->
                try {
                    if (err != null) {
                        promise.completeExceptionally(err)
                    } else {
                        byteBuf.writerIndex(byteBuf.writerIndex() + res)
                        promise.complete(res)
                    }
                } catch (e: Throwable) {
                    promise.completeExceptionally(e)
                } finally {
                    byteBuf.release()
                }
            }
        return promise
    }

    fun readvAsync(offset: Long, buffers: Array<out ByteBuf>): CompletableFuture<Int> {
        val promise = UncancellableFuture<Int>()
        val iovArray = createSafeIovArray(buffers, true)
        ioUringIoHandle.readvAsync(iovArray, offset)
            .onComplete { res, err ->
                try {
                    iovArray.release()
                    if (err != null) {
                        promise.completeExceptionally(err)
                    } else {
                        progressBuffers(buffers, res, isRead = true)
                        promise.complete(res)
                    }
                } catch (e: Throwable) {
                    promise.completeExceptionally(e)
                } finally {
                    buffers.forEach { it.release() }
                }
            }

        return promise
    }

    fun writeAsync(byteBuf: ByteBuf, offset: Long, dsync: Boolean = false): CompletableFuture<Int> {
        ensureBuffer(byteBuf, false)
        val promise = UncancellableFuture<Int>()
        ioUringIoHandle.writeAsync(byteBuf.retain(), offset, dsync)
            .onComplete { res, err ->
                try {
                    if (err != null) {
                        promise.completeExceptionally(err)
                    } else {
                        byteBuf.readerIndex(byteBuf.readerIndex() + res)
                        promise.complete(res)
                    }
                } catch (e: Throwable) {
                    promise.completeExceptionally(e)
                } finally {
                    byteBuf.release()
                }
            }
        return promise
    }

    fun writevAsync(offset: Long, buffers: Array<out ByteBuf>): CompletableFuture<Int> {
        val promise = UncancellableFuture<Int>()
        val iovArray = createSafeIovArray(buffers, false)
        ioUringIoHandle.writevAsync(iovArray, offset)
            .onComplete { res, err ->
                try {
                    iovArray.release()
                    if (err != null) {
                        promise.completeExceptionally(err)
                    } else {
                        progressBuffers(buffers, res, isRead = false)
                        promise.complete(res)
                    }
                } catch (e: Throwable) {
                    promise.completeExceptionally(e)
                } finally {
                    buffers.forEach { it.release() }
                }
            }
        return promise
    }

    fun fsync(isSyncData: Boolean = false, len: Int = 0, offset: Long = 0): CompletableFuture<Int> {
        val promise = UncancellableFuture<Int>()
        ioUringIoHandle.fsyncAsync(isSyncData, len, offset)
            .onComplete { res, err ->
                if (err != null) promise.completeExceptionally(err)
                else promise.complete(res)
            }
        return promise
    }

    // Delete = unlink() + close()
    fun unlink(): CompletableFuture<Int> {
        val promise = UncancellableFuture<Int>()

        if (ioUringIoHandle.isAnonymous) {
            promise.completeExceptionally(
                IOException("Cannot delete anonymous file. Call close instead.")
            )
        } else {
            ioUringIoHandle.unlinkAsync()
                .onComplete { res, err ->
                    if (err != null) promise.completeExceptionally(err)
                    else promise.complete(res)
                }
        }

        return promise
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

        @JvmStatic
        fun open(
            path: Path,
            ioEventLoop: IoEventLoop,
            openOptions: Array<out OpenOption>,
            attrs: Array<out FileAttribute<*>> = emptyArray(),
        ): CompletableFuture<IoUringFile> {
            return IoUringFileIoHandle.open(path, ioEventLoop, openOptions, attrs)
                .thenApply {
                    IoUringFile(it)
                }
        }

        @JvmStatic
        fun openAnonymous(
            ioEventLoop: IoEventLoop,
            openOptions: Array<out OpenOption> = emptyArray(),
            attrs: Array<out FileAttribute<*>> = emptyArray(),
        ): CompletableFuture<IoUringFile> {
            return IoUringFileIoHandle.openAnonymous(ioEventLoop, openOptions, attrs)
                .thenApply {
                    IoUringFile(it)
                }
        }
    }
}
