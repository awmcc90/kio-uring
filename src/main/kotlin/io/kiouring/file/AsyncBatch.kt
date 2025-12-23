package io.kiouring.file

fun interface AsyncBatch {
    /**
     * Blocks the calling thread until all operations that were
     * active in the registry are complete.
     */
    fun await()
}