package io.kiouring.util

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.util.internal.PlatformDependent
import java.nio.ByteBuffer

// Necessary for O_DIRECT
fun alignedByteBuf(size: Int): ByteBuf {
    val alignment = 4096
    val totalSize = size + alignment

    val raw = ByteBuffer.allocateDirect(totalSize)
    val baseAddress = PlatformDependent.directBufferAddress(raw)

    val remainder = (baseAddress % alignment).toInt()
    val offset = if (remainder == 0) 0 else alignment - remainder

    raw.position(offset)
    raw.limit(offset + size)
    val alignedSlice = raw.slice()

    // Prefill
    while (alignedSlice.hasRemaining()) alignedSlice.put(1.toByte())
    alignedSlice.clear()

    return Unpooled.wrappedBuffer(alignedSlice)
}
