/*
 * PIXEL RAIDER — 原生 Android 纵版弹幕射击
 * Copyright (C) 2026 Flexiatom
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.flexiatom.pixelraider.core.Time;
import com.flexiatom.pixelraider.game.BulletPool.Bullet;
import com.flexiatom.pixelraider.plat.Screen;

import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

/**
 * 碰撞网格 + 子弹池（规格 §六 / §五）。
 *
 * 网格里最值钱的一条是 {@link #gridNeverMissesAnythingWithinStrikeDistance()}：
 * 它拿**全量暴力扫**当基准，把"3×3 邻格到底漏不漏"变成可判定的事——
 * 漏判在真机上表现为"子弹穿过一个 12 血的精英"，玩家只会以为是自己手感差。
 */
public class BulletAndGridTest {

    private final int[] cand = new int[64];

    // ---- SpatialGrid ------------------------------------------------------------------

    @Test
    public void ringGrowsWithStrikeDistance() {
        assertEquals(1, SpatialGrid.ringFor(0));
        assertEquals(1, SpatialGrid.ringFor(18));
        assertEquals(1, SpatialGrid.ringFor(48));      // [规格] 48px 格 + 3×3 的临界点
        assertEquals(2, SpatialGrid.ringFor(49));
        assertEquals(2, SpatialGrid.ringFor(96));
        assertEquals(3, SpatialGrid.ringFor(97));
        assertEquals(1, SpatialGrid.ringFor(-5));
    }

    @Test
    public void gridCoversLogicCanvas() {
        SpatialGrid g = new SpatialGrid(240, 320, 64, 36);
        assertEquals(5, g.cols());
        assertEquals(7, g.rows());
        assertEquals(35, g.bucketCount());
        assertEquals(64, g.capacity());
        assertEquals("36 的中心距只需 3×3", 1, g.ring());
    }

    @Test
    public void queryExcludesDistantItemsAndIncludesNearOnes() {
        SpatialGrid g = new SpatialGrid(240, 320, 16, 36);
        g.insert(0, 100f, 100f);      // 格 (2,2)
        g.insert(1, 106f, 106f);      // 同格
        g.insert(2, 200f, 100f);      // 格 (4,2)：横向差两格，必被排除
        g.insert(3, 230f, 310f);
        assertEquals(4, g.itemCount());
        assertEquals(2, g.query(104f, 102f, cand));
        assertTrue(cand[0] == 0 || cand[0] == 1);
        assertTrue(cand[1] == 0 || cand[1] == 1);
        assertEquals(1, g.query(230f, 310f, cand));
        assertEquals(3, cand[0]);
        // 3×3 只保证"不漏"，是否真命中由调用方按半径算——相距 40 的两个也会一起进候选，
        // 这正是它比全量双重循环便宜、又比"只查本格"安全的折中点。
        assertEquals(1, g.query(198f, 102f, cand));
        assertEquals(2, cand[0]);
    }

    /** 基准 = 全量扫；网格给出的候选必须是全量结果的超集。 */
    @Test
    public void gridNeverMissesAnythingWithinStrikeDistance() {
        final int W = 240, H = 320, N = 48, MAXD = 36;
        SpatialGrid g = new SpatialGrid(W + 80, H + 80, N, MAXD);
        Random rnd = new Random(20260923L);
        float[] xs = new float[N], ys = new float[N];
        for (int i = 0; i < N; i++) {
            // 故意撒到画面外：生成区与离屏回收都发生在边界上，那里最容易漏
            xs[i] = -30f + rnd.nextFloat() * (W + 60f);
            ys[i] = -60f + rnd.nextFloat() * (H + 120f);
        }
        for (int trial = 0; trial < 400; trial++) {
            g.clear();
            for (int i = 0; i < N; i++) g.insert(i, xs[i], ys[i]);
            float qx = -20f + rnd.nextFloat() * (W + 40f);
            float qy = -20f + rnd.nextFloat() * (H + 40f);
            int n = g.query(qx, qy, cand);
            boolean[] got = new boolean[N];
            for (int i = 0; i < n; i++) {
                assertTrue("候选越界：" + cand[i], cand[i] >= 0 && cand[i] < N);
                assertFalse("同一 item 出现两次：邻格遍历重了", got[cand[i]]);
                got[cand[i]] = true;
            }
            for (int i = 0; i < N; i++) {
                float dx = xs[i] - qx, dy = ys[i] - qy;
                if (dx * dx + dy * dy <= MAXD * MAXD) {
                    assertTrue("第 " + trial + " 次漏判 item " + i, got[i]);
                }
            }
        }
    }

