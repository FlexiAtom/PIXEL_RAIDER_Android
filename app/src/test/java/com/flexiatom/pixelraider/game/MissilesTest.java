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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;

import org.junit.Test;

/**
 * 导弹池的簿记（提案 missile-warhead-sim §六）。{@link MissileBehaviorTest} 管的是"怎么飞"，
 * 这里管的是"哪一格归谁、什么时候还回去"——因为**这两族故障的表现完全不同**：仿真写错玩家看得见，
 * 池写错玩家看不见（少几枚弹、或者持着上一代的对象继续读）。
 *
 * <p>三条本类独有的形状，都是别处踩过的坑：
 * <ul>
 *   <li>**池按实例复用**。free-list 弹回同一格时 {@code spawn()} 交回的正是同一个 Java 对象
 *       （{@link #reuseResetsEveryFieldButStampsAFreshSequence} 把它钉成断言）。所以"淘汰之后
 *       手里那只还能不能用"这类问题只能靠 {@link Missiles.Missile#seq} 分辨，不能靠引用比较。</li>
 *   <li>**满池淘汰不走拒发**，也不走自爆。"不拒发"是我定的；曾经把它的处置**接**到自爆上，
 *       更是我自己加的（那时借的是他的字 L14750，feedback(status=rejected)，UTC 2026-09-25
 *       T09:19:51.727Z＝本地 2026-09-25 17:19:51，逐字「若视场内无敌人则自爆」，可他那句的条件
 *       是**视场内无敌人**）。这一半已被 R22 收回：淘汰**只播自毁动画、不结算**（L20551）。
 *       留下的那条仍是我定的：玩家按下去的那一下永远会出弹，代价付在战场上而不是账本上——所以
 *       {@link #firingThroughAFullPoolNeverRefusesTheShot} 钉的是"返回值恒非空"。</li>
 *   <li>{@link Missiles#clear()} **不写任何格子字段**（{@link #clearReturnsEverySlotWithoutWritingFields}）。
 *       {@code resetRun} 有一整条调用链跑在 UI 线程上（提案 ⑥），与渲染循环并发，撕裂读的最坏
 *       后果必须是"多丢几枚导弹"，不能是"读到半个相位状态机"。</li>
 * </ul>
 *
 * <p>⚠ 全部用例只**读** {@link Balance#missile}，一个字段都不改（可变静态会串到同 JVM 的别的测试）。
 */
public final class MissilesTest {

    // ---- 一、复用必须重置全部字段 -------------------------------------------------------------

    /**
     * 反射遍历 {@link Missiles.Missile} 的**每一个**实例字段：灌哨兵 → 复用 → 断言归零。
     *
     * <p>跳过项只有两个，而且都是"复用时**故意**写新值"的代次戳，不是漏网：
     * {@code seq}（淘汰按它比最老者）与 {@code targetSlot}（出膛挂 {@code RADAR_PENDING}，
     * 让 {@code MissileBehavior} 在下一帧补做射前锁定）。后者必须单独钉等于 {@code RADAR_PENDING}
     * 而不是 {@code NO_TARGET}——写成后者池子照样跑、弹照样飞，只是**射前雷达永远不会补做**，
     * 而且没有任何地方会红。
     */
    @Test
    public void reuseResetsEveryFieldButStampsAFreshSequence() throws Exception {
        Missiles pool = new Missiles(1);
        Missiles.Missile a = pool.spawn();
        long firstSeq = a.seq;
        ResetProbe.poison(a);
        ResetProbe.assertStillPoisoned(a);

        pool.killAt(0);
        Missiles.Missile b = pool.spawn();
        assertSame("池不按实例复用 ⇒ 下面那些断言测的就不是复用了", a, b);
        ResetProbe.assertAllZero(b, "seq", "targetSlot");
        assertTrue("seq 没往前走 ⇒ 淘汰最老者那条判据在第二次运行里会挑错人", b.seq > firstSeq);
        assertEquals("出膛没挂待锁标记：射前雷达会被静默跳过",
                Missiles.Missile.RADAR_PENDING, b.targetSlot);
    }

