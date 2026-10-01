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
package com.flexiatom.pixelraider.ui;

/**
 * 缓动（纯算术，JVM 可测）。
 *
 * 只放规格点名的两条曲线，不做"通用 easing 库"：多出来的每一条都没有对应的界面决定，
 * 而界面动画一旦各画各的缓动，全站节奏就会散。
 */
public final class Easing {

    /** 规格 §四 暂停面板指定的过冲系数。 */
    public static final float BACK_OVERSHOOT = 1.2f;

    private Easing() { }

    /** 0→0、1→1，中段超过 1 再落回：面板"落位轻微过冲回弹"。 */
    public static float easeOutBack(float k) {
        float x = clamp01(k) - 1f;
        float c1 = BACK_OVERSHOOT;
        float c3 = c1 + 1f;
        return 1f + c3 * x * x * x + c1 * x * x;
    }

    /** 减速收尾，用于透明度与位移这类不许过冲的量。 */
    public static float easeOutCubic(float k) {
        float x = 1f - clamp01(k);
        return 1f - x * x * x;
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v)) return 0f;
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
