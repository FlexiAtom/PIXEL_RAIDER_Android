/*
 * PIXEL RAIDER — 原生 Android 纵版弹幕射击
 * Copyright (C) 2026 Flexiatom
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
 * 本局趋势采样（规格 §四 暂停面板"折线图分析"）。
 *
 * **只存累计量**（击杀、分数、生命比例），速率在读取时由"后一帧减前一帧"算出来。反过来存
 * 速率的话，换一种指标口径就得重采一遍——而采样点一旦丢了就补不回来。
 *
 * 定长环形缓冲：暂停面板每帧都要读它，运行期扩容等于在渲染线程分配。窗口 {@link #CAP} 秒，
 * 满了就覆盖最旧的一帧（趋势板看的是"最近怎么走"，不是"整局史"）。
 */
public final class TrendSampler {

    /** 采样帧数上限（每秒一帧 ⇒ 24 秒窗口）。 */
    public static final int CAP = 24;
    /** 采样间隔：规格指定每 1 秒一帧。 */
    public static final float INTERVAL_SEC = 1f;

    public static final int TAB_KILLS = 0;
    public static final int TAB_SCORE = 1;
    public static final int TAB_HP = 2;
    public static final int TABS = 3;

    private final int[] kills = new int[CAP];
    private final long[] score = new long[CAP];
    private final float[] hp = new float[CAP];

    private int count;
    /** 下一个写入位；满窗时它同时是最旧一帧的位置。 */
    private int head;
    private float since;

    public void reset() {
        count = 0;
        head = 0;
        since = 0f;
    }

    public int frames() {
        return count;
    }

    /**
     * 每帧喂一次累计量，内部攒够 {@link #INTERVAL_SEC} 就落一帧。
     *
     * @param dt 世界步长（暂停期间世界本就冻结，所以暂停时不会偷偷多采一帧）
     * @return true 表示这一 tick 推进了一帧
     */
    public boolean tick(float dt, int killTotal, long scoreNow, float hpRatio) {
        if (dt <= 0f) return false;
        since += dt;
        if (since < INTERVAL_SEC) return false;
        // 只补一帧：卡了 3 秒也照样只落一个采样点，然后把欠账清零——连续补帧会把横轴挤成一团
        since = 0f;
        kills[head] = killTotal;
        score[head] = scoreNow;
        hp[head] = hpRatio;
        head = head + 1 == CAP ? 0 : head + 1;
        if (count < CAP) count++;
        return true;
    }

    /** 折线够不够画：不足两帧时画出来的是假线（规格点名"采样中…"）。 */
    public boolean hasSeries() {
        return count >= 2;
    }

    /** 该 tab 下的 plotting 点数：速率类要相邻两帧相减，所以少一个。 */
    public int pointCount(int tab) {
        if (tab == TAB_HP) return count;
        return count >= 2 ? count - 1 : 0;
    }

    /** 第 i 个点的值（自旧到新）。速率单位是"每秒"，因为采样间隔恒为 1 秒，差值即速率。 */
    public float valueAt(int tab, int i) {
        int n = pointCount(tab);
        if (i < 0 || i >= n) return 0f;
        switch (tab) {
            case TAB_HP:
                return hp[slot(i)];
            case TAB_SCORE:
                return (float) (score[slot(i + 1)] - score[slot(i)]);
            default:
                return kills[slot(i + 1)] - kills[slot(i)];
        }
    }

    private int slot(int i) {
        int s = head - count + i;
        return s < 0 ? s + CAP : (s >= CAP ? s - CAP : s);
    }
}
