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

import com.flexiatom.pixelraider.plat.KeyValue;

/**
 * 存档槽位账本：哪些槽有档、每槽列表页要显示的那几枚标量（[规格 §5.5] 那条"回退"功能的真源）。
 *
 * <p><b>为什么这一枚住在 {@code game} 而不是 {@code plat}</b>：槽位语义是规则（上限几格、
 * 满了怎么办、一行显示什么），字节才是平台。他 2026-10-10 逐字「上限50槽，允许覆盖和删除存档」
 * 给的正是这一层的数；而 {@link com.flexiatom.pixelraider.plat.SaveSink} 那边连"50"都不该知道。
 *
 * <p><b>掩码是一枚 {@code long}，这是运气不是设计必然</b>：50 ≤ 64 才装得下。上限哪天涨到 65，
 * 就得换两枚或 {@code long[]}， {@code plat/SaveSink} 的签名跟着改（那条账登记在
 * {@code pause-save-entry} §6 第 2 条）。所以低位有效、高位一律当"不存在"来对待：
 * 只有低 {@link #CAPACITY} 位会被读写，别用 {@code Long.bitCount(mask)} 之外的方式假设它是满 64 位在用。
 *
 * <p><b>它是"有没有档"的唯一真源</b>：列表页画"有档/空格"两形读这里，<b>不</b>问介质
 * （问就是渲染线程 {@code File.exists()}）。代价要说清——盘上文件与掩码可以不一致（删档失败留下孤儿字节、
 * 或外部清了目录而掩码还说有档）。不一致时以掩码为准，读回 {@code null} 由 apply 侧报"这一份读不出来"，
 * 反过来（有文件、掩码没有）那些字节是等下一次同槽覆盖来回收的孤儿。
 *
 * <p><b>字段非 final、只在渲染线程改</b>：与 {@link GrowthTree} 同一条纪律，读盘只发生在
 * {@code attachStore} 那一刻（渲染线程可能还没起跑）。
 */
public final class SaveSlots {

    /** 策略上限（他 2026-10-10 逐字给的数）。物理上限是 {@code long} 的 64 位，两者取小者生效。 */
    public static final int CAPACITY = 50;

    /** "满了"的返回值：{@link #allocateFree()} 与一切按槽号回话的接口都用它，不用负数二义。 */
    public static final int NONE = -1;

    private static final String MASK = "save_mask";
    private static final String META = "save_meta_";
    private static final String STAMP = "save_time_";

    /** 位段宽度（打包/解包共用，测试按同一组常量断言，不写字面量）。 */
    public static final int BITS_MODE = 1, BITS_TIER = 2, BITS_CHAPTER = 4, BITS_WAVE = 9, BITS_SCORE = 32;
    private static final int SHIFT_TIER = BITS_MODE;
    private static final int SHIFT_CHAPTER = SHIFT_TIER + BITS_TIER;
    private static final int SHIFT_WAVE = SHIFT_CHAPTER + BITS_CHAPTER;
    private static final int SHIFT_SCORE = SHIFT_WAVE + BITS_WAVE;
    /** 用到的总位数：32+9+4+2+1 = 48，剩 16 位留给今后加字段（加在高分端，旧字节仍在低 48 位）。 */
    public static final int BITS_USED = SHIFT_SCORE + BITS_SCORE;

    private long mask;
    private final long[] meta = new long[CAPACITY];
    private final long[] stamp = new long[CAPACITY];

    /** 启动时读一次。之后介质侧的字节永远不再被这里问起。 */
    public void readFrom(KeyValue store) {
        mask = store.getLong(MASK, 0L) & occupiedBits();
        for (int slot = 0; slot < CAPACITY; slot++) {
            if (!isOccupied(slot)) {
                meta[slot] = 0L;
                stamp[slot] = 0L;
                continue;
            }
            meta[slot] = store.getLong(META + slot, 0L);
            stamp[slot] = store.getLong(STAMP + slot, 0L);
        }
    }

    /** 低频时机落盘（按下「存档」那一次、删档那一次）。渲染线程调 {@code KeyValue.putLong} 只登记，不写盘。 */
    public void commit(KeyValue store) {
        store.putLong(MASK, mask);
        for (int slot = 0; slot < CAPACITY; slot++) {
            if (!isOccupied(slot)) continue;
            store.putLong(META + slot, meta[slot]);
            store.putLong(STAMP + slot, stamp[slot]);
        }
    }

    public boolean isOccupied(int slot) {
        return slot >= 0 && slot < CAPACITY && (mask & (1L << slot)) != 0L;
    }

    /** 占用几槽。列表页的"3 / 50"那行读它。 */
    public int count() {
        return Long.bitCount(mask & occupiedBits());
    }

