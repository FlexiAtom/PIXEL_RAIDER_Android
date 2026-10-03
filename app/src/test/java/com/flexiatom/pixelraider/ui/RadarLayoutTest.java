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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.flexiatom.pixelraider.game.Balance;

import org.junit.Test;

/**
 * 雷达屏的坐标换算与点选判定（规格 §四那块绿方块）。
 *
 * <p>期望值全部**手推写死**，不在测试里把「× side / width」再算一遍——那是自证。这里每个数都能用纸笔
 * 复核：战场 240×480 折进 48 格的正方形，就是横 5:1、竖 10:1。
 *
 * <p><b>这块几何值得单独钉的理由</b>：各向异性是它最容易**静默做错**的一处——两边共用一个 0.2，
 * 竖屏上敌人会全挤在方块上半截，而画面照样是一屏绿底的点，肉眼看不出比例错了。点选端的错更坏：
 * 玩家点 A 选中 B。两端都是纯算术，所以在纯算术里钉死。
 */
public class RadarLayoutTest {

    /** 竖屏那一档战场高（宽恒 240，高随屏生长；{@code Screen.LOGIC_H} 的常见取值）。 */
    private static final float W = 240f;
    private static final float H = 480f;
    private static final float SIDE = HudLayout.RADAR_SIDE;          // 48

    // ---- 一、两个 scale，不是一个 ----------------------------------------------------------------

    /**
     * 横 5:1、竖 10:1——**同一个比例数用两边是错的**。
     *
     * <p>取同一个战场距离 120 往两轴各折一次，这条对比才干净：横的落 24（120 ÷ 5），纵的落 12
     * （120 ÷ 10）。谁把它"简化"成一条统一比例，红法就很具体——按横比 0.2 去除纵的那一枚，
     * 得到 24，而正确的数是 12；竖屏上于是所有敌点在方块里偏高一致的一截，画面照样读得通。
     */
    @Test
    public void theTwoAxesCompressByDifferentAmounts() {
        assertEquals(24f, RadarLayout.toLocalX(120f, W, SIDE), 1e-4f);   // 120 × 48/240 = 120 ÷ 5
        assertEquals(12f, RadarLayout.toLocalY(120f, H, SIDE), 1e-4f);   // 120 × 48/480 = 120 ÷ 10
    }

    /** 四条边：横的两端落到方块左右缘，纵的中点落到中线。 */
    @Test
    public void cornersLandWhereTheAspectSays() {
        assertEquals(0f, RadarLayout.toLocalX(0f, W, SIDE), 1e-4f);
        assertEquals(SIDE, RadarLayout.toLocalX(W, W, SIDE), 1e-4f);
        assertEquals(0f, RadarLayout.toLocalY(0f, H, SIDE), 1e-4f);
        assertEquals(SIDE, RadarLayout.toLocalY(H, H, SIDE), 1e-4f);
        assertEquals(SIDE / 2f, RadarLayout.toLocalY(H / 2f, H, SIDE), 1e-4f);
    }

    /**
     * 尺寸非正（几何还没装好）时给 **0**，不给 NaN／Infinity。
     *
     * <p>不是防御性凑数：{@code installGeometry} 之前若真被画上一帧，除零出来的 NaN 会让
     * {@code Canvas.drawCircle} 静默不画——这块屏于是读起来像"上面没人"，而真相是换算压根没跑。
     * 给 0 至少点落在角上，看得见。上面那两条 {@code assertEquals(0f, …, 0f)} 已经把 NaN 钉死了
     * （NaN 与 0 不等），这里不再重复断言一遍。
     */
    @Test
    public void aZeroSizedBattlefieldYieldsZeroNotNaN() {
        assertEquals(0f, RadarLayout.toLocalX(100f, 0f, SIDE), 0f);
        assertEquals(0f, RadarLayout.toLocalX(100f, -1f, SIDE), 0f);
        assertEquals(0f, RadarLayout.toLocalY(100f, 0f, SIDE), 0f);
        assertEquals(0f, RadarLayout.toLocalY(100f, -1f, SIDE), 0f);
        assertEquals(0f, RadarLayout.toLocalX(100f, W, 0f), 0f);
        assertEquals(0f, RadarLayout.clampToSquare(9f, 0f), 0f);
    }

    // ---- 二、框外的点贴边画，不静默跳过 ------------------------------------------------------------

    /**
     * 进场前 {@code y < 0}、离场后 {@code y > H}——都**钳到边上**，不是不画。
     *
     * <p>这块屏要给的读法之一是"顶端有一排点在等进场"。跳过等于把它翻译成"上面没人"，那是假话；
     * 贴边画最坏也只是几点对齐在同一条边框上，信息还在。
     */
    @Test
    public void offscreenPointsStickToTheRim() {
        assertEquals(0f, RadarLayout.clampToSquare(-7f, SIDE), 0f);
        assertEquals(SIDE, RadarLayout.clampToSquare(SIDE + 7f, SIDE), 0f);
        assertEquals(20f, RadarLayout.clampToSquare(20f, SIDE), 0f);          // 界内不动
        assertEquals(0f, RadarLayout.clampToSquare(RadarLayout.toLocalY(-30f, H, SIDE), SIDE), 0f);
        assertEquals(SIDE, RadarLayout.clampToSquare(RadarLayout.toLocalY(H + 30f, H, SIDE), SIDE), 0f);
    }