    @Test
    public void doubleInsertIsIgnoredNotLooped() {
        SpatialGrid g = new SpatialGrid(240, 320, 8, 36);
        g.insert(3, 100f, 100f);
        g.insert(3, 100f, 100f);      // 同一帧重复入格：链表桶最怕这个（自环 → 死循环）
        assertEquals(1, g.itemCount());
        assertEquals(1, g.query(100f, 100f, cand));
        assertEquals(3, cand[0]);
    }

    @Test
    public void reinsertAfterClearGoesIntoTheNewCell() {
        SpatialGrid g = new SpatialGrid(240, 320, 8, 36);
        g.insert(0, 10f, 10f);
        assertEquals(1, g.query(10f, 10f, cand));
        g.clear();
        assertEquals(0, g.query(10f, 10f, cand));
        g.insert(0, 200f, 300f);
        assertEquals(0, g.query(10f, 10f, cand));
        assertEquals(1, g.query(200f, 300f, cand));
        assertEquals(1, g.itemCount());
    }

    @Test
    public void offScreenItemsMergeIntoEdgeCellInsteadOfVanishing() {
        SpatialGrid g = new SpatialGrid(240, 320, 8, 36);
        g.insert(0, 120f, -500f);          // 生成区上方很远：夹到第 0 行，宁可多给候选
        assertEquals(1, g.query(120f, 4f, cand));
        assertEquals(0, cand[0]);
    }

    @Test
    public void candidateBufferOverflowTruncatesWithoutThrowing() {
        SpatialGrid g = new SpatialGrid(240, 320, 16, 36);
        for (int i = 0; i < 10; i++) g.insert(i, 100f + i, 100f);
        int[] small = new int[3];
        assertEquals(3, g.query(100f, 100f, small));
    }

    @Test(expected = IllegalArgumentException.class)
    public void itemIdBeyondCapacityFailsLoudly() {
        new SpatialGrid(240, 320, 4, 36).insert(4, 10f, 10f);
    }

    // ---- BulletPool -------------------------------------------------------------------

    /**
     * 玩家池容量是**推导出来的数**（算式写在 {@code Balance.Bullet#capacity}），不是手感值。
     * 这条把字面量钉住：改容量必须同时改算式，两边对不上这里就红。
     * 满了 {@code spawn()} 返回 null，由调用方决定降级（玩家侧走 {@code spawnEvictingOldest(true)}）。
     */
    @Test
    public void playerPoolCapacityIsDerivedAndRefusesPlainSpawnBeyondIt() {
        final int cap = Balance.bullet.capacity;
        assertEquals("改了容量就得回去改 Balance 里那条算式", 384, cap);
        BulletPool p = new BulletPool(cap);
        for (int i = 0; i < cap; i++) assertNotNull(p.spawn());
        assertNull("满了就返回 null，由调用方决定降级", p.spawn());
        assertEquals(cap, p.activeCount());
        assertEquals(0, p.freeCount());
    }

