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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.flexiatom.pixelraider.game.BulletPool.Bullet;
import com.flexiatom.pixelraider.game.Drops.Item;
import com.flexiatom.pixelraider.game.Enemies.Enemy;

import org.junit.Test;

/**
 * 实体层：敌人池、运动行为、玩家状态、开火、掉落。
 *
 * 全部走"跑很多帧再断言"的方式——单帧断言只能证明算术，多帧才能证明
 * "射手不会溜出战斗区""冲刺锁定后不再追踪""池转起来之后不新增对象"这三类真问题。
 */
public class EntitiesTest {

    private static final int W = 240;
    private static final int TOP = 40;
    private static final float DT = 1f / 60f;
    private static final int STEPS_1S = 60;

    // ---- Enemies 池 ---------------------------------------------------------------------

    @Test
    public void enemyPoolReusesInsteadOfGrowing() {
        final int CAP = 8;
        Enemies es = new Enemies(CAP);
        Enemy[] seen = new Enemy[CAP];
        for (int i = 0; i < CAP; i++) seen[i] = es.spawn();
        assertNull("池满返回 null，由导演排回队列", es.spawn());
        for (int r = 0; r < 200; r++) {
            es.killAt(es.activeCount() - 1);
            Enemy e = es.spawn();
            assertNotNull(e);
            boolean known = false;
            for (int i = 0; i < CAP; i++) if (seen[i] == e) known = true;
            assertTrue("第 " + r + " 轮出现新对象", known);
        }
        assertEquals((long) CAP, es.spawnTotal() - es.reuseTotal());
    }

    @Test
    public void enemyReuseResetsEveryField() throws Exception {
        Enemies es = new Enemies(1);
        Enemy a = es.spawn();
        long firstBorn = a.born;
        ResetProbe.poison(a);
        ResetProbe.assertStillPoisoned(a);
        es.killAt(0);
        Enemy b = es.spawn();
        assertSame(a, b);
        // born 是复用时**故意**写的新代次戳，和 Bullet.seq 同一种东西：跳过它，但要单独钉它重新盖了章。
        ResetProbe.assertAllZero(b, "born");
        assertTrue("born 必须重新盖章：槽号会被 free-list 复用，光凭槽号认不出换了哪只怪", b.born > firstBorn);
    }

    @Test
    public void isLiveFollowsTheActiveTableAndBornDetectsSlotReuse() {
        Enemies es = new Enemies(2);
        Enemy a = es.spawn();
        long bornA = a.born;
        int slotA = es.slotOfActive(0);
        assertTrue("刚 spawn 就该判活", es.isLive(slotA));
        es.spawn();                                  // 占掉第二个槽
        es.killAt(0);                                // 末位交换：b 落到下标 0
        assertFalse("摘表之后槽号立刻失效——导弹持着它必须改锁或自爆", es.isLive(slotA));
        Enemy c = es.spawn();
        assertSame("free-list 把同一个槽还给了新敌人", a, c);
        assertTrue("同槽复用的两只必须靠代次戳区分", c.born != bornA);
        assertTrue(es.isLive(slotA));
        assertFalse("负槽号越界", es.isLive(-1));
        assertFalse("等于容量的槽号越界", es.isLive(es.capacity()));
    }

    @Test
    public void cullRemovesDeadAndOffscreenBySwappingBackwards() {
        Enemies es = new Enemies(8);
        Enemy dead = es.spawn();
        dead.hp = 0;
        Enemy alive = es.spawn();
        alive.hp = 3;
        alive.y = TOP + 10f;
        Enemy slid = es.spawn();
        slid.hp = 3;
        slid.y = TOP + 400f;                       // 战斗区底 320 + margin 之外
        es.cullDeadAndOffscreen(TOP + 320, 16);
        assertEquals(1, es.activeCount());
        assertSame(alive, es.activeAt(0));
        assertEquals(1, es.aliveCount());
    }

    @Test
    public void slotOfActiveFeedsTheSpatialGridIdentity() {
        Enemies es = new Enemies(4);
        es.spawn();
        Enemy second = es.spawn();
        assertEquals(1, es.slotOfActive(1));
        assertSame(second, es.objAt(es.slotOfActive(1)));
        assertEquals(-1, es.slotOfActive(9));
    }

    // ---- 运动行为 ---------------------------------------------------------------------------

