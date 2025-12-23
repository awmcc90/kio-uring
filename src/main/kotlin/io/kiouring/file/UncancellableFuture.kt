package io.kiouring.file

import java.util.concurrent.CompletableFuture

class UncancellableFuture<T> : CompletableFuture<T>() {
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        return false
    }

    override fun obtrudeValue(value: T) {
        throw UnsupportedOperationException("Cannot obtrude value in IoUring future")
    }

    override fun obtrudeException(ex: Throwable?) {
        throw UnsupportedOperationException("Cannot obtrude exception in IoUring future")
    }
}
