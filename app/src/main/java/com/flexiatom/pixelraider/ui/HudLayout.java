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

import com.flexiatom.pixelraider.core.RectI;
import com.flexiatom.pixelraider.plat.Screen;

/**
 * HUD 五行的几何真源（规格 §四）。
 *
 * 全部是纯算术：绘制端与命中端读同一份矩形，测试直接钉"互不重叠"和"避开暂停按钮"，
 * 不靠人眼在设备上比对（规格 §九.20）。改动这里必须同步改 {@code HudLayoutTest}，
 * 因为规格明文写死的数只剩两个了：行1 y 2~16、右边界 208 / 215 / 214~232。
 *
 * <p><b>行2 的 y 22~62 已经在 2026-09-24 被有意顶破</b>：汉字内嵌像素字体的原生网格是 12px，
 * 8px 的中文标签槽装不下（用户指令"同步加大相关的UI部分配合12px"）。行2 现在是 y 22~70，
 * 下面所有行位一律由它往外推，不再各写绝对数。
 *
 * 一条口径先说清楚：规格说 FPS 面板"下边在 Boss 血条之上"，又说"Boss 血条会盖掉面板，
 * 而 Boss 战恰恰最需要看帧率"——后者是在陈述问题，解法是它给的"绘制层级在所有 UI 之上"。
 * 这里把两条都满足：几何上 FPS 让位（bottom ≤ boss.top），z 序上 FPS 仍最后画，
 * 模态面板再盖住 FPS。
 */
public final class HudLayout {

    private HudLayout() { }

    public static final int W = Screen.LOGIC_W;

    // ---- 行1：顶部信息 + 暂停 ---------------------------------------------------------------
    public static final int ROW1_TOP = 2;
    public static final int ROW1_BOTTOM = 16;
    /** 数值右对齐到这个 x，为的是不压到暂停按钮（规格 §四 明文）。 */
    public static final int INFO_RIGHT = 208;
    public static final int PAUSE_LEFT = 214;
    public static final int PAUSE_RIGHT = W - 8;      // 232

    // ---- 行2：四条状态条（竖向严格对齐） -----------------------------------------------------
    // 汉字像素字的原生网格是 12px（10px 网格装不下多数汉字，字库作者直接把那批字砍了）。
    // 像素字只能按原生网格或其整数倍渲染，糊成 8px 等于把这套决策作废，所以这一档整体抬高。
    // 代价：**顶破规格 §四 明文写死的 `行2 (y 22~62)`** —— 2026-09-24 用户指令"同步加大相关的
    // UI 部分配合12px"授权，行2 变为 y 22~70。
    public static final int ROW2_TOP = 22;
    public static final int BAR_PITCH = 12;
    public static final int BAR_ROWS = 4;             // 生命 / 护盾 / 过载 / 敌人
    public static final int ROW2_BOTTOM = ROW2_TOP + BAR_PITCH * BAR_ROWS;   // 70
    public static final int LABEL_X = 4;
    public static final int LABEL_W = 24;             // 两个 12px 汉字（等宽字里 ASCII 占半格）
    public static final int LABEL_H = 12;             // = BAR_PITCH：烘焙高度恰好一行，相邻两行相切
    public static final int BAR_X = LABEL_X + LABEL_W + 4;                   // 32
    public static final int BAR_W = 88;
    public static final int BAR_RIGHT = BAR_X + BAR_W;                       // 120
    /** main 模式条高 6px（生命条保留视觉权重），通用条 4px。 */
    public static final int MAIN_BAR_H = 6;
    public static final int SUB_BAR_H = 4;

    // ---- 行3：状态效果胶囊（环形消退，无秒数） ------------------------------------------------
    public static final int ROW3_TOP = ROW2_BOTTOM + 2;                      // 72
    public static final int CAP_RIGHT = 215;
    public static final int CAP_RING = 10;
    public static final int CAP_NAME_W = 24;          // 状态名两位 12px 汉字
    public static final int CAP_GAP = 2;
    /** 一枚胶囊占的横向步长。 */
    public static final int CAP_STEP = CAP_RING + CAP_GAP + CAP_NAME_W;      // 36
    public static final int ROW3_BOTTOM = ROW3_TOP + CAP_RING;               // 82
    /** 拾取层同时只保留一个，加上环境层最多两枚；三枚就会顶到状态条列。 */
    public static final int CAP_MAX = 2;

    // ---- 帧数面板（纯文本，6px 点阵） ---------------------------------------------------------
    public static final int FPS_RIGHT = PAUSE_RIGHT;
    public static final int FPS_TOP = ROW3_BOTTOM + 2;                       // 84
    public static final int FPS_LINE_H = 7;
    public static final int FPS_BOTTOM = FPS_TOP + FPS_LINE_H * 2;           // 98
    public static final int FPS_W = 46;

    // ---- Boss 血条（名 + 条）-----------------------------------------------------------------
    /**
     * 三条 Boss 靠弹道区分，名字得给出来；这一行夹在帧数面板与血条之间，不让谁去叠谁。
     *
     * <p>这三段现在**由上面的出口串行推出**，不再各写一个魔数：汉字标签抬到 12px 之后行2 长了
     * 8 格，任何一处还留着旧绝对值，帧数面板就会压在状态胶囊上、血条又压回帧数面板。
     */
    public static final int BOSS_LABEL_TOP = FPS_BOTTOM + 2;                 // 100
    public static final int BOSS_LABEL_BOTTOM = BOSS_LABEL_TOP + LABEL_H;      // 112
    public static final int BOSS_TOP = BOSS_LABEL_BOTTOM + 2;                // 114
    public static final int BOSS_BOTTOM = BOSS_TOP + 7;
    public static final int BOSS_LEFT = 4;
    public static final int BOSS_RIGHT = W - 4;

