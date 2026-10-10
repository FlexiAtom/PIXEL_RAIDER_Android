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
 * 超载槽：[规格] **30 秒冷却制**，不是击杀蓄能制。
 *
 * 为什么冷却制：蓄能制下"什么时候能清场"取决于打得快不快，玩家无法规划；
 * 冷却制是一个可预期的战略资源——倒计时读得出来，就知道这一波能不能撑到转好。
 *
 * 初始即为可用：开局第一个 Boss 就允许释放，否则教学期拿不到这个机制。
 * 纯算术、不碰 android，JVM 单测直接钉"释放后 30 秒不可用、转满恰好 30 秒"。
 */
public final class OverloadMeter {

    private float remaining;

    public OverloadMeter() {
        remaining = 0f;   // 开局可用
    }

    /** 每帧推进；只减不增，夹在 0。 */
    public void step(float dt) {
        remaining -= dt;
        if (remaining < 0f) remaining = 0f;
    }

    public boolean ready() {
        return remaining <= 0f;
    }

    /** 还需等待的秒数，已就绪为 0。HUD 只在 >0 时画倒计时。 */
    public float remaining() {
        return remaining;
    }

    /** 0 = 刚释放完，1 = 就绪。用于环状进度条。 */
    public float charge01() {
        float cd = cooldown();
        if (cd <= 0f) return 1f;
        return 1f - remaining / cd;
    }

    /**
     * 释放。**未冷却则拒绝**，返回 false 且不改变状态——调用方据此决定要不要播特效，
     * 免得连点把冷却"重置回 30 秒"这种恶性反馈。
     */
    public boolean trigger() {
        if (!ready()) return false;
        remaining = cooldown();
        return true;
    }

    /** 冷却时长每次 trigger 现读表，调平衡改 {@link Balance#overload} 下一帧生效。 */
    public static float cooldown() {
        return Balance.overload.cooldownSec;
    }

    /** 调试/存档用：强制灌满或清空。 */
    public void forceReady() {
        remaining = 0f;
    }

    public void forceCooling(float seconds) {
        remaining = seconds < 0f ? 0f : seconds;
    }

    // ---- 快照 -----------------------------------------------------------------------------

    /**
     * 只这一格。它决定读档后"超载还能不能立刻再按"——那条 30 秒冷却是这个机制唯一的规划依据
     * （类注释：可预期的战略资源，倒计时读得出来才知道这一波撑不撑得到转好），丢了它就等于
     * 白送一次释放，而玩家看得见环是空的。
     *
     * <p>{@code charge01()} 现算，不存：它是 {@code remaining} 除以**当期**冷却时长，存了就会在
     * 有人调 {@link Balance#overload} 之后给出两个不同的环。
     */
    public void writeSnapshot(Buf w) {
        w.writeFloat(remaining);
    }

    public void restoreSnapshot(Buf.Reader r) {
        float left = r.readFloat();
        // 负值与 ready() 的判据是 `<= 0f`，读进来一个负数不会报错，只会让环算出 1.x 的进度。
        // 上界给到冷却的 4 倍：那已经是"这台机器调了个离谱数值"或者字节错位，两种都不该进局。
        if (left < 0f || left > cooldown() * 4f) {
            throw new Buf.Invalid("overload remaining " + left + " outside 0.." + cooldown() * 4f);
        }
        remaining = left;
    }
}
