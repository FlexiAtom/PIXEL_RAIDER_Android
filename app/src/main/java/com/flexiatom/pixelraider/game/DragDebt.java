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
 * 触屏拖动的位移账（纯算术，可测）。
 *
 * <p>手指每动一次就记一笔欠账，战机每帧按限速放一部分出去。为什么不直接把手指位移搬到坐标上：
 * 那样走位速度恒等于手指速度，成长树的"机动"、商店的"疾速引擎"、THRUSTS 状态三个乘子在
 * 主输入上全部为 0——玩家花钱买的数没人读。记账之后它们才有落点。
 *
 * <p>同时规格 §二 的口径没变：这是**相对位移**拖动，按下点即锚点，战机不跟随手指的绝对位置
 * （跟随绝对位置的话手指会挡住机体）。欠账只表示"还差多少没走完"，不表示"手指在哪"。
 */
public final class DragDebt {

    /** 还没放出去的欠账（逻辑像素）。 */
    public float pendingX, pendingY;
    /** 最近一次 {@link #consume} 放行的位移——调用方把它加到机体坐标上。 */
    public float appliedX, appliedY;

    public void add(float dx, float dy) {
        pendingX += dx;
        pendingY += dy;
    }

    public void clear() {
        pendingX = 0f;
        pendingY = 0f;
        appliedX = 0f;
        appliedY = 0f;
    }

    public boolean hasPending() {
        return pendingX != 0f || pendingY != 0f;
    }

    /**
     * 消费一帧。
     *
     * @param dt         世界步长（秒）——暂停与 hit-stop 期间由调用方给缩减后的值
     * @param speed      含全部乘子的速度（逻辑像素/秒）
     * @param catchupSec 欠账额度（秒）：一次甩动最多欠这么多行程，停手后不会继续飞
     */
    public void consume(float dt, float speed, float catchupSec) {
        appliedX = 0f;
        appliedY = 0f;
        if (!hasPending()) return;
        float len = (float) Math.sqrt(pendingX * pendingX + pendingY * pendingY);
        float owed = speed * catchupSec;
        if (len > owed) {
            float k = owed / len;
            pendingX *= k;
            pendingY *= k;
            len = owed;
        }
        float budget = speed * dt;
        if (len <= budget) {
            appliedX = pendingX;
            appliedY = pendingY;
            pendingX = 0f;
            pendingY = 0f;
            return;
        }
        float k = budget / len;
        appliedX = pendingX * k;
        appliedY = pendingY * k;
        pendingX -= appliedX;
        pendingY -= appliedY;
    }
}