    @Test
    public void straightDescendsWithoutDrifting() {
        Enemy e = fresh(Balance.Enemy.STRAIGHT, 120f, TOP - 8f);
        for (int i = 0; i < STEPS_1S; i++) {
            advance(e, 120f, TOP + 240f);
        }
        assertEquals(120f, e.x, 1e-3f);
        assertEquals(TOP - 8f + Balance.enemies[Balance.Enemy.STRAIGHT].speed, e.y, 0.2f);
        assertTrue(e.enter >= 1f);                  // 0.25 秒渐入，1 秒后必须满
    }

    @Test
    public void weaveOscillatesHorizontallyWhileAdvancingVertically() {
        Enemy e = fresh(Balance.Enemy.WEAVE, 120f, TOP + 4f);
        float min = 120f, max = 120f;
        for (int i = 0; i < 3 * STEPS_1S; i++) {
            advance(e, 120f, TOP + 240f);
            if (e.x < min) min = e.x;
            if (e.x > max) max = e.x;
        }
        assertTrue("横摆没有摆起来", max - min > 20f);
        assertTrue("左右不对称", Math.abs((max - 120f) - (120f - min)) < 6f);
        assertTrue(e.y > TOP + 100f);
    }

    @Test
    public void shooterHoldsItsLineInsteadOfRunningToTheBottom() {
        Enemy e = fresh(Balance.Enemy.SHOOTER, 120f, TOP - 8f);
        float hold = EnemyBehavior.holdY(TOP, e.anchorX);
        int shots = 0;
        for (int i = 0; i < 10 * STEPS_1S; i++) {
            shots += (advance(e, 120f, TOP + 240f) & EnemyBehavior.FLAG_FIRED) != 0 ? 1 : 0;
            assertTrue("射手怪溜出了战斗区", e.y <= TOP + 320f);
        }
        assertTrue("停线后还在往下走: " + e.y, e.y <= hold + 1f);
        assertEquals("10 秒内应开 7 次火（fireGap 1.6，首发在第 0 帧，之后每 96 帧一次）", 7, shots);
        assertTrue("到线后没在巡边", Math.abs(e.x - e.anchorX) > 0.5f);
    }

    @Test
    public void rusherLocksOnceAndThenStaysCommitted() {
        Enemy e = fresh(Balance.Enemy.RUSHER, 120f, TOP + 2f);
        advance(e, 120f, TOP + 240f);
        float slowVy = e.vy;
        assertTrue("锁定前就该很慢", slowVy < Balance.enemies[Balance.Enemy.RUSHER].speed);
        assertFalse(e.locked);
        int guard = 0;
        while (!e.locked && guard++ < 600) {
            advance(e, 120f, TOP + 240f);
        }
        assertTrue("迟迟不锁定", e.locked);
        assertTrue("锁定后必须比锁定前快", e.vy > slowVy * 2f);
        float vx = e.vx, vy = e.vy;
        for (int i = 0; i < STEPS_1S; i++) advance(e, 12f, TOP + 300f);   // 玩家逃到左上
        assertEquals("锁定后不再修正方向（可躲）", vx, e.vx, 1e-4f);
        assertEquals(vy, e.vy, 1e-4f);
    }

    @Test
    public void bursterHomesAndExplodesOnlyInsideBlastRadius() {
        Balance.Enemy spec = Balance.enemies[Balance.Enemy.BURSTER];
        Enemy e = fresh(Balance.Enemy.BURSTER, 60f, TOP + 100f);
        advance(e, 200f, TOP + 100f);
        assertTrue("该往玩家那侧靠", e.vx > 0f);
        assertEquals(0, advance(e, 200f, TOP + 10f) & EnemyBehavior.FLAG_EXPLODE);
        e.x = 120f;
        e.y = TOP + 100f;
        Enemy far = fresh(Balance.Enemy.BURSTER, 120f, TOP + 100f);
        assertEquals("距离 " + spec.burstRadius + " 之外不该炸", 0,
                advance(far, 120f, TOP + 100f + spec.burstRadius + 20f) & EnemyBehavior.FLAG_EXPLODE);
        assertFalse(EnemyBehavior.withinBlast(e, 120f, TOP + 100f + spec.burstRadius + 20f,
                spec.burstRadius));
        assertTrue(EnemyBehavior.withinBlast(e, 120f, TOP + 100f, spec.burstRadius));
    }

