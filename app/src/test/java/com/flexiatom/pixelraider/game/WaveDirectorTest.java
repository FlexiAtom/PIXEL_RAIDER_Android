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

import org.junit.Test;

/**
 * 波次规划（规格 §六）。这里钉的是**具体数字**，不是"再算一遍同一条公式"：
 * 5+floor(n^0.72×3) 在 1/2/5/10/20/40 波的期望值用 Python 事先算好写死，
 * 公式改错了、指数写成了 1.0（线性）都会立刻红。
 */
public class WaveDirectorTest {

    private final WaveDirector d = new WaveDirector();

    @Test
    public void enemyCountMatchesLogarithmicTable() {
        assertEquals(8, d.enemyCountFor(1));
        assertEquals(9, d.enemyCountFor(2));
        assertEquals(14, d.enemyCountFor(5));
        assertEquals(20, d.enemyCountFor(10));
        assertEquals(30, d.enemyCountFor(20));
        assertEquals(47, d.enemyCountFor(40));
    }

    @Test
    public void growthStaysSubLinear() {
        // 线性（5+3n）在 n=10 会给 35，对数只给 20；这条断言把"别写成线性"钉住。
        assertTrue(d.enemyCountFor(10) < 5 + 3 * 10);
        assertEquals(d.enemyCountFor(20), 30);
        assertTrue("翻倍波次不该翻倍怪数", d.enemyCountFor(20) < 2 * d.enemyCountFor(10));
        assertTrue(d.enemyCountFor(40) < 2 * d.enemyCountFor(20));
    }

    @Test
    public void waveTotalIsOneOnBossWaves() {
        assertEquals(d.enemyCountFor(7), d.waveTotalFor(7));
        assertEquals(1, d.waveTotalFor(5));
        assertEquals(1, d.waveTotalFor(10));
    }

    @Test
    public void bossCadenceAndRotation() {
        assertTrue(d.isBossWave(5));
        assertTrue(d.isBossWave(40));
        assertFalse(d.isBossWave(4));
        assertFalse(d.isBossWave(6));
        assertFalse(d.isBossWave(0));
        // [规格] 每 5 波一个 Boss，3 种轮换：5/10/15 → 0/1/2，20 回到 0，40 → 第 8 个 Boss = 1
        assertEquals(0, d.bossIndexFor(5));
        assertEquals(1, d.bossIndexFor(10));
        assertEquals(2, d.bossIndexFor(15));
        assertEquals(0, d.bossIndexFor(20));
        assertEquals(1, d.bossIndexFor(40));
    }

    @Test
    public void bossHpGrowsEachCycle() {
        assertEquals(90, d.bossHpFor(5));          // 首轮毁灭者：基准值，指数为 0
        assertEquals("第 4 轮 = 90 × 1.45³", 274, d.bossHpFor(20));
        assertTrue(d.bossHpFor(20) > d.bossHpFor(5));
        assertTrue("每轮 +45%，不该跳十倍", d.bossHpFor(20) < d.bossHpFor(5) * 4);
        assertTrue(d.bossHpFor(25) > d.bossHpFor(20));
    }

    @Test
    public void kindWeightsShiftFromTrashToElites() {
        int[] w1 = new int[6], w21 = new int[6];
        assertEquals(212, d.kindWeightsFor(1, w1));
        assertEquals(225, d.kindWeightsFor(21, w21));
        // 第 1 波：几乎全是杂兵
        assertEquals(100, w1[Balance.Enemy.STRAIGHT]);
        assertEquals(80, w1[Balance.Enemy.WEAVE]);
        assertEquals(18, w1[Balance.Enemy.SHOOTER]);
        assertEquals(8, w1[Balance.Enemy.RUSHER]);
        assertEquals(2, w1[Balance.Enemy.ELITE]);
        assertEquals(4, w1[Balance.Enemy.BURSTER]);
        // 过渡完成后（t 钳在 1）：杂兵衰减到 15%/30%，精英反而变厚
        assertEquals(15, w21[Balance.Enemy.STRAIGHT]);
        assertEquals(24, w21[Balance.Enemy.WEAVE]);
        assertEquals(64, w21[Balance.Enemy.SHOOTER]);
        assertEquals(60, w21[Balance.Enemy.RUSHER]);
        assertEquals(32, w21[Balance.Enemy.ELITE]);
        assertEquals(30, w21[Balance.Enemy.BURSTER]);
        // 过渡期内单调：不钉中点浮点边界，只钉方向
        int[] w11 = new int[6];
        d.kindWeightsFor(11, w11);
        assertTrue(w11[Balance.Enemy.STRAIGHT] < w1[Balance.Enemy.STRAIGHT]);
        assertTrue(w11[Balance.Enemy.STRAIGHT] > w21[Balance.Enemy.STRAIGHT]);
        assertTrue(w11[Balance.Enemy.ELITE] > w1[Balance.Enemy.ELITE]);
        assertTrue(w11[Balance.Enemy.ELITE] < w21[Balance.Enemy.ELITE]);
    }

    @Test
    public void weightsNeverHitZeroSoEveryKindStaysReachable() {
        int[] w = new int[6];
        for (int wave = 1; wave <= 80; wave++) {
            int total = d.kindWeightsFor(wave, w);
            for (int i = 0; i < 6; i++) {
                assertTrue("波次 " + wave + " 的种 " + i + " 权重为 0，会永久刷不出", w[i] > 0);
            }
            assertTrue(total > 0);
        }
    }

    @Test
    public void pickKindRespectsWeightOrder() {
        int[] w = new int[6];
        int total = d.kindWeightsFor(1, w);
        assertEquals(Balance.Enemy.STRAIGHT, d.pickKind(w, total, 0f));
        assertEquals(Balance.Enemy.WEAVE, d.pickKind(w, total, 140f / total));
        assertEquals(Balance.Enemy.BURSTER, d.pickKind(w, total, 0.999f));
        assertEquals(Balance.Enemy.BURSTER, d.pickKind(w, total, 1f));   // 越界兜底在最后一格
    }

    @Test
    public void spawnGapTightensButClamps() {
        assertEquals(0.55f, d.spawnGapFor(1), 1e-5f);
        assertTrue(d.spawnGapFor(20) < d.spawnGapFor(1));
        assertEquals(0.22f, d.spawnGapFor(60), 1e-5f);
    }

    @Test
    public void zoneMappingAndEnvironmentZones() {
        assertEquals(0, d.zoneIndexForWave(1));
        assertEquals(0, d.zoneIndexForWave(5));
        assertEquals(1, d.zoneIndexForWave(6));
        assertEquals(3, d.zoneIndexForWave(20));
        assertEquals(4, d.zoneIndexForWave(21));
        assertEquals(7, d.zoneIndexForWave(40));
        assertEquals(7, d.zoneIndexForWave(41));    // 越界不越图
        assertFalse(WaveDirector.zoneHasEnvironment(0));
        assertFalse(WaveDirector.zoneHasEnvironment(3));
        assertTrue(WaveDirector.zoneHasEnvironment(4));
        assertTrue(WaveDirector.zoneHasEnvironment(7));
        assertFalse("40 波之后没有第 9 星区", WaveDirector.zoneHasEnvironment(8));
    }

    @Test
    public void badWaveNumberDegradesInsteadOfThrowing() {
        assertEquals(0, d.enemyCountFor(0));
        assertEquals(0, d.enemyCountFor(-3));
    }
}
