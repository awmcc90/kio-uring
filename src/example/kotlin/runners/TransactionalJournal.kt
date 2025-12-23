package example.runners

import io.kiouring.file.IoUringFile
import io.netty.buffer.PooledByteBufAllocator
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.uring.IoUringIoHandler
import io.netty.util.concurrent.DefaultThreadFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureTimeMillis
import org.apache.logging.log4j.kotlin.Logging

object TransactionalJournal : Logging {

    fun run(): CompletableFuture<Unit> {
        val group = MultiThreadIoEventLoopGroup(1, IoUringIoHandler.newFactory())
        val loop = group.next()

        val worker = Executors.newSingleThreadExecutor(
            DefaultThreadFactory("journal-benchmark-worker")
        )

        logger.info("Starting journal engine...")

        val promise = CompletableFuture<Unit>()

        fun shutdown(err: Throwable?) {
            worker.shutdown()

            group.shutdownGracefully()
                .addListener { f ->
                    if (err != null) promise.completeExceptionally(err)
                    else if (!f.isSuccess) promise.completeExceptionally(f.cause())
                    else promise.complete(Unit)
                }
        }

        IoUringFile.createTempFile(loop)
            .thenCompose { file ->
                CompletableFuture.supplyAsync({
                    runInternal(file).join()
                }, worker)
                    .handle { _, err ->
                        file.closeAsync().handle { _, closeErr ->
                            shutdown(err ?: closeErr)
                        }
                    }
                    .thenApply { }
            }
            .exceptionally { t ->
                logger.error("Failed to create temp file", t)
                shutdown(t)
            }

        return promise
    }

    private fun runInternal(file: IoUringFile): CompletableFuture<Void> {
        val totalRecords = 50_000_000L
        val recordSize = 128
        val recordsPerBatch = 128
        val batchSize = recordSize * recordsPerBatch

        val totalBatches = totalRecords / recordsPerBatch
        val actualTotalRecords = totalBatches * recordsPerBatch
        val maxInFlightBatches = 4096

        val completedBatches = AtomicLong(0)
        val finishedPromise = CompletableFuture<Void>()
        val backpressure = Semaphore(maxInFlightBatches)

        logger.info {
            "Writing $actualTotalRecords transactions in $totalBatches batches ($batchSize bytes per write)..."
        }

        val timeTaken = measureTimeMillis {
            for (i in 0 until totalBatches) {
                backpressure.acquire()

                val fileOffset = i * batchSize
                val buffer = PooledByteBufAllocator.DEFAULT.directBuffer(batchSize)

                for (r in 0 until recordsPerBatch) {
                    val recordId = (i * recordsPerBatch) + r
                    buffer.writeLong(recordId)
                    buffer.writeDouble(99.99)
                    buffer.writeLong(System.nanoTime())
                    buffer.writeZero(recordSize - 24)
                }

                file.writeAsync(buffer, fileOffset)
                    .handle { _, err ->
                        backpressure.release()

                        if (err != null) {
                            finishedPromise.completeExceptionally(err)
                        } else {
                            val c = completedBatches.incrementAndGet()

                            if (c % 40_000 == 0L) {
                                val progress = c * recordsPerBatch
                                logger.info("Progress: $progress / $actualTotalRecords records")
                            }

                            if (c == totalBatches) {
                                finishedPromise.complete(null)
                            }
                        }
                    }

                buffer.release()
            }

            finishedPromise.join()
        }

        return file.fsync().thenAccept {
            val bytesWritten = actualTotalRecords * recordSize
            val mb = bytesWritten / (1024 * 1024)
            val throughput = (mb * 1000) / timeTaken

            logger.info("--- Benchmark Results ---")
            logger.info("Status:      Success")
            logger.info("Time:        $timeTaken ms")
            logger.info("Total Size:  $mb MB")
            logger.info("Throughput:  $throughput MB/s")
            logger.info("-------------------------")
        }
    }
}
