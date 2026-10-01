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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 波次状态机。全部用固定种子跑——"这一波刷出什么组合"是随机数抽出来的，
 * 不钉住种子的话今天绿的用例明天会因组合不同而红，那种红查不动。
 */
public class WaveFlowTest {

    private static final float DT = 1f / 60f;
    private static final int FRAME_CAP = 400_000;

    private static WaveFlow flow(long seed) {
        WaveFlow f = new WaveFlow(new WaveDirector(), new Rng(seed));
        f.begin();
        return f;
    }

    // ---- 相位 --------------------------------------------------------------------------------

    @Test
    public void firstWaveGetsPrepTimeThenStartsSpawning() {
        WaveFlow f = flow(7L);
        assertEquals(WaveFlow.PREP, f.phase());
        for (int i = 0; i < 120; i++) {       // 2 秒：横幅还没完
            assertEquals(WaveFlow.NONE, f.pollSpawn(DT, 0));
        }
        assertEquals("首波宽限 + 横幅应还没走完", WaveFlow.PREP, f.phase());
        for (int i = 0; i < 120 && f.phase() == WaveFlow.PREP; i++) {
            f.pollSpawn(DT, 0);
        }
        assertEquals(WaveFlow.SPAWN, f.phase());
        assertNotEquals("进入出怪相位后一只都不刷", WaveFlow.NONE, f.pollSpawn(DT, 0));
    }

    @Test
    public void intermissionThenNextWavePrep() {
        WaveFlow f = flow(3L);
        assertEquals(1, f.wave());
        advanceToWave(f, 2);
        assertEquals(2, f.wave());
        assertEquals("刚进下一波应当在准备期，不是直接开刷", WaveFlow.PREP, f.phase());
    }

    @Test
    public void wavesClearedCountsOnlyFinishedWaves() {
        WaveFlow f = flow(5L);
        assertEquals("第 1 波还在准备期，一波都没清", 0, f.wavesCleared());
        advanceToWave(f, 2);
        assertEquals("死在第 2 波中场不能算清过 2 波", 1, f.wavesCleared());
    }

    @Test
    public void runEndsInVictoryAtTheLastWave() {
        WaveFlow f = flow(11L);
        int frames = 0;
        while (!f.victory()) {
            f.pollSpawn(DT, 0);
            // 保底芯片每波取走，否则它只是累加，不影响本用例，但读一次能确认接口不吞值
            f.takeGuaranteeChips();
            if (++frames > FRAME_CAP) throw new AssertionError("跑不到第 40 波，状态机卡住了");
        }
        assertEquals(Balance.wave.maxWave, f.wave());
        assertEquals("通关后还在刷怪", WaveFlow.NONE, f.pollSpawn(DT, 0));
    }

    // ---- 出怪 --------------------------------------------------------------------------------

    @Test
    public void spawnWaveEmitsExactlyThePlannedCount() {
        WaveFlow f = flow(5L);
        advanceToWave(f, 1, WaveFlow.SPAWN);
        int total = f.waveTotal();
        assertEquals("第 1 波敌人数 = 5 + floor(1^0.72 × 3)", 8, total);
        assertEquals("总数对不上队列", total, f.pendingSpawns());
        int got = drainSpawns(f);
        assertEquals(total, got);
        assertEquals("多刷了", 0, f.pendingSpawns());
    }

    @Test
    public void aliveCapQueuesInsteadOfDroppingEnemies() {
        WaveFlow f = flow(9L);
        advanceToWave(f, 1, WaveFlow.SPAWN);
        int total = f.waveTotal();
        for (int i = 0; i < 60 * 60; i++) {
            // 场上永远顶满：一只都不该放出来，但队列不能缩水
            assertEquals(WaveFlow.NONE, f.pollSpawn(DT, Balance.wave.maxAlive));
        }
        assertEquals(WaveFlow.SPAWN, f.phase());
        assertEquals("超出同屏上限的怪被丢掉了（规格要求排队等待）", total, f.pendingSpawns());
    }

    @Test
    public void failedSpawnIsReturnedToTheQueueNotSkipped() {
        WaveFlow f = flow(4L);
        advanceToWave(f, 1, WaveFlow.SPAWN);
        int total = f.waveTotal();
        int first = f.pollSpawn(DT, 0);
        assertTrue(first >= 0);
        assertEquals(total - 1, f.pendingSpawns());
        f.returnSpawn();
        assertEquals(total, f.pendingSpawns());
        assertEquals("退回后应当还是同一只", first, f.pollSpawn(DT, 0));
    }

