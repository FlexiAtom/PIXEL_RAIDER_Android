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
 * 模式这一维（[规格 §5.5] 那张分叉清单）。
 *
 * <p>这里钉的是<b>两件事的形状</b>，不是数值表：① 战役侧一字节不动（那条由
 * {@code WaveDirectorTest.normalTierIsByteForByteTheBaseline} 与下面那批用例一起守住）；② 两条 hp
 * 来源在任一模式下都<b>只有一枚非 1</b>（{@link #campaignHasOnlyTheTierHpAxis} /
 * {@link #endlessHasOnlyTheGrowthHpAxis}）。第二条是这批最容易烂掉的地方：它一旦不成立，
 * {@code spec.hp × tier.hpMul × hpGrowth} 就成了双计，而双计在数值表上完全看不出来。
 */
public class ModeTest {

    private static final int BURSTER = Balance.Enemy.BURSTER;
    private static final int RUSHER = Balance.Enemy.RUSHER;
    private static final int SHOOTER = Balance.Enemy.SHOOTER;

    /** 两枚常量各自的方向：{@code campaign} 是唯一判据，其余布尔都从它派生。 */
    @Test
    public void theTwoConstantsPointOppositeWays() {
        assertTrue(Mode.CAMPAIGN.campaign);
        assertFalse(Mode.ENDLESS.campaign);
        assertTrue(Mode.CAMPAIGN.hasVictory());
        assertFalse("无尽不能有第二个终局出口", Mode.ENDLESS.hasVictory());
        assertFalse(Mode.CAMPAIGN.batchesSpawns());
        assertTrue(Mode.ENDLESS.batchesSpawns());
    }

    /**
     * [规格 §5.5] 战役侧那条 hp 轴<b>恒 1</b>：不管档是哪一档，{@code hpGrowth} 都不参与。
     * 于是"个体厚度"在战役里只有一条来源（{@link Tier#hpMul}），终结档 1.75 不会被别处再乘一遍。
     */
    @Test
    public void campaignHasOnlyTheTierHpAxis() {
        for (Tier t : new Tier[]{Tier.CASUAL, Tier.NORMAL, Tier.TERMINAL}) {
            Mode m = Mode.campaign(t);
            for (int wave = 1; wave <= 200; wave++) {
                for (int kind = 0; kind < Balance.enemies.length; kind++) {
                    assertEquals("战役侧 hpGrowth 不该动波次（档 " + t + " 第 " + wave + " 波）",
                            1f, m.hpGrowth(wave, kind), 0f);
                }
            }
        }
    }

    /**
     * 另一半不变式：无尽的<b>档</b>恒普通（今天没有选档入口，[规格 §5] 待裁第 2 格答"不加"）。
     * 这条红了＝有人给无尽接了选档，那么 {@code tier.hpMul × hpGrowth} 两轴同时开放，
     * 上面那条"单轴"推理当场作废，得重签后期厚度。
     */
    @Test
    public void endlessHasOnlyTheGrowthHpAxis() {
        assertEquals(Tier.NORMAL, Mode.ENDLESS.tier);
        assertEquals(Tier.NORMAL, Mode.CAMPAIGN.tier);
    }

    /**
     * 无尽 hp 曲线＝线性 + 两道闸（[规格 §5.5]）。这里钉的是<b>封顶的位置</b>：
     * 比例伤害那两种在第 21 波走完（闸 2），其余四种在第 41 波走完（闸 3），第 100 波仍是那两个数。
     *
     * <p>41 不是随手取的：它正好是"战役本该结束的地方再前一波"，之后压力只从数量一侧来。
     * 那条被否掉的二次项若还在这，第 100 波会是 63 倍（精英 765 血）——用例走不到那格就已经红了。
     */
    @Test
    public void endlessHpCurveSaturatesAtItsTwoCaps() {
        Mode m = Mode.ENDLESS;
        assertEquals(1f, m.hpGrowth(1, SHOOTER), 0f);
        assertEquals(1.95f, m.hpGrowth(20, SHOOTER), 1e-5f);
        assertEquals(3f, m.hpGrowth(41, SHOOTER), 1e-5f);
        assertEquals("第 100 波还在长＝那道闸没合上", 3f, m.hpGrowth(100, SHOOTER), 1e-5f);
        assertEquals(2f, m.hpGrowth(21, BURSTER), 1e-5f);
        assertEquals(2f, m.hpGrowth(100, BURSTER), 1e-5f);
        assertEquals(2f, m.hpGrowth(100, RUSHER), 1e-5f);
        assertEquals("精英走的是那道更高的闸", 3f, m.hpGrowth(100, Balance.Enemy.ELITE), 1e-5f);
        for (int wave = 1; wave <= 400; wave++) {
            assertTrue("杂鱼穿透了 endlessHpCeil", m.hpGrowth(wave, SHOOTER) <= Balance.wave.endlessHpCeil + 1e-6f);
            assertTrue("自爆穿透了 endlessHpCap", m.hpGrowth(wave, BURSTER) <= Balance.wave.endlessHpCap + 1e-6f);
        }
    }

    /** 敌数：战役对数、无尽线性，两条在第 7 波相交（实算都是 17 只），之后无尽更陡。 */
    @Test
    public void enemyCountCurvesCrossAtWaveSeven() {
        WaveDirector c = new WaveDirector(Balance.wave, Mode.CAMPAIGN);
        WaveDirector e = new WaveDirector(Balance.wave, Mode.ENDLESS);
        assertEquals(8, c.enemyCountFor(1));
        assertEquals(5, e.enemyCountFor(1));
        assertEquals("交点之前无尽更少，这是刻意的（开局不该比战役更凶）",
                c.enemyCountFor(7), e.enemyCountFor(7));
        assertEquals(17, e.enemyCountFor(7));
        assertEquals(47, c.enemyCountFor(40));
        assertEquals(83, e.enemyCountFor(40));
        assertEquals(Balance.wave.base + Balance.wave.endlessStep * 99, e.enemyCountFor(100));
    }

    /** 换了 Mode 这枚真源之后，战役侧那几格的读数必须与基线**逐个相等**（档＝普通时）。 */
    @Test
    public void campaignModeIsByteForByteTheBaseline() {
        WaveDirector plain = new WaveDirector();
        WaveDirector viaMode = new WaveDirector(Balance.wave, Mode.CAMPAIGN);
        int[] a = new int[Balance.Enemy.BURSTER + 1];
        int[] b = new int[Balance.Enemy.BURSTER + 1];
        for (int wave = 1; wave <= 60; wave++) {
            assertEquals(plain.enemyCountFor(wave), viaMode.enemyCountFor(wave));
            assertEquals(plain.spawnGapFor(wave), viaMode.spawnGapFor(wave), 0f);
            assertEquals(plain.bossHpFor(wave), viaMode.bossHpFor(wave));
            assertEquals(plain.kindWeightsFor(wave, a), viaMode.kindWeightsFor(wave, b));
            for (int i = 0; i < a.length; i++) assertEquals(a[i], b[i]);
        }
    }

    /**
     * [规格 §5.5] 新暴露的那格连带（那张清单里没有）：{@code 1.45^n} 是给<b>八轮</b>设计的，
     * 无尽没有第 9 轮该多厚的裁定 ⇒ 轮次夹在 {@link Mode#chapterCount()}。这一夹在战役侧是惰性的
     * （nth 最大正好 8），所以上面那条基线用例照绿。
     *
     * <p>取 40/100/160 三波比：{@code bossIndexFor} 在这三波都落在同一种 Boss（索引 1），
     * 于是"夹住"与"仍在长"的差不会被 Boss 种类差污染。
     */
    @Test
    public void endlessBossRoundsAreClampedAtTheChapterCount() {
        WaveDirector e = new WaveDirector(Balance.wave, Mode.ENDLESS);
        WaveDirector c = new WaveDirector(Balance.wave, Mode.CAMPAIGN);
        assertEquals(e.bossHpFor(40), e.bossHpFor(100));
        assertEquals("第 32 轮还在按 1.45 长＝夹没生效", e.bossHpFor(40), e.bossHpFor(160));
        assertTrue("夹是模式侧的，不许顺手把战役也削平",
                c.bossHpFor(160) > c.bossHpFor(40));
        assertEquals("无尽 Boss＝战役最后一轮的厚度", 8, Mode.ENDLESS.chapterCount());
    }

    /**
     * 章界取<b>全局波号</b>（[规格 §5.3] 那条争议的结账）：八章＝40 波，与既有星区界同源。
     * 不做"每章从第 1 波起"——那会让八章共用同一条曲线。
     */
    @Test
    public void chaptersAreCutFromGlobalWaveNumbers() {
        Mode m = Mode.CAMPAIGN;
        assertEquals(1, m.chapterForWave(1));
        assertEquals(1, m.chapterForWave(5));
        assertEquals(2, m.chapterForWave(6));
        assertEquals(8, m.chapterForWave(40));
        assertEquals("无尽第 41 波之后没有第 9 章（剧情表只有 8 句）", 8, m.chapterForWave(41));
        assertEquals(8, m.chapterForWave(1000));
        assertEquals("越界不倒扣", 0, m.chapterForWave(0));
        assertEquals("战役每章末尾正是一场 Boss", 40, Balance.wave.maxWave);
        assertEquals(Balance.wave.wavesPerZone, 5);
    }

    /** 波号越界的两格都往下退化，不抛（这条链在 resetRun 的头几帧会被读到）。 */
    @Test
    public void badWaveNumbersDegradeInBothModes() {
        for (Mode m : new Mode[]{Mode.CAMPAIGN, Mode.ENDLESS}) {
            assertEquals(0, m.chapterForWave(-3));
            assertEquals("0 波", 1f, m.hpGrowth(0, SHOOTER), 0f);
            assertEquals("负波", 1f, m.hpGrowth(-7, SHOOTER), 0f);
        }
        assertEquals(0, new WaveDirector(Balance.wave, Mode.ENDLESS).enemyCountFor(0));
    }
}
