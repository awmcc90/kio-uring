package example.runners

import io.kiouring.file.IoUringFile
import io.netty.buffer.Unpooled
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.uring.IoUringIoHandler
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * Basic example demonstrating io_uring file I/O operations.
 */
object BasicExample {
    fun run() {
        val group = MultiThreadIoEventLoopGroup(1, IoUringIoHandler.newFactory())
        val ioEventLoop = group.next()

        val tempDir = Paths.get(System.getProperty("java.io.tmpdir"))
        val fileName = "uring-${UUID.randomUUID()}.dat"
        val path = tempDir.resolve(fileName)

        val f = IoUringFile.open(
            path = path,
            ioEventLoop = ioEventLoop,
            openOptions = arrayOf(
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ),
        ).get()

        val data = "hello-io-uring".toByteArray()
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
        writeBuf.release()

        // Closes handle too
        f.delete().get()

        group.shutdownGracefully().get()
    }
}
