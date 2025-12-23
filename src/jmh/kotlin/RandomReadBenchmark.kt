package benchmark

import com.sun.nio.file.ExtendedOpenOption
import io.kiouring.file.IoUringFileIoHandle
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.uring.IoUringIoHandler
import org.apache.logging.log4j.kotlin.Logging
import org.openjdk.jmh.annotations.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(
    value = 1,
    jvmArgs = [
        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED",
        "-Xms1G",
        "-Xmx1G",
        "-XX:+AlwaysPreTouch",
        "-XX:MaxDirectMemorySize=2G",
        "-Dio.netty.tryReflectionSetAccessible=true",
        "-Dio.netty.iouring.ringSize=4096",
        "-Dio.netty.noUnsafe=false",
        "-Dlog4j2.contextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector",
    ]
)
open class RandomReadBenchmark : Logging {

    private lateinit var group: MultiThreadIoEventLoopGroup
    private lateinit var ioUringFile: IoUringFileIoHandle
    private lateinit var channel: FileChannel

    @Setup(Level.Trial)
    fun setup(state: RandomIoState) {
        val path = Files.createTempFile("bench_read", ".dat")
            .apply { toFile().deleteOnExit() }

        FileChannel.open(path, StandardOpenOption.WRITE).use { ch ->
            val chunkBuf = ByteBuffer.allocateDirect(1024 * 1024) // 1MB
            while (chunkBuf.hasRemaining()) chunkBuf.put(0xFF.toByte())

            var written = 0L
            while (written < state.fileSize) {
                chunkBuf.clear()
                ch.write(chunkBuf, written)
                written += chunkBuf.capacity()
            }
            ch.force(true)
        }

        // io_uring
        group = MultiThreadIoEventLoopGroup(1, IoUringIoHandler.newFactory())
        ioUringFile = IoUringFileIoHandle.open(
            path = path,
            ioEventLoop = group.next(),
            options = arrayOf<OpenOption>(
                StandardOpenOption.READ,
                ExtendedOpenOption.DIRECT,
            )
        ).get()

        // Java nio baseline
        channel = FileChannel.open(
            path,
            StandardOpenOption.READ,
            ExtendedOpenOption.DIRECT,
        )
    }

    @Benchmark
    fun ioUring_random_read(state: RandomIoState.Read) {
        val futures = state.futures
        val buffers = state.buffers
        val offsets = state.randomOffsets

        for (i in 0 until state.batchSize) {
            futures[i] = ioUringFile.readAsync(buffers[i].retain(), offsets[i])
        }

        for (i in 0 until state.batchSize) {
            futures[i]!!.join()
            buffers[i].release()
        }
    }

    @Benchmark
    fun fileChannel_random_read(state: RandomIoState.Read) {
        val nioBuffers = state.nioBuffers
        val offsets = state.randomOffsets

        for (i in 0 until state.batchSize) {
            channel.read(nioBuffers[i], offsets[i])
        }
    }

    @TearDown(Level.Trial)
    fun teardown() {
        channel.close()
        ioUringFile.close()
        group.shutdownGracefully().syncUninterruptibly()
    }
}