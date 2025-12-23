## fio baseline

### Hardware

HP SSD EX920 1TB

### Random Read, 4K block, 30s test, Direct I/O, Queue Depth 64, 4 threads

This test fully saturates the disk used for testing.

#### Run

```bash

fio \
  --name=random_read_test \
  --ioengine=io_uring \
  --rw=randread \
  --bs=4k \
  --direct=1 \
  --size=1G \
  --numjobs=4 \
  --iodepth=64 \
  --group_reporting \
  --runtime=30 \
  --time_based
```

#### Result
```bash
fio-3.36
Starting 4 processes
random_read_test: Laying out IO file (1 file / 1024MiB)
random_read_test: Laying out IO file (1 file / 1024MiB)
random_read_test: Laying out IO file (1 file / 1024MiB)
random_read_test: Laying out IO file (1 file / 1024MiB)
Jobs: 4 (f=4): [r(4)][100.0%][r=1220MiB/s][r=312k IOPS][eta 00m:00s]
random_read_test: (groupid=0, jobs=4): err= 0: pid=1532513: Mon Dec 22 04:44:32 2025
  read: IOPS=311k, BW=1215MiB/s (1274MB/s)(35.6GiB/30001msec)
    slat (usec): min=2, max=247, avg= 3.51, stdev= 2.35
    clat (usec): min=138, max=6554, avg=819.18, stdev=167.98
     lat (usec): min=142, max=6557, avg=822.69, stdev=167.89
    clat percentiles (usec):
     |  1.00th=[  457],  5.00th=[  562], 10.00th=[  611], 20.00th=[  685],
     | 30.00th=[  734], 40.00th=[  775], 50.00th=[  816], 60.00th=[  857],
     | 70.00th=[  898], 80.00th=[  947], 90.00th=[ 1020], 95.00th=[ 1090],
     | 99.00th=[ 1254], 99.50th=[ 1352], 99.90th=[ 1598], 99.95th=[ 1729],
     | 99.99th=[ 2507]
   bw (  MiB/s): min= 1124, max= 1239, per=100.00%, avg=1215.41, stdev= 4.16, samples=236
   iops        : min=287912, max=317402, avg=311144.27, stdev=1065.82, samples=236
  lat (usec)   : 250=0.01%, 500=2.16%, 750=31.06%, 1000=54.53%
  lat (msec)   : 2=12.23%, 4=0.01%, 10=0.01%
  cpu          : usr=12.08%, sys=26.77%, ctx=1883548, majf=0, minf=286
  IO depths    : 1=0.1%, 2=0.1%, 4=0.1%, 8=0.1%, 16=0.1%, 32=0.1%, >=64=100.0%
     submit    : 0=0.0%, 4=100.0%, 8=0.0%, 16=0.0%, 32=0.0%, 64=0.0%, >=64=0.0%
     complete  : 0=0.0%, 4=100.0%, 8=0.0%, 16=0.0%, 32=0.0%, 64=0.1%, >=64=0.0%
     issued rwts: total=9330535,0,0,0 short=0,0,0,0 dropped=0,0,0,0
     latency   : target=0, window=0, percentile=100.00%, depth=64

Run status group 0 (all jobs):
   READ: bw=1215MiB/s (1274MB/s), 1215MiB/s-1215MiB/s (1274MB/s-1274MB/s), io=35.6GiB (38.2GB), run=30001-30001msec

Disk stats (read/write):
  nvme0n1: ios=9320707/668, sectors=74565656/121216, merge=0/121, ticks=7416041/526, in_queue=7416584, util=67.58%
```

### Random Write, 4K block, 30s test, Direct I/O, Queue Depth 64, 4 threads

#### Run

```bash
fio \
  --name=random_write_test \
  --ioengine=io_uring \
  --rw=randwrite \
  --bs=4k \
  --direct=1 \
  --size=1G \
  --numjobs=4 \
  --iodepth=64 \
  --group_reporting \
  --runtime=30 \
  --time_based
```

#### Result

