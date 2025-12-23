package io.kiouring.util

import io.netty.util.concurrent.Future
import io.netty.util.concurrent.Promise
import java.util.concurrent.CompletableFuture

fun <T> CompletableFuture<T>.completeFrom(other: CompletableFuture<out T>) {
    other.handle { v, t ->
        if (t != null) {
            this.completeExceptionally(t)
        } else {
            this.complete(v)
        }
    }
}

fun <T> Future<T>.toCompletableFuture(): CompletableFuture<T> {
    val cf = CompletableFuture<T>()
    this.addListener { f ->
        if (f.isSuccess) {
            @Suppress("UNCHECKED_CAST")
            cf.complete(f.get() as T)
        } else {
            cf.completeExceptionally(f.cause())
        }
    }
    return cf
}

fun <T> CompletableFuture<T>.completeFrom(nettyFuture: Future<out T>) {
    nettyFuture.addListener { f ->
        if (f.isSuccess) {
            @Suppress("UNCHECKED_CAST")
            this.complete(f.get() as T)
        } else {
            this.completeExceptionally(f.cause())
        }
    }
}

fun <T> CompletableFuture<T>.completeInto(promise: Promise<T>) {
    this.handle { v, t ->
        if (t != null) {
            promise.setFailure(t)
        } else {
            promise.setSuccess(v)
        }
    }
}
