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
package com.flexiatom.pixelraider.game;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 触屏位移账（规格 §二 相对位移拖动）。
 *
 * <p>这块的失败方式全在"手感"里，肉眼在截图上看不出来：欠账不封顶 → 停手之后战机自己飞；
 * 按轴分量放行 → 斜着拖比直着拖快一倍；忘记消费 → 乘子白买。所以每一条都手推写死。
 */
public class DragDebtTest {

    private static final float EPS = 1e-4f;
    /** 基准现场：速度 150、60fps → 每帧额度 2.5；欠账额度 0.12 秒 → 18。 */
    private static final float SPEED = 150f;
    private static final float DT = 1f / 60f;
    private static final float CATCHUP = 0.12f;

    private final DragDebt d = new DragDebt();

    @Test
    public void nothingPendingAppliesNothing() {
        d.consume(DT, SPEED, CATCHUP);
        assertEquals(0f, d.appliedX, EPS);
        assertEquals(0f, d.appliedY, EPS);
        assertFalse(d.hasPending());
    }

    /** 小额欠账一帧结清：跟手是默认态，只有甩动才该出现延迟。 */
    @Test
    public void smallDebtSettlesWithinOneFrame() {
        d.add(2f, 0f);
        d.consume(DT, SPEED, CATCHUP);
        assertEquals(2f, d.appliedX, EPS);
        assertEquals(0f, d.pendingX, EPS);
        assertFalse(d.hasPending());
    }

    /** 每笔 MOVE 累加，不是覆盖：一次拖动里事件比帧密得多。 */
    @Test
    public void movesAccumulate() {
        d.add(1f, -0.5f);
        d.add(2f, -0.5f);
        assertEquals(3f, d.pendingX, EPS);
        assertEquals(-1f, d.pendingY, EPS);
    }

    /**
     * 欠账封顶在 0.12 秒行程（18）：甩出 20 也只欠 18。
     *
     * <p>这条是"停手之后战机不该继续自己飞"的算术形式。不封顶的话，一次 200 像素的甩动
     * 会在之后 1.3 秒里一直拽着机体走——那已经不是拖动，是自动驾驶。
     */
    @Test
    public void flickIsCappedAtTheCatchupDistance() {
        d.add(20f, 0f);
        d.consume(DT, SPEED, CATCHUP);
        assertEquals(2.5f, d.appliedX, EPS);          // 一帧只放行 speed×dt
        assertEquals(15.5f, d.pendingX, EPS);         // 20 被钳到 18，再扣掉走掉的 2.5
    }

    /** 斜向不比正向快：放行量沿欠账方向，不是各轴分别给额。 */
    @Test
    public void diagonalDoesNotGetDoubleBudget() {
        d.add(6f, 8f);                                 // |(6,8)| = 10
        d.consume(DT, SPEED, CATCHUP);
        assertEquals(1.5f, d.appliedX, EPS);           // 10 的方向上走 2.5
        assertEquals(2f, d.appliedY, EPS);
        assertEquals(2.5f, Math.sqrt(d.appliedX * d.appliedX + d.appliedY * d.appliedY), EPS);
        assertEquals(7.5f, Math.sqrt(d.pendingX * d.pendingX + d.pendingY * d.pendingY), EPS);
    }

    /** 速度乘子真的落到走位上：同一笔欠账，2 倍速一帧放行 2 倍位移。 */
    @Test
    public void speedMultiplierMovesTheShip() {
        d.add(20f, 0f);
        d.consume(DT, SPEED, CATCHUP);
        float base = d.appliedX;
        DragDebt doubled = new DragDebt();
        doubled.add(20f, 0f);
        doubled.consume(DT, SPEED * 2f, CATCHUP);
        assertTrue("买了机动但走位没变快", doubled.appliedX > base * 1.9f);
        assertEquals(5f, doubled.appliedX, EPS);
    }

    /** 定格帧（dt=0）一分都不放行，欠账原样留着——hit-stop 期间机体不该滑。 */
    @Test
    public void frozenFrameSpendsNothing() {
        d.add(5f, 0f);
        d.consume(0f, SPEED, CATCHUP);
        assertEquals(0f, d.appliedX, EPS);
        assertEquals(5f, d.pendingX, EPS);
    }

    /** 速度为 0（读表读飞了）不该产出 NaN，也不该留下永远走不掉的欠账。 */
    @Test
    public void zeroSpeedClearsWithoutNaN() {
        d.add(5f, 5f);
        d.consume(DT, 0f, CATCHUP);
        assertEquals(0f, d.appliedX, 0f);
        assertEquals(0f, d.pendingX, 0f);
        assertFalse("欠账不该变成走不掉的死账", d.hasPending());
        assertFalse(Float.isNaN(d.appliedY));
    }

    /** 长时间不再动手指之后，累计放行量恰好等于封顶的那段行程，不多不少。 */
    @Test
    public void totalTravelAfterOneFlickIsTheCatchupDistance() {
        d.add(400f, 0f);                               // 一次横跨全场的甩动
        float moved = 0f;
        for (int frame = 0; frame < 600; frame++) {
            d.consume(DT, SPEED, CATCHUP);
            moved += d.appliedX;
        }
        assertEquals(SPEED * CATCHUP, moved, 0.05f);
        assertFalse(d.hasPending());
    }

    @Test
    public void clearDropsBothTheDebtAndTheLastApply() {
        d.add(9f, 9f);
        d.consume(DT, SPEED, CATCHUP);
        assertTrue(d.appliedX > 0f);
        d.clear();
        assertFalse(d.hasPending());
        assertEquals(0f, d.appliedX, 0f);
        assertEquals(0f, d.appliedY, 0f);
    }
}
