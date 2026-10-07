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
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.flexiatom.pixelraider.game;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.flexiatom.pixelraider.core.Time;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.junit.Test;

/**
 * 光束形态（提案 {@code laser-weapon-form}，他 2026-10-07 逐字裁「激光取丙（独立光束池）」）。
 * 这个文件钉的是那条裁定里**可以判定的**那几条读数，不是手感：
 *
 * <ol>
 *   <li><b>激光不占弹池名额</b>（{@link #theLaserFillsTheBeamPoolAndNeverTheShotPool}）——
 *       丙与乙的分界就这一条，别的都是它的推论。</li>
 *   <li><b>一束对沿途每架各结一次、且只结一次</b>（{@link #oneBeamHitsEveryEnemyAlongItsLength}、
 *       {@link #reuseIsWhatReArmsABeam}）。</li>
 *   <li><b>节拍是 fireGap</b>（{@link #halvingTheFireGapDoublesTheBeamsPerSecond}）——这条是
 *       "按节拍的瞬时光束"而不是"自由流 DPS"的钉子：若伤害是流，射速卡就成了假商品
 *       （自家判据 {@code ShopRules} 那句「卖一件场上不存在的机制就是假商品」）。</li>
 *   <li><b>判据用的几何与画面是同一条线段</b>（{@link #aBeamDoesNotReachPastItsOwnEnds}）。</li>
 *   <li><b>束恒竖直</b>的前提写在武器表上（{@link #laserRowIsASingleUprightBeam}），
 *       {@link BeamRules} 的点到<b>线段</b>判据整个建立在它之上。</li>
 * </ol>
 *
 * <p>⚠ 全部用例只**读** {@link Balance#weapons} 与 {@link Balance#beam}；唯一改表的那条
 * （{@link #halvingTheFireGapDoublesTheBeamsPerSecond}）在 {@code finally} 里把值还回去——
 * 同一个 JVM 里后面的测试读到的还是真表。
 *
 * <p>⚠ {@code Game.playerBeamsAgainstEnemies} 那十几行（扣血、{@code killEnemy}、火花、一声）
 * 在本类是**结构性零覆盖**：{@code Game} 是渲染类，静态块 {@code new Paint} ⇒ JVM 直调必
 * {@code ExceptionInInitializerError}。所以这里把"结几次"下沉成两位真源：判据在
 * {@link BeamRules#hits}（纯几何）、次数闸门在 {@link Beams.Beam#settled}（纯簿记）。
 * 那两半各自都钉得住，中间"Game 有没有读它们"钉不住——与 {@code touchInputReaches...}
 * 那镜像同一条限制，如实登记，不当成已验证。
 */
public final class BeamTest {

    private static final float NOSE_X = 120f;
    private static final float NOSE_Y = 470f;
    /** 战场顶边：与 {@code Game} 传给结算与绘制的 {@code metrics.battleTop()} 同语义，取个可达的数。 */
    private static final float TOP = 40f;
    private static final float STEP = Time.STEP;

    // ---- 一、分流：丙的形状 --------------------------------------------------------------------

    /**
     * 手持激光打一代：束进 {@link Beams}，{@link BulletPool} 一个名额都不占。
     *
     * <p>这条是丙与乙的**唯一**分界。乙（借道弹池）在画面上几乎一样——一道会飞的长刃也是
     * "激光"，所以没有别的读数能把它俩分开；只有"它是不是在积分"这一件可判定。
     * 断言写成 {@code pool.activeCount() == 0} 而不是"束数 == 1"，因为后者乙也满足。
     */
    @Test
    public void theLaserFillsTheBeamPoolAndNeverTheShotPool() {
        Balance.Weapon laser = Balance.weapons[Balance.Weapon.LASER];
        BulletPool shots = new BulletPool(16);
        Missiles missiles = new Missiles(4);
        Beams beams = new Beams(4);
        BulletChain chain = new BulletChain();
        chain.layout(laser, Math.max(1, laser.pellets), laser.fireGap);
        chain.beginGeneration(template(laser));

        int made = drain(chain, shots, beams);
        assertEquals("一代激光该出一道束", Math.max(1, laser.pellets), made);
        assertEquals("激光占了弹池名额 ⇒ 它其实是直飞弹，不是光束（乙混回丙了）",
                0, shots.activeCount());
        assertEquals("一道都没进光束池", made, beams.activeCount());
        assertEquals("激光不该进导弹仿真池", 0, missiles.activeCount());

        Beams.Beam b = beams.at(0);
        assertEquals("束宽该是武器表那一格的 size", laser.size, b.width, 0f);
        assertEquals("束色该是武器表那一格的颜色", laser.color, b.color);
        assertEquals("束的武器号该是激光", Balance.Weapon.LASER, b.weaponId);
        assertEquals("束的下端该钉在出膛那一刻的机头 y", NOSE_Y, b.y, 0f);
        assertEquals("束的 x 该钉在出膛那一刻的机头 x", NOSE_X, b.x, 0f);
    }

