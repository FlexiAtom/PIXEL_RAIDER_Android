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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.flexiatom.pixelraider.game.BulletPool.Bullet;

import org.junit.Test;

/**
 * 玩家侧规则：伤害、护盾、连击、超载、hit-stop、状态层。
 *
 * 这一批是规格 §五 点名的"肉眼测不出"的那类——铁壁图标亮着但伤害一点没少，
 * 只有把 {@code takenMul()} 真正接进结算路径再断言，才看得见它有没有接线。
 */
public class PlayerRulesTest {

    private final int[] out = new int[3];

    // ---- 伤害：比例类 vs 固定点数类，不能混 -------------------------------------------------

    @Test
    public void contactDamageScalesWithMaxHp() {
        // [规格] 撞小怪 = 最大生命 50%：上限涨了，撞机伤害必须跟着涨
        assertEquals(50, DamageRules.contactDamage(Balance.enemies[Balance.Enemy.STRAIGHT], 100));
        assertEquals(75, DamageRules.contactDamage(Balance.enemies[Balance.Enemy.STRAIGHT], 150));
        assertEquals(35, DamageRules.bossContactDamage(100));
        assertEquals(49, DamageRules.bossContactDamage(140));   // 140×35%
    }

    @Test
    public void bulletDamageIsFixedPointsRegardlessOfMaxHp() {
        // [规格] 小怪弹 12、Boss 弹 20 —— 固定点数，与生命上限无关
        assertEquals(12, DamageRules.bulletDamage(false));
        assertEquals(20, DamageRules.bulletDamage(true));
    }

    @Test
    public void bursterOnlyHitsHardAtPointBlank() {
        int plain = DamageRules.contactDamageAt(Balance.enemies[Balance.Enemy.BURSTER], 100, false);
        int blank = DamageRules.contactDamageAt(Balance.enemies[Balance.Enemy.BURSTER], 100, true);
        assertEquals(60, blank);                       // [规格] 贴脸 60%
        assertEquals("擦碰等同杂兵，否则'躲开爆心'没有意义", 50, plain);
        assertTrue(blank > plain);
    }

    @Test
    public void contactFloorKeepsEveryHitDeadly() {
        // 血量上限被改小时，50% 可能四舍五入到 0；那等于"撞到不掉血"，必须兜到 1
        assertEquals(1, DamageRules.contactDamage(Balance.enemies[Balance.Enemy.STRAIGHT], 1));
        assertEquals(1, DamageRules.scaleTaken(0, 0.01f));
    }

    @Test
    public void statusLayerMultipliesIntoEveryDamagePath() {
        StatusLayers s = new StatusLayers();
        s.activate(StatusLayers.IRON);
        s.setEnvironment(StatusLayers.ENV_FORGE);
        assertEquals(0.875f, s.takenMul(), 1e-5f);
        // 铁壁 -30% × 熔炉 +25%：两层相乘，谁也不覆盖谁
        assertEquals(44, DamageRules.scaleTaken(50, s.takenMul()));
        s.setEnvironment(StatusLayers.ENV_NONE);
        assertEquals(35, DamageRules.scaleTaken(50, s.takenMul()));
        s.activate(StatusLayers.BRITTLE);
        assertEquals("[规格] 拾取层同时只保留一个：脆化顶掉铁壁，不是 0.7×1.4", 1f * 1.4f,
                s.takenMul(), 1e-5f);
        assertEquals(70, DamageRules.scaleTaken(50, s.takenMul()));
    }

    @Test
    public void shieldAbsorbsBeforeHpAndReportsBreakOnce() {
        DamageRules.applyToPlayer(100, 100, 40, 1f, out);
        assertEquals(60, out[0]);
        assertEquals(100, out[1]);
        assertEquals(0, out[2]);

        DamageRules.applyToPlayer(30, 100, 40, 1f, out);
        assertEquals(0, out[0]);
        assertEquals(90, out[1]);
        assertEquals("盾从有到无就是破碎", 1, out[2]);

        DamageRules.applyToPlayer(0, 90, 40, 1f, out);
        assertEquals(0, out[0]);
        assertEquals(50, out[1]);
        assertEquals("本来就没盾，不是这次破的", 0, out[2]);
    }

