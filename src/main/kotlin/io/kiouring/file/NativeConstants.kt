package io.kiouring.file

object NativeConstants {

    object IoRingOp {
        const val NOP: Byte             = 0
        const val READV: Byte           = 1
        const val WRITEV: Byte          = 2
        const val FSYNC: Byte           = 3
        const val READ_FIXED: Byte      = 4
        const val WRITE_FIXED: Byte     = 5
        const val POLL_ADD: Byte        = 6
        const val POLL_REMOVE: Byte     = 7
        const val SYNC_FILE_RANGE: Byte = 8
        const val SENDMSG: Byte         = 9
        const val RECVMSG: Byte         = 10
        const val TIMEOUT: Byte         = 11
        const val TIMEOUT_REMOVE: Byte  = 12
        const val ACCEPT: Byte          = 13
        const val ASYNC_CANCEL: Byte    = 14
        const val LINK_TIMEOUT: Byte    = 15
        const val CONNECT: Byte         = 16
        const val FALLOCATE: Byte       = 17
        const val OPENAT: Byte          = 18
        const val CLOSE: Byte           = 19
        const val FILES_UPDATE: Byte    = 20
        const val STATX: Byte           = 21
        const val READ: Byte            = 22
        const val WRITE: Byte           = 23
        const val FADVISE: Byte         = 24
        const val MADVISE: Byte         = 25
        const val SPLICE: Byte          = 28
        const val TEE: Byte             = 33
        const val SHUTDOWN: Byte        = 34
        const val RENAMEAT: Byte        = 35
        const val UNLINKAT: Byte        = 36
        const val MKDIRAT: Byte         = 37
    }

    object OpenFlags {
        const val RDONLY: Int    = 0x00000000
        const val WRONLY: Int    = 0x00000001
        const val RDWR: Int      = 0x00000002

        const val CREAT: Int     = 0x00000040
        const val EXCL: Int      = 0x00000080
        const val NOCTTY: Int    = 0x00000100
        const val TRUNC: Int     = 0x00000200
        const val APPEND: Int    = 0x00000400
        const val NONBLOCK: Int  = 0x00000800

        const val DSYNC: Int     = 0x00001000
        const val ASYNC: Int     = 0x00002000
        const val DIRECT: Int    = 0x00004000
        const val LARGEFILE: Int = 0x00008000
        const val DIRECTORY: Int = 0x00010000
        const val NOFOLLOW: Int  = 0x00020000
        const val NOATIME: Int   = 0x00040000
        const val CLOEXEC: Int   = 0x00080000
        const val SYNC: Int      = 0x00100000 or DSYNC
        const val PATH: Int      = 0x00200000
        const val TMPFILE: Int   = 0x00400000 or DIRECTORY
    }

    object FileMode {
        const val S_IRUSR: Int = 0x100
        const val S_IWUSR: Int = 0x080
        const val S_IXUSR: Int = 0x040
        const val S_IRWXU: Int = 0x1C0

        const val S_IRGRP: Int = 0x020
        const val S_IWGRP: Int = 0x010
        const val S_IXGRP: Int = 0x008
        const val S_IRWXG: Int = 0x038

        const val S_IROTH: Int = 0x004
        const val S_IWOTH: Int = 0x002
        const val S_IXOTH: Int = 0x001
        const val S_IRWXO: Int = 0x007

        const val DEFAULT_FILE: Int = S_IRUSR or S_IWUSR or S_IRGRP or S_IROTH
        const val DEFAULT_DIR: Int = S_IRWXU or S_IRGRP or S_IXGRP or S_IROTH or S_IXOTH
    }

    object RwFlags {
        const val HIPRI: Int  = 0x00000001
        const val DSYNC: Int  = 0x00000002
        const val SYNC: Int   = 0x00000004
        const val NOWAIT: Int = 0x00000008
        const val APPEND: Int = 0x00000010
    }

    object FsyncFlags {
        const val DATASYNC: Int = 1
    }

    object AsyncCancelFlags {
        const val ALL: Int = 1
        const val FD: Int  = 2
        const val ANY: Int = 4
    }

    object AtFlags {
        const val AT_REMOVEDIR: Int = 0x200
    }
}