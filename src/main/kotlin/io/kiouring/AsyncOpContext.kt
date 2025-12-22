package io.kiouring

class AsyncOpContext(val id: Short) {
    var op: Byte = 0
    var inUse: Boolean = false
    var future = IoUringFileIoFuture(op)
    var uringId: Long = -1

    fun reset(nextOp: Byte) {
        this.op = nextOp
        this.inUse = true
        this.future = IoUringFileIoFuture(nextOp)
    }
}