    @Test
    public void depletionClampsAtZeroAndDoesNotGoNegative() {
        DamageRules.applyToPlayer(10, 20, 500, 1f, out);
        assertEquals(0, out[0]);
        assertEquals(0, out[1]);
        assertEquals(1, out[2]);
    }

    @Test
    public void shieldGainReportsActuallyAppliedAmount() {
        assertEquals(35, DamageRules.shieldGainActuallyApplied(50, 100, 35));
        assertEquals(10, DamageRules.shieldGainActuallyApplied(90, 100, 35));
        assertEquals("满盾时不能谎报 +35", 0, DamageRules.shieldGainActuallyApplied(100, 100, 35));
        assertEquals("上限由调用方传：抬到 150 之后还有 30 的空间，按实际收到的算", 30,
                DamageRules.shieldGainActuallyApplied(120, 150, 35));
        assertEquals(25, DamageRules.shieldGainActuallyApplied(120, 150, 25));
    }

    @Test
    public void overloadSweepDamageDoesNotGoThroughTakenLayer() {
        assertEquals(45, DamageRules.overloadDamage());   // [可调] 表里的扫描伤害
    }

    // ---- 连击 -------------------------------------------------------------------------------

    @Test
    public void comboAccumulatesInsideWindowAndDiesOutside() {
        ComboMeter c = new ComboMeter();
        assertEquals(0, c.count(0f));
        c.hit(0f);
        c.hit(1f);
        c.hit(1.5f);
        assertEquals(3, c.count(1.5f));
        assertEquals(1.3f, c.multiplier(1.5f), 1e-5f);
        assertEquals("窗口外连击归零，不是留着慢慢掉", 0, c.count(3.6f));
        assertEquals(1f, c.multiplier(3.6f), 1e-5f);
        // 隔太久的那一次算新起点，不是"3+1"
        c.hit(4f);
        assertEquals(1, c.count(4f));
    }

    @Test
    public void comboBonusCapsAtPlus200() {
        ComboMeter c = new ComboMeter();
        for (int i = 0; i < 40; i++) c.hit(i * 0.1f);     // 全程在 2 秒窗口内
        assertEquals(40, c.count(3.9f));
        assertEquals("[规格] 上限 +200%，之后持平", 200, c.bonusPercent(3.9f));
        assertEquals(3f, c.multiplier(3.9f), 1e-5f);
        c.hit(4.0f);
        assertEquals(200, c.bonusPercent(4f));
    }

    @Test
    public void comboResetClearsImmediately() {
        ComboMeter c = new ComboMeter();
        c.hit(0f);
        c.reset();
        assertEquals(0, c.count(0f));
    }

    // ---- 超载 -------------------------------------------------------------------------------

    @Test
    public void overloadStartsReadyAndCoolsThirtySeconds() {
        OverloadMeter m = new OverloadMeter();
        assertTrue("开局就该能用，否则教学期碰不到这个机制", m.ready());
        assertEquals(1f, m.charge01(), 1e-5f);
        assertTrue(m.trigger());
        assertFalse(m.ready());
        assertEquals(30f, m.remaining(), 1e-5f);
        m.step(15f);
        assertEquals(0.5f, m.charge01(), 1e-5f);
        m.step(15f);
        assertTrue(m.ready());
        assertEquals("就绪时倒计时归零，不给负数", 0f, m.remaining(), 0f);
        assertEquals(1f, m.charge01(), 1e-5f);
    }

    @Test
    public void spammingTriggerDoesNotResetTheCooldown() {
        OverloadMeter m = new OverloadMeter();
        assertTrue(m.trigger());
        m.step(20f);
        assertFalse("未冷却时拒绝", m.trigger());
        assertEquals("拒绝必须不改变状态：连点不能把冷却重置回 30 秒", 10f, m.remaining(), 1e-5f);
    }