    @Test
    public void helperMathIsExactWhereItNeedsToBe() {
        assertEquals(74f * 0.75f, EnemyBehavior.homeVx(0f, 240f, 74f), 1e-4f);   // 限速
        assertEquals(2f, EnemyBehavior.homeVx(118f, 120f, 74f), 1e-4f);
        assertEquals(-2f, EnemyBehavior.homeVx(122f, 120f, 74f), 1e-4f);
        assertEquals(TOP + 64f, EnemyBehavior.rushLockY(TOP), 0f);
        float hold = EnemyBehavior.holdY(TOP, 3.7f);
        assertTrue(hold >= TOP + 44f && hold <= TOP + 96f + 1e-4f);
        assertEquals("同一道永远同一条线", hold, EnemyBehavior.holdY(TOP, 3.7f), 0f);

        // 位移式摆动：绕 anchorX 严格对称，且被走廊钳住
        Enemy e = new Enemy();
        e.anchorX = 120f;
        e.radius = 4f;
        e.x = 120f;
        e.phase = (float) Math.PI / 2f;
        EnemyBehavior.strafe(e, 26f, W, DT);
        assertEquals(146f, e.x, 1e-3f);
        e.phase = (float) Math.PI * 1.5f;
        EnemyBehavior.strafe(e, 26f, W, DT);
        assertEquals(94f, e.x, 1e-3f);
        e.anchorX = 8f;
        e.phase = (float) Math.PI * 1.5f;
        EnemyBehavior.strafe(e, 26f, W, DT);
        assertEquals("越界时钳在走廊里，不钻出画面", 6f, e.x, 1e-3f);
    }

    @Test
    public void corridorClampReflectsInsteadOfStickingToTheWall() {
        Enemy e = new Enemy();
        e.radius = 4f;
        e.x = -20f;
        e.vx = -30f;
        EnemyBehavior.keepInsideCorridor(e, W);
        assertEquals(6f, e.x, 1e-4f);
        assertEquals(30f, e.vx, 1e-4f);                   // 弹回
        e.x = 9999f;
        e.vx = 5f;
        EnemyBehavior.keepInsideCorridor(e, W);
        assertEquals(W - 6f, e.x, 1e-4f);
        assertEquals(-5f, e.vx, 1e-4f);
    }

    @Test
    public void timersNeverGoNegativeAndEnterSaturates() {
        Enemy e = fresh(Balance.Enemy.STRAIGHT, 120f, TOP);
        e.enter = 0f;
        e.hitFlash = 0.02f;
        for (int i = 0; i < 10; i++) advance(e, 120f, TOP + 240f);
        assertEquals("计时器减到 0 就该停，不能变负", 0f, e.hitFlash, 0f);
        assertEquals(10f * DT / EnemyBehavior.ENTER_SEC, e.enter, 1e-4f);
        for (int i = 0; i < STEPS_1S; i++) advance(e, 120f, TOP + 240f);
        assertEquals("渐入饱和在 1，不会一路累加", 1f, e.enter, 0f);
    }

    // ---- 玩家状态 ---------------------------------------------------------------------------

    @Test
    public void shieldIsSpentBeforeHp() {
        PlayerState p = newPlayer();
        int flags = p.hurt(50, 1f);
        assertEquals(PlayerState.FLAG_HIT, flags);
        assertEquals(50, p.shield);
        assertEquals(100, p.hp);
        assertTrue(p.invulnerable());
        assertEquals("无敌期不掉血", 0, p.hurt(999, 1f));
        assertEquals(50, p.shield);
        p.stepTimers(Balance.player.invulnSec + 0.01f);
        assertFalse(p.invulnerable());
        assertEquals("[规格] 受击无敌 0.7 秒", 0.7f, Balance.player.invulnSec, 0f);
    }

    @Test
    public void shieldBreakGrantsItsOwnShortInvuln() {
        PlayerState p = newPlayer();
        p.shield = 20;
        int flags = p.hurt(30, 1f);
        assertEquals(0, p.shield);
        assertEquals(90, p.hp);
        assertEquals(PlayerState.FLAG_HIT | PlayerState.FLAG_SHIELD_BROKEN, flags);
        // 破碎的 0.5 秒不该把受击的 0.7 秒抹短
        assertEquals(0.7f, p.invuln, 1e-5f);
        p.invuln = 0f;
        p.hurt(10, 1f);
        assertEquals("只加长不缩短", 0.7f, p.invuln, 1e-5f);
    }

