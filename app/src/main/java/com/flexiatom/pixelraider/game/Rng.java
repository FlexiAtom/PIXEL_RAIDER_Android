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
package com.flexiatom.pixelraider.game;

/**
 * 可复现随机数（splitmix64）。
 *
 * 为什么不用 {@link Math#random()}：掉落、武器权重抽样与波次组合都进了单测，
 * "这次该不该掉芯片"必须能被钉死。把随机源做成显式注入的一个小对象，
 * 测试传同一个种子就能重放整局；纯逻辑类也因此不必 import 任何系统时钟。
 *
 * 每帧调用次数在几十以内，纯整数运算、不分配对象。
 */
public final class Rng {

    private static final long GOLDEN = 0x9E3779B97F4A7C15L;

    private long state;

    public Rng(long seed) {
        state = seed;
    }

    public void setSeed(long seed) {
        state = seed;
    }

    public long seed() {
        return state;
    }

    /** 下一个 64 位混合值。 */
    public long nextLong() {
        state += GOLDEN;
        long z = state;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** [0,1) 的浮点值，只取高 24 位精度——概率判定用不到更多。 */
    public float next01() {
        return (int) ((nextLong() >>> 40) & 0xFFFFFFL) / 16777216f;
    }

    /** [0,bound) 的整数；bound ≤ 0 时返回 0（不抛，调用方常在池满的路径上）。 */
    public int nextInt(int bound) {
        if (bound <= 0) return 0;
        return (int) ((nextLong() >>> 33) % bound);   // 取 31 位，避免负号
    }

    public float range(float lo, float hi) {
        return lo + (hi - lo) * next01();
    }

    /** 概率判定，p 越界时按 0/1 钳住（数值表被改坏也不该抛在渲染中途）。 */
    public boolean chance(float p) {
        if (p <= 0f) return false;
        if (p >= 1f) return true;
        return next01() < p;
    }
}