    // ---- 三、点选：容差内取**最近**，一个都没有就是 -1 ----------------------------------------------

    /**
     * 两点都在容差内 ⇒ 取**更近的那个**，不是遍历中先遇到的那个。
     *
     * <p>这条故意把更远的排在数组**前面**：实现若写成"第一个命中就返回"，按顺序它会返回 0，
     * 看着完全正常；只有把顺序反过来才测得出取舍到底看的是距离还是下标。
     */
    @Test
    public void theNearerPointWinsRegardlessOfOrder() {
        float[] ys = {20f, 20f};
        assertEquals(1, RadarLayout.nearestOf(new float[]{20f, 12f}, ys, 2, 14f, 20f, 8f));
        assertEquals("取舍取决于数组顺序 ⇒ 读的是'第一个命中'而不是'最近'",
                0, RadarLayout.nearestOf(new float[]{12f, 20f}, ys, 2, 14f, 20f, 8f));
    }

    /** 容差是**闭区间**：正好 8 格算命中。玩家点空那一下不该多吞一格，也不该少这一格。 */
    @Test
    public void theToleranceEdgeIsInclusive() {
        float[] xs = {10f};
        float[] ys = {10f};
        assertEquals(0, RadarLayout.nearestOf(xs, ys, 1, 18f, 10f, 8f));      // 正好 = tol
        assertEquals(-1, RadarLayout.nearestOf(xs, ys, 1, 18.01f, 10f, 8f));  // 越界一丝
    }

    /**
     * 容差内一个都没有 ⇒ **-1**（调用方据此**取消指定**）。
     *
     * <p>钉的是"不许就近选一个"：他那条逐字是「死了即清空」，空掉本身就是玩家意图；点空白处还把目标
     * 续到隔壁那只，是越权，而且让他没法主动取消指定。
     */
    @Test
    public void aTapWithNoHitReturnsMinusOneSoTheCallerClears() {
        float[] xs = {10f, 40f};
        float[] ys = {10f, 40f};
        assertEquals(-1, RadarLayout.nearestOf(xs, ys, 2, 25f, 25f, 8f));
        assertEquals(-1, RadarLayout.nearestOf(xs, ys, 0, 10f, 10f, 8f));     // 场上没人
    }

    /**
     * 只读前 {@code count} 格：草稿数组是复用的，比本帧实际敌数长，尾部是上一帧的残值。
     *
     * <p>把残值算进来会出一个设备上很难复现的 bug：敌群缩到 2 只之后，玩家仍能点中"第 3 只"——
     * 那只是上一帧还在、这帧已被摘表的那只，槽号指向一个已回收的槽。
     */
    @Test
    public void staleTailBeyondCountIsNotReadable() {
        float[] xs = {30f, 40f, 10f};     // 第 3 格是残值
        float[] ys = {30f, 40f, 10f};
        assertEquals(-1, RadarLayout.nearestOf(xs, ys, 2, 10f, 10f, 8f));
        assertEquals(0, RadarLayout.nearestOf(xs, ys, 2, 30f, 30f, 8f));
    }

    // ---- 四、与 Balance 的接线：吸附半径的量级是可解释的 --------------------------------------------

    /**
     * 48 格 / 4 格 = 每格 12；容差 8 ⇒ 吸附半径**不到一格**。
     *
     * <p>写成断言而不是注释的原因：容差一旦被调到 ≥ 半屏（24），"点空取消指定"在物理上再也做不到——
     * 每一次点击都至少命中某只。反过来调到远小于一格，密集编队就基本点不中。两端都在这里钉住。
     */
    @Test
    public void theHitToleranceSitsBetweenHalfACellAndHalfTheSquare() {
        float cell = SIDE / (float) Balance.radar.gridCells;
        assertEquals(12f, cell, 1e-4f);
        assertTrue("吸附半径不足半格 ⇒ 密集编队下基本点不中，调它之前先改这条",
                Balance.radar.hitTolerance >= cell / 2f);
        assertTrue("吸附半径过半屏 ⇒ 取消指定这件事做不到了",
                Balance.radar.hitTolerance < SIDE / 2f);
    }

    /** 敌点直径与括号边长都**小于一格**，否则密集编队糊成一团、选中框吃掉邻点。 */
    @Test
    public void dotAndBracketFitInsideOneGridCell() {
        float cell = SIDE / (float) Balance.radar.gridCells;
        assertTrue(Balance.radar.dotRadius * 2f < cell);
        assertTrue(Balance.radar.bracketLen < cell);
    }
}
