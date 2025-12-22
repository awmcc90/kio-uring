package io.kiouring

import io.netty.channel.unix.Errors
import java.util.concurrent.CompletableFuture

class IoUringFileIoFuture(private val op: Byte) : CompletableFuture<Int>() {

    // Translate the syscall result immediately, avoiding a chained future
    override fun complete(result: Int): Boolean {
        return if (result < 0) {
            val exception = Errors.newIOException("op: $op", result)
            super.completeExceptionally(exception)
        } else {
            super.complete(result)
        }
    }
}