    @Test
    public void cooldownNeverGoesNegative() {
        OverloadMeter m = new OverloadMeter();
        m.trigger();
        m.step(1000f);
        assertEquals(0f, m.remaining(), 0f);
        assertEquals(1f, m.charge01(), 1e-5f);
    }

    // ---- hit-stop：世界时间与表现时间是两个时钟 ----------------------------------------------

    @Test
    public void hitStopFreezesWorldButNotRealTime() {
        HitStop h = new HitStop();
        assertEquals(0.016f, h.worldSecondsFor(0.016f), 1e-6f);   // 未 arm 时全额给世界
        h.arm(0.04f);
        assertTrue(h.active());
        assertEquals("整帧被定格吃掉", 0f, h.worldSecondsFor(0.016f), 1e-6f);
        assertEquals("定格吃掉世界，但自己的时钟照 16ms 走", 0.024f, h.remaining(), 1e-5f);
        assertEquals(0f, h.worldSecondsFor(0.016f), 1e-6f);
        assertEquals(0.008f, h.worldSecondsFor(0.016f), 1e-6f);   // 第三帧只欠 8ms
        assertFalse(h.active());
        assertEquals(0.016f, h.worldSecondsFor(0.016f), 1e-6f);
    }

    @Test
    public void hitStopNeverHandsBackNegativeWorldTime() {
        HitStop h = new HitStop();
        h.arm(0.05f);
        for (int i = 0; i < 20; i++) {
            float world = h.worldSecondsFor(1f / 144f);           // 高刷屏的小帧
            assertTrue("世界时间不能为负，否则物理会倒放", world >= 0f);
        }
        h.arm(-1f);
        assertFalse("负秒数不该把已有定格加长", h.active());
    }

    @Test
    public void hitStopSecondsStayInsideSpecBand() {
        assertEquals(0.030f, HitStop.secondsFor(0f), 1e-6f);
        assertEquals(0.050f, HitStop.secondsFor(1f), 1e-6f);
        assertEquals(0.050f, HitStop.secondsFor(9f), 1e-6f);      // 越界钳住
        assertEquals(0.030f, HitStop.secondsFor(-9f), 1e-6f);
        float mid = HitStop.secondsFor(0.5f);
        assertTrue(mid > 0.030f && mid < 0.050f);
    }

    // ---- 状态层 -----------------------------------------------------------------------------

    @Test
    public void freshLayersAreNeutral() {
        StatusLayers s = new StatusLayers();
        assertEquals(1f, s.takenMul(), 1e-6f);
        assertEquals(1f, s.rateMul(), 1e-6f);
        assertEquals(1f, s.speedMul(), 1e-6f);
        assertEquals(1f, s.dropMul(), 1e-6f);
        assertEquals(0, s.activeCount());
    }

    @Test
    public void pickupExpiresAfterTwelveSecondsButEnvironmentNeverDoes() {
        StatusLayers s = new StatusLayers();
        s.activate(StatusLayers.FRENZY);
        s.setEnvironment(StatusLayers.ENV_VOID);
        assertEquals("拾取层与环境层同时生效时相乘",
                Balance.status.frenzySpeedMul * Balance.status.voidSpeedMul, s.speedMul(), 1e-5f);
        s.step(11.9f);
        assertTrue(s.has(StatusLayers.FRENZY));
        assertEquals(0.1f, s.remainingOf(StatusLayers.FRENZY), 1e-4f);
        s.step(0.2f);
        assertFalse(s.has(StatusLayers.FRENZY));
        assertEquals(0, s.activeCount());
        assertEquals("[规格] 拾取 12 秒；环境层由星区决定，不到点不掉",
                Balance.status.voidSpeedMul, s.speedMul(), 1e-5f);
        s.step(9999f);
        assertEquals(Balance.status.voidTakenMul, s.takenMul(), 1e-5f);
    }

