package com.flexiatom.pixelraider.plat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * 规格 §八 那条红线的结构测试：**调用线程上不该发生任何写**。
 *
 * 用一个只收任务不执行的手动 executor，把"提交"与"落地"拆成两步断言。
 * 这样测的是分工而不是线程调度的运气——不去 sleep 等真线程，也不假装量得到真机的 fsync。
 */
public class AsyncKeyValueTest {

    /** 只排队不跑：{@link #runOne()} 才让"IO 线程"那半边发生。 */
    private static final class QueuedIo extends AbstractExecutorService {
        final Deque<Runnable> queue = new ArrayDeque<>();
        boolean shutDown;

        @Override public void execute(Runnable command) {
            if (shutDown) throw new IllegalStateException("已关闭");
            queue.add(command);
        }

        void runOne() {
            queue.poll().run();
        }

        @Override public void shutdown() { shutDown = true; while (!queue.isEmpty()) runOne(); }
        @Override public List<Runnable> shutdownNow() { List<Runnable> l = List.copyOf(queue); queue.clear(); shutDown = true; return l; }
        @Override public boolean isShutdown() { return shutDown; }
        @Override public boolean isTerminated() { return shutDown && queue.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return queue.isEmpty(); }
    }

    @Test
    public void nothingReachesTheTargetUntilTheIoTaskRuns() {
        MemStore target = new MemStore();
        QueuedIo io = new QueuedIo();
        AsyncKeyValue kv = new AsyncKeyValue(target, io);
        kv.putLong("k", 7);
        kv.flush();
        assertEquals("调用线程上一个写都没落地", 0, target.writes);
        assertEquals(0, target.flushes);
        assertEquals(1, io.queue.size());
        io.runOne();
        assertEquals(7L, target.getLong("k", 0));
        assertEquals(1, target.flushes);              // 一次 flush 只落一次盘
    }

    @Test
    public void emptyFlushSubmitsNoTask() {
        MemStore target = new MemStore();
        QueuedIo io = new QueuedIo();
        AsyncKeyValue kv = new AsyncKeyValue(target, io);
        kv.flush();
        kv.putLong("a", 1);
        kv.flush();
        kv.flush();                                   // 第二批已经交出去了，第三次无事可做
        assertEquals(1, io.queue.size());
    }

    @Test
    public void aWholeRecordShipsAsOneOrderedBatch() {
        MemStore target = new MemStore();
        QueuedIo io = new QueuedIo();
        AsyncKeyValue kv = new AsyncKeyValue(target, io);
        for (int i = 0; i < 7; i++) kv.putLong("k" + i, i);
        kv.flush();
        io.runOne();
        assertEquals(7, target.writes);
        assertEquals(1, target.flushes);
        assertEquals(6L, target.getLong("k6", 0));
    }

    @Test
    public void writesAfterAFlushStartANewBatch() {
        MemStore target = new MemStore();
        QueuedIo io = new QueuedIo();
        AsyncKeyValue kv = new AsyncKeyValue(target, io);
        kv.putLong("a", 1);
        kv.flush();
        io.runOne();
        kv.putLong("b", 2);
        kv.flush();
        io.runOne();
        assertEquals(1L, target.getLong("a", 0));
        assertEquals(2L, target.getLong("b", 0));
        assertEquals(2, target.flushes);
    }

    @Test
    public void readsGoStraightToTheMemoryMirror() {
        MemStore target = new MemStore();
        target.putLong("wallet", 42);
        QueuedIo io = new QueuedIo();
        AsyncKeyValue kv = new AsyncKeyValue(target, io);
        assertEquals(42L, kv.getLong("wallet", 0));   // 不等 IO
        assertEquals(0, io.queue.size());
        assertEquals(-1L, kv.getLong("missing", -1));
    }

    @Test
    public void shutdownDrainsWhatIsStillBuffered() {
        MemStore target = new MemStore();
        QueuedIo io = new QueuedIo();
        AsyncKeyValue kv = new AsyncKeyValue(target, io);
        kv.putLong("score", 900);
        kv.shutdown();
        assertTrue(io.isShutdown());
        assertEquals(900L, target.getLong("score", 0));
    }
}