    /**
     * 玩家弹池那条算式的<b>可执行</b>版本：逐武器算「在屏代数 × 每代发数」，取最大值要求装得进池。
     *
     * <p>提案 ② 把这条显式推给了 {@code shop-cards-uncap-pellet}：取消满级之后"每代发数"只能从
     * {@link ShopRun#pelletBonus} 读——测试里自己重述一遍"激光不吃扳机"就是造第二个真源
     * （{@code Balance.Bullet#capacity} 那条 ⚠ 段警告的正是这件事）。现在那个定义点立起来了。
     *
     * <p><b>周期取量化后的实测值</b>：{@code fireTimer} 的过冲被钳回 0（{@link PlayerState#stepTimers}），
     * 所以玩家按不出比 {@code ceil(cd/STEP)·STEP} 更快的射速。这不是为了让数好看——注释里那个
     * 367 用的正是<b>连续</b>周期（0.0864s），而 0.0864 这一档在 60Hz 固定步长下不存在。
     *
     * <p>过屏距离按<b>最高屏</b>（{@code Screen.LOGIC_H_MAX} = 21:9 机型）再加 24：机头出膛点在机体
     * 上方 8、出界回收余量 16（都在 {@code Game}：{@code stepChain} 的 {@code player.y - 8f} 与
     * {@code MARGIN}），机体最远坐在画布底边 ⇒ 净 8 + 16 = 24。代数向上取整，多算的是"两代重叠"
     * 的那一代。制导弹不进 shots 池（走 {@code Missiles}），按表里的 {@code w.guided} 跳过。
     */
    @Test
    public void playerBulletPeakAtTheIncomeCapBuildFitsThePool() {
        ShopRun maxed = new ShopRun();
        for (int lv = 0; lv < MissilesTest.TRIGGER_LEVEL_AT_INCOME_CAP; lv++) {
            assertTrue("收入上界那档扳机等级买不满（第 " + lv + " 级被拒）：容量账的前提变了",
                    maxed.buyUpgrade(Balance.ShopCard.TRIGGER));
        }
        float mul = Balance.status.frenzyRateMul
                * (1f + Balance.growth.ratePerLevel * Balance.Growth.MAX_LEVEL);
        int peak = 0;
        Balance.Weapon peakGun = null;
        for (Balance.Weapon w : Balance.weapons) {
            if (w.guided) continue;
            float cd = w.fireGap / mul;
            float period = (float) Math.ceil(cd / Time.STEP) * Time.STEP;
            float transit = Math.min((Screen.LOGIC_H_MAX + 24f) / w.bulletSpeed,
                    Balance.bullet.playerLife);
            int pellets = Math.max(1, w.pellets) + maxed.pelletBonus(w.id);
            int onScreen = (int) Math.ceil(transit / period) * pellets;
            if (onScreen > peak) {
                peak = onScreen;
                peakGun = w;
            }
        }
        assertNotNull("一把武器都没进扫描（全被 {@code guided} 跳过，或武器表空了）："
                + "下面两条断言就成了对着 null 的假绿", peakGun);
        assertTrue(peakGun.name + " 在最坏 build 下在屏 " + peak + " 发，超出池容量 "
                + Balance.bullet.capacity + " ⇒ 会开始淘汰玩家自己的弹", peak <= Balance.bullet.capacity);
        assertTrue("余量不足一代（" + (Balance.bullet.capacity - peak) + " 发 < " + peakGun.pellets
                + "+扳机 的每代 " + (Math.max(1, peakGun.pellets)
                + maxed.pelletBonus(peakGun.id)) + " 发）：上面那个 +24 的粗算就没被吸收",
                Balance.bullet.capacity - peak >= Math.max(1, peakGun.pellets)
                        + maxed.pelletBonus(peakGun.id));
    }

    @Test
    public void overflowEvictsTheOldestBullet() {
        BulletPool p = new BulletPool(3);
        p.spawn();                       // seq 1：最老，该被淘汰的就是它
        p.spawn();                       // seq 2
        p.spawn();                       // seq 3
        Bullet fourth = p.spawnEvictingOldest(true);
        assertNotNull(fourth);
        assertEquals(3, p.activeCount());
        assertEquals(1, p.evictTotal());
        assertEquals(0, p.refuseTotal());
        long[] left = seqsOf(p);
        Arrays.sort(left);
        assertArrayEquals(new long[] { 2, 3, 4 }, left);   // 淘汰的是 seq 1
    }

    @Test
    public void evictionFollowsSpawnOrderNotSlotOrder() {
        // free-list 复用的槽位顺序 ≠ 出膛顺序。按槽号判"最早"会踢掉刚生成的那发。
        BulletPool p = new BulletPool(3);
        p.spawn();                                   // seq 1 → slot 0
        p.spawn();                                   // seq 2 → slot 1
        p.spawn();                                   // seq 3 → slot 2
        p.killAt(0);                                 // 还回 slot 0
        p.spawn();                                   // seq 4 → slot 0（槽号最小、年龄最新）
        Bullet fifth = p.spawnEvictingOldest(true);
        assertNotNull(fifth);
        assertEquals("新弹的 seq 必须最大：它才是最后出膛的", 5L, fifth.seq);
        long[] left = seqsOf(p);
        Arrays.sort(left);
        assertArrayEquals(new long[] { 3, 4, 5 }, left);   // 淘汰 seq 2（最老的），保留 seq 3
    }