    @Test
    public void bossWaveSpawnsOneBossAndNothingElse() {
        WaveFlow f = flow(6L);
        advanceToWave(f, Balance.wave.bossEvery, WaveFlow.SPAWN);
        assertEquals("第 5 波是 Boss 波", 1, f.waveTotal());
        assertTrue(f.currentWaveIsBoss());
        assertEquals("3 种 Boss 轮换，第 1 轮是第 0 种", 0, f.currentBossIndex());
        assertEquals(WaveFlow.BOSS, f.pollSpawn(DT, 0));
        assertEquals(WaveFlow.NONE, f.pollSpawn(DT, 0));
    }

    @Test
    public void sameSeedReplaysTheSameComposition() {
        int[] a = kindsFor(4242L, 90);
        int[] b = kindsFor(4242L, 90);
        assertArrayEquals(a, b);
        int[] c = kindsFor(4243L, 90);
        assertFalse("换种子结果一模一样，说明随机源没接进去", java.util.Arrays.equals(a, c));
    }

    @Test
    public void everyEmittedKindIsARealEnemyKind() {
        int[] seq = kindsFor(31L, 200);
        for (int i = 0; i < seq.length; i++) {
            assertTrue("kind " + seq[i] + " 越界",
                    seq[i] >= 0 && seq[i] < Balance.enemies.length);
        }
    }

    // ---- 星区 / 得分因子 ------------------------------------------------------------------------

    @Test
    public void zoneAndScoreFactorFollowTheWave() {
        WaveDirector d = new WaveDirector();
        WaveFlow f = flow(2L);
        assertEquals(0, f.zoneIndex());
        assertEquals(0, StatusLayers.envForZone(f.zoneIndex()));
        assertEquals("第 1 波没有波次加成", 1f, f.scoreWaveFactor(), 0f);
        assertEquals("40 波 8 星区，每 5 波一格", 7, d.zoneIndexForWave(40));
        // [规格] 前 4 区无惩罚，后 4 区依次是冰封 / 熔炉 / 磁暴 / 虚空
        assertEquals(StatusLayers.ENV_NONE, StatusLayers.envForZone(d.zoneIndexForWave(20)));
        assertEquals(StatusLayers.ENV_COLD, StatusLayers.envForZone(d.zoneIndexForWave(21)));
        assertEquals(StatusLayers.ENV_FORGE, StatusLayers.envForZone(d.zoneIndexForWave(26)));
        assertEquals(StatusLayers.ENV_STORM, StatusLayers.envForZone(d.zoneIndexForWave(31)));
        assertEquals(StatusLayers.ENV_VOID, StatusLayers.envForZone(d.zoneIndexForWave(36)));
        assertEquals(1f + 19 * Balance.score.waveBonusPerWave, d.scoreWaveFactor(20), 1e-5f);
    }

    // ---- 芯片保底 ------------------------------------------------------------------------------

    @Test
    public void chipGuaranteeStartsFromWaveTwo() {
        WaveFlow f = flow(8L);
        advanceToWave(f, 2);
        assertEquals("第 1 波没有保底", 0, f.takeGuaranteeChips());
        advanceToWave(f, 3);
        assertEquals("[规格] 第 2 波起整波没掉芯片就补 1 枚", 1, f.takeGuaranteeChips());
    }

    @Test
    public void droppingAChipThisWaveCancelsTheGuarantee() {
        WaveFlow f = flow(8L);
        advanceToWave(f, 3);
        f.takeGuaranteeChips();              // 清掉第 2 波那枚，专心看第 3 波
        f.notifyChipDropped();
        advanceToWave(f, 4);
        assertEquals("本波掉过了就不该再补", 0, f.takeGuaranteeChips());
    }

    // ---- 辅助 ----------------------------------------------------------------------------------

    private static void advanceToWave(WaveFlow f, int target) {
        advanceToWave(f, target, WaveFlow.PREP);
    }

    private static void advanceToWave(WaveFlow f, int target, int phase) {
        int frames = 0;
        while ((f.wave() < target || f.phase() != phase) && !f.victory()) {
            f.pollSpawn(DT, 0);
            if (++frames > FRAME_CAP) throw new AssertionError("推进不到第 " + target + " 波");
        }
    }

    private static int drainSpawns(WaveFlow f) {
        int n = 0;
        int guard = 0;
        while (f.phase() == WaveFlow.SPAWN) {
            if (f.pollSpawn(DT, 0) != WaveFlow.NONE) n++;
            if (++guard > 60 * 600) throw new AssertionError("出怪停不下来");
        }
        return n;
    }

    /** 跑够 count 只普通小怪（Boss 占位跳过），用于序列比对。 */
    private static int[] kindsFor(long seed, int count) {
        WaveFlow f = flow(seed);
        int[] out = new int[count];
        int n = 0;
        int frames = 0;
        while (n < count && !f.victory()) {
            int k = f.pollSpawn(DT, 0);
            if (k >= 0) out[n++] = k;
            if (++frames > FRAME_CAP) throw new AssertionError("凑不够 " + count + " 只");
        }
        if (n < count) {
            return java.util.Arrays.copyOf(out, n);
        }
        return out;
    }
}
