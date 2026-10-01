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
 * 连击计量（纯逻辑，JVM 可测）。
 * [规格] 2 秒窗口内连杀累积倍率，上限 +200%。
 *
 * 用"游戏时间"而不是真实时间：暂停时连击不该继续掉，否则打开商店回来倍率就没了。
 */
public final class ComboMeter {

    private final Balance.Combo cfg;
    private float lastHitAt = Float.NEGATIVE_INFINITY;
    private int hits;
    /**
     * 本局最高连击。窗口一过 hits 就归零，结算页与成就（连击 X10 / X25）要的是"曾经到过多少"，
     * 所以峰值只能在 hit() 里顺手记，不能在读取时现算。
     */
    private int peak;

    public ComboMeter() {
        this(Balance.combo);
    }

    public ComboMeter(Balance.Combo cfg) {
        this.cfg = cfg;
    }

    public void hit(float nowGame) {
        if (nowGame - lastHitAt <= cfg.windowSec) hits++;
        else hits = 1;
        lastHitAt = nowGame;
        if (hits > peak) peak = hits;
    }

    /** 本局最高连击（跨窗口保留），结算页风格维度与成就读它。 */
    public int peakHits() {
        return peak;
    }

    /** 当前连击数；窗口已过返回 0（UI 与得分都读这个，不各判一次时间）。 */
    public int count(float nowGame) {
        return nowGame - lastHitAt <= cfg.windowSec ? hits : 0;
    }

    /**
     * 加成百分比。[推导] 上限 200%、步数 20 → 每连 +10%，满 20 连后持平。
     * 持平而不是继续涨：不然第 40 连会把得分拉成二次曲线，分数没有可比性。
     */
    public int bonusPercent(float nowGame) {
        int n = count(nowGame);
        if (n <= 0) return 0;
        int capped = Math.min(n, cfg.steps);
        return capped * (cfg.maxBonusPercent / cfg.steps);
    }

    /** 得分乘数：1.0 + 加成。 */
    public float multiplier(float nowGame) {
        return 1f + bonusPercent(nowGame) / 100f;
    }

    public void reset() {
        lastHitAt = Float.NEGATIVE_INFINITY;
        hits = 0;
        peak = 0;
    }
}