```bash
fio-3.36
Starting 4 processes
random_write_test: Laying out IO file (1 file / 1024MiB)
random_write_test: Laying out IO file (1 file / 1024MiB)
random_write_test: Laying out IO file (1 file / 1024MiB)
random_write_test: Laying out IO file (1 file / 1024MiB)
Jobs: 4 (f=4): [w(4)][100.0%][w=1026MiB/s][w=263k IOPS][eta 00m:00s]
random_write_test: (groupid=0, jobs=4): err= 0: pid=1578892: Mon Dec 22 15:22:01 2025
  write: IOPS=280k, BW=1092MiB/s (1145MB/s)(32.0GiB/30001msec); 0 zone resets
    slat (usec): min=2, max=995, avg= 4.28, stdev= 6.38
    clat (usec): min=99, max=137044, avg=910.74, stdev=1235.99
     lat (usec): min=103, max=137046, avg=915.02, stdev=1235.95
    clat percentiles (usec):
     |  1.00th=[  545],  5.00th=[  570], 10.00th=[  603], 20.00th=[  676],
     | 30.00th=[  701], 40.00th=[  766], 50.00th=[  930], 60.00th=[ 1029],
     | 70.00th=[ 1057], 80.00th=[ 1074], 90.00th=[ 1156], 95.00th=[ 1188],
     | 99.00th=[ 1369], 99.50th=[ 1483], 99.90th=[ 3261], 99.95th=[ 4686],
     | 99.99th=[83362]
   bw (  MiB/s): min=  869, max= 1169, per=100.00%, avg=1092.55, stdev=15.77, samples=236
   iops        : min=222714, max=299372, avg=279693.46, stdev=4037.17, samples=236
  lat (usec)   : 100=0.01%, 250=0.01%, 500=0.22%, 750=38.60%, 1000=18.81%
  lat (msec)   : 2=42.19%, 4=0.13%, 10=0.01%, 20=0.01%, 50=0.01%
  lat (msec)   : 100=0.02%, 250=0.01%
  cpu          : usr=6.97%, sys=31.24%, ctx=3469380, majf=0, minf=47
  IO depths    : 1=0.1%, 2=0.1%, 4=0.1%, 8=0.1%, 16=0.1%, 32=0.1%, >=64=100.0%
     submit    : 0=0.0%, 4=100.0%, 8=0.0%, 16=0.0%, 32=0.0%, 64=0.0%, >=64=0.0%
     complete  : 0=0.0%, 4=100.0%, 8=0.0%, 16=0.0%, 32=0.0%, 64=0.1%, >=64=0.0%
     issued rwts: total=0,8389640,0,0 short=0,0,0,0 dropped=0,0,0,0
     latency   : target=0, window=0, percentile=100.00%, depth=64

Run status group 0 (all jobs):
  WRITE: bw=1092MiB/s (1145MB/s), 1092MiB/s-1092MiB/s (1145MB/s-1145MB/s), io=32.0GiB (34.4GB), run=30001-30001msec

Disk stats (read/write):
  nvme0n1: ios=10/8359263, sectors=80/66912288, merge=0/4768, ticks=6/6124500, in_queue=6124631, util=77.24%
```

### JMH

```
Benchmark                                                          (batchSize)  (bufferSize)   Mode  Cnt      Score       Error   Units
RandomReadBenchmark.fileChannel_random_read                               1024          4096  thrpt    5     63.221 ±     1.091   ops/s
RandomReadBenchmark.fileChannel_random_read:·gc.alloc.rate                1024          4096  thrpt    5     ≈ 10⁻³              MB/sec
RandomReadBenchmark.fileChannel_random_read:·gc.alloc.rate.norm           1024          4096  thrpt    5      6.423 ±    20.270    B/op
RandomReadBenchmark.fileChannel_random_read:·gc.count                     1024          4096  thrpt    5        ≈ 0              counts
RandomReadBenchmark.ioUring_random_read                                   1024          4096  thrpt    5    305.508 ±     0.823   ops/s
RandomReadBenchmark.ioUring_random_read:·gc.alloc.rate                    1024          4096  thrpt    5     26.921 ±    19.320  MB/sec
RandomReadBenchmark.ioUring_random_read:·gc.alloc.rate.norm               1024          4096  thrpt    5  95044.736 ± 45032.766    B/op
RandomReadBenchmark.ioUring_random_read:·gc.count                         1024          4096  thrpt    5      3.000              counts
RandomReadBenchmark.ioUring_random_read:·gc.time                          1024          4096  thrpt    5     10.000                  ms
RandomWriteBenchmark.fileChannel_random_write                             1024          4096  thrpt    5    204.808 ±    13.118   ops/s
RandomWriteBenchmark.fileChannel_random_write:·gc.alloc.rate              1024          4096  thrpt    5     ≈ 10⁻³              MB/sec
RandomWriteBenchmark.fileChannel_random_write:·gc.alloc.rate.norm         1024          4096  thrpt    5      2.015 ±     6.031    B/op
RandomWriteBenchmark.fileChannel_random_write:·gc.count                   1024          4096  thrpt    5        ≈ 0              counts
RandomWriteBenchmark.ioUring_random_write                                 1024          4096  thrpt    5    270.217 ±    15.340   ops/s
RandomWriteBenchmark.ioUring_random_write:·gc.alloc.rate                  1024          4096  thrpt    5     22.965 ±    15.439  MB/sec
RandomWriteBenchmark.ioUring_random_write:·gc.alloc.rate.norm             1024          4096  thrpt    5  91775.808 ± 38087.400    B/op
RandomWriteBenchmark.ioUring_random_write:·gc.count                       1024          4096  thrpt    5      2.000              counts
RandomWriteBenchmark.ioUring_random_write:·gc.time                        1024          4096  thrpt    5      6.000                  ms
```

At 1024 batch size and 4096 buffer size in the benchmarks:

Random Read:

kio-uring: 305 * 1024 = 312320 IOPS & 305 * 1024 * 406 = 1220 MB/s
file channel: 64k * 1024 = 312320 IOPS & 63 * 1024 * 406 = 252 MB/s