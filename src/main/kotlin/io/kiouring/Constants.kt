package io.kiouring

object Constant {
    const val O_RDONLY: Int = 0
    const val O_WRONLY: Int = 1
    const val O_RDWR: Int = 2
    const val O_CREAT: Int = 64
    const val O_EXCL: Int = 128
    const val O_TURNC: Int = 512
    const val O_APPEND: Int = 1024
    const val O_DIRECT: Int = 16384
    const val O_DSYNC: Int = 4096
    const val O_SYNC: Int = 1052672

    const val IORING_FSYNC_DATASYNC: Int = 1

    const val IORING_OP_READV: Byte = 1
    const val IORING_OP_WRITEV: Byte = 2
    const val IORING_OP_FSYNC: Byte = 3
    const val IORING_OP_ASYNC_CANCEL: Byte = 14
    const val IORING_OP_OPENAT: Byte = 18
    const val IORING_OP_CLOSE: Byte = 19
    const val IORING_OP_READ: Byte = 22
    const val IORING_OP_WRITE: Byte = 23
}
