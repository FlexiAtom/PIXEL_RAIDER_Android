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
 * 结算五维评级（纯算术，JVM 可测）。
 *
 * 规格 §四："五维（生存/击杀/效率/超载/风格）各自归一，加权合成字母等级。单一总分没有成就感，
 * 也没法指导改进。"所以这里**先归一再加权**，不是先加权再归一——后者的话击杀分天然比超载次数
 * 大两个数量级，权重形同虚设。
 *
 * 参照值全在 {@link Balance.Grade}，这里不写字面量。
 */
public final class Rating {

    public static final int SURVIVAL = 0, KILL = 1, EFFICIENCY = 2, OVERLOAD = 3, STYLE = 4;
    public static final int COUNT = 5;

    /** 等级字符，索引即档位（0=D 最高到 4=S 之外的排法见 {@link #letterIndex}）。 */
    private static final char[] LETTERS = { 'D', 'C', 'B', 'A', 'S' };

    private Rating() { }

    public static float weightOf(int dim, Balance.Grade g) {
        switch (dim) {
            case SURVIVAL: return g.wSurvival;
            case KILL: return g.wKill;
            case EFFICIENCY: return g.wEfficiency;
            case OVERLOAD: return g.wOverload;
            case STYLE: return g.wStyle;
            default: return 0f;
        }
    }

    /** 生存：清波数抵掉死亡折算后，除以参照。低于 0 就是 0，不给负分留活路。 */
    public static float survival(int wavesCleared, int deaths, Balance.Grade g) {
        float net = wavesCleared - deaths * g.deathCostWaves;
        return norm(net, g.survivalWaves);
    }

    public static float kill(RunStats s, Balance.Grade g) {
        return norm(s.kills, g.killRef);
    }

    /** 效率 = 命中率。一枪未开时 accuracy() 已经是 0，这里不会再除一次零。 */
    public static float efficiency(RunStats s) {
        return clamp01(s.accuracy());
    }

    public static float overload(RunStats s, Balance.Grade g) {
        return norm(s.overloadUses, g.overloadRef);
    }

    public static float style(RunStats s, Balance.Grade g) {
        return norm(s.peakCombo, g.comboRef);
    }

    /** 把五维写进调用方复用的数组（结算只算一次，但保持"结果不新造对象"的整局一致口径）。 */
    public static void dimensions(RunStats s, int wavesCleared, Balance.Grade g, float[] out) {
        out[SURVIVAL] = survival(wavesCleared, s.deaths, g);
        out[KILL] = kill(s, g);
        out[EFFICIENCY] = efficiency(s);
        out[OVERLOAD] = overload(s, g);
        out[STYLE] = style(s, g);
    }

    public static float overall(float[] dims, Balance.Grade g) {
        float sum = 0f;
        for (int i = 0; i < COUNT; i++) sum += dims[i] * weightOf(i, g);
        return clamp01(sum);
    }

    /** 加权总分对表取档：从 S 往下比，第一个达到的就是它。 */
    public static int letterIndex(float total, Balance.Grade g) {
        float t = Float.isNaN(total) ? 0f : total;
        if (t >= g.atS) return 4;
        if (t >= g.atA) return 3;
        if (t >= g.atB) return 2;
        if (t >= g.atC) return 1;
        return 0;
    }

    public static char letterOf(int index) {
        return index >= 0 && index < LETTERS.length ? LETTERS[index] : LETTERS[0];
    }

    /**
     * 归一并钳到 0..1。参照 &lt;= 0 直接给 0 而不是 Infinity：调试面板可以把表里的参照改成 0，
     * 那种情况下"没有参照"该读成"这一维不计分"，不是"人人满分"。
     */
    private static float norm(float v, float ref) {
        if (ref <= 0f) return 0f;
        return clamp01(v / ref);
    }

    /** 钳到 0..1，NaN 一律当 0（NaN 进结算页会一路传染到条宽和字母等级）。 */
    public static float clamp01(float v) {
        if (Float.isNaN(v)) return 0f;
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
