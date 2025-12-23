package example

import io.kiouring.file.IoUringFile
import io.netty.buffer.Unpooled
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.uring.IoUringIoHandler
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * Basic example demonstrating io_uring file I/O operations.
 */
fun main() {
    val group = MultiThreadIoEventLoopGroup(1, IoUringIoHandler.newFactory())
    val eventLoop = group.next()

    val file = Files.createTempFile("uring", ".dat")
        .apply { toFile().deleteOnExit() }
    val data = "hello-io-uring".toByteArray()

    val f = IoUringFile.open(
        file,
        eventLoop,
        StandardOpenOption.READ,
        StandardOpenOption.WRITE,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING
    ).get()

    val writeBuf = Unpooled.directBuffer(data.size).writeBytes(data)
    val readBuf = Unpooled.directBuffer(data.size)

    f.writeAsync(writeBuf, 0, true).get()
    f.readAsync(readBuf, 0).get()

    val out = ByteArray(data.size)
    readBuf.readBytes(out)

    assert(out.contentEquals(data)) { "out does not match data" }
    println("Written: ${data.toString(Charsets.UTF_8)}")
    println("Read:    ${out.toString(Charsets.UTF_8)}")

    readBuf.release()
    f.close()
    group.shutdownGracefully()
}
