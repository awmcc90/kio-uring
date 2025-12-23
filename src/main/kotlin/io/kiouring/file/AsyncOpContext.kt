package io.kiouring.file

class AsyncOpContext(val id: Short) {
    @Volatile var inUse: Boolean = false
    @Volatile var uringId: Long = -1

    var op: Byte = 0
    var startTime: Long = 0L

    val future = SyscallFuture()

    // NOTE: The order of the resets matters
    fun reset(op: Byte) {
        this.op = op
        this.future.reset(op)
        this.startTime = System.nanoTime()
        this.uringId = -1
        this.inUse = true
    }
}