    /**
     * 寿命字段只许有一个：{@code life}。若有人补一个 {@code maxLife} 并把模板里那 4s 抄进池，
     * 导弹就能活过 {@link Balance.Missile#maxLifeSec}——那正是提案 ② 记过的"第二个真源"形状。
     */
    @Test
    public void theMissileCarriesOnlyItsOwnElapsedLife() {
        for (Field f : Missiles.Missile.class.getDeclaredFields()) {
            assertTrue("导弹格子上出现了 " + f.getName() + "：仿真体的寿命只有 maxLifeSec 一个数，"
                            + "把它烘进池里就是第二个真源（出膛方那份 4s 是匀速弹的口径）",
                    !f.getName().toLowerCase().contains("maxlife"));
        }
    }

    // ---- 二、满池：拒发 vs 淘汰 ---------------------------------------------------------------

    /** {@link Missiles#spawn()} 满则 null（"你自己决定要不要踢人"），{@code spawnEvictingOldest} 不 null。 */
    @Test
    public void spawnRefusesWhenFullButEvictionNeverRefuses() {
        Missiles pool = new Missiles(2);
        assertNotNull(pool.spawn());
        assertNotNull(pool.spawn());
        assertNull("满池还生得出 ⇒ 淘汰与拒发这两条路混成一条了", pool.spawn());
        assertEquals(0, pool.freeCount());
        assertEquals("拒发不该记在淘汰账上：那是两条不同的处置", 0L, pool.evictTotal());

        Missiles.Missile third = pool.spawnEvictingOldest();
        assertNotNull(third);
        assertEquals(2, pool.activeCount());
        assertEquals(1L, pool.evictTotal());
    }

    /**
     * 出膛端穿过满池也必须交出弹来。"满池不拒发、改走淘汰"这条口径是我定的，他没有一条原文
     * **立过**这条口径（归属拆解见类注释"满池淘汰**不**走自爆"那条）。这条钉的不是池，是
     * {@link WeaponFire#fireMissile} 有没有偷偷用 {@code spawn()}（那样一来玩家按下去的那一下
     * 就被静默吞掉，冷却照付）。
     */
    @Test
    public void firingThroughAFullPoolNeverRefusesTheShot() {
        Balance.Weapon gun = Balance.weapons[Balance.Weapon.MISSILE];
        WeaponFire.Template t = new WeaponFire.Template();
        WeaponFire.fillTemplate(t, gun, 1f, 1f, 0, 0.99f, 0);
        assertTrue("前提：这把枪不走制导分支的话，这条测的是弹池", t.guided);

        Missiles pool = new Missiles(2);
        for (int i = 0; i < 5; i++) {
            assertNotNull("第 " + i + " 枚被池拒了 ⇒ 出膛端在用 spawn() 而不是淘汰",
                    WeaponFire.fireMissile(pool, t, 120f, 500f, 0f));
        }
        assertEquals(2, pool.activeCount());
        assertEquals(3L, pool.evictTotal());
    }

