package io.kiouring.file

import com.sun.nio.file.ExtendedOpenOption
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.absolutePathString

object OpenHelpers {
    fun cStr(path: Path): ByteBuf = path.absolutePathString().let { path ->
        Unpooled.directBuffer(path.length + 1).apply {
            writeBytes(path.toByteArray())
            writeByte('\u0000'.code)
        }
    }

    fun openFlags(options: Array<out OpenOption>): Int {
        var read = false
        var write = false

        val flags = options.fold(0) { acc, option ->
            when (option) {
                StandardOpenOption.READ -> {
                    read = true
                    acc
                }
                StandardOpenOption.WRITE -> {
                    write = true
                    acc
                }
                StandardOpenOption.APPEND -> acc or NativeConstants.OpenFlags.APPEND
                StandardOpenOption.TRUNCATE_EXISTING -> acc or NativeConstants.OpenFlags.TRUNC
                StandardOpenOption.CREATE -> acc or NativeConstants.OpenFlags.CREAT
                StandardOpenOption.CREATE_NEW -> acc or (NativeConstants.OpenFlags.CREAT or NativeConstants.OpenFlags.EXCL)
                StandardOpenOption.SYNC -> acc or NativeConstants.OpenFlags.SYNC
                StandardOpenOption.DSYNC -> acc or NativeConstants.OpenFlags.DSYNC
                ExtendedOpenOption.DIRECT -> acc or NativeConstants.OpenFlags.DIRECT
                LinkOption.NOFOLLOW_LINKS -> acc or NativeConstants.OpenFlags.NOFOLLOW
                else -> throw UnsupportedOperationException("$option not supported")
            }
        }

        val access =
            if (read && write) NativeConstants.OpenFlags.RDWR
            else if (write) NativeConstants.OpenFlags.WRONLY
            else NativeConstants.OpenFlags.RDONLY

        return flags or access
    }
}