    @Test
    public void duplicatePickupRefreshesInsteadOfStackingLayers() {
        StatusLayers s = new StatusLayers();
        s.activate(StatusLayers.IRON);
        s.activate(StatusLayers.IRON);
        assertEquals("重复拾取只刷新时长，不叠两层", 1, s.activeCount());
        assertEquals(0.7f, s.takenMul(), 1e-5f);
        s.step(11f);
        s.activate(StatusLayers.IRON);
        s.step(11f);
        assertTrue("刷新过就必须重新走满 12 秒", s.has(StatusLayers.IRON));
    }

    @Test
    public void activeKindsFeedsHudWithoutAllocation() {
        StatusLayers s = new StatusLayers();
        s.activate(StatusLayers.LUCKY);
        int[] buf = new int[StatusLayers.PICKUP_COUNT];
        assertEquals(1, s.activeKinds(buf));
        assertEquals(StatusLayers.LUCKY, buf[0]);
        s.activate(StatusLayers.STICKY);
        assertEquals("新的顶掉旧的，HUD 上永远只有一个拾取状态", 1, s.activeKinds(buf));
        assertEquals(StatusLayers.STICKY, buf[0]);
        assertFalse("被顶掉的旧状态不该还能查到", s.has(StatusLayers.LUCKY));
        assertEquals(1, s.activeCount());
        s.step(11.9f);
        assertEquals(1, s.activeCount());
        s.step(0.2f);
        assertEquals(0, s.activeCount());
        assertEquals(0, s.activeKinds(buf));
        assertEquals("槽位空了以后读不到残留", StatusLayers.NONE, s.pickup());
    }

    @Test
    public void dropLayerCombinesPickupAndEnvironment() {
        StatusLayers s = new StatusLayers();
        s.activate(StatusLayers.LUCKY);
        s.setEnvironment(StatusLayers.ENV_STORM);
        assertEquals(Balance.status.luckyDropMul * Balance.status.stormDropMul, s.dropMul(), 1e-5f);
        assertEquals(1f, s.takenMul(), 1e-6f);   // 掉落层不许悄悄改受伤
        s.activate(StatusLayers.BARREN);         // 顶掉幸运
        assertEquals(Balance.status.barrenDropMul * Balance.status.stormDropMul, s.dropMul(), 1e-5f);
        s.clearPickups();
        assertEquals(Balance.status.stormDropMul, s.dropMul(), 1e-5f);
        assertEquals(0, s.activeCount());
    }

    @Test
    public void badKindOrEnvironmentIgnoredInsteadOfCrashing() {
        StatusLayers s = new StatusLayers();
        s.activate(-1);
        s.activate(StatusLayers.PICKUP_COUNT);
        s.setEnvironment(99);
        assertEquals(0, s.activeCount());
        assertEquals(StatusLayers.ENV_NONE, s.environment());
        assertEquals(1f, s.takenMul(), 1e-6f);
        assertFalse(s.has(999));
        assertEquals(0f, s.remainingOf(-5), 0f);
    }

    @Test
    public void labelsExistForEveryKind() {
        for (int i = 0; i < StatusLayers.PICKUP_COUNT; i++) {
            assertTrue("状态 " + i + " 缺名字", StatusLayers.label(i).length() == 2);
            assertTrue("状态 " + i + " 没登记过却标成用过", !new StatusLayers().everUsed(i));
        }
        StatusLayers s = new StatusLayers();
        s.activate(StatusLayers.BARREN);
        assertTrue(s.everUsed(StatusLayers.BARREN));
        assertFalse(s.everUsed(StatusLayers.SWIFT));
        assertEquals("", StatusLayers.environmentLabel(StatusLayers.ENV_NONE));
        assertTrue(StatusLayers.environmentLabel(StatusLayers.ENV_FORGE).length() == 2);
    }

    // ---- 编译期就顺带确认：子弹对象的 reset 覆盖全部字段 ----------------------------------------

    @Test
    public void bulletDefaultsAreNeutral() {
        Bullet b = new Bullet();
        assertEquals(0f, b.x, 0f);
        assertEquals(0L, b.seq);
        assertFalse(b.hostile);
    }
}
