package io.kiouring

import io.netty.channel.uring.IoUringIoEvent

class AsyncOpRegistry(private val maxInFlight: Int = 4096) : Iterable<AsyncOpContext> {

    private val contextPool: Array<AsyncOpContext>
    private val freeIndices: IntArray
    private var freeTop: Int = 0

    init {
        require(maxInFlight <= 65536) { "Cannot exceed 64k slots due to Short ID mapping" }

        freeIndices = IntArray(maxInFlight) { it }
        freeTop = maxInFlight - 1

        contextPool = Array(maxInFlight) { index ->
            val id = (Short.MIN_VALUE + index).toShort()
            AsyncOpContext(id)
        }
    }

    fun next(op: Byte): AsyncOpContext {
        check(freeTop >= 0) { "Registry full; too many in-flight ops" }

        val index = freeIndices[freeTop--]
        val ctx = contextPool[index]
        ctx.reset(op)

        return ctx
    }

    fun complete(event: IoUringIoEvent) {
        val id = event.data()
        val index = id - Short.MIN_VALUE

        if (index in 0 until maxInFlight) {
            val ctx = contextPool[index]

            if (ctx.inUse) {
                ctx.future.complete(event.res())
                ctx.inUse = false
                freeIndices[++freeTop] = index
            }
        }
    }

    fun isEmpty(): Boolean {
        return freeTop == maxInFlight - 1
    }

    override fun iterator(): Iterator<AsyncOpContext> {
        return object : Iterator<AsyncOpContext> {
            private var currentIdx = 0
            private var nextElement: AsyncOpContext? = null

            init {
                findNext()
            }

            private fun findNext() {
                nextElement = null
                while (currentIdx < maxInFlight) {
                    val ctx = contextPool[currentIdx++]
                    if (ctx.inUse) {
                        nextElement = ctx
                        break
                    }
                }
            }

            override fun hasNext(): Boolean = nextElement != null

            override fun next(): AsyncOpContext {
                val res = nextElement ?: throw NoSuchElementException()
                findNext()
                return res
            }
        }
    }
}
