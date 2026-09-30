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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.flexiatom.pixelraider.core.Time;
import com.flexiatom.pixelraider.game.Warheads.Warhead;

/**
 * 战斗部池的簿记（提案 missile-warhead-sim §六）：连续杆亮线与它破碎后的碎片**共用一张表**。
 *
 * <p>几何、节拍、伤害口径、覆盖不变量都在 {@link WarheadRulesTest}，这里只管"哪一格归谁"。
 * 两条与 {@link MissilesTest} 相反的处置值得单独说：
 * <ul>
 *   <li>**满池拒生，不淘汰**。导弹池必须"永不拒发"（玩家按下去的那一下是意志行为），而一根杆
 *       少生成一片碎片不改变任何一发结算——所以这里的失败模式允许"少几片"，但不允许静默
 *       （{@link Warheads#refuseTotal()} 记账）。</li>
 *   <li>**复用必须归零全部字段，一个跳过项都没有**。{@link Warhead#kind} 的 {@code reset()} 写的是
 *       {@link Warhead#KIND_ROD} = 0，而 {@link Warheads} 没有 {@code seq} 这种"故意留新值"的
 *       代次戳 ⇒ {@link ResetProbe#assertAllZero} 的 {@code skipNames} 传空数组本身就是这条的断言。</li>
 * </ul>
 */
public final class WarheadsTest {

    /** 敌人容量：只为断言 {@code Warhead.contact} 那张 int 位图装得下，取真值同量级即可。 */
    private static final int FOES = Balance.wave.maxAlive + 2;

    // ---- 一、复用必须重置全部字段 -------------------------------------------------------------

    /**
     * 反射灌哨兵 → 复用 → 断言**每个**字段归零，跳过项为空。
     *
     * <p>{@link Warhead#contact} 在这一族里最要紧：它是"接触每步采样、伤害每 tick 计费"之间唯一的桥
     * （位号 = 敌人 obj 槽号），残留上一位的一位接触会让下一根杆开局就白扣某个敌人一格血，
     * 而那恰好是画面最难看出来的错。
     */
    @Test
    public void reuseResetsEverySingleField() throws Exception {
        Warheads pool = new Warheads(1, FOES);
        Warhead a = pool.spawn();
        ResetProbe.poison(a);
        ResetProbe.assertStillPoisoned(a);

        pool.killAt(0);
        Warhead b = pool.spawn();
        assertSame("池不按实例复用 ⇒ 下面测的就不是复用了", a, b);
        ResetProbe.assertAllZero(b);                       // 一个跳过项都不许有
        assertEquals(Warhead.KIND_ROD, b.kind);
        assertEquals(0, b.contact);
    }

    /** 摘掉的槽按后进先出还回来——{@code WarheadRules.spawnShards} 那条"必须先快照杆"的前提。 */
    @Test
    public void reapedSlotsComeBackLastOutFirstIn() {
        Warheads pool = new Warheads(3, FOES);
        Warhead a = pool.spawn();
        pool.spawn();
        Warhead c = pool.spawn();
        pool.killAt(2);
        assertSame("不是后进先出 ⇒ spawnShards 那条快照用例的根因变了", c, pool.spawn());
        assertSame(a, pool.activeAt(0));                   // 摘一格不该把别人挪位
    }

    // ---- 二、满池：拒生并记账 -----------------------------------------------------------------

    /**
     * 满池 {@code spawn()} 返回 null 且**不踢掉任何东西**：杆与碎片谁都不该被后来的挤掉，
     * 因为挤掉一根已经在切人的杆等于把它的剩余 tick 凭空抹掉（伤害少结），而少生一片碎片玩家看不出来。
     */
    @Test
    public void fullPoolRefusesWithoutEvictingAnything() {
        Warheads pool = new Warheads(2, FOES);
        Warhead a = pool.spawn();
        Warhead b = pool.spawn();
        long before = pool.refuseTotal();
        for (int i = 0; i < 5; i++) {
            assertNull("满池还生得出 ⇒ 拒生这条处置不存在了", pool.spawn());
        }
        assertEquals("每次被拒都要记一笔：静默截断是本仓的头号敌人", before + 5, pool.refuseTotal());
        assertEquals(2, pool.activeCount());
        assertSame(a, pool.activeAt(0));
        assertSame(b, pool.activeAt(1));
    }

