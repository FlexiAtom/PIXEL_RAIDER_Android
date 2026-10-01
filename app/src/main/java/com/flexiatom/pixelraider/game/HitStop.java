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
 * hit-stop：[规格] 重击时**世界定格 30~50ms，但震屏继续衰减**。
 *
 * 这一条看起来只是手感，实际上是两个时钟的分离：世界时间停住、表现时间照常走。
 * 如果实现成"整个 frame 跳过"，震屏会一起冻结，画面卡在最大偏移上再突然弹回——
 * 那正是 hit-stop 最丑的样子。所以这里只交还"世界该走多少秒"，震屏由调用方拿满 dt 去衰减。
 */
public final class HitStop {

    private float remaining;

    public void arm(float seconds) {
        if (seconds > 0f) remaining = seconds;
    }

    public void step(float dt) {
        remaining -= dt;
        if (remaining < 0f) remaining = 0f;
    }

    public boolean active() {
        return remaining > 0f;
    }

    public float remaining() {
        return remaining;
    }

    /**
     * 本帧世界应前进的秒数：定格期间为 0，最后一帧给出不足额度的余量，不让定格吃掉一帧以上。
     * 调用方**不得**用返回值去衰减震屏——那会把两个时钟又并回一个。
     */
    public float worldSecondsFor(float frameDt) {
        if (remaining <= 0f) return frameDt;
        float used = remaining > frameDt ? frameDt : remaining;
        remaining -= used;
        return frameDt - used;
    }

    /** [规格] 30~50ms。 用 {@link Balance.Overload#hitStopMin}/{@code hitStopMax} 随机取，越重的击取越高端。 */
    public static float secondsFor(float severity01) {
        float k = severity01 < 0f ? 0f : (severity01 > 1f ? 1f : severity01);
        return Balance.overload.hitStopMin + (Balance.overload.hitStopMax - Balance.overload.hitStopMin) * k;
    }
}
