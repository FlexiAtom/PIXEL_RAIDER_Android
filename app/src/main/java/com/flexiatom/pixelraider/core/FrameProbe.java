/*
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
 * 帧内分段计时（诊断仪器，不是玩法）。
 *
 * 为什么要它：真机读到的 22~26fps 是**整帧真实间隔**的 EMA（见 {@link Time}），
 * 它把"锁画布阻塞 / 逻辑 / 绘制 / 提交"混成一个数。38ms 花在哪一段，
 * 决定的修法完全不同——锁与提交是排队与缓冲尺寸，绘制是软件光栅算力，逻辑是算法复杂度。
 *
 * 三条自我约束：
 * - **每帧零分配**（规格 §三）：全部是定长数组下标，段名不进字符串、不建对象。
 * - **自身开销可忽略**：一次 {@code System.nanoTime()} 约几十纳秒，十段 × 2 次 = 每帧不到 1µs，
 *   而它要解释的是 38_000_000ns 级的差，instrument 不污染被测对象。
 * - **平滑口径与 {@link Time} 一致**：同一个 EMA 系数 0.1，这样"面板 FPS"与分段数能对得上账。
 *
 * 段之间**可以嵌套**（{@link #DRAW} 罩住 BG/ENT/FX/HUD/MODAL），所以这里不校验重叠；
 * 读表时按"父段 ≈ 子段之和"自查：对不上就是有待测的黑洞。
 */
public final class FrameProbe {

    public static final int LOCK = 0;      // holder.lockCanvas() —— 等一块空闲缓冲
    public static final int STEP = 1;      // 逻辑步（含碰撞、粒子推进）
    public static final int DRAW = 2;      // host.frame() 总时长（含 STEP）
    public static final int BG = 3;        // 清屏 + 背景层 + 战斗框
    public static final int ENT = 4;       // 掉落 / 敌人 / 机体 / 双方弹幕
    public static final int FX = 5;        // 特效与全屏闪光
    public static final int HUD = 6;       // HUD 五行 + 信息栏 + FPS/调试行
    public static final int MODAL = 7;     // 波次横幅 + 结算页
    public static final int POST = 8;      // unlockCanvasAndPost() —— 把帧交回合成器
    public static final int TAIL = 9;      // 暂停面板 + 重开转场（画在 HUD 之上）

    /**
     * {@link #BG} 的子段。真机实测 38.4ms 的整帧里有 36.6ms 落在 BG，"背景慢"这个结论
     * 不足以决定改法——六层里 glow / 星云 / 星场 / 尘埃的**像素覆盖量差一个数量级**，
     * 各自的修法也完全不同（前者是预混合，中间那两个是"别再用全屏位图"）。
     */
    public static final int BG_CLR = 10;   // drawColor 全屏清屏
    public static final int BG_GLOW = 11;  // 层 1：空气辉（预合成成不透明后的全屏拷贝）
    public static final int BG_NEB = 12;   // 层 2：星云双相位平铺叠加
    public static final int BG_PLAN = 13;  // 层 3：行星
    public static final int BG_STAR = 14;  // 层 4：五层视差星场（逐星 drawRect）
    public static final int BG_DUST = 15;  // 层 5：尘埃带 + 层 6 流星 + 战斗框

    public static final int COUNT = 16;

    private final long[] marks = new long[COUNT];
    private final float[] emaMs = new float[COUNT];

    public void begin(int section) {
        marks[section] = System.nanoTime();
    }

    public void end(int section) {
        float ms = (System.nanoTime() - marks[section]) / 1_000_000f;
        emaMs[section] += (ms - emaMs[section]) * 0.1f;
    }

    /** 本段的平滑毫秒。 */
    public float ms(int section) {
        return emaMs[section];
    }
}