    // ---- 三、清场与簿记 -----------------------------------------------------------------------

    /**
     * {@link Warheads#clear()} 只动游标与槽位表，**不写格子字段**——与 {@code Missiles.clear()} 同一条
     * 理由：{@code resetRun} 有一整条调用链跑在 UI 线程上（提案 ⑥），与渲染循环并发，撕裂读的最坏
     * 后果必须是"这一局的战斗部提前消失"，不能是"读到半个 tick 累计器"。
     */
    @Test
    public void clearReturnsEverySlotWithoutWritingFields() {
        Warheads pool = new Warheads(2, FOES);
        Warhead a = pool.spawn();
        Warhead b = pool.spawn();
        a.x = 42f;
        a.tickAcc = 0.07f;
        b.x = 7f;
        b.contact = 1;
        pool.clear();
        assertEquals(0, pool.activeCount());
        assertEquals(2, pool.freeCount());
        assertEquals("clear 写了格子字段 ⇒ 它不再只是个游标操作", 42f, a.x, 0f);
        assertEquals(0.07f, a.tickAcc, 0f);
        assertEquals(7f, b.x, 0f);
        assertEquals(1, b.contact);

        Warhead r1 = pool.spawn();
        Warhead r2 = pool.spawn();
        assertNotSame("两次 spawn 拿回同一格 ⇒ free-list 坏了一格", r1, r2);
        assertEquals("归零该发生在 occupy 的 reset() 里", 0f, r1.x, 0f);
        assertEquals(0f, r2.x, 0f);
        assertEquals(0, r1.contact);
        assertEquals(0, r2.contact);
    }

    /** 活跃 + 空闲 = 容量，任何操作序列之后都成立：漏还一格会在几百轮循环后红，而不是靠肉眼数。 */
    @Test
    public void activePlusFreeIsAlwaysTheCapacity() {
        Warheads pool = new Warheads(4, FOES);
        for (int round = 0; round < 500; round++) {
            assertEquals(4, pool.activeCount() + pool.freeCount());
            if (round % 3 == 0) assertNotNull(pool.spawn());
            else if (pool.activeCount() > 0) pool.killAt(pool.activeCount() - 1);
        }
        assertEquals(4, pool.activeCount() + pool.freeCount());
    }

    /** 越界下标一律返回 null / 无操作：倒序遍历的回收循环每帧都在拿它们试边界。 */
    @Test
    public void outOfRangeIndicesAreInert() {
        Warheads pool = new Warheads(2, FOES);
        pool.spawn();
        assertNull(pool.activeAt(-1));
        assertNull(pool.activeAt(1));
        assertNull(pool.activeAt(99));
        pool.killAt(-1);
        pool.killAt(1);
        assertEquals(1, pool.activeCount());
        assertEquals(1, pool.freeCount());
    }

