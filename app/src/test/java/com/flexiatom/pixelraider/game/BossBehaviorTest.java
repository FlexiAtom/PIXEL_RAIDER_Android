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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.flexiatom.pixelraider.game.Enemies.Enemy;

import org.junit.Test;

/**
 * Boss 行为与弹幕形状（纯算术，不需要渲染器就能钉死）。
 *
 * 弹幕形状是最容易"看着挺热闹其实必中"的部分，所以每条都断言到方向向量上：
 * 墙是不是全朝下、缺口是不是真的在玩家那一侧、扇形是不是以玩家为中心线。
 */
public class BossBehaviorTest {

    private static final float DT = 1f / 60f;
    private static final int W = 240;
    private static final int TOP = 55;

    @Test
    public void descendsIntoItsLaneAndHoldsThere() {
        Balance.Boss spec = Balance.bosses[Balance.Boss.DESTROYER];
        Enemy e = boss(spec, 120f, TOP - 40f);
        for (int i = 0; i < 150; i++) {
            int f = BossBehavior.advance(e, spec, DT, 120f, 320f, W, TOP);
            assertEquals("入场段不许开火", 0, f & BossBehavior.FLAG_FIRED);
        }
        assertTrue("2.5 秒还没到位", e.locked);
        for (int i = 0; i < 600; i++) {
            BossBehavior.advance(e, spec, DT, 120f, 320f, W, TOP);
        }
        assertEquals(TOP + spec.holdY, e.y, 0.5f);
    }

    @Test
    public void firstVolleyWaitsOneFullGapAfterArrival() {
        Balance.Boss spec = Balance.bosses[Balance.Boss.FORTRESS];
        Enemy e = boss(spec, 120f, TOP + spec.holdY);
        e.locked = true;
        e.anchorX = 120f;
        e.fireTimer = BossBehavior.fireGapFor(spec, e);
        boolean firedEarly = false;
        int frames = (int) (spec.fireGap / DT);
        for (int i = 0; i < frames - 2; i++) {
            firedEarly |= (BossBehavior.advance(e, spec, DT, 120f, 320f, W, TOP)
                    & BossBehavior.FLAG_FIRED) != 0;
        }
        assertFalse("还没到间隔就开火了", firedEarly);
        boolean fired = false;
        for (int i = 0; i < 4 && !fired; i++) {
            fired = (BossBehavior.advance(e, spec, DT, 120f, 320f, W, TOP)
                    & BossBehavior.FLAG_FIRED) != 0;
        }
        assertTrue("到点就该开火", fired);
    }

    @Test
    public void strafeSweepsBothSidesAndNeverLeavesTheCorridor() {
        Balance.Boss spec = Balance.bosses[Balance.Boss.SPIDER];
        Enemy e = boss(spec, W / 2f, TOP + spec.holdY);
        e.locked = true;
        e.anchorX = W / 2f;
        float min = e.x, max = e.x;
        for (int i = 0; i < 60 * 12; i++) {
            BossBehavior.advance(e, spec, DT, 120f, 320f, W, TOP);
            if (e.x < min) min = e.x;
            if (e.x > max) max = e.x;
            assertTrue("左边界外：" + e.x, e.x >= e.radius);
            assertTrue("右边界外：" + e.x, e.x <= W - e.radius);
        }
        assertTrue("摆幅不到位：" + (max - min), max - min > spec.amp);
        assertTrue("整体漂移了（位移式摆动写成了积分式）",
                Math.abs((min + max) / 2f - W / 2f) < 6f);
    }

    @Test
    public void phaseTwoTriggersOnceAndTightensTheRhythm() {
        Balance.Boss spec = Balance.bosses[Balance.Boss.DESTROYER];
        Enemy e = boss(spec, 120f, TOP + spec.holdY);
        e.locked = true;
        e.anchorX = 120f;
        e.hp = e.maxHp;
        assertEquals(0, BossBehavior.advance(e, spec, DT, 120f, 320f, W, TOP)
                & BossBehavior.FLAG_PHASE2);
        e.hp = (int) Math.floor(e.maxHp * spec.phase2AtRatio);
        int flags = 0;
        for (int i = 0; i < 5; i++) flags |= BossBehavior.advance(e, spec, DT, 120f, 320f, W, TOP);
        assertTrue("血量到位却没进二阶段", (flags & BossBehavior.FLAG_PHASE2) != 0);
        assertTrue("二阶段间隔没收紧", BossBehavior.fireGapFor(spec, e) < spec.fireGap);
        assertEquals("二阶段弹没变多", spec.pellets + spec.phase2ExtraPellets,
                BossBehavior.pelletsFor(spec, e));
        int again = 0;
        for (int i = 0; i < 60; i++) again |= BossBehavior.advance(e, spec, DT, 120f, 320f, W, TOP);
        assertEquals("二阶段反复触发", 0, again & BossBehavior.FLAG_PHASE2);
    }

