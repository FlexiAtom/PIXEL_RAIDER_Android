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
package com.flexiatom.pixelraider.game;

/**
 * 空间网格：子弹 × 敌人从 O(n×m) 降到近似 O(n)。
 *
 * [规格] 每帧把敌人分桶，子弹只查 3×3 邻格；格子 48px（命中范围最大约 18px，±48 必定不漏判）。
 * [规格] 桶用 {@code int[]} 索引数组，**不用 HashMap<Integer,List>**——装箱在每帧路径上制造临时对象。
 *
 * 实现是**头插链表桶**：{@code head[cell]} 指向第一个 item，{@code next[slot]} 串起同桶其余 item。
 * 于是 clear 只要把 head 填 -1（O(格数) ≈ 5×9），不用清 next，也不产生任何对象。
 *
 * ⚠ 两条不能破的前提，都体现在构造参数里：
 * 1. **按中心入格**。一个 item 只进一个桶，所以邻格遍历天然不重不漏。
 * 2. **命中判定的最大中心距 ≤ ring × CELL**（{@link #ringFor} 由这个约束算出）。
 *    敌人半径一旦调大到中心距超过 48，邻格自动从 3×3 涨到 5×5；写死 3×3 才是会漏判的那种实现。
 */
public final class SpatialGrid {

    /** [规格] 48 逻辑像素。 */
    public static final int CELL = 48;

    /**
     * [默认] 建网格时传给 {@link #SpatialGrid} 的 maxCenterDistance。**提出来做常量，是为了让
     * {@code Game} 与"战斗部覆盖不变量"那条单测同读一处**——测试若自己重述这个数，
     * 就立了第二个真源，改这边不会让那边红，覆盖漏判照样静默。
     *
     * <p>40 的来源：所有调用方里判据最宽的是连续杆战斗部
     * {@code rodLength/2 + 最大敌半径 + 命中 pad = 17 + 14.5 + 2 = 33.5}（弹-敌那条只有
     * {@code 14.5 + 4 = 18.5}），对它留一点余量。
     * ⚠ 同时它必须 {@code ≤ CELL}：一旦超过 48，{@link #ringFor} 把邻域从 3×3 涨到 5×5，
     * 每帧查询代价翻 2.8 倍——那是"看起来没改什么、帧时间掉一半"的那种改法。
     */
    public static final int DEFAULT_MAX_CENTER_DISTANCE = 40;

    private final int cols;
    private final int rows;
    private final int ring;
    private final int[] head;
    private final int[] next;
    /** 同一轮内重复 insert 直接跳过：链表桶里自己指自己会死循环，那是最难查的一种卡死。 */
    private final int[] stamp;
    private int generation = 1;
    private int itemCount;

    /**
     * @param width             网格覆盖的逻辑宽（含边界外一点，越界坐标会被夹到边缘格）
     * @param height            网格覆盖的逻辑高
     * @param itemCapacity       同时可容纳的 item 数（= 敌人数组上限）
     * @param maxCenterDistance   命中判定的最大中心距（子弹半径 + 最大敌人半径）
     */
    public SpatialGrid(int width, int height, int itemCapacity, int maxCenterDistance) {
        cols = Math.max(1, (width + CELL - 1) / CELL);
        rows = Math.max(1, (height + CELL - 1) / CELL);
        ring = ringFor(maxCenterDistance);
        head = new int[cols * rows];
        next = new int[Math.max(1, itemCapacity)];
        stamp = new int[Math.max(1, itemCapacity)];
        clear();
    }

    /** 分桶前清场：只填 head、挪一代，不用清 next/stamp（只有本代 stamp 命中的链才会被读到）。 */
    public void clear() {
        for (int i = 0; i < head.length; i++) head[i] = -1;
        generation++;
        itemCount = 0;
    }

    /** itemId 必须是 [0, itemCapacity) 的稳定索引——通常是敌人池的槽号，不是对象。 */
    public void insert(int itemId, float x, float y) {
        if (itemId < 0 || itemId >= next.length) {
            throw new IllegalArgumentException("itemId out of capacity: " + itemId);
        }
        if (stamp[itemId] == generation) return;
        stamp[itemId] = generation;
        int cx = cellOfX(x);
        int cy = cellOfY(y);
        int cell = cy * cols + cx;
        next[itemId] = head[cell];
        head[cell] = itemId;
        itemCount++;
    }

    /**
     * 查询 (x,y) 邻域内的候选，塞进 out，返回个数。
     * **不做距离判定**——这里只负责"不漏"，是否命中由调用方按各自的半径算，
     * 也正因为如此，调用方可以用 {@code hp <= 0} 而不是 indexOf 去过滤死敌（规格 §五点名）。
     */
    public int query(float x, float y, int[] out) {
        int n = 0;
        int cx = cellOfX(x);
        int cy = cellOfY(y);
        int y0 = cy - ring, y1 = cy + ring;
        int x0 = cx - ring, x1 = cx + ring;
        if (y0 < 0) y0 = 0;
        if (x0 < 0) x0 = 0;
        if (y1 >= rows) y1 = rows - 1;
        if (x1 >= cols) x1 = cols - 1;
        for (int cellY = y0; cellY <= y1; cellY++) {
            int base = cellY * cols;
            for (int cellX = x0; cellX <= x1; cellX++) {
                for (int it = head[base + cellX]; it != -1; it = next[it]) {
                    if (n < out.length) out[n++] = it;
                }
            }
        }
        return n;
    }

    /** 越界（例如敌人在 y<0 的生成区外）夹到边缘格：只会多给候选，不会漏。 */
    int cellOfX(float x) {
        int c = (int) Math.floor(x / CELL);
        return c < 0 ? 0 : (c >= cols ? cols - 1 : c);
    }

    int cellOfY(float y) {
        int c = (int) Math.floor(y / CELL);
        return c < 0 ? 0 : (c >= rows ? rows - 1 : c);
    }

    /** 邻格半径：能覆盖 maxCenterDistance 所需的最小 ring，至少 1（就是规格里的 3×3）。 */
    public static int ringFor(int maxCenterDistance) {
        if (maxCenterDistance <= 0) return 1;
        int r = (maxCenterDistance + CELL - 1) / CELL;
        return r < 1 ? 1 : r;
    }

    public int ring() {
        return ring;
    }

    public int cols() {
        return cols;
    }

    public int rows() {
        return rows;
    }

    public int bucketCount() {
        return head.length;
    }

    public int itemCount() {
        return itemCount;
    }

    public int capacity() {
        return next.length;
    }
}
