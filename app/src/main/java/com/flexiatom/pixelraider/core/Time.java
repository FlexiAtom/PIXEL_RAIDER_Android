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
package com.flexiatom.pixelraider.core;

/**
 * 固定步长时钟（规格 §一）。一个实例同时持有两套时间：
 * game —— 暂停时冻结，玩法逻辑读它；
 * ui   —— 暂停时照走，界面动画读它（否则暂停菜单按钮会僵住且定格相位取决于按暂停的那一刻）。
 *
 * 纯逻辑、不碰系统时钟，便于 JVM 单测直接验累加器行为。
 */
public final class Time {

    public static final float STEP = 1f / 60f;
    public static final float MAX_DELTA = 0.25f;
    /** 单帧补帧上限：卡顿后不追帧，超出部分直接丢弃（guard 防爆炸）。 */
    public static final int MAX_CATCHUP_STEPS = 5;

    private float accumulator;
    private float game;
    private float ui;
    private boolean paused;
    private boolean forceNextStep;

    private float frameAvgSeconds = STEP;
    private long lastFrameNanos;
    private boolean hasLastFrame;

    public float game() {
        return game;
    }

    public float ui() {
        return ui;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean p) {
        paused = p;
    }

    /** 后台回前台的首帧单独处理（规格 §零：即使有 0.25s 截断，首帧也必须钉死成 1/60）。 */
    public void forceNextFrameToOneStep() {
        forceNextStep = true;
    }

    /**
     * 喂入本帧真实耗时，返回本帧应推进的逻辑步数。
     * 暂停时仍排空累加器（否则解暂停瞬间会一次追进一堆帧）。
     */
    public int advance(float realDeltaSeconds) {
        float d;
        if (forceNextStep) {
            d = STEP;
            forceNextStep = false;
        } else if (realDeltaSeconds > MAX_DELTA) {
            d = MAX_DELTA;
        } else if (realDeltaSeconds < 0f) {
            d = 0f;
        } else {
            d = realDeltaSeconds;
        }

        ui += d;
        smoothFrameTime(d);

        accumulator += d;
        int steps = 0;
        while (accumulator >= STEP && steps < MAX_CATCHUP_STEPS) {
            accumulator -= STEP;
            steps++;
        }
        if (steps == MAX_CATCHUP_STEPS && accumulator >= STEP) {
            // 追不上了：丢掉积压，让时间跳一次而不是逐帧偿还
            accumulator = 0f;
        }
        if (paused) {
            return 0;
        }
        game += steps * STEP;
        return steps;
    }

    private void smoothFrameTime(float d) {
        if (d <= 0f) return;
        // EMA，兼顾"看得出趋势"与"不抖动"
        frameAvgSeconds += (d - frameAvgSeconds) * 0.1f;
    }

    public float fps() {
        return frameAvgSeconds > 0f ? 1f / frameAvgSeconds : 0f;
    }

    public float frameMs() {
        return frameAvgSeconds * 1000f;
    }

    /** 渲染线程的 pacing 用：记录帧起点，返回距上一帧的真实秒数。 */
    public float tickWallDelta(long nowNanos) {
        if (!hasLastFrame) {
            hasLastFrame = true;
            lastFrameNanos = nowNanos;
            return STEP;
        }
        float d = (nowNanos - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = nowNanos;
        return d;
    }

    public void resetWallClock() {
        hasLastFrame = false;
    }
}