    /**
     * 淘汰按 {@code seq} 挑最老的，**不按槽号**。槽位是 free-list 复用的，与出膛顺序无关：
     * 摘掉最老那枚再生一枚，新来的就占着最小的槽号却是最年轻的一枚（{@link BulletPool} 踩过同一条坑）。
     */
    @Test
    public void evictionTakesTheOldestBySequenceNotTheLowestSlot() {
        Missiles pool = new Missiles(3);
        Missiles.Missile first = pool.spawn();
        Missiles.Missile second = pool.spawn();
        pool.spawn();
        pool.killAt(0);                                   // 最老那枚走，它的槽位回 free-list
        Missiles.Missile recycled = pool.spawn();
        assertSame("前提没成立：free-list 没把同一格还回来，这条测不到复用", first, recycled);
        assertTrue("复用同槽的那一枚反而是最年轻的", recycled.seq > second.seq);

        long secondSeq = second.seq;
        Missiles.Missile replacement = pool.spawnEvictingOldest();
        assertEquals("被淘汰的不是 seq 最小的那枚（是槽号最小的那枚？）", secondSeq, pool.evicted.seq);
        assertSame("被淘汰的实例会被就地复用 ⇒ 手里攥着它的那段代码已经在读新一代", second, replacement);
        assertTrue(replacement.seq > secondSeq);
        for (int i = 0; i < pool.activeCount(); i++) {
            assertNotEquals("表里还留着被淘汰那一代：" + pool.activeAt(i).seq, secondSeq,
                    pool.activeAt(i).seq);
        }
    }

    /** 不满池时调淘汰**就是**普通出膛：既不记淘汰账，也不许写副本（{@code Game} 靠计数前进才读它）。 */
    @Test
    public void evictingFromANotFullPoolJustSpawns() {
        Missiles pool = new Missiles(3);
        assertNotNull(pool.spawnEvictingOldest());
        assertEquals(0L, pool.evictTotal());
        assertEquals("没淘汰人却写了副本 ⇒ 调用方会给一枚根本不存在的弹播自毁动画", 0L, pool.evicted.seq);
    }

    /**
     * 副本是**池外那只独立对象**，永远不进活跃表；而且一步内淘汰多枚时它会被后一条覆盖
     * （没有队列——那由 {@link Balance.Missile#capacity} 的余量保证走不到，这里把"走不到的后果"
     * 钉成事实，免得日后有人以为副本攒得下一整步）。
     */
    @Test
    public void theEvictedHandoffIsOutsideThePoolAndKeepsOnlyTheLatest() {
        Missiles pool = new Missiles(1);
        pool.spawn();
        Missiles.Missile obj = pool.evicted;
        long older = 0L;
        for (int r = 0; r < 32; r++) {
            Missiles.Missile fresh = pool.spawnEvictingOldest();
            assertSame("每次交接换了对象 ⇒ Game 侧那份引用会飘", obj, pool.evicted);
            assertNotSame(fresh, pool.evicted);
            for (int i = 0; i < pool.activeCount(); i++) {
                assertNotSame("副本混进了活跃表", pool.evicted, pool.activeAt(i));
            }
            assertTrue("交接副本描述的不是刚被淘汰的那一枚", pool.evicted.seq > older);
            assertTrue(pool.evicted.seq < fresh.seq);
            older = pool.evicted.seq;
        }
        assertEquals(32L, pool.evictTotal());
    }

    // ---- 三、清场的形状 -----------------------------------------------------------------------

    /**
     * {@link Missiles#clear()} 只动游标与槽位表，**一个字段都不写**：归零发生在下一次
     * {@code occupy()} 的 {@code reset()} 里。这条不是洁癖——{@code resetRun} 跑在 UI 线程上
     * （提案 ⑥ 记的那条链），与渲染循环并发，清场写得越"少"，撕裂读的后果就越轻。
     */
    @Test
    public void clearReturnsEverySlotWithoutWritingFields() {
        Missiles pool = new Missiles(3);
        Missiles.Missile a = pool.spawn();
        a.x = 42f;
        a.life = 1f;
        pool.clear();
        assertEquals(0, pool.activeCount());
        assertEquals(3, pool.freeCount());
        assertEquals("clear 写了格子字段 ⇒ 它不再只是个游标操作", 42f, a.x, 0f);
        assertEquals(1f, a.life, 0f);

        Missiles.Missile b = pool.spawn();
        assertSame(a, b);
        assertEquals(0f, b.x, 0f);
        assertEquals(0f, b.life, 0f);
    }