    @Test
    public void deathFlagIsRaisedOnceAndFurtherHitsIgnore() {
        PlayerState p = newPlayer();
        p.shield = 0;
        p.hp = 20;
        p.invuln = 0f;
        int flags = p.hurt(50, 1f);
        assertEquals(PlayerState.FLAG_HIT | PlayerState.FLAG_DIED, flags);
        assertEquals(0, p.hp);
        assertFalse(p.alive);
        assertEquals(0, p.hurt(50, 1f));
    }

    @Test
    public void overfullHealConvertsIntoShield() {
        PlayerState p = newPlayer();
        p.hp = 90;
        p.shield = 40;
        assertEquals(10, p.healHp(20, Balance.shield.fromHpOverfullSmall));
        assertEquals(100, p.hp);
        assertEquals(65, p.shield);
        p.invuln = 0f;
        assertEquals("[推导] 溢出小血包只补 25 盾：40 + 25 = 65", 65, p.shield);
        p.shield = 100;
        assertEquals("满盾时一格也塞不进", 0, p.addShield(35));
        assertEquals(100, p.shield);
        p.shield = 80;
        assertEquals("差 20 就只收 20，不谎报 +35", 20, p.addShield(35));
        assertEquals(100, p.shield);
    }

    /**
     * 两条加盾通道同一起点（80/100）、同一份额度（+50），镜像放在一起：
     * 谁把截断改回共享入口、或把溢出漏给道具，只会红一条，当场就能看出是哪条通道出的问题。
     */
    @Test
    public void shopChannelStacksAboveTheCapWhileTheItemChannelStillClamps() {
        PlayerState shop = newPlayer();
        shop.shield = 80;
        assertEquals("商店卡不打折：写多少给多少", Balance.shield.fromUpgrade,
                shop.addShieldBeyondCap(Balance.shield.fromUpgrade));
        assertEquals(130, shop.shield);
        assertTrue("溢出真的存在，没有被别处悄悄钳回上限", shop.shield > shop.maxShield);
        assertEquals("[推导] 比值出口不钳，HUD 才看得到 >1（越限色靠它）", 1.3f,
                shop.shieldRatio(), 1e-6f);

        PlayerState item = newPlayer();
        item.shield = 80;
        assertEquals("道具照旧：差 20 就只收 20", 20, item.addShield(Balance.shield.fromUpgrade));
        assertEquals(100, item.shield);
    }

    /** 溢出是局内态：重开一局不该把上一局多出来的那一截带过来。 */
    @Test
    public void respawnPutsTheShieldBackInsideTheCap() {
        PlayerState p = newPlayer();
        p.shield = 130;
        assertTrue(p.shield > p.maxShield);
        p.respawn(120f, 300f);
        assertEquals("respawn 把盾打回上限，跨局残留不可能活下来", p.maxShield, p.shield);
    }

    @Test
    public void raisedCapsCarryTheDifferenceUpwards() {
        PlayerState p = newPlayer();
        p.hp = 100;
        p.raiseCaps(120, 110);
        assertEquals(120, p.maxHp);
        assertEquals(120, p.hp);
        assertEquals(110, p.maxShield);
        assertEquals(110, p.shield);
        p.raiseCaps(100, 100);            // 只降不升：不该把已有增益抹掉
        assertEquals(120, p.maxHp);
    }

    @Test
    public void fireCadenceFollowsTheStatusLayer() {
        PlayerState p = newPlayer();
        float normal = p.cooldownFor(1f);
        assertEquals(Balance.weapons[p.weaponId].fireGap, normal, 1e-6f);
        assertTrue("狂热必须真的打得更快", p.cooldownFor(1.35f) < normal);
        assertTrue("迟滞必须真的打得更慢", p.cooldownFor(0.75f) > normal);
        assertEquals("rateMul 传坏值时按 1 算而不是除零", normal, p.cooldownFor(0f), 1e-6f);
        assertTrue(p.canFire(1f));
        p.consumeShot(1f);
        assertFalse(p.canFire(1f));
        p.stepTimers(normal + 0.01f);
        assertTrue(p.canFire(1f));
    }

    @Test
    public void playerStaysInsideTheBattleArea() {
        PlayerState p = newPlayer();
        p.x = 9999f;
        p.y = -9999f;
        p.clampToBattle(W, TOP, 320);
        assertEquals(W - p.radius - 1f, p.x, 1e-4f);
        assertEquals(TOP + p.radius + 1f, p.y, 1e-4f);
        p.x = -9999f;
        p.y = 9999f;
        p.clampToBattle(W, TOP, 320);
        assertEquals(p.radius + 1f, p.x, 1e-4f);
        assertEquals(TOP + 320 - p.radius - 1f, p.y, 1e-4f);
    }