    /**
     * 容量为 0 或负数当场拒。敌人容量越 32 界那条构造期断言在
     * {@link WarheadRulesTest#enemyCapacityAbove32FailsLoudly} 钉着，这里不重复立据。
     */
    @Test
    public void nonsenseCapacityFailsLoudly() {
        for (int cap : new int[]{0, -1}) {
            try {
                new Warheads(cap, FOES);
                throw new AssertionError("capacity=" + cap + " 应该当场拒");
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    /**
     * 容量余量（方案 §十「取证后仍无守护」第 1 条，2026-09-29 补测）。
     *
     * <p>{@code Balance.missile.warheadCapacity} 的注释里写着一条算式和 1.4× 这个数，而这条注释
     * 落在 {@code Balance.java} 的 javadoc 里——**全项目零个测试读它**（同一条注释的姊妹例
     * {@code MissilesTest.spawnRateBoundLeavesHeadroomInThePool} 早就把导弹池钉住了，唯独这张表没有）。
     * 于是"把 256 调小一点"这件事在 JVM 侧不会红，而它的表现是战场上少几片碎片、且只在密集引爆期少。
     *
     * <p>算式照注释走：每枚弹**至多引爆一次**（{@code maxLifeSec} 到点是静默回收、不展开战斗部），
     * 所以引爆率 {@code D ≤ 出膛率}；一次引爆占用的**格·秒** = 一根杆 + {@code shardCount} 片碎片
     * 各自的驻留时长。⚠ 驻留步数不取注释里那个 1.88 的连续寿命口径，而是**用真函数跑出来**：
     * {@link WarheadRules#advance} 累 {@code float}、{@link WarheadRules#expired} 用 {@code >=}，
     * 量化之后一根杆实际活多少步由这两者决定（注释里那个 1.90 是解析口径，同一族数字但不同算法）。
     *
     * <p>出膛率那五项一律现读，与导弹池那条同一条来源（连"收入上界那档扳机等级"都直接引用它，
     * 不重述 24 这个数）。⚠ 这条 bound 只在**射速卡读 B**（{@code rateMul} 有上界）下成立：
     * 读 A 下引爆率随卡等级无上界抬升，174 格那个上界与这条余量一起失效——方案 §1.6 与本条注释
     * 都登记了这件事，测试不替谁裁定（R29 待裁）。
     */
    @Test
    public void warheadCapacityLeavesHeadroomAtTheWorstCaseDetonationRate() {
        Balance.Missile spec = Balance.missile;
        Balance.Weapon gun = Balance.weapons[Balance.Weapon.MISSILE];
        float mul = Balance.status.frenzyRateMul
                * (1f + Balance.growth.ratePerLevel * Balance.Growth.MAX_LEVEL);
        ShopRun maxed = new ShopRun();
        for (int lv = 0; lv < MissilesTest.TRIGGER_LEVEL_AT_INCOME_CAP; lv++) {
            assertTrue("收入上界那档扳机等级买不满（第 " + lv + " 级被拒）：容量账的前提变了",
                    maxed.buyUpgrade(Balance.ShopCard.TRIGGER));
        }
        int n = Math.max(1, gun.pellets) + maxed.pelletBonus(Balance.Weapon.MISSILE);
        float shotsPerSecond = n / (gun.fireGap / mul);

        float cellSeconds = (stepsAlive(spec.rodLifeSec)
                + spec.shardCount * stepsAlive(spec.shardLifeSec)) * Time.STEP;
        float concurrent = cellSeconds * shotsPerSecond;      // D 取上界 = 出膛率

        assertTrue("最坏引爆率下并发 " + concurrent + " 格，超出战斗部池 " + spec.warheadCapacity
                + " ⇒ 会开始拒生（拒生是静默的，玩家看不出少了哪几片）",
                concurrent <= spec.warheadCapacity);
        assertTrue("余量只剩 " + (spec.warheadCapacity / concurrent) + " 倍，注释里写的 1.4× 已经不成立",
                spec.warheadCapacity >= concurrent * 1.4f);
    }

    /** 一格按真函数活多少步：累加与判据都用 {@link WarheadRules} 那两个，不在测试里重算。 */
    private static int stepsAlive(float maxLife) {
        Warheads pool = new Warheads(1, FOES);
        Warhead w = pool.spawn();
        w.maxLife = maxLife;
        int n = 0;
        while (!WarheadRules.expired(w)) {
            WarheadRules.advance(w, Time.STEP, Balance.missile);
            n++;
        }
        return n;
    }
}