    /** 簿记恒等式：活跃 + 空闲 = 容量，任何操作序列之后都成立（漏还一格会在几千次循环后红）。 */
    @Test
    public void activePlusFreeIsAlwaysTheCapacity() {
        Missiles pool = new Missiles(4);
        for (int round = 0; round < 500; round++) {
            assertEquals(4, pool.activeCount() + pool.freeCount());
            if (round % 3 == 0) pool.spawn();
            else if (round % 3 == 1) pool.spawnEvictingOldest();
            else if (pool.activeCount() > 0) pool.killAt(pool.activeCount() - 1);
        }
        assertEquals(4, pool.activeCount() + pool.freeCount());
    }

    /** 越界下标一律返回 null / 无操作，而不是抛：绘制与回收循环每帧都在拿它们试边界。 */
    @Test
    public void outOfRangeIndicesAreInert() {
        Missiles pool = new Missiles(2);
        pool.spawn();
        assertNull(pool.activeAt(-1));
        assertNull(pool.activeAt(1));
        assertNull(pool.activeAt(99));
        int before = pool.activeCount();
        pool.killAt(-1);
        pool.killAt(before);
        assertEquals(before, pool.activeCount());
        assertEquals(2 - before, pool.freeCount());
    }

    // ---- 四、容量与真表 -----------------------------------------------------------------------

    /**
     * 扳机卡在<b>收入上界</b>下的等级——取消满级之后每代发数没有表里的上限，能买几级改由钱包决定。
     *
     * <p>2026-09-26 取消满级之后，累计成本
     * {@code Σ(34 + 16k) = 8L² + 26L}，按"第 40 波吃满 + 贪婪叠满"的收入口径 5319 币解得
     * {@code L = 24}（{@code 8·576 + 624 = 5232}，买完只剩 87 币，第 25 级要 5650）。
     * 那条收入推导牵动整张掉落表，不是这一个字段能表达的 ⇒ 它留在注释里，{@code pelletPerLevel}
     * 与武器底数则一律现读。调档时这条会红在"容量够不够"那一句上，不会静默按旧 N 通过。
     *
     * <p>包内可见：{@code BulletAndGridTest} 的玩家弹池 bound 要读<b>同一档 build</b>，
     * 两处各抄一遍就是两个真源——它们会各自朝不同的方向漂移，而这两条算的是同一局游戏。
     */
    static final int TRIGGER_LEVEL_AT_INCOME_CAP = 24;