    /**
     * 玩家侧的淘汰**永不拒发**：池满就踢最老的那发腾位。
     * 这条取代先前的"全是追踪弹则拒绝生成"——那条规则为假追踪服务，自己制造了
     * "整轮零发而冷却照付"的死锁（提案 unguide-bullets）。
     */
    @Test
    public void fullPoolEvictionNeverRefusesThePlayerAShot() {
        BulletPool p = new BulletPool(2);
        p.spawn();
        p.spawn();
        assertNotNull("满了也要给位：宁可淘汰最老的，不可吞掉玩家按下去的那一下",
                p.spawnEvictingOldest(true));
        assertEquals(0, p.refuseTotal());   // 淘汰路径不产生"拒绝"计数：它总是给到位
        assertEquals(1, p.evictTotal());
        assertEquals(2, p.activeCount());
    }

    /**
     * 敌弹侧的两个事实一起钉：① 容量是推导值（算式在 {@code Balance.Bullet#enemyCapacity}：
     * 视觉口径"整屏一列排满"187 与产出口径"满屏射手 or Boss 二阶段取 max"98，取前者上整）；
     * ② 满池时 {@code allowEvict=false} 是**合法选择**——宁可少一发新弹，不踢已在途的弹。
     */
    @Test
    public void hostilePoolCanRefuseEvictionEntirely() {
        final int cap = Balance.bullet.enemyCapacity;
        assertEquals("改了容量就得回去改 Balance 里那两条算式", 192, cap);
        BulletPool p = new BulletPool(cap);
        for (int i = 0; i < cap; i++) {
            Bullet b = p.spawnEvictingOldest(false);
            assertNotNull(b);
            b.hostile = true;
        }
        assertNull(p.spawnEvictingOldest(false));
        assertEquals(1, p.refuseTotal());
        assertEquals(0, p.evictTotal());
    }

    /**
     * 「饱和不发生」的**可执行**版本：把敌弹的产出上界从数值表现算一遍，要求它落在池容量之内。
     * 两条路径互斥（{@code WaveDirector.waveTotalFor} 在 Boss 波只放 1 只怪，大小怪永不同屏）⇒ 各取 max。
     * 寿命一律按 {@code maxLife} 算（实际会被出界回收截得更短），所以这是上界而不是估计。
     * 谁把小怪射速调快、或给 Boss 二阶段加弹，这里会先红——而不是真机上悄悄开始拒弹：
     * 敌弹满池的后果是"少了几发"，画面上永远没人会去查。
     */
    @Test
    public void hostileBulletProductionBoundFitsTheRefusingPool() {
        float mobBound = 0f;
        for (Balance.Enemy spec : Balance.enemies) {
            if (spec.fireGap > 0f) {   // 0 = 这怪不开火
                mobBound = Math.max(mobBound,
                        Balance.wave.maxAlive / spec.fireGap * Balance.bullet.hostileLife);
            }
        }
        float bossBound = 0f;
        for (Balance.Boss spec : Balance.bosses) {
            int pellets = Math.min(BossBehavior.MAX_SHOTS, spec.pellets + spec.phase2ExtraPellets);
            bossBound = Math.max(bossBound,
                    pellets / (spec.fireGap * spec.phase2GapMul) * Balance.bullet.bossLife);
        }
        float bound = Math.max(mobBound, bossBound);
        assertTrue("敌弹产出上界 " + bound + " 超过池容量 " + Balance.bullet.enemyCapacity
                + "：要么抬容量，要么明写接受拒弹", bound <= Balance.bullet.enemyCapacity);
    }

    @Test
    public void stableStateAllocatesNoNewBulletObjects() {
        final int CAP = 32, ROUNDS = 300;
        BulletPool p = new BulletPool(CAP);
        Bullet[] seen = new Bullet[CAP];
        for (int i = 0; i < CAP; i++) seen[i] = p.spawn();
        for (int r = 0; r < ROUNDS; r++) {
            p.killAt(p.activeCount() - 1);
            Bullet b = p.spawnEvictingOldest(true);
            assertNotNull(b);
            boolean known = false;
            for (int i = 0; i < CAP; i++) if (seen[i] == b) known = true;
            assertTrue("第 " + r + " 轮出现新对象：池在偷偷增长", known);
        }
    }

