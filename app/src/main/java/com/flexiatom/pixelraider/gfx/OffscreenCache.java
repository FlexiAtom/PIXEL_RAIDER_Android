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
package com.flexiatom.pixelraider.gfx;

import android.graphics.Bitmap;

/**
 * 离屏缓存统一注册与回收（规格 §三 / §九.17）。
 *
 * 两条硬规则：
 * 1. **key 必须含目标尺寸**。非整数倍缩放会让像素宽窄不均（规格 §三），把 24 和 25 两种目标
 *    宽度混到同一个 key 上，就会有一档在错误的分辨率下被复用，看起来"某些图标发糊"。
 * 2. **逐出与 surface 销毁时必须 recycle()**。低端机 heap 128MB，离屏 Bitmap 是 native+java 双份，
 *    只丢引用不 recycle 等于把旧图留在 heap 上等 GC，而 GC 什么时候来不由渲染线程决定。
 *
 * 用开放寻址的定长哈希表，不用 LinkedHashMap：后者每次 get 都要建 Map.Entry（accessOrder 模式）
 * 且迭代走 Entry 对象，和"每帧零分配"直接冲突。
 */
public final class OffscreenCache {

    public interface Producer {
        Bitmap create(int w, int h);
    }

    /** 全局注册表：surfaceChanged / 变档 / onTrimMemory 时统一释放重建。 */
    private static final OffscreenCache[] REGISTRY = new OffscreenCache[8];
    private static int registryCount;

    public static void register(OffscreenCache cache) {
        if (registryCount < REGISTRY.length) REGISTRY[registryCount++] = cache;
    }

    /** @return 释放的 Bitmap 数（诊断/单测用） */
    public static int releaseAll() {
        int n = 0;
        for (int i = 0; i < registryCount; i++) n += REGISTRY[i].clear();
        return n;
    }

    private final long[] keys;
    private final Bitmap[] values;
    private final int[] stamp;      // 环形淘汰时钟
    private final int mask;
    private int size;
    private int clock;
    private long hits;
    private long misses;
    private long evictions;

    public OffscreenCache(int name, int buckets) {
        int cap = 1;
        while (cap < buckets) cap <<= 1;
        keys = new long[cap];
        values = new Bitmap[cap];
        stamp = new int[cap];
        mask = cap - 1;
        register(this);
    }

    /** key = (tag << 50) | (id << 40) | (w << 20) | h；尺寸与色彩变体都显式入 key。 */
    public static long keyOf(int id, int w, int h) {
        return keyOf(id, w, h, 0);
    }

    /**
     * @param tag 色彩变体槽（0..MAX_TAGS-1）。不能直接把 RGB 混进 key：
     *            {@code 0xFF000000L & opaqueColor} 恒等于 0xFF000000，
     *            那样不同主题色会算出同一个 key，换色后命中的是旧色的图（实测踩到）。
     */
    public static long keyOf(int id, int w, int h, int tag) {
        return ((long) (tag & 0x3FFF) << 50) | ((long) (id & 0x3FF) << 40)
                | ((long) (w & 0xFFFFF) << 20) | (long) (h & 0xFFFFF);
    }

    public static final int MAX_TAGS = 1 << 14;

    /** 尺寸进 key 后，同一精灵的 24×24 与 24×25 必然落到不同 key。 */
    public static boolean distinctSizeKeys(int id, int w, int h1, int h2) {
        return keyOf(id, w, h1) != keyOf(id, w, h2);
    }

    /** 不同色彩变体在 w/h 相同的情况下也必须分开。 */
    public static boolean distinctTagKeys(int id, int w, int h, int tagA, int tagB) {
        return keyOf(id, w, h, tagA) != keyOf(id, w, h, tagB);
    }

    private int slotFor(long key) {
        int i = mix(key) & mask;
        while (values[i] != null && keys[i] != key) i = (i + 1) & mask;
        return i;
    }

    private static int mix(long k) {
        long h = k * 0x9E3779B97F4A7C15L;
        h ^= h >>> 29;
        return (int) h;
    }

    public Bitmap get(long key) {
        int i = slotFor(key);
        Bitmap b = values[i];
        if (b != null) {
            hits++;
            stamp[i] = ++clock;
        }
        return b;
    }

    /** 命中即返回；未命中用 producer 造一张并放入缓存（producer 返回 null 时不缓存）。 */
    public Bitmap getOrCreate(long key, int w, int h, Producer producer) {
        int i = slotFor(key);
        Bitmap b = values[i];
        if (b != null && !b.isRecycled()) {
            hits++;
            stamp[i] = ++clock;
            return b;
        }
        misses++;
        Bitmap made = producer.create(w, h);
        if (made == null) return null;
        if (b != null) {           // 外部 recycle 过的死条目
            b.recycle();
            size--;
        }
        if (size >= values.length - (values.length >> 2)) {  // 75% 满 → FIFO 逐出一格
            evictOldestSlot();
        }
        if (values[i] != null) {   // 逐出可能挪动了本槽
            values[i].recycle();
            evictions++;
            size--;
        }
        keys[i] = key;
        values[i] = made;
        stamp[i] = ++clock;
        size++;
        return made;
    }

    private void evictOldestSlot() {
        int oldest = -1;
        int oldStamp = Integer.MAX_VALUE;
        for (int j = 0; j < values.length; j++) {
            if (values[j] != null && stamp[j] < oldStamp) {
                oldStamp = stamp[j];
                oldest = j;
            }
        }
        if (oldest < 0) return;
        values[oldest].recycle();
        values[oldest] = null;
        keys[oldest] = 0;
        size--;
        evictions++;
    }

    /** 尺寸变了或内存吃紧：整表释放。调用方之后按需重建。 */
    public int clear() {
        int n = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null) {
                values[i].recycle();
                values[i] = null;
                keys[i] = 0;
                n++;
            }
        }
        size = 0;
        return n;
    }

    public int size() { return size; }
    public int capacity() { return values.length; }
    public long hits() { return hits; }
    public long misses() { return misses; }
    public long evictions() { return evictions; }
    public void resetCounters() { hits = 0; misses = 0; evictions = 0; }
}
