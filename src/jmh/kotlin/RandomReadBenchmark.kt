package benchmark

import com.sun.nio.file.ExtendedOpenOption
import io.kiouring.IoUringFile
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.uring.IoUringIoHandler
import org.apache.logging.log4j.kotlin.Logging
import org.openjdk.jmh.annotations.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture
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
    ]
)
open class RandomReadBenchmark : Logging {

    private lateinit var group: MultiThreadIoEventLoopGroup
    private lateinit var ioUringFile: IoUringFile
    private lateinit var channel: FileChannel

    @Setup(Level.Trial)
    fun setup(state: RandomIoState) {
        val path = Files.createTempFile("bench_read", ".dat")
            .apply { toFile().deleteOnExit() }

        FileChannel.open(path, StandardOpenOption.WRITE).use { fc ->
            val chunkBuf = ByteBuffer.allocateDirect(1024 * 1024) // 1MB
            while (chunkBuf.hasRemaining()) chunkBuf.put(0xFF.toByte())

            var written = 0L
            while (written < state.fileSize) {
                chunkBuf.clear()
                fc.write(chunkBuf, written)
                written += chunkBuf.capacity()
            }
            fc.force(true)
        }

        // io_uring
        group = MultiThreadIoEventLoopGroup(1, IoUringIoHandler.newFactory())
        ioUringFile = IoUringFile.open(
            path,
            group.next(),
            StandardOpenOption.READ,
            ExtendedOpenOption.DIRECT,
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
        val f = state.futures
        val buf = state.buffers
        val off = state.randomOffsets

        for (i in 0 until state.batchSize) {
            f[i] = ioUringFile.readAsync(buf[i], off[i])
        }

        CompletableFuture.allOf(*f).join()
    }

    @Benchmark
    fun fileChannel_random_read(state: RandomIoState.Read) {
        val buf = state.nioBuffers
        val off = state.randomOffsets

        for (i in 0 until state.batchSize) {
            channel.read(buf[i], off[i])
        }
    }

    @TearDown(Level.Trial)
    fun teardown() {
        channel.close()
        ioUringFile.close()
        group.shutdownGracefully().syncUninterruptibly()
    }
}
