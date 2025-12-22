package io.kiouring

import com.sun.nio.file.ExtendedOpenOption
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.absolutePathString

fun Path.cstr(): ByteBuf = absolutePathString().let { path ->
    Unpooled.directBuffer(path.length + 1).apply {
        writeBytes(path.toByteArray())
        writeByte('\u0000'.code)
    }
}

fun openFlags(vararg options: OpenOption): Int {
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
            StandardOpenOption.APPEND -> acc or Constant.O_APPEND
            StandardOpenOption.TRUNCATE_EXISTING -> acc or Constant.O_TURNC
            StandardOpenOption.CREATE -> acc or Constant.O_CREAT
            StandardOpenOption.CREATE_NEW -> acc or (Constant.O_CREAT or Constant.O_EXCL)
            StandardOpenOption.SYNC -> acc or Constant.O_SYNC
            StandardOpenOption.DSYNC -> acc or Constant.O_DSYNC
            ExtendedOpenOption.DIRECT -> acc or Constant.O_DIRECT
            else -> throw UnsupportedOperationException("$option not supported")
        }
    }

    val access =
        if (read && write) Constant.O_RDWR
        else if (write) Constant.O_WRONLY
        else Constant.O_RDONLY

    return flags or access
}