    /**
     * 找第一个空槽。没有空槽给 {@link #NONE}——**这是拒绝，不是回绕**：他给的"允许覆盖和删除"意味着
     * 腾位是玩家显式动作，所以自动淘汰最旧那份（我把它做掉的话，打歪之前的原始点会无声消失）不成。
     * 拒绝之后页面要提示"先删一份或选一份覆盖"（这条自决登记在 {@code pause-save-entry} 待裁第 6 格落地账）。
     */
    public int allocateFree() {
        for (int slot = 0; slot < CAPACITY; slot++) {
            if (!isOccupied(slot)) return slot;
        }
        return NONE;
    }

    /**
     * 登记一份档落在这槽（覆盖已有槽也走这里：槽号不变、掩码不变，只有 meta/stamp 换值）。
     * 时刻由调用方给——这里不读时钟，测试才能钉死"两秒内连按两次得到同一个读数"。
     */
    public void occupy(int slot, long packedMeta, long epochMillis) {
        if (slot < 0 || slot >= CAPACITY) throw new IllegalArgumentException("slot=" + slot);
        mask |= 1L << slot;
        meta[slot] = packedMeta;
        stamp[slot] = epochMillis;
    }

    /**
     * 删档：清掩码位与那一格的 meta/stamp。字节侧由调用方交给 {@code AsyncSaveSink#submitDelete}，
     * 这里不等它——不等是故意的，等就等于渲染线程等盘。
     */
    public void release(int slot) {
        if (slot < 0 || slot >= CAPACITY) return;
        mask &= ~(1L << slot);
        meta[slot] = 0L;
        stamp[slot] = 0L;
    }

    /** 全清（调试/将来"删所有档"那格用；今天没有入口）。 */
    public void clearAll() {
        mask = 0L;
        for (int slot = 0; slot < CAPACITY; slot++) {
            meta[slot] = 0L;
            stamp[slot] = 0L;
        }
    }

    public long meta(int slot) {
        return inRange(slot) ? meta[slot] : 0L;
    }

    /** 存档时刻（epoch 毫秒）；没档给 0。列表页自己格式化成"10-10 17:14"。 */
    public long stamp(int slot) {
        return inRange(slot) ? stamp[slot] : 0L;
    }

    /** 掩码原值（测试与"要不要落一次盘"的比对用；画列表请走 {@link #isOccupied}）。 */
    public long mask() {
        return mask;
    }

    // ---- 打包：一枚 long 装下列表页要显示的全部标量 ------------------------------------------

    /**
     * 打一行列表项需要的数。{@code score} 只占 {@link #BITS_SCORE} 位（32 位有符号上限 ≈21 亿，
     * 本游戏的分数量级远在其下；真超了是截断不是崩溃，因为这一枚只是**显示用的副本**，
     * 权威数值在快照二进制里）。
     *
     * <p>那一枚 {@code & 0xFFFFFFFFL} 不是装饰：不掩的话，一笔负分（今天不可能出现，但位段是位段）
     * 的符号扩展会漫进 {@code SHIFT_SCORE} 之上的 16 位保留段，把"高位留给今后加字段"这句话当场作废。
     */
    public static long packMeta(Mode mode, int chapter, int wave, int score) {
        long m = mode.campaign ? 1L : 0L;
        m |= (long) tierOrdinal(mode.tier) << SHIFT_TIER;
        m |= (long) clamp(chapter, 0, (1 << BITS_CHAPTER) - 1) << SHIFT_CHAPTER;
        m |= (long) clamp(wave, 0, (1 << BITS_WAVE) - 1) << SHIFT_WAVE;
        m |= (score & 0xFFFFFFFFL) << SHIFT_SCORE;
        return m;
    }

    public static int modeOf(long packed) {
        return (int) (packed & 1L);            // 1 = 战役，0 = 无尽；与 Mode.campaign 同序
    }

    public static int tierOf(long packed) {
        return (int) ((packed >>> SHIFT_TIER) & (1L << BITS_TIER) - 1L);
    }

    public static int chapterOf(long packed) {
        return (int) ((packed >>> SHIFT_CHAPTER) & (1L << BITS_CHAPTER) - 1L);
    }

    public static int waveOf(long packed) {
        return (int) ((packed >>> SHIFT_WAVE) & (1L << BITS_WAVE) - 1L);
    }

    /**
     * 分数段。先无符号右移落到 0…2³²−1，再由 {@code (int)} 收回符号——这样高位那 16 位保留段
     * 无论是什么都不参与这一格的读数（与 {@link #packMeta} 的掩码是同一件事的两半）。
     */
    public static int scoreOf(long packed) {
        return (int) (packed >>> SHIFT_SCORE);
    }

    /** 档的序号：与 {@link Tier} 声明序一致（休闲 0／普通 1／终结 2）。 */
    private static int tierOrdinal(Tier tier) {
        if (tier == Tier.CASUAL) return 0;
        return tier == Tier.TERMINAL ? 2 : 1;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static boolean inRange(int slot) {
        return slot >= 0 && slot < CAPACITY;
    }

    /** 低 CAPACITY 位那一段掩码——高位脏了就按没发生过处理。 */
    private static long occupiedBits() {
        return CAPACITY >= 64 ? -1L : (1L << CAPACITY) - 1L;
    }
}