    @Test
    public void destroyerFanIsCentredOnThePlayer() {
        Balance.Boss spec = Balance.bosses[Balance.Boss.DESTROYER];
        Enemy e = boss(spec, 70f, TOP + spec.holdY);
        e.locked = true;
        float[] vx = new float[BossBehavior.MAX_SHOTS];
        float[] vy = new float[BossBehavior.MAX_SHOTS];
        int n = BossBehavior.shots(e, spec, 200f, 320f, vx, vy);
        assertEquals(spec.pellets, n);
        double want = Math.atan2(320f - e.y, 200f - e.x);
        double mid = Math.atan2(vy[n / 2], vx[n / 2]);
        assertEquals("扇形中心线没对准玩家", 0d, Math.abs(BossBehavior.angleDiff(mid, want)), 1e-3);
        assertUnitVectors(vx, vy, n);
        // 扇形必须覆盖从中心向两侧各半张角
        double edge = Math.abs(BossBehavior.angleDiff(Math.atan2(vy[0], vx[0]),
                Math.atan2(vy[n - 1], vx[n - 1])));
        assertEquals("张角不对", Math.toRadians(spec.spreadDeg), edge, 1e-3);
    }

    @Test
    public void fortressWallFallsDownwardWithOneGapOnThePlayerSide() {
        Balance.Boss spec = Balance.bosses[Balance.Boss.FORTRESS];
        Enemy e = boss(spec, 120f, TOP + spec.holdY);
        e.locked = true;
        float[] vx = new float[BossBehavior.MAX_SHOTS];
        float[] vy = new float[BossBehavior.MAX_SHOTS];
        int n = BossBehavior.shots(e, spec, 170f, 320f, vx, vy);
        assertEquals("墙该留一个缺口", spec.pellets - 1, n);
        for (int i = 0; i < n; i++) {
            assertTrue("墙弹不该往上飞", vy[i] > 0f);
        }
        assertUnitVectors(vx, vy, n);
        // 缺口 = 少掉的那一度：把弹按角度排开，断言中间有一段明显的空档
        double widest = 0d;
        for (int i = 1; i < n; i++) {
            widest = Math.max(widest, Math.abs(BossBehavior.angleDiff(
                    Math.atan2(vy[i], vx[i]), Math.atan2(vy[i - 1], vx[i - 1]))));
        }
        double step = Math.toRadians(spec.spreadDeg) / (spec.pellets - 1);
        assertTrue("没有缺口（最大空档 " + widest + "，步长 " + step + "）", widest > step * 1.5d);
    }

    @Test
    public void everyBossKindIsReachableAndHasADistinctShape() {
        int[] pellets = new int[Balance.bosses.length];
        float[] spread = new float[Balance.bosses.length];
        for (int i = 0; i < Balance.bosses.length; i++) {
            Balance.Boss spec = Balance.bosses[i];
            pellets[spec.id] = spec.pellets;
            spread[spec.id] = spec.spreadDeg;
            assertTrue(spec.name + " 弹数太少", spec.pellets >= 3);
            assertTrue(spec.name + " 悬停线越界", spec.holdY > 0 && spec.holdY < 120);
            assertEquals(spec.name + " id 与数组下标不符", i, spec.id);
        }
        assertFalse("弹数全一样，形状就没区分开",
                pellets[0] == pellets[1] && pellets[1] == pellets[2]);
        assertFalse("张角全一样，形状就没区分开",
                spread[0] == spread[1] && spread[1] == spread[2]);
    }

    // ---- 辅助 -------------------------------------------------------------------------------

    private static void assertUnitVectors(float[] vx, float[] vy, int n) {
        for (int i = 0; i < n; i++) {
            double len = Math.sqrt(vx[i] * vx[i] + vy[i] * vy[i]);
            assertEquals("第 " + i + " 发不是单位向量", 1d, len, 1e-4);
        }
    }

    private static Enemy boss(Balance.Boss spec, float x, float y) {
        Enemies es = new Enemies(1);
        Enemy e = es.spawn();
        e.boss = true;
        e.kind = spec.id;
        e.hp = e.maxHp = spec.hp;
        e.score = 0;
        e.radius = spec.spriteSize / 2f;
        e.x = e.px = e.anchorX = x;
        e.y = e.py = y;
        e.phase2AtRatio = spec.phase2AtRatio;
        e.fireTimer = BossBehavior.fireGapFor(spec, e);
        return e;
    }
}
