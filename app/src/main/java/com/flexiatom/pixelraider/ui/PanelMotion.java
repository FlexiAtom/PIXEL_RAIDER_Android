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
 * 整块面板共用的入场节奏（规格 §四：遮罩渐暗、面板从上方落位、按钮级联延后）。
 *
 * <p>为什么单独一个类而不是各面板自己写一份：暂停、商店、设置、图鉴四块面板用的是**同一种**
 * 拉下数据板的手感，时长差 0.05 秒就会被读成"这块面板比那块迟钝"。数值一旦分叉就没有测试
 * 能发现——它不改变任何一帧的正确性，只改变手感。
 *
 * <p>纯算术（不 import android），绘制端与命中端都读同一组函数：淡入途中两者差一像素，
 * 玩家点到的就是面板外面。
 */
public final class PanelMotion {

    private PanelMotion() { }

    /** 遮罩浓度上限；不得低于 {@link #MASK_FLOOR}，否则浅色字压在弹幕上读不出。 */
    public static final float MASK_ALPHA = 0.78f;
    public static final float MASK_FLOOR = 0.7f;

    /** 入场总时长；按钮每级延后 {@link #BUTTON_STEP}，末级正好在 {@link #IN_SEC} 落定。 */
    public static final float IN_SEC = 0.28f;
    public static final float BUTTON_STEP = 0.08f;
    public static final float BUTTON_RAMP = 0.12f;

    /** 入场总进度（遮罩渐暗与面板落位共用一个时钟）。 */
    public static float enterProgress(float elapsedSec) {
        return ramp(elapsedSec, IN_SEC);
    }

    /**
     * 第 level 级按钮（0 = 主操作）的自身进度。
     *
     * <p>走**绝对秒数**而不是入场总进度：总进度是遮罩与面板共用的时钟，而规格给级联的是
     * "每级延后 0.08s"这个具体秒数——换算成份额的话，末级会在入场结束时只淡到一半。
     */
    public static float buttonProgress(int level, float elapsedSec) {
        return ramp(elapsedSec - level * BUTTON_STEP, BUTTON_RAMP);
    }

    /**
     * 入场位移（负值 = 从上方落下来）。
     *
     * <p>{@code easeOutBack} 会过冲，所以末段这个值是**正的**（面板略低于最终位）——那不是 bug。
     * @param liftPx 该面板自己的抬升距离（各面板的位移量属于版面，不属于节奏）
     */
    public static int liftFor(float p, int liftPx) {
        return Math.round(-(1f - Easing.easeOutBack(p)) * liftPx);
    }

    /** 遮罩在当前入场进度上的浓度。 */
    public static float maskAlphaAt(float p) {
        return Easing.easeOutCubic(p) * MASK_ALPHA;
    }

    private static float ramp(float elapsed, float length) {
        if (length <= 0f) return elapsed >= 0f ? 1f : 0f;
        if (elapsed <= 0f) return 0f;
        return elapsed >= length ? 1f : elapsed / length;
    }
}