    // ---- 开火 -------------------------------------------------------------------------------

    /**
     * 用**真实路径**打一整个世代：布局 → 冻结模板 → 每帧 (链排放, 弹积分) 直到排完。
     *
     * <p>为什么不直接调 {@code WeaponFire.fireOne} 了事：{@code fire} 那个门面已经删了，
     * 留下一条"测试专用的一次打完 N 发"的路径就等于让测试跑在生产代码不走的那条路上——
     * 弹链改造后生产路径横跨 C 个时隙，只有照它的帧顺序喂时间，量到的间距才是屏幕上的间距。
     *
     * <p>{@code missiles} 给 {@link Balance.Weapon#guided} 那把枪用；其余武器传 null 即可
     * （链只在模板是制导的时候才读它，读不到就抛——见 {@link BulletChain#step}）。
     */
    private static int fireGeneration(BulletChain chain, BulletPool pool, Missiles missiles,
                                      Balance.Weapon w,
                                      int pellets, float period, WeaponFire.Template tpl,
                                      float speedMul, float attackMul, int flatDamage,
                                      float roll01, int critBonus) {
        chain.layout(w, pellets, period);
        WeaponFire.fillTemplate(tpl, w, speedMul, attackMul, flatDamage, roll01, critBonus);
        chain.beginGeneration(tpl);
        int made = 0;
        while (chain.remaining() > 0) {
            made += chain.step(DT, 120f, 300f, pool, missiles);
            pool.stepAndCompact(DT, W, 533, 16);
        }
        return made;
    }

    /** 只关心"这一发的伤害/暴击算得对不对"时用这条：单发不必经过链。 */
    private static Bullet oneBullet(Balance.Weapon w, float speedMul, float attackMul,
                                    int flatDamage, float roll01, int critBonus) {
        WeaponFire.Template t = new WeaponFire.Template();
        WeaponFire.fillTemplate(t, w, speedMul, attackMul, flatDamage, roll01, critBonus);
        return WeaponFire.fireOne(new BulletPool(4), t, 120f, 300f, 0f);
    }

    /**
     * 一代弹丸是一条**沿瞄准线排开的链**：相邻两发首尾相切（间距 = 绘制弹长），全部正上方飞。
     *
     * <p>取代先前的"散射扇形对称张开"——那 5 发同角重叠的弹在屏幕上是一条线、DPS 却按 5 放大，
     * 弹链把"多发"改放到**时间**轴上（提案 bullet-chain-geometry）。张角现在只在**多列**时出现，
     * 归 {@code BulletChainTest} 管；这里钉的是"一列时必须严格成一条竖链"。
     */
    @Test
    public void aGenerationIsAChainNotAPile() {
        BulletPool pool = new BulletPool(Balance.bullet.capacity);
        BulletChain chain = new BulletChain();
        WeaponFire.Template tpl = new WeaponFire.Template();
        Balance.Weapon shot = Balance.weapons[Balance.Weapon.SHOT];      // 5 发、张角 44°
        int made = fireGeneration(chain, pool, null, shot, Math.max(1, shot.pellets), shot.fireGap,
                tpl, 1f, 1f, 0, 0.9f, 0);
        assertEquals(5, made);
        assertEquals(5, pool.activeCount());
        float len = shot.size * shot.drawLenRatioY;
        for (int i = 0; i < 5; i++) {
            Bullet b = pool.activeAt(i);
            assertEquals(shot.bulletSpeed, (float) Math.hypot(b.vx, b.vy), 1e-2f);
            assertTrue("弹丸没有向上飞", b.vy < 0f);
            assertEquals("单列一代不该有任何横向分量", 0f, b.vx, 1e-3f);
            assertEquals(shot.size, b.size, 0f);
            assertEquals(shot.color, b.color);
        }
        // activeAt 的下标顺序 = 出膛顺序（free-list 从 0 往上取，且这一代没有任何一发出界），
        // 所以第 i 发比第 i−1 发晚恰好一个时隙。间距的下界是弹长（相切），上界再多一帧行程
        // （时隙只能落在帧边界上，量不出来更细的分度）。
        for (int i = 1; i < 5; i++) {
            float gap = pool.activeAt(i).y - pool.activeAt(i - 1).y;
            assertTrue("第 " + i + " 发压在了前一发身上：弹链退化成了同一点", gap >= len - 1e-3f);
            assertTrue("第 " + i + " 发与前一发之间断开了超过一帧行程",
                    gap <= len + DT * shot.bulletSpeed + 1e-3f);
        }
        assertTrue("链应该往屏幕上方排", pool.activeAt(4).y < 300f);
    }