    /**
     * 漏传光束池 = **接线错误**，当场抛，且修好之后同一代照样发满。与
     * {@code BulletChainTest.aGuidedGenerationWithoutAPoolFailsLoudly} 同一条理由：
     * {@code step} 的返回值是三条分支的合计，安静返回 0 会让出膛账蒸发，而 {@code shotsHit}
     * 一侧照样记命中 ⇒ 命中率被 {@code Rating.clamp01} 钳成 100%，全链路没有任何东西会红。
     */
    @Test
    public void aBeamGenerationWithoutAPoolFailsLoudly() {
        Balance.Weapon laser = Balance.weapons[Balance.Weapon.LASER];
        BulletChain chain = new BulletChain();
        chain.layout(laser, laser.pellets, laser.fireGap);
        chain.beginGeneration(template(laser));
        int slotsLeft = chain.remaining();
        try {
            chain.step(STEP, NOSE_X, NOSE_Y, new BulletPool(8), null, null);
            fail("光束代缺 Beams 池应该抛，而不是返回 0 发");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("no Beams pool"));
        }
        assertEquals("抛之前链已经推进了 ⇒ 头几档被这个错误吃掉", slotsLeft, chain.remaining());

        Beams beams = new Beams(4);
        int made = 0;
        int guard = 0;
        while (chain.remaining() > 0) {
            made += chain.step(STEP, NOSE_X, NOSE_Y, new BulletPool(8), null, beams);
            assertTrue("补上池之后这一代排不完", ++guard < 10_000);
        }
        assertEquals("修好接线之后同一代仍该发满", laser.pellets, made);
        assertEquals(laser.pellets, beams.activeCount());
    }

    /**
     * 武器表那一格必须真的支撑"束恒竖直"：{@code pellets == 1} 且 {@code spreadDeg == 0}，
     * 而且扳机卡不许给激光加发。
     *
     * <p>{@link BeamRules#hits} 算的是"圆心到**竖**线段的距离"。一旦哪一天激光有了张角或一次出两道，
     * 判据就得换成带旋转的写法（{@link WarheadRules#sweptBandHitsCircle}），而**忘了换**的失败方式
     * 是画面上扇开、判定上还是一条竖线——正是 {@code Game.settleImpact} 那族"画得与打的不是一套几何"。
     * 所以这里钉的三半边都在表上，不在判据里。
     *
     * <p>第三半边（扳机不吃激光）读的是 {@link ShopRun#pelletBonus}：例外若写在 {@code Game} 里
     * 就是第二个真源（那条 javadoc 自己警告过），所以判据住在这张表上，测试也只能读它。
     */
    @Test
    public void laserRowIsASingleUprightBeam() {
        Balance.Weapon laser = Balance.weapons[Balance.Weapon.LASER];
        assertEquals("激光不属于光束体系 ⇒ 分流走不到 fireBeam",
                Balance.Weapon.SCHOOL_LASER, laser.school);
        assertEquals("激光一代出了不止一道：BeamRules 那条竖线段判据不再成立",
                1, Math.max(1, laser.pellets));
        assertEquals("激光带张角：BeamRules 那条竖线段判据不再成立", 0f, laser.spreadDeg, 0f);

        ShopRun maxed = new ShopRun();
        for (int lv = 0; lv < 40; lv++) {
            if (!maxed.buyUpgrade(Balance.ShopCard.TRIGGER)) break;
        }
        assertEquals("扳机卡给激光加了发 ⇒ 场上会出现第二道束，而判据只认一道竖线",
                0, maxed.pelletBonus(Balance.Weapon.LASER));
    }

    // ---- 二、结算：穿几架、结几次 ---------------------------------------------------------------

    /**
     * 一道束的纵向跨度上有三架 ⇒ 三架**各**被打到；横向偏开一格半 ⇒ 谁都不该掉血。
     *
     * <p>"穿透"在这里的全部内容就是**结算对象不止一个**：不是伤害衰减，也不是"打穿之后剩多少威力"。
     * 这条同时钉住判据的两半——纵向要一路通（少了它就是把束当一颗弹打最前面那架）、
     * 横向要有界（松了它就是一根打在整屏上的光棒）。
     */
    @Test
    public void oneBeamHitsEveryEnemyAlongItsLength() {
        float half = Balance.weapons[Balance.Weapon.LASER].size / 2f;
        float pad = Balance.beam.hitPad;
        float er = 6f;
        int along = 0;
        for (float ey = TOP + er; ey <= NOSE_Y - er; ey += 37f) {
            if (BeamRules.hits(NOSE_X, TOP, NOSE_Y, half, pad, NOSE_X, ey, er)) along++;
        }
        assertTrue("束在自己那一列上有打不到的（沿途每一架都该被结一次）：" + along, along >= 3);
        assertFalse("横向偏出一整格还能打中 ⇒ 束宽没进判据",
                BeamRules.hits(NOSE_X, TOP, NOSE_Y, half, pad, NOSE_X + half + pad + er + 1f,
                        200f, er));
        assertTrue("贴着束宽边缘该算中（hitPad 的语义就是擦边算）",
                BeamRules.hits(NOSE_X, TOP, NOSE_Y, half, pad, NOSE_X + half + pad + er, 200f, er));
    }

    /**
     * 束的两端不许超出它自己画出来的那段：顶边之上、机头之下都不算。
     *
     * <p>少这一条就是"打得比画得长"——还没进场的敌人（{@code y < top}，它们今天就在战场上方等着）
     * 会被一道画面根本没过那里的束打死。判据用的是**竖线段**而不是无限直线，靠的就是那两次 clamp；
     * {@code hitPad} 在这里取 0，为的是把"clamp 给的界"与"pad 给的擦边"分两件事量——
     * 胶囊的界本来就在端点之外再出一格 {@code er + halfWidth}，那不叫超程。
     */
    @Test
    public void aBeamDoesNotReachPastItsOwnEnds() {
        float half = Balance.weapons[Balance.Weapon.LASER].size / 2f;
        float er = 6f;
        float reach = er + half;                       // 胶囊在端点之外的最后一格
        assertFalse("打到战场顶边之上去了（画面上没有那一段）",
                BeamRules.hits(NOSE_X, TOP, NOSE_Y, half, 0f, NOSE_X, TOP - reach - 1f, er));
        assertTrue("刚压着顶边该算中",
                BeamRules.hits(NOSE_X, TOP, NOSE_Y, half, 0f, NOSE_X, TOP - reach, er));
        assertFalse("打到机头之下（束根本没有那一段）",
                BeamRules.hits(NOSE_X, TOP, NOSE_Y, half, 0f, NOSE_X, NOSE_Y + reach + 1f, er));
        assertTrue("刚压着机头该算中",
                BeamRules.hits(NOSE_X, TOP, NOSE_Y, half, 0f, NOSE_X, NOSE_Y + reach, er));
        // 段中间的远处才算真的"直线 vs 线段"：那一架在顶边之上很远，无限直线判据会打中它
        assertFalse("判据其实是无限直线（顶边之上十几格也打中）",
                BeamRules.hits(NOSE_X, TOP, NOSE_Y, half, 0f, NOSE_X, TOP - reach - 12f, er));
    }

    /**
     * 一道束**复用**之前不许再被点亮：{@code settled} 这一位翻成 true 之后，只有
     * {@link Beams#spawn} 能把它翻回 false。
     *
     * <p>这是"一束打十几遍"的唯一闸门。束亮着 {@code lifeSec = 0.1s} ⇒ 六帧，{@code Game} 每帧都
     * 遍历一次池；没有这一位，同一道就把沿途每架各打六轮，而**画面上看不出来**（伤害早就结完了，
     * 只有分数在涨）。这里钉的是数据侧那一半：{@code advance} 与 {@code clear} 都不许重新装填。
     */
    @Test
    public void reuseIsWhatReArmsABeam() {
        Beams beams = new Beams(2);
        Beams.Beam b = beams.spawn(NOSE_X, NOSE_Y, 2f, 3f, 0xFF0000FF, Balance.Weapon.LASER,
                false, Balance.beam.lifeSec);
        assertFalse("新道一开始就被标成已结算 ⇒ 一束都打不出", b.settled);
        b.settled = true;
        for (int f = 0; f < 30; f++) beams.advance(STEP);
        assertTrue("寿命走完了就被重新装填 ⇒ 一道束会结两次", b.settled);
        assertEquals("寿命钳不到位（负数会喂给绘制的 alpha）", 0f, b.life, 0f);

        beams.clear();
        assertTrue("clear 之后没标已结算 ⇒ 换局那一帧旧束还能再结一次", b.settled);

        Beams.Beam again = beams.spawn(NOSE_X, NOSE_Y, 2f, 3f, 0xFF0000FF, Balance.Weapon.LASER,
                false, Balance.beam.lifeSec);
        assertFalse("复用槽位没重新装填 ⇒ 这把枪从此一发都结不了", again.settled);
    }

    // ---- 三、节拍：fireGap 就是束的节奏 ----------------------------------------------------------

    /**
     * 把激光的 {@code fireGap} 减半 ⇒ 同一秒钟出的束**跟着翻倍**。
     *
     * <p>这条钉的是"瞬时光束"而不是"自由流 DPS"：光束不飞、出膛即结算，所以它单位时间能打出
     * 几道完全由 {@code fireGap} 说了算。若伤害是一条流，射速卡买下去场上不会有任何可观察变化
     * ——而 {@code ShopRules} 自己的判据是「卖一件场上不存在的机制就是假商品」。
     *
     * <p>两个 {@code fireGap} 都写成 {@link Time#STEP} 的**整数倍**（30 步 / 15 步），意图是让
     * "过冲钳回 0"那条量化不参与。但它还是参与了，只是只朝一个方向：<b>{@code fireTimer} 是浮点
     * 连减</b>，{@code 15*STEP} 减 15 次 {@code STEP} 落不到 {@code <= 0f}，个别间隔于是被顶成 16 帧。
     * 实测（600 帧）：30 步档 <b>20 道</b>一格不漂，15 步档 <b>38 道</b>漂了 4 格。漂移只会把间隔
     * **变长**、不会变短——所以这里的下界断言写成"不短于 expectDelta"（{@code Run.minDelta}），
     * 翻倍写成"不少于 2 倍减 2"，而不是等式。这与 {@code BulletAndGridTest}「周期取量化后的实测值」
     * 是同一条事实的两次登记：<b>整步的 fireGap 不等于整步的实测间隔</b>。
     * "减半恰好翻倍"那条等式不在这里要，它归 {@code PlayerState.cooldownFor} 的直读。
     *
     * <p>⚠ 循环里的两行（{@code canFire ⇒ beginGeneration + consumeShot}）是
     * {@code Game.playerFire} 的**镜像**，不是生产逻辑的复制：{@code Game} 直测不到。镜像错了
     * 就会测出一条场上不存在的节奏，与 {@code ShopRulesTest.theSchoolKeyJumpsSystemsAndLandsBackWhereItLeftOff}
     * 同一条限制，如实登记。
     */
    @Test
    public void halvingTheFireGapDoublesTheBeamsPerSecond() {
        Balance.Weapon laser = Balance.weapons[Balance.Weapon.LASER];
        float original = laser.fireGap;
        try {
            laser.fireGap = 30 * STEP;
            Run slow = beamsIn(30, 600);
            laser.fireGap = 15 * STEP;
            Run fast = beamsIn(15, 600);
            assertEquals("30 步一按、600 帧该出 20 道（束数不对说明节奏不是 fireGap 给的）", 20, slow.beams);
            assertTrue("15 步档的按间隔短于 fireGap（浮点连减只会变长，变短说明节拍不是 fireGap 给的）",
                    fast.minDelta >= 15);
            assertTrue("fireGap 减半，单位时间束数没翻倍 ⇒ 射速这一半在激光上是假商品（实测 20 → 38，"
                            + "漂移只朝变长一侧，所以这里要 2 倍减 2 的下界）",
                    fast.beams >= slow.beams * 2 - 2);
        } finally {
            laser.fireGap = original;
        }
    }

    /** 一次喂帧的读数：出膛束数、按下去的次数、最小实测间隔。 */
    private static final class Run {
        int beams;
        int presses;
        int minDelta = Integer.MAX_VALUE;
    }

    /**
     * 按 {@code Game.step} 的帧顺序喂 {@code frames} 帧，返回出膛的束数与实测节奏。
     *
     * <p>{@code expectDelta} 是 fireGap 换算成的**步数名义值**，这里只当**下界**用（见
     * {@link #halvingTheFireGapDoublesTheBeamsPerSecond} 那段：浮点连减会把个别间隔顶长一格，
     * 但顶不短）。间隔与 {@code shots} 空这两条都在这里当场钉，改表的人才会看到数字而不是拿到
     * 一个悄悄漂了的期望值。
     */
    private static Run beamsIn(int expectDelta, int frames) {
        PlayerState player = new PlayerState();
        player.weaponId = Balance.Weapon.LASER;
        BulletChain chain = new BulletChain();
        BulletPool shots = new BulletPool(4);
        Beams beams = new Beams(2);          // 故意给小池：覆盖最老不减出膛计数，made 读的是返回值
        WeaponFire.Template tpl = new WeaponFire.Template();
        Balance.Weapon laser = Balance.weapons[Balance.Weapon.LASER];
        Run run = new Run();
        int lastPress = -1;
        for (int f = 0; f < frames; f++) {
            player.stepTimers(STEP);
            run.beams += chain.step(STEP, NOSE_X, NOSE_Y, shots, null, beams);
            if (player.canFire(1f)) {
                if (lastPress >= 0) run.minDelta = Math.min(run.minDelta, f - lastPress);
                lastPress = f;
                float period = player.cooldownFor(1f);
                if (!chain.matches(laser, laser.pellets, period)) {
                    chain.layout(laser, laser.pellets, period);
                }
                WeaponFire.fillTemplate(tpl, laser, 1f, 1f, 0, 0.99f, 0);
                chain.beginGeneration(tpl);
                player.consumeShot(1f);
                run.presses++;
            }
        }
        assertEquals("一代激光就该是一道束（pellets == 1 那半边在这儿第二次被钉住）",
                run.presses, run.beams);
        assertTrue("按下去的间隔比 fireGap 还短：节奏不是 fireGap 给的", run.minDelta >= expectDelta);
        assertEquals("激光全程一个弹池名额都没占", 0, shots.activeCount());
        return run;
    }

    // ---- 四、池的簿记 ---------------------------------------------------------------------------

    /**
     * 满池覆盖最老的一道，**永不拒发**，而且被覆盖的那一道不需要任何补偿。
     *
     * <p>与弹池那条最本质的不同写在 {@link Beams} 类注释第三条：那边丢的是结算机会，
     * 这边丢的只是一帧好不好看——所以这里没有 {@link Missiles#evicted} 那种"池外唯一副本"，
     * 也没有哑火小烟。覆盖之后窗口里剩下的必须是**最新的那几道**。
     */
    @Test
    public void aFullBeamPoolOverwritesTheOldestAndNeverRefuses() {
        Beams beams = new Beams(2);
        Beams.Beam a = beams.spawn(10f, NOSE_Y, 2f, 1f, 0xFF000001, 1, false, 0.1f);
        Beams.Beam b = beams.spawn(20f, NOSE_Y, 2f, 1f, 0xFF000002, 2, false, 0.1f);
        Beams.Beam c = beams.spawn(30f, NOSE_Y, 2f, 1f, 0xFF000003, 3, false, 0.1f);
        assertSame("环形槽不按位复用 ⇒ 下面那些'覆盖最老'的断言测的不是覆盖", a, c);
        assertEquals(2, beams.activeCount());
        assertEquals("窗口里该剩 b（最老）与 c（最新）", 20f, beams.at(0).x, 0f);
        assertEquals(30f, beams.at(1).x, 0f);
        assertTrue("被覆盖那道没重新装填 ⇒ 新道一出生就被当作已结算", !c.settled);
        try {
            beams.at(2);
            fail("活跃窗口越界没抛 ⇒ count 与游标不是一套");
        } catch (IndexOutOfBoundsException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    /** 池容量是**画面预算**不是玩法上限：{@code capacity <= 0} 是配置错误，当场抛。 */
    @Test
    public void theBeamPoolRefusesANonPositiveCapacity() {
        try {
            new Beams(0);
            fail("容量 0 的池编得出来 ⇒ Balance.beam.capacity 被改坏时没人报");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            new Beams(-1);
            fail("负容量同理");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        assertNotNull(new Beams(1).spawn(1f, 2f, 3f, 4f, 5, 6, false, 0.1f));
    }

    /**
     * 反射遍历 {@link Beams.Beam} 的**每一个**实例字段：灌哨兵 → 复用 → 逐字段核对写到了新值。
     *
     * <p>{@link ResetProbe} 那套"断言全部归零"在这里用不了：{@code occupy} 是**带参数**的复用，
     * 每个字段都该被写成调用方给的数，而不是 0。所以这条改判"没有一位还带着哨兵"——
     * 效果一样且更强：{@code occupy} 漏写任何一个字段，那一格就带着上一道的伤害与颜色出去，
     * 而那正是 {@code MissilesTest.reuseResetsEveryFieldButStampsAFreshSequence} 一族防的事。
     */
    @Test
    public void reusingABeamSlotWritesEveryField() throws Exception {
        Beams beams = new Beams(1);
        Beams.Beam first = beams.spawn(NOSE_X, NOSE_Y, 2f, 1f, 0xFF0000AA, 7, false, 0.1f);
        ResetProbe.poison(first);
        ResetProbe.assertStillPoisoned(first);

        Beams.Beam again = beams.spawn(11f, 22f, 3f, 4f, 0xFF00BB00, 8, false, 0.05f);
        assertSame("池不按实例复用 ⇒ 这条测的就不是复用", first, again);
        int checked = 0;
        for (Field f : Beams.Beam.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            Class<?> t = f.getType();
            String what = f.getName() + " 残留上一道的哨兵：occupy() 没写它";
            checked++;
            if (t == float.class) {
                assertFalse(what, f.getFloat(again) == ResetProbe.SENTINEL_FLOAT);
            } else if (t == int.class) {
                assertFalse(what, f.getInt(again) == ResetProbe.SENTINEL_INT);
            } else if (t == boolean.class) {
                assertFalse(what, f.getBoolean(again));
            } else {
                throw new AssertionError("探针不认字段 " + f.getName() + " 的类型 " + t
                        + "：新增类型请同时补这条用例的分支");
            }
        }
        assertTrue("一个字段都没检查，这条用例已经空转", checked > 0);
        assertEquals(11f, again.x, 0f);
        assertEquals(4f, again.damage, 0f);
        assertEquals(8, again.weaponId);
    }

    /**
     * {@link Beams#advance} 每帧只衰减，**不摘槽**：环形窗口没有"摘"这个动作。
     *
     * <p>为什么这条值得单独写：{@link BulletPool} 那边寿命到点是真的 compact 掉，若这里也照着写
     * 一遍"到期就摘"，游标与 count 的账就要跟着动，而 {@code clear()}（{@code resetRun} 那条
     * UI 线程调用链）就只能置游标不能置字段——两边规则一混合就是撕裂读的新形状。
     */
    @Test
    public void advanceFadesBeamsButNeverRemovesSlots() {
        Beams beams = new Beams(3);
        beams.spawn(1f, NOSE_Y, 2f, 1f, 0xFF000001, 1, false, 2 * STEP);
        beams.spawn(2f, NOSE_Y, 2f, 1f, 0xFF000002, 2, false, 2 * STEP);
        assertEquals(2, beams.activeCount());
        beams.advance(STEP);
        assertEquals(2, beams.activeCount());
        beams.advance(STEP);
        assertEquals("寿命没钳到 0（负数会喂给绘制的 alpha）", 0f, beams.at(0).life, 0f);
        assertEquals(0f, beams.at(1).life, 0f);
        assertEquals("衰减把槽位摘了 ⇒ 与'覆盖最老'那条不再是同一套簿记", 2, beams.activeCount());
        beams.advance(STEP);
        assertEquals("已经是 0 还被继续减 ⇒ 会跑成负数", 0f, beams.at(0).life, 0f);
    }

    // ---- 五、伤害走哪一条护甲 ---------------------------------------------------------------------

    /**
     * 光束的伤害走 {@link DamageRules#SRC_SHOT}：与直飞弹**同一档护甲**，不新开源。
     *
     * <p>他裁护甲表时给的那一列是「子弹 vs 除子弹外」，激光这一把是**玩家的枪**。新加一格
     * {@code SRC_BEAM} 等于替他决定"光束该不该被某种护甲减免"，那句话他没说。这条断言的作用
     * 是把"没替他决定"这件事钉成可读的：真有人加了第二个源，这里会红，然后他才需要去问。
     */
    @Test
    public void beamDamageResolvesThroughTheShotArmorColumn() {
        Beams beams = new Beams(4);
        Beams.Beam five = beams.spawn(NOSE_X, NOSE_Y, 2f, 5f, 0xFF00FF00, Balance.Weapon.LASER,
                false, 0.1f);
        assertEquals("光束没过子弹那一档",
                DamageRules.resolve(DamageRules.SRC_SHOT, DamageRules.ARMOR_REGULAR, 5f),
                BeamRules.damageOf(five));

        Balance.Weapon laser = Balance.weapons[Balance.Weapon.LASER];
        Beams.Beam plain = beams.spawn(NOSE_X, NOSE_Y, laser.size, laser.damage, laser.color,
                Balance.Weapon.LASER, false, 0.1f);
        Beams.Beam crit = beams.spawn(NOSE_X, NOSE_Y, laser.size,
                laser.damage * WeaponFire.CRIT_MULTIPLIER, laser.color, Balance.Weapon.LASER,
                true, 0.1f);
        assertEquals("暴击倍率没烘进出膛那一刻的 damage（整束共享一次判定，与弹丸同一条口径）",
                BeamRules.damageOf(plain) * WeaponFire.CRIT_MULTIPLIER,
                BeamRules.damageOf(crit), 0f);
    }

    // ---- 助手 -----------------------------------------------------------------------------------

    private static WeaponFire.Template template(Balance.Weapon w) {
        WeaponFire.Template t = new WeaponFire.Template();
        WeaponFire.fillTemplate(t, w, 1f, 1f, 0, 0.99f, 0);      // 0.99：一律不暴击，免得干扰断言
        return t;
    }

    /** 喂时间到这一代排完；帧顺序与 {@code Game.step} 一致（先推进链）。 */
    private static int drain(BulletChain c, BulletPool pool, Beams beams) {
        int made = 0;
        int guard = 0;
        while (c.remaining() > 0) {
            made += c.step(STEP, NOSE_X, NOSE_Y, pool, null, beams);
            pool.stepAndCompact(STEP, 120, 533, 16);
            assertTrue("一代排不完（" + guard + " 步）", ++guard < 10_000);
        }
        return made;
    }
}
