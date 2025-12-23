package benchmark

import io.kiouring.file.SyscallFuture
import io.kiouring.util.alignedByteBuf
import io.netty.buffer.ByteBuf
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.nio.ByteBuffer

@State(Scope.Thread)
open class RandomIoState {
    @Param("1024")
    var batchSize: Int = 0

    @Param("4096")
    var bufferSize: Int = 0

    val fileSize = 1024L * 1024L * 1024L // 1GB

    lateinit var randomOffsets: LongArray
    lateinit var buffers: Array<ByteBuf>
    lateinit var nioBuffers: Array<ByteBuffer>
    lateinit var futures: Array<SyscallFuture?>

    @Setup(Level.Trial)
    fun setup() {
        futures = arrayOfNulls(batchSize)
        buffers = Array(batchSize) { alignedByteBuf(bufferSize) }
        nioBuffers = buffers.map { it.nioBuffer() }.toTypedArray()

        val maxBlockIndex = fileSize / 4096
        randomOffsets = LongArray(batchSize) {
            (0 until maxBlockIndex).random() * 4096
        }
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        buffers.forEach { it.release() }
    }

    open class Read : RandomIoState() {

        @Setup(Level.Invocation)
        fun reset() {
            for (i in 0 until batchSize) {
                buffers[i].clear()
                nioBuffers[i].clear()
            }
            randomOffsets.shuffle()
        }
    }

    open class Write : RandomIoState() {

        @Setup(Level.Invocation)
        fun reset() {
            for (i in 0 until batchSize) {
                buffers[i].setIndex(0, bufferSize)
                nioBuffers[i].clear()
            }
            randomOffsets.shuffle()
        }
    }
}