    @Test
    public void critIsDeterministicGivenTheRoll() {
        Balance.Weapon pulse = Balance.weapons[Balance.Weapon.PULSE];
        Bullet crit = oneBullet(pulse, 1f, 1f, 0, 0.01f, 0);
        assertTrue(crit.crit);
        assertEquals(pulse.damage * WeaponFire.CRIT_MULTIPLIER, crit.damage, 1e-5f);
        assertEquals(6, WeaponFire.damageOf(crit));
        Bullet plain = oneBullet(pulse, 1f, 1f, 0, 0.99f, 0);
        assertFalse(plain.crit);
        assertEquals(3, WeaponFire.damageOf(plain));
        assertTrue(WeaponFire.isCrit(0.079f, 8));
        assertFalse(WeaponFire.isCrit(0.08f, 8));
        assertTrue("暴击率超 100 当 100", WeaponFire.isCrit(0.5f, 999));
        assertFalse("0 暴击率永不暴击", WeaponFire.isCrit(0f, 0));
    }

    /** 加成走的是模板这一道门：百分点进判定，倍率与定值加伤进 damage，且定值排在乘子之后。 */
    @Test
    public void growthBonusesEnterCritRollAndBulletDamage() {
        Balance.Weapon pulse = Balance.weapons[Balance.Weapon.PULSE];      // critPercent 8、damage 3
        // roll 0.09 卡在基础暴击率之外，但 +2 点（8→10）之后就落在里面
        assertFalse(oneBullet(pulse, 1f, 1f, 0, 0.09f, 0).crit);
        assertTrue(oneBullet(pulse, 1f, 1f, 0, 0.09f, 2).crit);
        Bullet scaled = oneBullet(pulse, 1f, 1.15f, 0, 0.5f, 0);
        assertEquals(3 * 1.15f, scaled.damage, 1e-5f);
        assertEquals("攻击乘数取整后至少 1 伤", 3, WeaponFire.damageOf(scaled));
        // 局外乘子与局内定值同时在场：先乘再加，所以卡的那 2 点不会被 1.15 再放大
        Bullet both = oneBullet(pulse, 1f, 1.15f, 2, 0.5f, 0);
        assertEquals(3 * 1.15f + 2f, both.damage, 1e-5f);
        assertEquals(5, WeaponFire.damageOf(both));
    }

    /**
     * 火力强化**每一级都必须真的多打掉一格血**，且**买多少级都买得动**。
     *
     * <p>这条测的是 2026-09-25 那次改版的理由本身：伤害按发取整，所以"每级 +2%"在低基数武器上
     * 十级全空——卡面写着每级都有收益，结算里一格没动。改成每级固定 +1 点之后，加法不会被取整吃掉。
     *
     * <p>用激光（基础 1 点）而不是脉冲（3 点）：基数越低越容易被量化吞掉，正是原来失效的那一档。
     *
     * <p>⚠ 扫描到 12 级是**有意的**：2026-09-26 取消满级之后 {@code maxLevelOf} 返回
     * {@code UNLIMITED}，"扫到哪一级"只能由测试自己定，但它必须跨过原来的 10 级上限，
     * 否则第 11 级买不买得动这件事就没有任何东西钉着（哨兵值本身测不出玩法）。
     *
     * <p>⚠ 循环上界从"读表"变成"测试自定"之后，**空区间假绿**就成了真风险：循环一次都没跑，
     * 这条用例照样全绿。所以循环之外必须留一条只在"真的买到了那一级"时才成立的断言。
     */
    @Test
    public void everyFirepowerLevelSurvivesThePerShotRounding() {
        Balance.Weapon laser = Balance.weapons[Balance.Weapon.LASER];      // damage 1、critPercent 4
        ShopRun run = new ShopRun();
        for (int lv = 1; lv <= 12; lv++) {
            run.reset();
            for (int i = 0; i < lv; i++) {
                assertTrue("火力卡第 " + lv + " 级买不动了",
                        run.buyUpgrade(Balance.ShopCard.FIREPOWER));
            }
            // roll 0.99：这发刻意不暴击，免得把"暴击翻倍"和"每级加点"两件事混在一条断言里
            assertEquals("买到第 " + lv + " 级，实伤却没跟着变：卡面写了收益，结算里没有",
                    1 + lv, WeaponFire.damageOf(oneBullet(laser, 1f, 1f, run.damageBonus(), 0.99f, 0)));
        }
        assertEquals("扫描区间没跑过（这条就成了零断言假绿）：最后一级必须真买到了",
                12, run.levelOf(Balance.ShopCard.FIREPOWER));
    }

