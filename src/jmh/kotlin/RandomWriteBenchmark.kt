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
open class RandomWriteBenchmark : Logging {

    private lateinit var group: MultiThreadIoEventLoopGroup
    private lateinit var ioUringFile: IoUringFileIoHandle
    private lateinit var channel: FileChannel

    @Setup(Level.Trial)
    fun setup() {
        val path = Files.createTempFile("bench_write", ".dat")
            .apply { toFile().deleteOnExit() }

        FileChannel.open(path, StandardOpenOption.WRITE).use { ch ->
            ch.write(ByteBuffer.wrap(byteArrayOf(0)), 1024L * 1024L * 1024L - 1)
            ch.force(true)
        }

        // io_uring
        group = MultiThreadIoEventLoopGroup(1, IoUringIoHandler.newFactory())
        ioUringFile = IoUringFileIoHandle.open(
            path,
            group.next(),
            options = arrayOf<OpenOption>(
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                ExtendedOpenOption.DIRECT,
            )
        ).get()

        // Java nio baseline
        channel = FileChannel.open(
            path,
            StandardOpenOption.WRITE,
            ExtendedOpenOption.DIRECT,
        )
    }

    @Benchmark
    fun ioUring_random_write(state: RandomIoState.Write) {
        val futures = state.futures
        val buffers = state.buffers
        val offsets = state.randomOffsets

        for (i in 0 until state.batchSize) {
            futures[i] = ioUringFile.writeAsync(buffers[i].retain(), offsets[i])
        }

        for (i in 0 until state.batchSize) {
            futures[i]!!.join()
            buffers[i].release()
        }
    }

    @Benchmark
    fun fileChannel_random_write(state: RandomIoState.Write) {
        val buffers = state.nioBuffers
        val offsets = state.randomOffsets

        for (i in 0 until state.batchSize) {
            channel.write(buffers[i], offsets[i])
        }
    }

    @TearDown(Level.Trial)
    fun teardown() {
        try {
            if (channel.isOpen) channel.force(false)
        } catch (e: Exception) {
            logger.error("Final sync failed", e)
        } finally {
            channel.close()
            ioUringFile.closeAsync().get()
            group.shutdownGracefully().syncUninterruptibly().get()
        }
    }
}
