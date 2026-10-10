/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * PIXEL RAIDER — 原生 Android 纵版弹幕射击
 * Copyright (C) 2026 FlexiAtom
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.flexiatom.pixelraider.plat;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 存档落盘层：{@link FileSaveSink} 的介质纪律 ＋ {@link AsyncSaveSink} 的线程纪律。
 *
 * <p>临时目录是真的、读写是真的（{@link FileSaveSink} 只收 {@link File}，不收 Context，
 * 为的就是这一格能在 JVM 里跑）。"覆盖要么完整要么不算写"和"读不到给 null 不给空数组"
 * 这两条只有对着真文件才测得出来。
 */
public class FileAndAsyncSaveSinkTest {

    private File dir;
    private FileSaveSink sink;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("pr-saves").toFile();
        sink = new FileSaveSink(dir);
    }

    @After
    public void tearDown() {
        deleteRecursively(dir);
    }

    // ---- FileSaveSink：介质面 ----------------------------------------------------------------

    @Test
    public void writtenBytesComeBackIdentically() {
        byte[] data = {1, 2, 3, (byte) 200, 0, 9};
        sink.write(0, data);
        assertTrue(sink.exists(0));
        assertArrayEquals(data, sink.read(0));
    }

    @Test
    public void emptyPayloadIsReadAsNullNotAsAnEmptyArray() {
        // 覆盖那条纪律的另一半：0 字节文件不算"有档"。空数组留给"真存了一份空快照"那种事，
        // 而 FileSaveSink 写不出 0 字节档（写出去是 0 长度文件，read 判 length<=0 → null）。
        sink.write(3, new byte[0]);
        assertNull(sink.read(3));
    }

    @Test
    public void missingSlotReadsNull() {
        assertFalse(sink.exists(7));
        assertNull(sink.read(7));
    }

    @Test
    public void overwriteReplacesTheWholePayload() {
        // 直接 FileOutputStream(true) 那种"追加"写法会被这条抓住：长度对不上。
        sink.write(1, new byte[]{9, 9, 9, 9, 9});
        sink.write(1, new byte[]{7, 7, 7});
        assertArrayEquals(new byte[]{7, 7, 7}, sink.read(1));
    }

    @Test
    public void aSuccessfulWriteLeavesNoTmpCarcass() {
        sink.write(2, new byte[]{5, 5});
        assertEquals("只剩那一份 .bin，不留 .tmp", Arrays.asList("2.bin"), namesIn(dir));
    }

    @Test
    public void deleteRemovesTheFileAndIsIdempotent() {
        sink.write(4, new byte[]{1});
        sink.delete(4);
        assertFalse(sink.exists(4));
        assertNull(sink.read(4));
        sink.delete(4);           // 删不存在的槽不是错误
        assertEquals(0, countFiles(dir));
    }

    @Test
    public void slotsAreIndependentFiles() {
        sink.write(0, new byte[]{1});
        sink.write(1, new byte[]{2, 2});
        assertArrayEquals(new byte[]{1}, sink.read(0));
        assertArrayEquals(new byte[]{2, 2}, sink.read(1));
        assertEquals(Arrays.asList("0.bin", "1.bin"), namesIn(dir));
    }

    @Test
    public void directoryIsCreatedOnDemand() {
        // MainActivity 只给一个路径，不保证它存在（首次存档那一刻 saves/v1 还没有）。
        File nested = new File(dir, "saves/v" + FileSaveSink.FORMAT_VERSION);
        FileSaveSink late = new FileSaveSink(nested);
        assertFalse(nested.exists());
        late.write(0, new byte[]{8});
        assertArrayEquals(new byte[]{8}, late.read(0));
    }

    @Test
    public void theFallbackSinkLiesAboutNothing() {
        SaveSink empty = SaveSink.EMPTY;
        empty.write(0, new byte[]{1});
        assertFalse("EMPTY 不能让『这一槽有档』看起来成立", empty.exists(0));
        assertNull(empty.read(0));
        empty.delete(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeSlotIsAProgrammingErrorNotADiskFile() {
        sink.write(-1, new byte[]{1});
    }

    // ---- AsyncSaveSink：线程面 ----------------------------------------------------------------

    /** 只收任务不跑的 executor：调用线程"什么都没发生"由此钉死，而不是靠读代码的印象。 */
    private static final class Recorded extends AbstractExecutorService {
        final List<Runnable> queued = new ArrayList<>();

        @Override public void execute(Runnable command) {
            queued.add(command);
        }

        @Override public void shutdown() { }

        @Override public List<Runnable> shutdownNow() {
            return queued;
        }

        @Override public boolean isShutdown() {
            return false;
        }

        @Override public boolean isTerminated() {
            return false;
        }

        @Override public boolean awaitTermination(long timeout, TimeUnit unit) {
            return false;
        }
    }

    /** 记录被调过什么的介质替身：只记不调真盘。 */
    private static final class Spy implements SaveSink {
        final List<String> calls = new ArrayList<>();
        volatile String callerThread;
        byte[] next;

        @Override public void write(int slot, byte[] data) {
            calls.add("write" + slot);
            callerThread = Thread.currentThread().getName();
        }

        @Override public byte[] read(int slot) {
            calls.add("read" + slot);
            callerThread = Thread.currentThread().getName();
            return next;
        }

        @Override public void delete(int slot) {
            calls.add("delete" + slot);
        }

        @Override public boolean exists(int slot) {
            calls.add("exists" + slot);
            return next != null;
        }
    }

    @Test
    public void submittingADiskTaskTouchesNothingOnTheCallingThread() {
        Spy spy = new Spy();
        Recorded io = new Recorded();
        AsyncSaveSink async = new AsyncSaveSink(spy, io);
        async.submitWrite(5, new byte[]{1});
        async.submitDelete(6);
        async.requestRead(7);
        assertEquals("登记阶段一次介质调用都没有", Arrays.asList(), spy.calls);
        assertEquals(3, io.queued.size());
    }

    @Test
    public void runningTheQueuedTaskIsWhenTheMediumGetsTouched() {
        Spy spy = new Spy();
        Recorded io = new Recorded();
        AsyncSaveSink async = new AsyncSaveSink(spy, io);
        async.submitWrite(5, new byte[]{42});
        io.queued.get(0).run();
        assertEquals(Arrays.asList("write5"), spy.calls);
    }

    @Test
    public void readComesBackThroughTheVolatileSlotAndIsTakenOnce() {
        Spy spy = new Spy();
        spy.next = new byte[]{3, 3};
        Recorded io = new Recorded();
        AsyncSaveSink async = new AsyncSaveSink(spy, io);
        assertNull("还没跑任务就没有结果", async.pollRead());
        async.requestRead(9);
        io.queued.get(0).run();
        AsyncSaveSink.Read r = async.pollRead();
        assertEquals(9, r.slot);
        assertArrayEquals(spy.next, r.data);
        assertNull("取走即清空——否则同一份档每帧还原一次", async.pollRead());
    }

    @Test
    public void readOfAnAbsentSlotComesBackAsNullDataNotNullSlot() {
        Spy spy = new Spy();               // spy.next 留 null = 那一槽没档
        Recorded io = new Recorded();
        AsyncSaveSink async = new AsyncSaveSink(spy, io);
        async.requestRead(2);
        io.queued.get(0).run();
        AsyncSaveSink.Read r = async.pollRead();
        assertEquals(2, r.slot);
        assertNull("回来的是『这一槽没档』这个事实，不是『还没回来』", r.data);
    }

    @Test
    public void readReadyAgreesWithPollRead() {
        Spy spy = new Spy();
        Recorded io = new Recorded();
        AsyncSaveSink async = new AsyncSaveSink(spy, io);
        assertFalse(async.readReady());
        async.requestRead(1);
        io.queued.get(0).run();
        assertTrue(async.readReady());
        async.pollRead();
        assertFalse(async.readReady());
    }

    @Test
    public void realPoolFinishesTheWriteOffTheCallingThread() throws Exception {
        // 反向证据：这一条用的是真单线程池，靠 shutdown()+awaitTermination 收敛，不靠睡眠。
        File to = Files.createTempDirectory("pr-async").toFile();
        try {
            FileSaveSink target = new FileSaveSink(to);
            AsyncSaveSink async = new AsyncSaveSink(target);
            async.submitWrite(11, new byte[]{1, 2, 3, 4});
            async.shutdown();
            assertArrayEquals(new byte[]{1, 2, 3, 4}, target.read(11));

            Spy spy = new Spy();
            AsyncSaveSink offThread = new AsyncSaveSink(spy);
            offThread.submitWrite(0, new byte[]{1});
            offThread.shutdown();
            assertEquals(Arrays.asList("write0"), spy.calls);
            assertFalse("[规格 §八] 写盘必须不在调用线程上",
                    Thread.currentThread().getName().equals(spy.callerThread));
        } finally {
            deleteRecursively(to);
        }
    }

    /**
     * 这条是<b>结构</b>断言，不是行为断言：{@link AsyncSaveSink} 故意<b>不</b>实现 {@link SaveSink}。
     * 实现了就等于 {@code game.attachSave(async)} 之后，渲染线程手握一枚类型完全合法的同步 IO 接口，
     * 某一帧调 {@code exists()} 就是站在渲染线程上等盘——[规格 §八] 那条红线被一次类型正确的调用踩过去。
     *
     * <p>对照组是键值那条链：{@link AsyncKeyValue} 与 {@link SharedPrefs} <b>共用</b> {@link KeyValue}
     * 接口，那里"渲染线程调一次 putLong"踩不到盘（只是登记），所以同接口是安全的；快照没有这种性质——
     * {@code SaveSink} 的每个方法都是 IO。
     */
    @Test
    public void theThreadLayerIsNotSubstitutableForTheMediumLayer() {
        assertFalse(SaveSink.class.isAssignableFrom(AsyncSaveSink.class));
        assertTrue(SaveSink.class.isAssignableFrom(FileSaveSink.class));
        assertTrue(KeyValue.class.isAssignableFrom(AsyncKeyValue.class));
        assertFalse(SaveSink.class.isAssignableFrom(AsyncKeyValue.class));
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static List<String> namesIn(File dir) {
        String[] ns = dir.list();
        List<String> out = new ArrayList<>();
        if (ns != null) out.addAll(Arrays.asList(ns));
        java.util.Collections.sort(out);
        return out;
    }

    private static int countFiles(File dir) {
        String[] ns = dir.list();
        return ns == null ? 0 : ns.length;
    }

    private static void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursively(k);
        }
        if (!f.delete() && f.exists()) {
            fail("临时目录没清干净：" + f);
        }
    }
}