    /** 寿命从 {@code Balance.bullet.playerLife} 来，不再是开火代码里的字面量。 */
    @Test
    public void bulletLifeComesFromTheBalanceTable() {
        Balance.Weapon pulse = Balance.weapons[Balance.Weapon.PULSE];
        assertEquals(Balance.bullet.playerLife, oneBullet(pulse, 1f, 1f, 0, 0.5f, 0).maxLife, 0f);
    }

    /**
     * 满池时任何武器都照常出弹——淘汰最老的一发腾位，**不拒发**。
     * 取代先前的"全是追踪弹则整轮零发"：那个死锁让玩家按下去的一下被静默吞掉，
     * 而冷却照付（提案 unguide-bullets）。
     *
     * <p>原来这条用导弹当"两发弹丸"的例子；导弹改走独立仿真池之后它不再进 {@code shots}，
     * 于是换成同为两发的回旋。淘汰路径本身与武器无关，被换掉的只是举例的那把枪。
     */
    @Test
    public void aFullPoolEvictsRatherThanRefusingTheVolley() {
        BulletPool pool = new BulletPool(2);
        BulletChain chain = new BulletChain();
        WeaponFire.Template tpl = new WeaponFire.Template();
        Balance.Weapon boomer = Balance.weapons[Balance.Weapon.BOOMER];   // 2 发
        assertEquals(2, fireGeneration(chain, pool, null, boomer, 2, boomer.fireGap, tpl, 1f, 1f, 0, 0.5f, 0));
        assertEquals("再打一代照样两发出膛", 2,
                fireGeneration(chain, pool, null, boomer, 2, boomer.fireGap, tpl, 1f, 1f, 0, 0.5f, 0));
        assertEquals(2, pool.activeCount());
        assertEquals("两次腾位各淘汰一发", 2L, pool.evictTotal());
        assertEquals("一次都没有拒发", 0L, pool.refuseTotal());
    }

    /**
     * 六把枪各打一代：**每一代的全部弹丸都落在它该落的那张池里**，一张不多、一张不少。
     *
     * <p>"落在哪张池"是 {@link BulletChain#step} 里全项目唯一的制导分叉，所以这条同时是
     * 那个分叉的钉据：导弹进 {@code shots} 池（走不到战斗部结算）或别的枪误进仿真池
     * （被当成仿真体导引），都会在这里红。
     */
    @Test
    public void everyWeaponMakesAtLeastOneBullet() {
        BulletPool pool = new BulletPool(Balance.bullet.capacity);
        Missiles missiles = new Missiles(8);
        BulletChain chain = new BulletChain();
        WeaponFire.Template tpl = new WeaponFire.Template();
        for (Balance.Weapon w : Balance.weapons) {
            pool.clear();
            missiles.clear();
            int made = fireGeneration(chain, pool, missiles, w, Math.max(1, w.pellets), w.fireGap,
                    tpl, 1f, 1f, 0, 0.5f, 0);
            assertTrue(w.name + " 一发都打不出", made >= 1);
            assertEquals(w.name + " 的弹丸数对不上", Math.max(1, w.pellets), made);
            assertEquals(w.name + " 排完就该没有剩余时隙", 0, chain.remaining());
            if (w.guided) {
                assertEquals(w.name + " 是制导武器，弹不该出现在 shots 池", 0, pool.activeCount());
                assertEquals(w.name + " 没进仿真池", made, missiles.activeCount());
            } else {
                assertEquals(w.name + " 不制导却进了仿真池", 0, missiles.activeCount());
                assertEquals(w.name + " 的弹没全进 shots 池", made, pool.activeCount());
            }
            assertTrue(w.name + " 没有颜色", w.color != 0);
            assertTrue(w.name + " 射速非法", w.fireGap > 0f);
            assertTrue(w.name + " 伤害非法", w.damage > 0);
            assertEquals(w.name + " 多弹丸却没给张角", w.pellets > 1, w.spreadDeg > 0f);
        }
    }

