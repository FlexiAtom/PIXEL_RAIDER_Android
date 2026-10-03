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
 * 雷达屏的坐标换算与点选判定（规格 §四 HUD 右侧那块「半透明绿色正方形」）。
 *
 * <p>与 {@link HudLayout} / {@link PauseLayout} 同族：**纯算术、无状态、不碰 Canvas**，
 * 这样"横 5:1、竖 10:1 的各向异性压缩"这类错法能被单测钉死，不必等人眼在设备上比对。
 *
 * <p><b>为什么必须两个 scale</b>：战场是 240 宽 × 随屏生长的 {@code battleHeight} 高
 * （{@code Screen.LOGIC_H = clamp(round(240 × aspect), 320, 560)}），而雷达是正方形。
 * 拿一个数除两边，竖屏手机上战场会被压扁或裁掉——所以本类**没有**"统一比例"这种入口。
 *
 * <p>这里全部是**雷达本地坐标**（0..side，左上角为原点）；落到屏幕的平移由调用方加
 * {@link HudLayout#RADAR_LEFT} / {@link HudLayout#RADAR_TOP}，命中端同理。
 */
public final class RadarLayout {

    private RadarLayout() { }

    /** 战场横坐标 → 雷达本地横坐标。战场宽度非正（几何还没装好）时给 0，不给 NaN。 */
    public static float toLocalX(float ex, float battleWidth, float side) {
        return battleWidth <= 0f || side <= 0f ? 0f : ex * side / battleWidth;
    }

    /** 战场纵坐标 → 雷达本地纵坐标；与横的那条**各自算**，见类注释那条"必须两个 scale"。 */
    public static float toLocalY(float ey, float battleHeight, float side) {
        return battleHeight <= 0f || side <= 0f ? 0f : ey * side / battleHeight;
    }

    /**
     * 钳进方块内。敌人在进场前 {@code y < 0}、离场后 {@code y > battleHeight}，
     * 换算出来就在框外——**贴边画**而不是不画：雷达上"顶端有一排点在等进场"这件事正是玩家要看的，
     * 静默跳过会让这块屏读起来像"上面没人"。
     */
    public static float clampToSquare(float v, float side) {
        if (side <= 0f) return 0f;
        return v < 0f ? 0f : (v > side ? side : v);
    }

    /**
     * 容差内的**最近**点：返回它的下标，容差内一个都没有 ⇒ -1（调用方据此**取消指定**，
     * 不是"就近选一个"——他那条逐字是「死了即清空」，空掉是玩家意图，替他挑第二只才是越权）。
     *
     * <p>取最近而不是"命中者取第一个"：48 格的屏里两只近邻敌会糊成一团（横 5:1、竖 10:1 的
     * 压缩使然），第一个遇到的那个取决于遍历顺序，玩家读起来就是"我点的是旁边那只"。
     *
     * @param tol 容差，雷达本地像素（取值在 {@code Balance.radar.hitTolerance}）
     */
    public static int nearestOf(float[] xs, float[] ys, int count, float tx, float ty, float tol) {
        int best = -1;
        float bestD2 = Float.MAX_VALUE;
        float tol2 = tol * tol;
        for (int i = 0; i < count; i++) {
            float dx = xs[i] - tx;
            float dy = ys[i] - ty;
            float d2 = dx * dx + dy * dy;
            if (d2 <= tol2 && d2 < bestD2) {
                bestD2 = d2;
                best = i;
            }
        }
        return best;
    }
}