    @Test
    public void reuseResetsEveryFieldButStampsAFreshSequence() throws Exception {
        BulletPool p = new BulletPool(1);
        Bullet a = p.spawn();
        long firstSeq = a.seq;
        ResetProbe.poison(a);
        ResetProbe.assertStillPoisoned(a);
        p.killAt(0);
        Bullet b = p.spawnEvictingOldest(true);
        assertSame(a, b);
        ResetProbe.assertAllZero(b, "seq");
        assertTrue("seq 复用时重新盖章且单调递增", b.seq > firstSeq);
    }

    @Test
    public void stepIntegratesAndCullsByLifeAndViewport() {
        BulletPool p = new BulletPool(4);
        Bullet fly = p.spawn();
        fly.x = 100f; fly.y = 0f; fly.vx = 0f; fly.vy = 200f; fly.maxLife = 9f;
        Bullet expired = p.spawn();
        expired.x = 100f; expired.y = 100f; expired.life = 1f; expired.maxLife = 1f;
        assertEquals(2, p.activeCount());
        int left = p.stepAndCompact(0.5f, 240, 320, 16);
        assertEquals(1, left);
        assertSame(fly, p.activeAt(0));
        assertEquals(100f, fly.x, 1e-4f);
        assertEquals(100f, fly.y, 1e-4f);
        assertEquals("px/py 记的是这一帧起点，不是终点", 0f, fly.py, 1e-4f);
        p.stepAndCompact(2f, 240, 320, 16);
        assertEquals("飞出画面 margin 后回收", 0, p.activeCount());
        assertEquals(4, p.freeCount());
    }

    /**
     * 生成端的约定：**只写 maxLife，不写 life**。
     *
     * 这条是 2026-09-24 那个"敌人完全不还手"的 bug 的墓碑：{@code fireEnemyShot} 照抄了
     * 粒子的写法 {@code life = maxLife = 6f}，而子弹池的 life 是**从 0 往上加的计时器**
     * （粒子那边是往下减的剩余寿命）。于是每发敌弹出膛的下一帧就被 {@code stepAndCompact}
     * 判过期回收，屏幕上一发都看不到，单测里"射手 10 秒开 7 次火"却全绿——因为那条测的是
     * 行为端的 flag，没穿过池。
     */
    @Test
    public void spawnSitesSetOnlyMaxLifeSoTheBulletSurvivesItsLifetime() {
        BulletPool p = new BulletPool(2);
        Bullet b = p.spawn();
        assertEquals("spawn 出来的 life 必须是 0：它是计时器，不是剩余寿命", 0f, b.life, 1e-6f);
        b.x = 120f; b.y = 160f;                     // 停在画面正中：把视野回收这条出路堵掉
        b.vx = 0f; b.vy = 0f;
        b.maxLife = Balance.bullet.hostileLife;
        int frames = (int) (Balance.bullet.hostileLife * 60f) - 1;
        for (int i = 0; i < frames; i++) {
            p.stepAndCompact(1f / 60f, 240, 320, 16);
            assertEquals("第 " + i + " 帧：寿命没到就被回收了", 1, p.activeCount());
        }
        p.stepAndCompact(1f / 60f, 240, 320, 16);
        assertEquals("寿命到了就该走", 0, p.activeCount());
    }

    @Test
    public void clearEmptiesWithoutGrowingThePool() {
        BulletPool p = new BulletPool(6);
        for (int i = 0; i < 6; i++) p.spawn();
        p.clear();
        assertEquals(0, p.activeCount());
        assertEquals(6, p.freeCount());
        assertNotNull(p.spawn());
    }

    @Test
    public void overloadSweepIsIdempotentOnAnEmptyPool() {
        BulletPool p = new BulletPool(4);
        p.clear();
        p.clear();
        assertEquals(4, p.freeCount());
        assertEquals(0, p.activeCount());
    }

    // ---- 辅助 -------------------------------------------------------------------------

    private static long[] seqsOf(BulletPool p) {
        long[] s = new long[p.activeCount()];
        for (int i = 0; i < s.length; i++) s[i] = p.activeAt(i).seq;
        return s;
    }
}