    // ---- 掉落 -------------------------------------------------------------------------------

    @Test
    public void collectedItemsAccumulateByKind() {
        Drops ds = new Drops(8);
        int[] got = new int[Drops.KIND_COUNT];
        ds.spawn(Drops.CHIP, 1, 120f, 200f);
        ds.spawn(Drops.COIN, 5, 120f, 200f);
        ds.spawn(Drops.HP, 20, 120f, 200f);
        ds.stepAndCollect(DT, W, TOP + 320, 16, 120f, 200f, 6f, 0f, got);
        assertEquals(1, got[Drops.CHIP]);
        assertEquals(5, got[Drops.COIN]);
        assertEquals(20, got[Drops.HP]);
        assertEquals(0, ds.activeCount());
        assertEquals(8, ds.freeCount());
    }

    @Test
    public void itemsFallOffscreenAndNeverStackForever() {
        Drops ds = new Drops(4);
        for (int i = 0; i < 4; i++) ds.spawn(Drops.COIN, 1, 120f + i, TOP);
        for (int f = 0; f < 60 * 15; f++) {
            ds.stepAndCollect(DT, W, TOP + 320, 16, 4f, 4f, 4f, 0f, new int[Drops.KIND_COUNT]);
        }
        assertEquals("下坠 15 秒后还留在场上", 0, ds.activeCount());
    }

    @Test
    public void magnetPullsItemsTowardThePlayer() {
        Drops ds = new Drops(4);
        Item it = ds.spawn(Drops.CHIP, 1, 20f, 60f);
        assertNotNull(it);
        it.magnetized = true;
        float before = Math.abs(it.x - 120f);
        ds.stepAndCollect(DT, W, TOP + 320, 16, 120f, 300f, 4f, 0f, new int[Drops.KIND_COUNT]);
        assertTrue("磁力没起作用", Math.abs(it.x - 120f) < before);
    }

    @Test
    public void dropRollsFollowChanceAndDropLayer() {
        assertTrue(Drops.chipRoll(0.19f, 1f));
        assertFalse(Drops.chipRoll(0.21f, 1f));
        assertTrue("幸运 ×1.5 后 0.28 的 roll 也算中", Drops.chipRoll(0.28f, 1.5f));
        assertFalse("贫瘠 ×0.6 后 0.15 的 roll 不中", Drops.chipRoll(0.15f, 0.6f));
        assertFalse("概率钳在 1 以内", Drops.chipRoll(1f, 99f));
        assertEquals(3, Drops.bossChips());
    }

    @Test
    public void waveChipGuaranteeFiresOnlyWhenTheWaveWasEmpty() {
        assertEquals("第 1 波没有保底", 0, Drops.waveGuarantee(1, 0));
        assertEquals(1, Drops.waveGuarantee(2, 0));
        assertEquals("本波已经掉过就不补", 0, Drops.waveGuarantee(2, 1));
        assertEquals(0, Drops.waveGuarantee(9, 4));
        assertEquals(1, Drops.waveGuarantee(40, 0));
    }

    @Test
    public void dropLabelsCoverEveryKind() {
        for (int k = 0; k < Drops.KIND_COUNT; k++) {
            assertTrue(Drops.label(k).length() >= 2);
            assertTrue(Drops.label(k) + " 没有颜色，画出来会走默认色和别的道具撞成一团",
                    Drops.colorOf(k) != 0);
        }
        assertTrue(Drops.label(99).length() < 2);
    }

    // ---- 辅助 -------------------------------------------------------------------------------

    private static Enemy fresh(int kindId, float x, float y) {
        Enemies es = new Enemies(1);
        Enemy e = es.spawn();
        Balance.Enemy spec = Balance.enemies[kindId];
        e.kind = kindId;
        e.hp = e.maxHp = spec.hp;
        e.score = spec.score;
        e.radius = spec.spriteSize / 2f;
        e.x = e.px = e.anchorX = x;
        e.y = e.py = y;
        e.fireTimer = 0f;
        return e;
    }

    private static int advance(Enemy e, float playerX, float playerY) {
        return EnemyBehavior.advance(e, Balance.enemies[e.kind], DT, playerX, playerY, W, TOP);
    }

    private static PlayerState newPlayer() {
        PlayerState p = new PlayerState();
        p.respawn(120f, 300f);
        p.invuln = 0f;
        return p;
    }
}
