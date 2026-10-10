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

    // ---- 难度档（[规格 §8.2] 三档签字表的三个波次格；形状与"每格唯一读取点"见 §8.5）--------------

    /**
     * 普通档＝基线：三格与不带档的那枚 director **逐波等值**（gap 那格连 delta 都不给，就是要求位相同）。
     * 这条红了＝有人改了 {@code Tier.NORMAL} 那一行而没打算动基线。
     */
    @Test
    public void normalTierIsByteForByteTheBaseline() {
        WaveDirector n = new WaveDirector(Balance.wave, Mode.campaign(Tier.NORMAL));
        int[] plain = new int[6];
        int[] scaled = new int[6];
        for (int wave = 1; wave <= 48; wave++) {
            assertEquals(d.enemyCountFor(wave), n.enemyCountFor(wave));
            assertEquals(d.spawnGapFor(wave), n.spawnGapFor(wave), 0f);
            assertEquals(d.kindWeightsFor(wave, plain), n.kindWeightsFor(wave, scaled));
            for (int i = 0; i < 6; i++) {
                assertEquals("种 " + i + " 在 " + wave + " 波被普通档动了", plain[i], scaled[i]);
            }
        }
    }

    /** [规格 §8.2] 数量乘子乘在**整波总数**上（休闲档第 39 波 46→39，少的那 7 只就是这条链给的）。 */
    @Test
    public void tierScalesTheWholeWaveCount() {
        WaveDirector casual = new WaveDirector(Balance.wave, Mode.campaign(Tier.CASUAL));
        WaveDirector terminal = new WaveDirector(Balance.wave, Mode.campaign(Tier.TERMINAL));
        assertEquals(7, casual.enemyCountFor(1));
        assertEquals(12, casual.enemyCountFor(5));
        assertEquals(39, casual.enemyCountFor(39));
        assertEquals(11, terminal.enemyCountFor(1));
        assertEquals(27, terminal.enemyCountFor(10));
        assertEquals(62, terminal.enemyCountFor(39));
        // HUD 那条"敌人"进度条的分母与队列同源：waveTotalFor 复用的就是上面这个乘过数的方法
        assertEquals(62, terminal.waveTotalFor(39));
    }

    /** [规格 §8.5] 档在权重里只动**精英那一格的下限**，其余五格是过渡曲线、与档无关。 */
    @Test
    public void tierMovesOnlyTheEliteFloorAmongWeights() {
        int[] plain = new int[6], casual = new int[6], terminal = new int[6];
        assertEquals(212, d.kindWeightsFor(1, plain));
        assertEquals(212, new WaveDirector(Balance.wave, Mode.campaign(Tier.CASUAL)).kindWeightsFor(1, casual));
        assertEquals(216, new WaveDirector(Balance.wave, Mode.campaign(Tier.TERMINAL)).kindWeightsFor(1, terminal));
        for (int i = 0; i < 6; i++) {
            if (i == Balance.Enemy.ELITE) continue;
            assertEquals("种 " + i + " 不该被档位动（休闲）", plain[i], casual[i]);
            assertEquals("种 " + i + " 不该被档位动（终结）", plain[i], terminal[i]);
        }
        assertEquals(2, casual[Balance.Enemy.ELITE]);      // 休闲档的下限＝今天那个字面量
        assertEquals(6, terminal[Balance.Enemy.ELITE]);
        // 过渡完成后（t 钳在 1）三档差的仍是同一个数：普通 32、终结 36，下限不参与那条 30 的斜坡
        int[] p21 = new int[6], t21 = new int[6];
        assertEquals(225, d.kindWeightsFor(21, p21));
        assertEquals(229, new WaveDirector(Balance.wave, Mode.campaign(Tier.TERMINAL)).kindWeightsFor(21, t21));
        assertEquals(32, p21[Balance.Enemy.ELITE]);
        assertEquals(36, t21[Balance.Enemy.ELITE]);
    }

    /**
     * [规格 §8.2] gap 乘在**收紧之后的整段**上，但下限按**未缩放的原始值**钳 ⇒ 高波段乘子被这道闸吃掉
     * 是设计（"同屏压力不该无限增大"），不是漏乘。这条同时钉住"乘子不许穿透 {@code spawnGapMin}"。
     */
    @Test
    public void tierScalesGapButNeverBreaksItsFloor() {
        WaveDirector casual = new WaveDirector(Balance.wave, Mode.campaign(Tier.CASUAL));
        WaveDirector terminal = new WaveDirector(Balance.wave, Mode.campaign(Tier.TERMINAL));
        assertEquals(0.6325f, casual.spawnGapFor(1), 1e-5f);
        assertEquals(0.473f, terminal.spawnGapFor(1), 1e-5f);
        assertEquals(0.27692f, terminal.spawnGapFor(39), 1e-5f);
        // 第 60 波未缩放已是 0.196 < 0.22 ⇒ 三档同值，乘子无处生效
        assertEquals(0.22f, casual.spawnGapFor(60), 1e-5f);
        assertEquals(0.22f, terminal.spawnGapFor(60), 1e-5f);
        for (int wave = 1; wave <= 120; wave++) {
            assertTrue("终结档穿透了 spawnGapMin", terminal.spawnGapFor(wave) >= Balance.wave.spawnGapMin);
            assertTrue("休闲档穿透了 spawnGapMin", casual.spawnGapFor(wave) >= Balance.wave.spawnGapMin);
        }
    }

    /**
     * [规格 §8.5] **Boss 豁免写成断言，不写成巧合**：Boss 血走 {@code bossHpFor} 那条 1.45^n 的独立写点，
     * 根本不读档。这条是"终结档 hp 1.75 不许把第 8 个 Boss 推到 ≈2830 血"的唯一机器防线。
     */
    @Test
    public void bossHpIsExemptFromTheTier() {
        WaveDirector terminal = new WaveDirector(Balance.wave, Mode.campaign(Tier.TERMINAL));
        WaveDirector casual = new WaveDirector(Balance.wave, Mode.campaign(Tier.CASUAL));
        assertEquals(90, terminal.bossHpFor(5));
        assertEquals(274, terminal.bossHpFor(20));
        assertEquals(d.bossHpFor(5), casual.bossHpFor(5));
        assertEquals(d.bossHpFor(40), terminal.bossHpFor(40));
    }

    /** 转授：{@code setMode} 是唯一的换模式写点（档在 Mode 里），换完之后三格一起跟着走（§8.5 的"档跟着一整局走"）。 */
    @Test
    public void setModeRetransmitsAllThreeWaveCells() {
        WaveDirector x = new WaveDirector();
        assertEquals(46, x.enemyCountFor(39));
        assertEquals(0.322f, x.spawnGapFor(39), 1e-5f);
        x.setMode(Mode.campaign(Tier.TERMINAL));
        assertEquals(62, x.enemyCountFor(39));
        assertEquals(0.27692f, x.spawnGapFor(39), 1e-5f);
        int[] w = new int[6];
        x.kindWeightsFor(1, w);
        assertEquals(6, w[Balance.Enemy.ELITE]);
        x.setMode(Mode.campaign(Tier.CASUAL));
        assertEquals(39, x.enemyCountFor(39));
        assertEquals(0.3703f, x.spawnGapFor(39), 1e-5f);
        x.kindWeightsFor(1, w);
        assertEquals(2, w[Balance.Enemy.ELITE]);
    }
}
