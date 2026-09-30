/*
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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 写路径的线程搬运工：调用线程只把键值登记下来，真正的写与 {@code flush} 都在单线程池上做。
 *
 * [规格 §八] **渲染线程内禁止 {@code SharedPreferences.apply()}**——apply 会往磁盘排队（含 fsync），
 * 卡的是主线程；{@code commit()} 更糟，它同步等盘，直接卡渲染线程。
 * 把这条规矩写进 {@link SharedPrefs} 里就等于把它绑在 android 类型上、JVM 测不到；
 * 单独一层，"调用线程什么都没发生"就能用普通 fake 钉死。
 *
 * 读**不**等挂起的写，直接落到 {@link #target} 的内存镜像。这不会读到错的数：
 * 钱包、等级、纪录都另有一份内存真值（{@code GrowthTree}/{@code RunRecords}），落盘只是副本。
 * 反过来如果这里去等 IO，就是把同一条红线从"写"换成"读"再踩一遍。
 */
public final class AsyncKeyValue implements KeyValue {

    private final KeyValue target;
    private final ExecutorService io;
    private final java.util.ArrayList<String> keys = new java.util.ArrayList<>();
    private final java.util.ArrayList<Long> values = new java.util.ArrayList<>();

    public AsyncKeyValue(KeyValue target) {
        this(target, Executors.newSingleThreadExecutor());
    }

    /** 测试注入用：给一个只收任务不跑的 executor，就能看清"谁在什么时候真的写了"。 */
    public AsyncKeyValue(KeyValue target, ExecutorService io) {
        this.target = target;
        this.io = io;
    }

    @Override
    public long getLong(String key, long fallback) {
        return target.getLong(key, fallback);
    }

    @Override
    public synchronized void putLong(String key, long value) {
        keys.add(key);
        values.add(value);
    }

    @Override
    public synchronized void flush() {
        if (keys.isEmpty()) return;
        final String[] ks = keys.toArray(new String[0]);
        final long[] vs = new long[ks.length];
        for (int i = 0; i < vs.length; i++) vs[i] = values.get(i);
        keys.clear();
        values.clear();
        io.execute(() -> writeAll(ks, vs));
    }

    /** 跑在 IO 线程上：顺序写完后一次 flush，一批键值只落一次盘。 */
    private void writeAll(String[] ks, long[] vs) {
        for (int i = 0; i < ks.length; i++) target.putLong(ks[i], vs[i]);
        target.flush();
    }

    /**
     * 等挂起的写完再走。主线程在 Activity 销毁时调——那时渲染线程已经在 surfaceDestroyed 里
     * joined，但"两个线程碰同一个 ArrayList"这件事不该靠调用顺序的记忆力来保证，所以上面加锁。
     */
    public void shutdown() {
        flush();
        io.shutdown();
        try {
            io.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