    // ---- 取位置（一律写回调用方复用的 RectI，不在这里 new） -----------------------------------

    public static int barTop(int row) {
        return ROW2_TOP + row * BAR_PITCH;
    }

    /** 第 row 条的条体矩形；main 决定 6px 还是 4px，但**中心线对齐**，不然换高度就错位。 */
    public static void barRect(int row, boolean main, RectI out) {
        int h = main ? MAIN_BAR_H : SUB_BAR_H;
        int mid = barTop(row) + BAR_PITCH / 2;
        out.set(BAR_X, mid - h / 2, BAR_X + BAR_W, mid - h / 2 + h);
    }

    public static void labelRect(int row, RectI out) {
        int mid = barTop(row) + BAR_PITCH / 2;
        int top = mid - LABEL_H / 2;
        out.set(LABEL_X, top, LABEL_X + LABEL_W, top + LABEL_H);
    }

    /** 第 slot 枚胶囊（0 = 最右）。环在左、名在右，整组右对齐到 {@link #CAP_RIGHT}。 */
    public static void capsuleRects(int slot, RectI ringOut, RectI nameOut) {
        int right = CAP_RIGHT - slot * CAP_STEP;
        nameOut.set(right - CAP_NAME_W, ROW3_TOP, right, ROW3_TOP + CAP_RING);
        ringOut.set(right - CAP_NAME_W - CAP_GAP - CAP_RING, ROW3_TOP,
                right - CAP_NAME_W - CAP_GAP, ROW3_TOP + CAP_RING);
    }

    /**
     * 规格 §二 说按钮"绘制 18×18"，§四 又把行1 钉在 y 2~16——两条给的只有宽度一致。
     * 这里让高度给行1（18×14），因为 §二 那句话的论点是"18 太小、命中区要外扩到 34"，
     * 高度少 4px 不改变这个结论；命中区仍然由 Widgets.hitRect 统一撑到 48dp。
     */
    public static void pauseRect(RectI out) {
        out.set(PAUSE_LEFT, ROW1_TOP, PAUSE_RIGHT, ROW1_BOTTOM);
    }

    public static void bossBarRect(RectI out) {
        out.set(BOSS_LEFT, BOSS_TOP, BOSS_RIGHT, BOSS_BOTTOM);
    }

    // ---- 炸弹按钮（战斗区右下角）-------------------------------------------------------------

    /**
     * 绘制框：图标 16 + 间距 3 + 两位点阵数字 12 + 左右内缩。
     *
     * <p>宽度不是随手给的：炸弹是"存货数"，一位数与两位数都得装下，否则第 10 枚会把数字画出框。
     */
    public static final int BOMB_W = 34;
    public static final int BOMB_H = 20;
    /** 图标边长：8 格像素精灵的 2 倍，整数倍放大不糊边（规格 §三）。 */
    public static final int BOMB_ICON = 16;
    public static final int BOMB_INSET = 6;
    /** 图标左内缩与数字间距。 */
    public static final int BOMB_PAD = 2, BOMB_NUM_GAP = 3;

    /**
     * 炸弹按钮矩形，**HUD 相对坐标**（与暂停按钮同一套：绘制端在 hudTop 平移组里，命中端另加偏移）。
     *
     * <p>钉在战斗区右下角：第一指全程负责拖动战机，第二指落在右下角是移动端射击的常规分工。
     * 规格 §掉落物 把炸弹改成"入栏，由玩家决定何时放"——那必须是一个看得见的按钮，
     * 不能藏进又一个手势里（双击已经给了超载）。
     */
    public static void bombRect(int battleHeight, RectI out) {
        int bottom = battleHeight - BOMB_INSET;
        out.set(W - BOMB_INSET - BOMB_W, bottom - BOMB_H, W - BOMB_INSET, bottom);
    }

    public static void bossLabelRect(RectI out) {
        out.set(BOSS_LEFT, BOSS_LABEL_TOP, BOSS_RIGHT, BOSS_LABEL_BOTTOM);
    }

    public static void fpsRect(RectI out) {
        out.set(FPS_RIGHT - FPS_W, FPS_TOP, FPS_RIGHT, FPS_BOTTOM);
    }

    /** 条体填充宽：ratio 先钳到 0..1，超界（读表读飞了）也不会画出条框。 */
    public static int fillWidth(float ratio) {
        return Math.round(BAR_W * clamp01(ratio));
    }

    /**
     * 残影段的宽度（像素）：从真值右端量到残影右端，正好是"掉血量就是那段残影的长度"这句话
     * 的算术形式。写成 0 表示没有残影（回血或满血），绘制端据此跳过——反向矩形会被静默吞掉。
     */
    public static int trailWidth(float ratio, float trail) {
        if (trail <= ratio) return 0;
        return fillWidth(trail) - fillWidth(ratio);
    }

    public static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** 环形消退：1 = 满环，0 = 空。绘制端把它换成 sweepAngle。 */
    public static float ringRemaining01(float remainingSec, float durationSec) {
        if (durationSec <= 0f) return 1f;        // 环境层没有倒计时：画满环，不是空环
        return clamp01(remainingSec / durationSec);
    }

    /**
     * 血条拖尾残影：残影值只往下追真值，速度恒定（规格 §四"掉血量就是那段残影的长度"）。
     * 回血时残影立刻等于真值——不然血条会凭空短一截，看起来像还在掉血。
     */
    public static float trailNext(float trail, float target, float dt, float speedPerSec) {
        if (dt <= 0f) return trail;
        if (target >= trail) return target;
        float next = trail - speedPerSec * dt;
        return next > target ? next : target;
    }
}
