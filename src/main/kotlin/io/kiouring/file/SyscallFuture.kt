package io.kiouring.file

import io.netty.channel.unix.Errors
import java.util.concurrent.locks.LockSupport
import org.apache.logging.log4j.kotlin.Logging

class SyscallFuture {
    @Volatile private var done: Boolean = false
    @Volatile private var waiter: Thread? = null
    @Volatile private var completionHandler: ((Int, Throwable?) -> Unit)? = null

    private var op: Byte = 0
    private var result: Int = 0
    private var exception: Throwable? = null

    // NOTE: The order of the resets matters
    fun reset(op: Byte) {
        this.op = op
        this.result = 0
        this.exception = null
        this.waiter = null
        this.completionHandler = null
        this.done = false
    }

    fun onComplete(handler: (Int, Throwable?) -> Unit) {
        if (this.done) {
            handler(this.result, this.exception)
        } else {
            check(this.completionHandler == null) {
                "SyscallFuture can only register one completionHandler"
            }
            this.completionHandler = handler
        }
    }

    fun complete(res: Int) {
        check(!done) { "Future is already completed" }

        this.result = res
        if (res < 0) {
            this.exception = Errors.newIOException("op: $op", res)
        }
        finish()
    }

    fun fail(cause: Throwable) {
        check(!done) { "Future is already completed" }

        this.exception = cause
        this.result = -1
        finish()
    }

    private fun finish() {
        this.done = true
        try {
            this.completionHandler?.invoke(this.result, this.exception)
        } catch (t: Throwable) {
            logger.fatal("User callback crashed due to an exception; this should never happen", t)
        }

        val w = this.waiter
        if (w != null) LockSupport.unpark(w)
    }

    fun join(): Int {
        if (this.done) return report()
        this.waiter = Thread.currentThread()
        while (!this.done) LockSupport.park(this)
        return report()
    }

    private fun report(): Int {
        if (this.exception != null) throw this.exception!!
        return this.result
    }

    private companion object : Logging
}
