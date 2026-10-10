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

    // ---- 快照 -----------------------------------------------------------------------------

    /**
     * 三格全存。{@code lastHitAt} 是这里面唯一"看着像时间戳所以容易被当成画面量"的一格，
     * 但它决定 {@link #count} 当下算不算还在窗口内 —— 不存就等于"读档后连击一定断了"。
     * 它必须走 {@code float} 的<b>原始位</b>：没开过连击的初值是 {@link Float#NEGATIVE_INFINITY}，
     * 任何文本化编码都会把它读成异常（{@link Buf} 类注释第二条说的就是这一格）。
     *
     * <p>它是<b>世界秒</b>口径，所以这份快照必须与 {@code Game.world} 同进同出：只还原其一，
     * 窗口判据就整段错位（world 大而 lastHitAt 小 ⇒ 连击凭空断；反之 ⇒ 窗口赖着不走）。
     */
    public void writeSnapshot(Buf w) {
        w.writeFloat(lastHitAt);
        w.writeInt(hits);
        w.writeInt(peak);
    }

    public void restoreSnapshot(Buf.Reader r) {
        float at = r.readFloat();
        int h = r.readInt();
        int p = r.readInt();
        // NaN 不在窗口判据的任何一侧（<= 与 > 都假），它会让 count() 稳定返回 0 而 hit() 把窗口
        // 永远判成"过期"——那不是断连击，那是这一格再也不会活。原始位编码本身造不出 NaN，
        // 能出现它的只有改坏的字节，所以这里按"这份档不合法"处理而不是将就。
        if (Float.isNaN(at)) throw new Buf.Invalid("lastHitAt is NaN");
        if (h < 0 || p < 0 || h > p) throw new Buf.Invalid("combo hits " + h + " vs peak " + p);
        lastHitAt = at;
        hits = h;
        peak = p;
    }
}