    /**
     * 把 {@link Balance.Missile#capacity} 那段注释里的账**变成可执行的**：出膛率上界 × 一枚弹的寿命
     * 上界 ＋ 在场敌数（派生互斥保证一目标一弹）必须装得进池，且留有余量。
     * ⚠ 寿命那一项在 mode0（L40027）之后从 0.68s 换成了 {@code maxLifeSec}，账因此涨了 3.4 倍——
     * 换了的理由与新旧对照都写在 {@link Balance.Missile#capacity} 那条注释里，这里不重述。
     *
     * <p>写死数字的测试会在下一次调表时变成一句假话，所以周期、乘子、寿命、敌数、每代发数五项全部现读。
     * 周期用**有效**周期（含射速乘子上界）：乘子越大周期越短、出膛率越高，这是这条 bound 的最坏侧；
     * 固定步长量化只会把周期拉长（{@code ceil}），所以真实出膛率比这里算的**更低** ⇒ 保守方向是对的。
     */
    @Test
    public void spawnRateBoundLeavesHeadroomInThePool() {
        Balance.Missile spec = Balance.missile;
        Balance.Weapon gun = Balance.weapons[Balance.Weapon.MISSILE];
        float mul = Balance.status.frenzyRateMul
                * (1f + Balance.growth.ratePerLevel * Balance.Growth.MAX_LEVEL);
        ShopRun maxed = new ShopRun();
        for (int lv = 0; lv < TRIGGER_LEVEL_AT_INCOME_CAP; lv++) {
            assertTrue("收入上界那档扳机等级买不满（第 " + lv + " 级被拒）：容量账的前提变了",
                    maxed.buyUpgrade(Balance.ShopCard.TRIGGER));
        }
        int n = Math.max(1, gun.pellets) + maxed.pelletBonus(Balance.Weapon.MISSILE);
        float shotsPerSecond = n / (gun.fireGap / mul);
        // ⚠ mode0（L40027）把"无锁弹"这一格换了人：不再是"扫不到目标的弹"（那条还是 0.5s 自爆），
        // 而是**装订段在飞的弹**——它既不占目标、也不累 seekT（空视场那条读的是导引头开没开眼，见
        // MissileBehaviorTest.aMissileThatNeverOpensItsEyeIsReclaimedByExpiryNotByTimeout），
        // 而射前雷达那 90° 锥是**无限长半径**（L38831），所以一枚弹的寿命上界就是 maxLifeSec。
        // 旧的 0.68s = boostSec + seekTimeoutSec 不再是个上界，容量按下面这条重算。
        float unlockedConcurrent = shotsPerSecond * spec.maxLifeSec;
        float concurrent = unlockedConcurrent + Balance.wave.maxAlive;
        assertTrue("最坏出膛率下并发 " + concurrent + " 枚，超出容量 " + spec.capacity
                + " ⇒ 会开始淘汰玩家自己的弹（淘汰只播自毁动画、不结算，代价付在战场上但不该常态发生）",
                concurrent <= spec.capacity);
        assertTrue("余量只剩 " + (spec.capacity / concurrent) + " 倍，注释里写的 1.7× 已经不成立",
                spec.capacity >= concurrent * 1.5f);
    }

    /** 构造期挡住 0 与负数：否则 {@code free} 是零长数组，第一条断言就会以越界的名义报成别的错。 */
    @Test
    public void nonsenseCapacityFailsLoudly() {
        for (int cap : new int[]{0, -1}) {
            try {
                new Missiles(cap);
                throw new AssertionError("capacity=" + cap + " 应该当场拒");
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    // ---- 五、出膛继承模板：暴击与伤害必须一路带到结算端 ----------------------------------------

    /**
     * {@code crit} 必须有（复核 DA-2）：{@link WeaponFire.Template#crit} 里的 ×2 烘的是**伤害**，
     * 而 {@code killEnemy} 的 1.5× 分数读的是这个布尔。导弹格子上缺它，导弹击杀就永久丢那 1.5 倍分，
     * 而画面上一点看不出来。
     */
    @Test
    public void critAndDamageRideThroughTheMuzzleHelper() {
        Balance.Weapon gun = Balance.weapons[Balance.Weapon.MISSILE];
        WeaponFire.Template t = new WeaponFire.Template();
        WeaponFire.fillTemplate(t, gun, 1f, 1.15f, 5, 0f, 100);   // roll=0 ⇒ 必暴击
        assertTrue("前提：这条要在暴击代上测才有意义", t.crit);

        Missiles pool = new Missiles(2);
        Missiles.Missile m = WeaponFire.fireMissile(pool, t, 120f, 500f, 0f);
        assertTrue("crit 没透传到导弹格 ⇒ 结算端只能 killEnemy(e, false)", m.crit);
        assertEquals(t.damage, m.damage, 0f);
        assertEquals(t.size, m.size, 0f);
        assertEquals(gun.id, m.weaponId);
        assertEquals(0f, m.life, 0f);
        // 0° = 屏幕正上方（口径在 WeaponFire.muzzleRad，这里只验它没被仿真弹这条路径改掉）
        assertEquals(0f, m.vx, 1e-4f);
        assertEquals("0° 必须是屏幕正上方：弹轴由 muzzleRad 定，仿真弹不许有第二个口径",
                -t.speedPx, m.vy, 1e-3f);
    }
}
