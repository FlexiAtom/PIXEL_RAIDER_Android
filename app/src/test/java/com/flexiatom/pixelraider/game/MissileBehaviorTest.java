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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.flexiatom.pixelraider.core.Time;
import org.junit.Test;

/**
 * 导弹仿真（提案 missile-warhead-sim §1.1–§1.3）。
 *
 * <p>这里钉的是五件"错了不会被任何别的东西发现"的事：
 * <ul>
 *   <li><b>两段视野的角度语义</b>——雷达 90° / 导引头 60° 都按**总夹角**算。导引头那边他的原话只到
 *       「以导弹正对方向60度夹角为导弹视场」（L14750 {@code feedback}，UTC 2026-09-25T09:19:51.727Z
 *       ＝本地 09-25 17:19）⇒ "总夹角 ⇒ 半角 30°" 这套换算是**我的措辞**（L14773 {@code answers-OPT}，
 *       UTC 2026-09-25T09:29:00.463Z＝本地 17:29；下同，后面只回指这条）。雷达这半边是他自敲的：
 *       「增加一个战机雷达数值，目前先填90度（这个同60度的语义，其实是两个45度）」（L14779
 *       {@code answers-FREE}，UTC 2026-09-25T09:33:09.768Z＝本地 17:33）⇒ 半角 45° 是**他的换算**。
 *       两边写反就是半角 90°/60°：视野大得离谱，而画面上完全正常。</li>
 *   <li><b>牛顿导引只改方向不改速率</b>，且<b>失速地板真的在挡钻头</b>（§1.3 那条 88°/步）。</li>
 *   <li><b>同帧判序</b>（§1.2）：引信 &gt; 自爆 &gt; 回收 &gt; 再锁。状态机图的行序是错的顺序，
 *       照它实现会让一枚贴脸的弹从敌人身上掠过去重新拐弯。</li>
 *   <li><b>派生互斥没有可泄漏的状态</b>（§1.1）：七条完毕态逐条走一遍，每条之后下一枚弹必须
 *       还能锁上同一个目标。这条是"用计数器就会漏"的那一类风险的替代品——它现在测的是
 *       "位图里不该留下上一代的戳"。</li>
 *   <li><b>第二条流（格斗弹）的锁语义</b>（簇 II C2，第九节）。它与普通弹共用 {@code advance}
 *       的躯干，分开的是三件事：锁什么时候给（发射那一刻由火控装配）、给完还换不换（全程不换）、
 *       空了怎么收场（同帧自爆）。三件都是<b>画面上看不出来</b>的那种错，所以整节都在钉这三件。</li>
 * </ul>
 *
 * <p><b>归属记法</b>：上面与本文件各方法注释里的 L147xx 一律是**对话 transcript 的记录行号**
 * （不是本仓代码行），后缀是记录形态——{@code feedback}／{@code answers-FREE} ＝ 他自敲的字；
 * {@code answers-OPT} ＝ 他从我（AI）给出的选项标签里挑了一档 ⇒ **决策是他的、措辞是我的**。
 *
 * <p>⚠ 全部用例只**读** {@link Balance#missile}，一个字段都不改（它是可变静态，同 JVM 里会串）。
 * 要造非法数值就 {@code new Balance.Missile()} 开局部实例。
 */
public final class MissileBehaviorTest {

    private static final float STEP = Time.STEP;
    private static final int W = 240;
    private static final int H = 560;
    /** 出界余量：绝大多数用例不希望"飞出画面"这条判据掺进来，给一个到不了的数。 */
    private static final int FAR = 1 << 20;
    private static final int ENEMY_CAP = Balance.wave.maxAlive + 2;
    private static final float START_X = 120f;
    private static final float START_Y = 500f;
    private static final float MUZZLE_SPEED = Balance.weapons[Balance.Weapon.MISSILE].bulletSpeed;

    private final Balance.Missile spec = Balance.missile;
    private final Missiles pool = new Missiles(4);
    private final Enemies foes = new Enemies(ENEMY_CAP);
    private final MissileBehavior beh = new MissileBehavior(ENEMY_CAP);
    /**
     * 第二条流的那一对：池与行为各自一套，**占用戳也各自一套**（"弹体池分家"，L37356）。
     * 分家的全部意义就是这两格字段：一只敌机同时挂普通弹的一锁＋二锁＋格斗锁是工程设计，
     * 共用一个 {@code MissileBehavior} 实例的话，第三条锁会被前两条的戳挡掉。
     */
    private final Missiles dogPool = new Missiles(8);
    private final MissileBehavior dog = new MissileBehavior(ENEMY_CAP, MissileBehavior.STREAM_DOGFIGHT);
    /**
     * 本局持有量。JUnit 每个 {@code @Test} 新建一次测试类实例 ⇒ 每个用例都从"一张卡都没买"起步，
     * 出厂有效值就是 {@link Balance.Missile} 里的原值（簇 II 的四把钥匙全零）。
     *
     * <p>⚠ 导引头的半径、视场、过载、再点火、中段引导**都从这里解析**（{@code beginFrame} 单点），
     * 所以任何用例想测"涨了的那一侧"，就在自己方法里 {@code run.buyUpgrade(...)}——不要去改
     * {@link #spec}：它是可变静态，改了会串到同 JVM 的别的测试。
     */
    private final ShopRun run = new ShopRun();

    // ---- 一、射前雷达（半角 45°、锥轴恒为屏幕正上方）------------------------------------------

    /**
     * 射前雷达在**出膛后的下一帧**补做（出膛方是 {@code BulletChain}，它不认识敌人）。
     *
     * <p>⚠ mode0（L40027「要改mode1为0」）之后这一帧做的是**装订**，不是**锁定**：雷达挑近的那只，
     * 用它此刻的位置与瞬时速度解出拦截点写进 {@link Missiles.Missile#unitX}/{@link Missiles.Missile#unitY}，
     * 把弹轴对过去，然后**不占目标**——"锁定"排在第四段（开眼之后）。所以这里同时钉三件事：
     * 装订点算对了、弹轴对齐了、而 {@code targetSlot} 仍然是 {@code NO_TARGET}。
     *
     * <p>近的那只摆在 12°、不摆到锥沿：这条要钉的是"取最近者"，把边界另交给
     * {@link #radarIgnoresAnEnemyJustOutsideItsCone}。两件事混在一个用例里，边界一抖就会以
     * "nearest 没算对"的名义红，报的是错的原因。
     */
    @Test
    public void radarBindsTheNearestEnemyInsideItsConeOneStepAfterLaunch() {
        Missiles.Missile m = justFired(START_X, START_Y, 0f, -MUZZLE_SPEED);
        Enemies.Enemy near = bearingUp(START_X, START_Y, 12f, 200f);
        bearingUp(START_X, START_Y, 0f, 300f);                    // 更远的也在锥内：nearest 才算数
        assertEquals("出膛那一刻没挂待锁标记，射前雷达这一帧就是白跑的",
                Missiles.Missile.RADAR_PENDING, m.targetSlot);
        int flags = frame(m);
        assertTrue("射前没装订出目标位置（unitSet=" + m.unitSet + "）", m.unitSet);
        assertEquals("静止目标的 CV 拦截点就是它自己", near.x, m.unitX, 1f);
        assertEquals("静止目标的 CV 拦截点就是它自己", near.y, m.unitY, 1f);
        // 装订轴是按**位移前**的位置算的（bind 读的就是这一步起点的 m.x），拿走过一步的 m.x 去比
        // 会差出这一步的横向位移，红在几何上而不是红在"轴没对齐"。
        float dx = m.unitX - m.px;
        float dy = m.unitY - m.py;
        float dl = (float) Math.sqrt(dx * dx + dy * dy);
        float len = speed(m);
        assertEquals("弹轴没对到装订方向", dx / dl, m.vx / len, 1e-4f);
        assertEquals("弹轴没对到装订方向", dy / dl, m.vy / len, 1e-4f);
        assertEquals("装订段不该占目标 ⇒ 它会把互斥提前到开眼之前",
                Missiles.Missile.NO_TARGET, m.targetSlot);
        assertEquals("开眼门不该在出膛那一帧就开", false, m.seekerOpen);
        assertEquals(0, flags & (MissileBehavior.FLAG_ACQUIRED | MissileBehavior.FLAG_IGNITED));
    }

    @Test
    public void radarIgnoresAnEnemyJustOutsideItsCone() {
        Missiles.Missile m = justFired(START_X, START_Y, 0f, -MUZZLE_SPEED);
        bearingUp(START_X, START_Y, spec.radarHalfDeg + 0.1f, 200f);
        int flags = frame(m);
        assertEquals(0, flags & MissileBehavior.FLAG_ACQUIRED);
        assertFalse("锥外的目标被装订了 ⇒ 射前雷达那 90° 白设", m.unitSet);
        assertEquals(Missiles.Missile.NO_TARGET, m.targetSlot);
    }

    /**
     * 装订段**不转向**：目标一路横移，弹轴仍是出膛那一帧算好的那条直线，{@code unitX/unitY}
     * 也不许被追帧重算。mode0 与 mode1 的正面分水岭就在这儿（L40027「与设计冲突，要改mode1为0」）——
     * mode1 在这一段一路 pure-pursuit，弹轴每帧跟着敌人跑。
     *
     * <p>"跟着发射单元跑、不跟着敌人跑"要是写反，画面只会更好看不报错：导弹看着像在追目标，
     * 实则每一帧都在重排拦截点，{@code maxLatAccel} 那张卡的账全废。所以这条钉的是方向余弦，
     * 末段还要反证目标确实跑出了装订轴，不然整条是拿同一个方向在比它自己。
     */
    @Test
    public void theBindSegmentFollowsTheLaunchUnitNotTheEnemy() {
        Missiles.Missile m = justFired(START_X, START_Y, 0f, -MUZZLE_SPEED);
        Enemies.Enemy e = foe(START_X, START_Y - 200f);
        e.vx = 60f;                                             // 敌人一路往右跑
        frame(m);                                               // 射前雷达这一帧装订
        assertTrue(m.unitSet);
        float boundX = m.unitX;
        float boundY = m.unitY;
        float len0 = speed(m);
        float ax0 = m.vx / len0;
        float ay0 = m.vy / len0;
        // 逐帧真的推进弹体：不 advance 的"恒定"是拿同一个方向比它自己，那条反证只钉得住几何，钉不住实现。
        // B3（thrust 1200／boost 1.0）之后这段很短——弹在点火段就吃掉大半屏，所以循环以"开眼"为终点。
        int frames = 0;
        while (!m.seekerOpen && frames < 20) {
            e.x += e.vx * STEP;
            frame(m);
            frames++;
            assertEquals("发射单元被追帧重算了（第 " + frames + " 帧）", boundX, m.unitX, 0f);
            assertEquals("发射单元被追帧重算了（第 " + frames + " 帧）", boundY, m.unitY, 0f);
            float len = speed(m);
            assertEquals("装订段还在改方向 ⇒ 又变回一路追的 mode1（第 " + frames + " 帧）",
                    ax0, m.vx / len, 1e-4f);
            assertEquals("装订段还在改方向 ⇒ 又变回一路追的 mode1（第 " + frames + " 帧）",
                    ay0, m.vy / len, 1e-4f);
            assertEquals("装订段不该占目标", Missiles.Missile.NO_TARGET, m.targetSlot);
            assertEquals("闭眼段不该累视场空计时", 0f, m.seekT, 0f);
        }
        assertTrue("一帧都没跑 ⇒ 弹出膛就开眼，这条退化成零断言", frames >= 1);
        float dx = e.x - m.x;
        float dy = e.y - m.y;
        float cos = (dx * ax0 + dy * ay0) / (float) Math.sqrt(dx * dx + dy * dy);
        assertTrue("目标 20 帧里没跑出装订轴 ⇒ 这条是拿同一个方向在比它自己（cos=" + cos + "）",
                cos < 0.999f);
    }

    /**
     * 「中段引导」买的是**闭眼段也会转向**（逐字「中段引导卡是独立新卡」，与 L41661 第四条
     * 「二次点火卡附加和过载卡新增…的作用」同批）。它是
     * {@link #theBindSegmentFollowsTheLaunchUnitNotTheEnemy} 的反面：同一条几何，那条弹轴一路
     * 钉死在出膛那一帧，这条每帧都在拐。
     *
     * <p>⚠ 两条边界必须同时钉住，否则这张卡会顺带改掉 mode0 的两半设计：
     * <ul>
     *   <li><b>不占目标</b>——装订只回答"往哪飞"。转向一旦写 {@code targetSlot}，互斥就被提前到
     *       开眼之前，"多枚弹朝同一只飞、进圈才竞争"那半条设计就没了。</li>
     *   <li><b>不开眼</b>——扫描门仍然只看 {@link Missiles.Missile#seekerOpen}（L41661 第一行
     *       「抵达发射单元描述的位置后扫描」）。这张卡卖的是"别再直飞"，不是"提前索敌"。</li>
     * </ul>
     *
     * <p>每一步的转角压在过载闸里（闸按 {@code advance} 同一式子从**上一步的速率**算，不取
     * {@code stallSpeed} 那个宽松上界）。那是必然成立的不变式，不是这条的判别式——判别式是累计转角：
     * 实测买了之后 24 帧累计 <b>6.43°</b>，没买同一条几何是 <b>3.7e-5°</b>（＝钉死）。
     */
    @Test
    public void theMidCourseCardBendsTheBlindSegmentWithoutTakingTheTarget() {
        run.buyUpgrade(Balance.ShopCard.MID_COURSE);                // 买断卡，一级即生效
        Missiles.Missile m = justFired(START_X, START_Y, 0f, -MUZZLE_SPEED);
        Enemies.Enemy e = bearingUp(START_X, START_Y, 25f, 200f);
        e.vx = 90f;                                                 // 一路横移 ⇒ 视线角速率恒为正
        frame(m);                                                   // 射前雷达装订
        assertTrue("这条用例的前提（已装订）没成立", m.unitSet);
        assertEquals("装订段占了目标", Missiles.Missile.NO_TARGET, m.targetSlot);
        float total = 0f;
        int frames = 0;
        while (!m.seekerOpen && frames < 24) {
            e.x += e.vx * STEP;
            float vFloor = Math.max(speed(m), spec.stallSpeed);     // 与 advance 里那道分母同式
            float gate = spec.maxLatAccel / vFloor * STEP;          // 没买过载卡 ⇒ 闸就是出厂那道
            float before = (float) Math.atan2(m.vy, m.vx);
            int flags = frame(m);
            frames++;
            assertEquals("闭眼段锁上了目标 ⇒ 中段引导顺带把扫描门也打开了",
                    0, flags & (MissileBehavior.FLAG_ACQUIRED | MissileBehavior.FLAG_RELOCKED));
            assertEquals("闭眼段占了目标 ⇒ 互斥被提前到开眼之前",
                    Missiles.Missile.NO_TARGET, m.targetSlot);
            float turned = Math.abs(angleDelta(m.vx, m.vy,
                    (float) Math.cos(before), (float) Math.sin(before)));
            assertTrue("第 " + frames + " 帧一步转了 " + Math.toDegrees(turned) + "°，超过过载闸 "
                    + Math.toDegrees(gate) + "° ⇒ 导引绕过了限幅", turned <= gate + 1e-4f);
            total += turned;
        }
        assertTrue("跑了 " + frames + " 帧弹轴纹丝不动 ⇒ 这张卡和没买一样",
                total > Math.toRadians(3f));
        // 只排除"拐到别处去"：装订那一刻弹轴本来就先扣了一个提前量（CV 拦截点在目标前方，
        // 这条几何实测约 11°），所以末帧还差十来度是**对的**；20° 这个上界钉的是"没拐飞"，
        // 它不是这条用例的判别式（判别式是上面那个累计转角）。
        float lead = Math.abs(losOffset(m, e));
        assertTrue("末帧弹轴离装订的那只 " + Math.toDegrees(lead) + "° ⇒ 转是转了，转的不是朝着它",
                lead < Math.toRadians(20f));
    }

    /**
     * 装订点**逐帧重解**（{@code reunit}）到底买到了什么：匀速飞的目标，它的 CV 拦截点在空间上
     * 近似不动，刷不刷都一样——所以这条必须先给目标一次**变速**（横摆怪每半个正弦换一次方向），
     * 才看得出差别。变速之后装订点必须跟着走，否则 ④ 那道开眼门会在等一个早已过期的圆心，
     * 买卡反而更晚开眼（{@code reunit} 的注释写的就是这件事）。
     *
     * <p>⚠ 断言的是**位移量级**而不是"恰好等于新圆心"：新圆心由 {@code leadTime} 递推解出，
     * 拿测试自己再解一遍就是拿被测函数算期望值。这里只钉"跟着变速走了至少 8px"——变速 −240px/s
     * 乘上还剩的零点几秒，量级在几十 px，而没刷新的那一支会停在旧圆心上（差 0）。
     */
    @Test
    public void theMidCourseCardResolvesTheBoundPointAgainWhenTheTargetChangesVelocity() {
        run.buyUpgrade(Balance.ShopCard.MID_COURSE);
        Missiles.Missile m = justFired(START_X, START_Y, 0f, -MUZZLE_SPEED);
        Enemies.Enemy e = bearingUp(START_X, START_Y, 25f, 200f);
        e.vx = 90f;
        frame(m);
        assertTrue("前提：这一枚已经装订上了", m.unitSet);
        float staleX = m.unitX;
        e.vx = -150f;                                               // 换向：CV 外推的整个未来都变了
        e.x += e.vx * STEP;
        frame(m);
        assertTrue("变速之后装订点还钉在旧圆心 ⇒ 开眼门在等一个已经不存在的位置（"
                        + staleX + " → " + m.unitX + "）",
                Math.abs(m.unitX - staleX) > 8f);
        assertEquals("装订段顺手占了目标", Missiles.Missile.NO_TARGET, m.targetSlot);
    }

    /**
     * 装订与锁定是**两个生命周期**：{@code boundSlot} 失效只清自己，绝不碰 {@code targetSlot}。
     * 写错的那一版（两条清理路径合并）会让开眼段"锁上了又立刻掉"——{@code boundTarget} 的注释
     * 点名的就是这个。
     *
     * <p>⚠ 下面这个状态**今天到不了**（开过眼就不会再闭，而 {@code boundTarget} 只在闭眼段被读），
     * 它是把两条清理路径分开的唯一构造：装订的那只打死、同时 {@code targetSlot} 指向另一只还活着的。
     * 也就是说这条钉的是**结构**而不是今天的可达行为——将来谁把装订读进开眼段，它才会变成真 bug。
     */
    @Test
    public void aDeadBoundTargetClearsTheBindingButNotTheLock() {
        run.buyUpgrade(Balance.ShopCard.MID_COURSE);
        Missiles.Missile m = justFired(START_X, START_Y, 0f, -MUZZLE_SPEED);
        Enemies.Enemy bound = bearingUp(START_X, START_Y, 25f, 200f);
        frame(m);                                                   // 射前雷达装订
        assertTrue("前提：这一枚已经装订上了", m.unitSet);
        assertEquals("装订没记下是哪一只 ⇒ 中段引导读的是空气", slotOf(bound), m.boundSlot);

        Enemies.Enemy held = bearingUp(START_X, START_Y, 10f, 120f);
        m.targetSlot = slotOf(held);
        m.targetBorn = held.born;
        bound.hp = 0;                                               // 装订的那只血归零、还没摘表
        frameAt(m, 0f, FAR);
        assertEquals("装订的那只打死了却不摘装订 ⇒ 中段引导朝一具尸体转向",
                Missiles.Missile.NO_TARGET, m.boundSlot);
        assertEquals("清理路径共用了 ⇒ 装订段替导引头摘掉了目标", slotOf(held), m.targetSlot);
    }

    /** 半角 ≥90° 时 {@code dot²} 判据会把**正后方**也算进锥内 ⇒ 视野静默变全向，必须当场炸。 */
    @Test
    public void aConeThatWideFailsLoudlyInsteadOfGoingOmnidirectional() {
        assertConeAngleRejected(true, 90f);
        assertConeAngleRejected(true, -1f);
        assertConeAngleRejected(false, 90f);
        assertConeAngleRejected(false, 180f);
    }

    private void assertConeAngleRejected(boolean radar, float halfDeg) {
        Balance.Missile bad = new Balance.Missile();
        if (radar) bad.radarHalfDeg = halfDeg; else bad.seekerHalfDeg = halfDeg;
        Missiles.Missile m = justFired(START_X, START_Y, 0f, -MUZZLE_SPEED);
        try {
            beh.beginFrame(pool, foes, bad, run);
            beh.advance(m, bad, STEP, foes, W, H, FAR);
            throw new AssertionError((radar ? "radar" : "seeker") + "HalfDeg=" + halfDeg + " 应该当场炸");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("cone half angle"));
        }
    }

    // ---- 二、导引头视场（半角 30°、锥轴 = 当前速度方向）+ 再次点火 ----------------------------

    /**
     * ⚠ 用 {@code dt = 0}：导引头视场是在**位移之后**扫的（对的，判据该读这一步之后的位置），
     * 所以按弹轴摆好的 29.9° 会因为弹往上走了 3.7px 而变成 30.25°——那条"刚刚在界内"的用例
     * 会红在位移上，而不是红在锥角上。锥沿两侧各钉一点时，把这一步的位移拿掉才是在测锥角。
     */
    @Test
    public void seekerLocksJustInsideItsFieldOfViewWithoutIgniting() {
        Missiles.Missile m = coasting(START_X, START_Y, 0f, -MUZZLE_SPEED);
        Enemies.Enemy e = bearingFromAxis(m, spec.seekerHalfDeg - 0.1f, spec.seekerRange - 5f);
        int flags = frameAt(m, 0f, FAR);
        assertTrue("视场内可锁目标没触发锁定（flags=" + flags + "）",
                (flags & MissileBehavior.FLAG_ACQUIRED) != 0);
        assertSame(e, foes.objAt(m.targetSlot));
        assertEquals("重新锁定后视场空累计要清", 0f, m.seekT, 0f);
        // 「（如果没有二次点火卡就没有再次点火）」＝L41661 第一条的括号那半 ⇒ 点火位是商品，不是出厂配置。
        assertEquals("没买卡也点了火 ⇒ 二次点火从商品退回成默认行为", 0,
                flags & MissileBehavior.FLAG_IGNITED);
        assertEquals(Missiles.Missile.PH_COAST, m.phase);
    }

    /**
     * 二次点火**要买**：他的原话 L14750「锁定最近的目标，再次点火」在 L41661 被自己加了括号
     * 「如果没有二次点火卡就没有再次点火」（UTC 2026-09-29T19:35:24.098Z＝本地 09-30 03:35:24）。
     * "没买"那一侧由 {@link #seekerLocksJustInsideItsFieldOfViewWithoutIgniting} 钉，这条钉"买了"那一侧。
     *
     * <p>⚠ 开关的载体换过一次：出厂那个 {@code Balance.Missile.reIgnitionOnAcquire} 布尔已摘除
     * （2026-10-01 簇 II C1 落码），商品位只从 {@link ShopRun#reIgnitionOn()} 经 {@code beginFrame}
     * 解析。留着那个布尔就是给"再点火"留第二条触发路径，而 {@code Balance} 那条在真机上永远不会被
     * 点亮——一条没人读、却能改行为的旁路，比缺功能更难查。
     */
    @Test
    public void anAcquisitionReIgnitesOnlyWhenTheCardIsOwned() {
        run.buyUpgrade(Balance.ShopCard.IGNITION);                  // 一级买的就是"再点火"这个机制本身
        Missiles.Missile m = coasting(START_X, START_Y, 0f, -MUZZLE_SPEED);
        Enemies.Enemy e = bearingFromAxis(m, spec.seekerHalfDeg - 0.1f, spec.seekerRange - 5f);
        int flags = frameAt(m, 0f, FAR);
        assertTrue("根本没锁定 ⇒ 这条会退化成只看相位（flags=" + flags + "）",
                (flags & MissileBehavior.FLAG_ACQUIRED) != 0);
        assertTrue("买了卡还没点火 ⇒ 商品位没接到 FLAG_IGNITED",
                (flags & MissileBehavior.FLAG_IGNITED) != 0);
        assertSame(e, foes.objAt(m.targetSlot));
        assertEquals(Missiles.Missile.PH_BOOST, m.phase);
        assertEquals("再点火要把计时器归零", 0f, m.boostT, 0f);
    }

    /**
     * 扇形的**两条边**各自钉一点：半角那条（角度）与半径那条（距离）。
     * 半径这条是 L40027 补的口径（「导弹视场是导弹前方60°圆心角、半径50的扇形」），
     * 它和半角是两条正交的判据——只钉角度的话，一枚弹能在屏幕另一头锁住目标而画面毫无异常。
     */
    @Test
    public void seekerIgnoresAnEnemyJustOutsideItsFieldOfView() {
        Missiles.Missile angle = coasting(START_X, START_Y, 0f, -MUZZLE_SPEED);
        bearingFromAxis(angle, spec.seekerHalfDeg + 0.1f, spec.seekerRange - 5f);   // 圈内、锥外
        assertEquals(0, frameAt(angle, 0f, FAR) & MissileBehavior.FLAG_ACQUIRED);
        assertEquals(Missiles.Missile.NO_TARGET, angle.targetSlot);

        Missiles.Missile range = coasting(START_X, START_Y, 0f, -MUZZLE_SPEED);
        bearingFromAxis(range, 0f, spec.seekerRange + 0.1f);                        // 锥轴正上方、圈外
        assertEquals("半径这条上界没生效 ⇒ 视场成了无限远", 0,
                frameAt(range, 0f, FAR) & MissileBehavior.FLAG_ACQUIRED);
        assertEquals(Missiles.Missile.NO_TARGET, range.targetSlot);
    }

    /**
     * 「二次点火」后面三级卖的是**扇形半径**：+25px/级，出厂 50 ⇒ 满级 125（他「收」的那句
     * 「seekerRange 125px」，分配见池件 §30；本卡领半径、过载卡领夹角）。
     *
     * <p>钉的是**上界恰好落在 75**（一级）：只钉"50.1 现在锁得到"的话，把半径写成 +∞、
     * 或把 25 加成 250，这条都照过——而那是两种完全不同的错（前者让视场退化成无限远，
     * 后者让一张卡吃掉整个屏幕）。两侧各钉一点，钉的才是 `spec.seekerRange + bonus` 这个式子。
     */
    @Test
    public void theIgnitionCardExtendsTheSeekerRadiusByExactlyItsBonus() {
        run.buyUpgrade(Balance.ShopCard.IGNITION);                  // 一级 = +25px
        float reach = spec.seekerRange + run.seekerRangeBonus();
        assertEquals("卡面对不上这一级的读数", 75f, reach, 0f);

        Missiles.Missile got = coasting(START_X, START_Y, 0f, -MUZZLE_SPEED);
        bearingFromAxis(got, 0f, spec.seekerRange + 0.1f);           // 出厂圈外一格、新圈内
        assertTrue("买了半径卡还锁不到 ⇒ 有效半径没进判据",
                (frameAt(got, 0f, FAR) & MissileBehavior.FLAG_ACQUIRED) != 0);

        Missiles.Missile miss = coasting(START_X, START_Y - 200f, 0f, -MUZZLE_SPEED);
        bearingFromAxis(miss, 0f, reach + 0.1f);                     // 新圈外一格
        assertEquals("上界没跟着涨 ⇒ 加成加在了别处（有效半径应该恰好 " + reach + "）", 0,
                frameAt(miss, 0f, FAR) & MissileBehavior.FLAG_ACQUIRED);
    }

    /**
     * 满级读数 125px 的**行为侧**落点：三级买满之后，扇形上界必须停在 50+75=125，一级不多一级少。
     *
     * <p>这条与 {@link #theIgnitionCardExtendsTheSeekerRadiusByExactlyItsBonus} 不是重复：那条钉
     * "加成的单位是 25"，这条钉"三级叠到 125 这个他点过头的数"，中间隔着 {@code seekerRangeMaxLevel}
     * 那道级数闸——级数被改到 2 而每级不变时，只有这条会红。
     */
    @Test
    public void theIgnitionRadiusReachesTheAdoptedOneTwentyFiveOnlyAtMaxLevel() {
        for (int n = 0; n < Balance.shop.seekerRangeMaxLevel; n++) run.buyUpgrade(Balance.ShopCard.IGNITION);
        float reach = spec.seekerRange + run.seekerRangeBonus();
        assertEquals("满级视场半径不是他「收」的那个数", 125f, reach, 0f);

        Missiles.Missile got = coasting(START_X, START_Y, 0f, -MUZZLE_SPEED);
        bearingFromAxis(got, 0f, reach - 0.1f);
        assertTrue("满级还够不着自己的读数", (frameAt(got, 0f, FAR) & MissileBehavior.FLAG_ACQUIRED) != 0);

        Missiles.Missile miss = coasting(START_X, START_Y - 200f, 0f, -MUZZLE_SPEED);
        bearingFromAxis(miss, 0f, reach + 0.1f);
        assertEquals(0, frameAt(miss, 0f, FAR) & MissileBehavior.FLAG_ACQUIRED);
    }

    /**
     * 「机动过载」卖的两样里，**视场**那半是**总夹角** 60°→84°（他「收」的口径），折半成 42° 是
     * 实现里的比较量。所以这条同时钉三件事：加成进了判据、进的是**半**份、折半没折错。
     *
     * <p>如果写成 {@code seekerHalfDeg + bonus}（整角当半角加），满级锥沿停在 54°——42.1° 那个点
     * 照样锁得到，只有下面第二枚弹会红。这正是"卡面印 84°、实际视野 108°"那种没人报的错。
     */
    @Test
    public void theHandlingCardWidensTheSeekerArcByHalfItsBonus() {
        for (int n = 0; n < Balance.shop.handlingMaxLevel; n++) run.buyUpgrade(Balance.ShopCard.HANDLING);
        float half = spec.seekerHalfDeg + 0.5f * run.seekerArcDegBonus();
        assertEquals("满级总夹角不是他「收」的那个数", 84f, 2f * half, 0f);

        Missiles.Missile got = coasting(START_X, START_Y, 0f, -MUZZLE_SPEED);
        bearingFromAxis(got, spec.seekerHalfDeg + 0.1f, spec.seekerRange - 5f);   // 出厂锥外、新锥内
        assertTrue("买满视场卡还是锁不到 ⇒ 角度加成没进判据",
                (frameAt(got, 0f, FAR) & MissileBehavior.FLAG_ACQUIRED) != 0);

        Missiles.Missile miss = coasting(START_X, START_Y - 200f, 0f, -MUZZLE_SPEED);
        bearingFromAxis(miss, half + 0.1f, spec.seekerRange - 5f);                // 新锥沿外一格
        assertEquals("锥沿退回出厂那条 ⇒ 加成没生效；锥沿越过 " + half + "° ⇒ 总夹角当成半角加了",
                0, frameAt(miss, 0f, FAR) & MissileBehavior.FLAG_ACQUIRED);
    }

    /**
     * **射前雷达锥不跟涨**：它的半角是 L38831 那句「无限长半径的扇形区域」里的形状，本来就没有
     * 半径可言；把夹角加成加到它上面会让"能不能出弹"变成成长量——那不在「机动过载」的卡面上，
     * 也不在他给这张卡定的那两样（最大过载、导引头视场）里。
     *
     * <p>⚠ 这条自己防自己空转：先断言卡确实买到了（{@code seekerArcDegBonus() > 0}），否则
     * "没装订上"会因为根本没买卡而通过。
     */
    @Test
    public void theHandlingCardLeavesTheLaunchRadarConeAlone() {
        for (int n = 0; n < Balance.shop.handlingMaxLevel; n++) run.buyUpgrade(Balance.ShopCard.HANDLING);
        assertTrue("卡没买上 ⇒ 这条会因为什么都没发生而通过", run.seekerArcDegBonus() > 0f);
        Missiles.Missile m = justFired(START_X, START_Y, 0f, -MUZZLE_SPEED);
        bearingUp(START_X, START_Y, spec.radarHalfDeg + 0.1f, 200f);
        assertEquals(0, frame(m) & MissileBehavior.FLAG_ACQUIRED);
        assertFalse("射前锥跟着视场卡涨了 ⇒ 出得出弹变成成长量", m.unitSet);
    }

    /**
     * 无可锁目标 ⇒ 保持出膛方向直飞、不哑火也不报错。
     *
     * <p>⚠ 归属：他的字面只到 L14750 {@code feedback}「若视场内无敌人则自爆」，那与"直飞"字面冲突；
     * 是**我**把冲突摆成选项、他挑了 L14773（{@code answers-OPT}，同前）里我写的那档标签
     * 「不锁定、直飞，看到再锁」⇒ 决策是他的、措辞是我的。"不哑火、不报错" 以及它与视场空自爆（见
     * {@code anEmptyFieldSelfDestructOutranksExpiry}）怎么并存，都是我的推导，不是他的原话。
     */
    @Test
    public void aMissileWithNothingToLockFliesStraightAndNeverGoesDumb() {
        Missiles.Missile m = coasting(START_X, START_Y, 0f, -MUZZLE_SPEED);
        bearingFromAxis(m, spec.seekerHalfDeg + 20f, 300f);       // 视场外有一只，永远不该锁
        for (int s = 0; s < 20; s++) {                            // 0.33s &lt; seekTimeoutSec
            int flags = frame(m);
            assertEquals("无目标时不该产生任何事件位（flags=" + flags + "）",
                    MissileBehavior.FLAG_NONE, flags);
            assertFalse("没在飞（提前回收了）", MissileBehavior.isRetired(flags));
            assertEquals("直飞时弹轴不该偏", 0f, m.vx, 0f);
            assertTrue("直飞时弹轴不该偏", m.vy < 0f);
        }
    }

    /**
     * 目标互斥：已被别的弹锁着的敌人要**跳过它找下一个**，而不是原地等。
     *
     * <p>⚠ mode0 之后这条只能拿**开眼滑行段**来测：射前雷达那一段只装订、不占目标
     * （{@link #radarBindsTheNearestEnemyInsideItsConeOneStepAfterLaunch}），所以三枚弹都摆成
     * {@code coasting} 且互斥的那两只必须都落在 {@code seekerRange} 圈内——扇形只有半径 50，
     * 发射点只能纵向错开 8px。三处取 {@code dt = 0}：这门要测的是"选谁"，不是"走一步之后离谁更近"。
     *
     * <p>从任何一格看，被占的那只都是**更近的**（近的那只在正轴 30/38/46、远的那只在 40/48/56），
     * 所以"第二枚锁到了远的那只"只能由互斥生效解释；"第三枚什么都没锁"则同时钉住了半径上界。
     */
    @Test
    public void anOccupiedTargetIsSkippedForTheNearestLockInsteadOfBlockingIt() {
        Missiles.Missile first = coasting(START_X, START_Y, 0f, -MUZZLE_SPEED);
        Enemies.Enemy near = foe(START_X, START_Y - (spec.seekerRange - 20f));     // 30
        Enemies.Enemy far = foe(START_X, START_Y - (spec.seekerRange - 10f));      // 40
        frameAt(first, 0f, FAR);
        assertSame(near, foes.objAt(first.targetSlot));                             // 第一枚抢下最近的

        Missiles.Missile second = coasting(START_X, START_Y + 8f, 0f, -MUZZLE_SPEED);
        frameAt(second, 0f, FAR);
        assertSame("第二枚抢了同一个目标 ⇒ 派生互斥没生效", far, foes.objAt(second.targetSlot));

        Missiles.Missile third = coasting(START_X, START_Y + 16f, 0f, -MUZZLE_SPEED);
        frameAt(third, 0f, FAR);
        assertEquals("两格都被占着还硬锁", Missiles.Missile.NO_TARGET, third.targetSlot);
    }

    /** 血已归零但还没摘表的敌人：**既不算撞上也别想锁**（{@code hp<=0} 过滤是结算段的义务）。 */
    @Test
    public void aDeadButUnreapedEnemyIsNeitherFusedNorLockable() {
        Missiles.Missile m = coasting(START_X, START_Y, 0f, -MUZZLE_SPEED);
        Enemies.Enemy corpse = foe(START_X, START_Y - 6f);        // 在引信距离内
        corpse.hp = 0;
        int flags = frame(m);
        assertEquals("打死的敌人还触发引信", 0, flags & MissileBehavior.FLAG_DETONATE);
        assertEquals("打死的敌人还能被锁", Missiles.Missile.NO_TARGET, m.targetSlot);
    }

    // ---- 三、运动学：只改方向、失速地板、引信距离 ---------------------------------------------

    /**
     * 转向只改方向、不改速率（§1.3）。两枚弹同步步数：一枚目标在正轴上（不转向），一枚目标偏轴
     * 40°（每步都在转）。速率若被转向掺和过，这两条曲线立刻分叉。
     */
    @Test
    public void guidanceTurnsTheAxisButNeverTheSpeed() {
        Missiles.Missile straight = coasting(START_X, START_Y, 0f, -MUZZLE_SPEED);
        lockAt(straight, foe(START_X, 200f));
        Missiles.Missile turning = coasting(START_X - 60f, START_Y, 0f, -MUZZLE_SPEED);
        lockAt(turning, bearingFromAxis(turning, 40f, 300f));

        float ax0 = turning.vx, ay0 = turning.vy;
        for (int s = 0; s < 30; s++) {
            beh.beginFrame(pool, foes, spec, run);
            beh.advance(straight, spec, STEP, foes, W, H, FAR);
            beh.advance(turning, spec, STEP, foes, W, H, FAR);
            assertEquals("第 " + s + " 步两枚弹速率不同 ⇒ 转弯改掉了速率",
                    speed(straight), speed(turning), 1e-3f);
        }
        float turned = Math.abs(angleDelta(turning.vx, turning.vy, ax0, ay0));
        // ⚠ 阈值随导引律换过：纯追踪时代这里钉的是 30°（那一律每步都索取"全部角差"，40° 的误差
        // 一定吃满 2.26°/步的限幅）。L41661 换成比例导引之后指令是 **N′·(V_c÷|v|)·λ̇**，
        // 索取的是**角速率**不是角差，同一条几何下实测 30 步转 23.9°（本轮读数，池件 §30）。
        // 阈值降到 15° 不是放宽判据：转向若整个失效，这里会是 0°。
        assertTrue("偏轴 40° 的目标 30 步内只拐了 " + Math.toDegrees(turned) + "° ⇒ 导引没生效",
                turned > Math.toRadians(15f));
        assertTrue("两枚弹的方向居然一样 ⇒ 目标摆放没造出差别",
                Math.abs(angleDelta(straight.vx, straight.vy, ax0, ay0)) < turned - 1e-3f);
        assertTrue("滑行段速率没衰减 ⇒ 这条比较是拿两个常数在比",
                speed(straight) < MUZZLE_SPEED - 1f);
    }

    /**
     * **失速地板**（§1.3 那条 ⚠，方案自查发现的荒谬行为）。"锁着但永远追不上"的弹不会被
     * {@code seekTimeout} 收掉（那条只管空视场），只能活到 {@code maxLifeSec}；纯滑行是指数衰减
     * {@code v = 220·e^(-1.25t)}，3s 末速只剩 5.6px/s ⇒ 无地板时 {@code ω = a/v = 93 rad/s} ⇒
     * **每步转 88°，导弹原地变成钻头**。这不是 NaN，是行为错到荒谬，而且恰好在打不到的那批弹身上。
     *
     * <p>⚠ 这条用例的**摆位随导引律换过一次**（L41661「预测模型换比例导引」）。纯追踪索取的是
     * "全部角差"，任何距离都会把限幅吃满，所以旧版把目标放在 4000px 外也照样贴着上界跑；
     * PN 索取的是**角速率** {@code λ̇ = (r×v_rel)÷|r|²}，它随距离反比衰减——同一句 4000px 摆在
     * 新律下只剩 0.033°/步，那条"贴界"断言当场空转（本轮实测）。于是改成**近距＋横向速度**：
     * 40px、目标横速 150px/s、偏轴 25°，这是 PN 下真会把过载闸压满的几何
     * （指令 5.3～10.6rad/s 对上 4.33rad/s 的上界）。
     *
     * <p>⚠ 目标的**速度**也得跟着弹轴转（不只是位置）：PN 读的是相对运动，而 {@code keepBearing}
     * 每步把弹轴转过 4.14°——速度钉死在世界横轴上的话，转到与弹轴同向那一刻
     * {@code λ̇→0、V_c} 还可能翻负，指令归零、这条用例退化成空转（实测末步 1.8e-6°）。
     * 位置与速度一起跟着轴走，才是"恒定的横向穿越几何"这一个被测试的对象。
     * ⚠ 这一句正是「导弹提升最大过载卖命中率」（L41661 第五条）的落点：过载闸**现在**是约束，
     * 那张（簇 II 拟增的）卡才有货可卖。
     */
    @Test
    public void stallFloorCapsTheTurnRateInsteadOfDrilling() {
        Missiles.Missile m = coasting(START_X, START_Y, 0f, -MUZZLE_SPEED);
        float dist = 40f;                                         // 近＋横速：让 PN 的指令压满过载闸
        Enemies.Enemy chase = bearingFromAxis(m, 25f, dist);
        lockAt(m, chase);
        float cap = spec.maxLatAccel / spec.stallSpeed * STEP;    // 地板给出的每步转角上界
        float lastTurn = 0f;
        for (int s = 0; s < 170; s++) {                           // 170 步 = 2.83s &lt; maxLifeSec
            keepBearing(m, chase, 25f, dist);                     // 目标始终偏轴 25° ⇒ 每步都吃满限幅
            float len = speed(m);                                 // 速度也始终垂直弹轴 ⇒ λ̇ 恒定
            chase.vx = -m.vy / len * 150f;
            chase.vy =  m.vx / len * 150f;
            float before = (float) Math.atan2(m.vy, m.vx);
            frame(m);
            float turned = Math.abs(angleDelta(m.vx, m.vy, (float) Math.cos(before), (float) Math.sin(before)));
            assertTrue("第 " + s + " 步转了 " + Math.toDegrees(turned) + "°，超过失速地板给的上界 "
                            + Math.toDegrees(cap) + "° ⇒ ω 的分母没夹住",
                    turned <= cap + 1e-4f);
            assertTrue("第 " + s + " 步速率掉到 " + speed(m) + "，低于失速地板",
                    speed(m) >= spec.stallSpeed - 1e-3f);
            lastTurn = turned;
        }
        assertEquals("末速没夹在地板上 ⇒ 地板这条没被考验到", spec.stallSpeed, speed(m), 1e-3f);
        assertTrue("全程没贴着转角上界跑 ⇒ 这条用例是空转：" + Math.toDegrees(lastTurn) + "°",
                lastTurn >= cap - 1e-4f);
    }

    /**
     * 「机动过载」卖的**第二样**：最大过载 {@code +370px/s² ×4} ⇒ 出厂 520 抬到 2000。
     * 这条要证明那张卡**有货可卖**——{@link #stallFloorCapsTheTurnRateInsteadOfDrilling} 已经量出
     * 同一条几何下 PN 的指令是 5.3～10.6rad/s，压死在出厂那道 4.33rad/s 的闸上；把闸抬到
     * 16.67rad/s 之后，同一条几何应该**放开转**、并且仍然停在抬高的那道闸以内。
     *
     * <p>只比"拐得更多"是不够的：真正的失效模式是**加成写进了 {@code Balance}**（那会让无卡的
     * 出厂值也跟着涨，而 {@code ShopRun} 全零那条由 {@code ShopRulesTest} 钉）或者**闸整个失效**
     * （那会变成钻头）。所以上界那条断言一句都不能省。
     */
    @Test
    public void theHandlingCardOpensTheOverloadGateItSells() {
        for (int n = 0; n < Balance.shop.handlingMaxLevel; n++) run.buyUpgrade(Balance.ShopCard.HANDLING);
        float baseCap = spec.maxLatAccel / spec.stallSpeed * STEP;                     // 出厂：4.14°/步
        float cardCap = (spec.maxLatAccel + run.latAccelBonus()) / spec.stallSpeed * STEP;
        assertEquals("满级过载读数对不上卡面", 2000f, spec.maxLatAccel + run.latAccelBonus(), 0f);
        assertTrue("抬完的闸比出厂还低 ⇒ 这张卡是负资产", cardCap > baseCap);

        Missiles.Missile m = coasting(START_X, START_Y, 0f, -MUZZLE_SPEED);
        float dist = 40f;
        Enemies.Enemy chase = bearingFromAxis(m, 25f, dist);
        lockAt(m, chase);
        float maxTurn = 0f;
        for (int s = 0; s < 170; s++) {                               // 与失速地板那条同一套摆位
            keepBearing(m, chase, 25f, dist);
            float len = speed(m);
            chase.vx = -m.vy / len * 150f;
            chase.vy = m.vx / len * 150f;
            float before = (float) Math.atan2(m.vy, m.vx);
            frame(m);
            float turned = Math.abs(angleDelta(m.vx, m.vy,
                    (float) Math.cos(before), (float) Math.sin(before)));
            assertTrue("第 " + s + " 步转了 " + Math.toDegrees(turned) + "°，超过卡抬起来的那道闸 "
                            + Math.toDegrees(cardCap) + "° ⇒ 过载上限整个失效（会变成钻头）",
                    turned <= cardCap + 1e-4f);
            if (turned > maxTurn) maxTurn = turned;
        }
        assertTrue("170 步里最大转角 " + Math.toDegrees(maxTurn) + "° 没越过出厂那道 "
                        + Math.toDegrees(baseCap) + "° ⇒ 卡没把闸抬起来（无卡对照见 stallFloor…）",
                maxTurn > baseCap + 1e-4f);
    }

    /**
     * 比例导引的**定义性**判据：它收敛到"常值视线角"＝提前量，而不是把弹轴对到目标**此刻**的位置
     * （那条叫尾追，横向速度高的怪永远追不上——正是 L41661 第二条要换掉的东西）。
     *
     * <p>两只手各钉一半：① 弹轴与视线的夹角**不为零**（旧码一帧就能把它压到限幅以内 2.26°，
     * 这条断言在旧律下必红）；② 这个夹角**稳定**（λ̇ 被归零＝导引收敛，不是在抖）。
     * 目标每步按自己的速度真走 {@code e.x += e.vx·dt}——不这么写的话"速度"只是给制导律看的谎话，
     * 几何不会收敛，②就测不到东西。
     */
    @Test
    public void proportionalNavigationLeadsTheTargetInsteadOfChasingItsCurrentPosition() {
        Missiles.Missile m = coasting(START_X, START_Y, 0f, -MUZZLE_SPEED);
        Enemies.Enemy e = bearingFromAxis(m, 0f, 200f);           // 正前方 200px
        e.vx = 60f;                                               // 横着跑
        lockAt(m, e);
        float leadEarly = 0f;
        for (int s = 0; s < 40; s++) {                            // 40 步 = 0.67s（滑行衰减到 ~135px/s）
            e.x += e.vx * STEP;
            frame(m);
            if (s == 29) leadEarly = losOffset(m, e);
        }
        float leadLate = losOffset(m, e);
        assertTrue("弹轴对到了目标此刻的位置 ⇒ 这还是纯追踪（提前角 " + Math.toDegrees(leadLate) + "°）",
                Math.abs(leadLate) > Math.toRadians(8f));
        assertTrue("提前角在漂 ⇒ 视线角速率没被归零，导没收敛（第 30 步 " + Math.toDegrees(leadEarly)
                        + "° → 第 40 步 " + Math.toDegrees(leadLate) + "°，速率 " + speed(m) + "px/s）",
                Math.abs(leadLate - leadEarly) < Math.toRadians(5f));
        assertTrue("弹轴没往目标那侧拐 ⇒ 制导根本没出力（" + Math.toDegrees(leadLate) + "°）",
                Math.abs(leadLate) < Math.toRadians(60f));
    }

    /** 弹轴与"指向目标此刻位置"那条视线之间的带符号夹角（**弧度**，与 {@link #angleDelta} 同口径）。 */
    private float losOffset(Missiles.Missile m, Enemies.Enemy e) {
        float len = speed(m);
        float dx = e.x - m.x, dy = e.y - m.y;
        float dl = (float) Math.sqrt(dx * dx + dy * dy);
        return angleDelta(m.vx / len, m.vy / len, dx / dl, dy / dl);
    }

    /**
     * 引信距离 = 敌半径 + 弹体半长 + {@code fusePad}，两侧各钉一点（0 步长，理由见
     * {@link #frameAt}）。
     *
     * <p>两侧各留 0.5px 而不是拿"正好相等/正好不相等"：{@code d² ≤ rr²} 在相等时算**界内**，
     * 那是判据的一部分、由 {@link #aMissileInTheFuzeRangeDetonatesInsteadOfIgnitingAgain}
     * 那一类贴脸用例覆盖，不必让这条边界测试去赌一个浮点相等。
     */
    @Test
    public void theFuzeRangeIsRadiusPlusHalfTheBodyPlusPad() {
        float r = Balance.enemies[Balance.Enemy.STRAIGHT].hitRadius();
        float reach = r + Balance.weapons[Balance.Weapon.MISSILE].size / 2f + spec.fusePad;
        assertTrue("判据半径算反了（敌半径 " + r + " + 半长 + pad 应该 > 0）", reach > 0f);

        Missiles.Missile inside = coasting(START_X, START_Y, 0f, -MUZZLE_SPEED);
        Enemies.Enemy hit = foe(START_X, START_Y - (reach - 0.5f));
        assertTrue((frameAt(inside, 0f, FAR) & MissileBehavior.FLAG_DETONATE) != 0);
        hit.hp = 0;                                   // 第一枚引爆的既定结果；摘表要到结算段，这里手动清算

        Missiles.Missile outside = coasting(START_X, START_Y, 0f, -MUZZLE_SPEED);
        foe(START_X, START_Y - (reach + 0.5f));
        assertEquals("够不着的距离也能引爆 ⇒ 引信半径算大了",
                0, frameAt(outside, 0f, FAR) & MissileBehavior.FLAG_DETONATE);
    }

    // ---- 四、同帧判序（§1.2：引信 → 自爆 → 回收 → 再锁）--------------------------------------

    /**
     * 状态机图把"再锁 → 引信 → 自爆 → GONE"画成从上到下的四条箭头，**照那个顺序实现会违背他的原话**
     * （L14750 feedback，坐标见类注释）：「命中接近后展开连续杆战斗部」「接触到亮线的扣完整伤害」——
     * 一枚已经贴到敌人脸上的弹，只要同帧视场里还有个可锁的敌人，就会"再次点火"从敌人身上掠过去。
     * ⇒ "引信排在再锁之前"这条同帧判序是我的推导，他没说同一帧内怎么排。
     */
    @Test
    public void aMissileInTheFuzeRangeDetonatesInsteadOfIgnitingAgain() {
        Missiles.Missile m = coasting(START_X, START_Y, 0f, -MUZZLE_SPEED);
        foe(START_X, START_Y - 6f);                               // 引信内
        bearingFromAxis(m, 0f, spec.seekerRange - 5f);            // 扇形里还有个可锁的（半径外就不算这条）
        int flags = frame(m);
        assertTrue((flags & MissileBehavior.FLAG_DETONATE) != 0);
        assertEquals("判序反了：引信还没结算就去再锁目标", 0, flags & MissileBehavior.FLAG_RELOCKED);
        assertEquals(Missiles.Missile.NO_TARGET, m.targetSlot);
    }

    /** 寿命到点**又**已在引信距离 ⇒ 该展开战斗部，而不是静默消失。 */
    @Test
    public void theFuzeOutranksItsOwnExpiry() {
        Missiles.Missile m = coasting(START_X, START_Y, 0f, -MUZZLE_SPEED);
        m.life = spec.maxLifeSec;
        foe(START_X, START_Y - 6f);
        int flags = frame(m);
        assertTrue((flags & MissileBehavior.FLAG_DETONATE) != 0);
        assertEquals("超时压过了引信 ⇒ 贴脸的弹会静默消失", 0, flags & MissileBehavior.FLAG_GONE);
    }

    /**
     * 视场空超时与超寿命同时成立 ⇒ 走自爆（**展开战斗部**，GONE 是不展开的）。
     * 「自爆也展开，与撞敌同路」是我在 L14773（{@code answers-OPT}，同前）给的标签、他挑的档
     * ⇒ 决策他的、措辞我的；他的原话只到 L14750 {@code feedback} 那句视场空自爆（引文见
     * {@code aMissileWithNothingToLockFliesStraightAndNeverGoesDumb}），"展开战斗部" 那是撞敌那条的说法。
     */
    @Test
    public void anEmptyFieldSelfDestructOutranksExpiry() {
        Missiles.Missile m = coasting(START_X, START_Y, 0f, -MUZZLE_SPEED);
        m.seekT = spec.seekTimeoutSec;
        m.life = spec.maxLifeSec;
        int flags = frame(m);
        assertTrue((flags & MissileBehavior.FLAG_SELF_DESTRUCT) != 0);
        assertEquals(0, flags & MissileBehavior.FLAG_GONE);
    }

    @Test
    public void anExpiredMissileRetiresSilently() {
        Missiles.Missile m = coasting(START_X, START_Y, 0f, -MUZZLE_SPEED);
        lockAt(m, bearingFromAxis(m, 0f, 400f));                  // 有目标 ⇒ seekT 恒零，不会自爆
        m.life = spec.maxLifeSec;
        int flags = frame(m);
        assertTrue((flags & MissileBehavior.FLAG_GONE) != 0);
        assertEquals("静默回收不该展开战斗部", 0,
                flags & (MissileBehavior.FLAG_DETONATE | MissileBehavior.FLAG_SELF_DESTRUCT));
        assertTrue(MissileBehavior.isRetired(flags));
    }

    /** 出界判据读的是**这一步之后**的位置，所以起点要摆到"走一步仍在界外"，否则钉的是位移不是边界。 */
    @Test
    public void anOffscreenMissileRetiresSilently() {
        Missiles.Missile m = coasting(START_X, H + 60f, 0f, MUZZLE_SPEED);   // 已经从底边掉出去
        int flags = frameAt(m, STEP, 40);
        assertTrue((flags & MissileBehavior.FLAG_GONE) != 0);
        assertEquals(0, flags & (MissileBehavior.FLAG_DETONATE | MissileBehavior.FLAG_SELF_DESTRUCT));
    }

    // ---- 五、闸门只剩一道：开眼（seekerOpen）；相位只管推力 -------------------------------------

    /**
     * 闭眼段（装订飞行中）：扇形里明明摆着一只，也**不选靶、不累视场空计时**。
     *
     * <p>这条要钉的是 mode0 的第三段本身——L38831「使用相对三角形判断脱锁飞行方向并装订发射单元，
     * 然后点火加速，**在抵达发射单元描述的位置后**扫描附近敌机」。旧码（mode1）在这一段一路
     * pure-pursuit，正是 L40027「与设计冲突，要改mode1为0」点名的那处。
     *
     * <p>全程 {@code dt = 0}：这一步若真走 3.7px，"没锁"就可能被读成"还没进圈"，而这里摆的
     * 位置本来就在圈内（{@code seekerRange - 5}）。进圈门留到最后一行单独打开，前后只差那一行。
     */
    @Test
    public void aClosedSeekerNeitherSearchesNorAgesTheSeekTimer() {
        Missiles.Missile m = coastingBlind(START_X, START_Y, 0f, -MUZZLE_SPEED);
        Enemies.Enemy ahead = foe(START_X, START_Y - (spec.seekerRange - 5f));
        for (int s = 0; s < 10; s++) {
            int flags = frameAt(m, 0f, FAR);
            assertEquals("开眼前就锁上了目标 ⇒ 装订那一段白设了",
                    Missiles.Missile.NO_TARGET, m.targetSlot);
            assertEquals("闭眼段在累视场空计时 ⇒ 它会在根本没开眼的时候自爆", 0f, m.seekT, 0f);
            assertEquals("闭眼段不该产生任何事件位（flags=" + flags + "）", 0,
                    flags & (MissileBehavior.FLAG_ACQUIRED | MissileBehavior.FLAG_RELOCKED
                            | MissileBehavior.FLAG_IGNITED | MissileBehavior.FLAG_SELF_DESTRUCT));
        }
        m.seekerOpen = true;                                    // 越过进圈门，别的都不动
        int flags = frameAt(m, 0f, FAR);
        assertTrue("门一开还是没锁 ⇒ 上面那十步测的是别的东西（flags=" + flags + "）",
                (flags & MissileBehavior.FLAG_ACQUIRED) != 0);
        assertSame(ahead, foes.objAt(m.targetSlot));
    }

    /**
     * 反过来的那道闸门**塌了，是他裁的**：眼开了就扫，**不看相位**。
     *
     * <p>⚠ 归属：旧码在扫描那里多要一道 {@code coasting}，那是我按 R9「再次点火」补的推导（出处链
     * L14750／L14369／L14773，本来就只是旁证）。他这一行把语序摆平了：
     * 「发现后就转向，开眼，锁定最近的目标，再次点火（如果没有二次点火卡就没有再次点火）」
     * （L41661 第一条，UTC 2026-09-29T19:35:24.098Z＝本地 09-30 03:35:24）——转向、开眼、锁定、
     * 点火四件全在扫描**之后**，而点火还带括号 ⇒ 相位不是索敌条件。
     *
     * <p>推导作废的代价是实测出来的，不是从画面上看出来的：那道门槛在 B3 档（boostSec 1.0，
     * L40806「要落B3」）把 mode0 的第四段**整个饿死**——48 发的导引步数总和为 0、开眼后转角 0.0°，
     * 命中全来自 CV 直飞加引信擦碰（池件 §28.2）。
     */
    @Test
    public void anOpenEyeStillInBoostPicksANewTargetWithoutIgniting() {
        Missiles.Missile m = coasting(START_X, START_Y, 0f, -MUZZLE_SPEED);
        Enemies.Enemy e = foe(START_X, START_Y - (spec.seekerRange - 5f));   // 一扫就能锁的位置
        m.phase = Missiles.Missile.PH_BOOST;                     // 推力段里：旧门槛在这里不索敌
        m.boostT = 0f;
        int flags = frameAt(m, STEP, FAR);
        assertSame("开眼还在 boost 就不选靶 ⇒ 那道被 L41661 作废的门槛还挂在扫描上",
                e, foes.objAt(m.targetSlot));
        assertTrue((flags & MissileBehavior.FLAG_ACQUIRED) != 0);
        assertEquals("默认没有二次点火卡 ⇒ 这一拍不该点火", 0, flags & MissileBehavior.FLAG_IGNITED);
        assertEquals(Missiles.Missile.PH_BOOST, m.phase);
    }

    /**
     * 残留的那道不对称：**空视场计时读的是眼，不是相位**。
     * 于是"开眼后目标在点火这几拍里死了"照样往自爆那条走，不等滑行段重新开始——这一半 L41661 没动，
     * 它只把"扫不扫"从相位上摘下来，没有把"计时"摘下来。
     */
    @Test
    public void theSeekTimerAgesInsideBoostBecauseItReadsTheEyeNotThePhase() {
        Missiles.Missile m = coasting(START_X, START_Y, 0f, -MUZZLE_SPEED);
        bearingFromAxis(m, spec.seekerHalfDeg + 20f, spec.seekerRange - 5f);   // 圈内、锥外，锁不上
        m.phase = Missiles.Missile.PH_BOOST;
        m.boostT = 0f;
        frameAt(m, STEP, FAR);
        assertEquals("boost 段就不累空视场 ⇒ 自爆那条从读眼改成了读相位", STEP, m.seekT, 1e-5f);
    }

    /**
     * ⚠ **mode0 翻掉的一条旧账**：从没开过眼的弹**不累 seekT**（"视场内无敌人"这个说法得先有视场），
     * 于是它只能被 {@code maxLifeSec} 或出界收掉，不再是旧的「点火 + 超时 ≈ 0.68s」。
     * {@code MissilesTest.spawnRateBoundLeavesHeadroomInThePool} 那笔容量账整段建立在这个数上，
     * 那里已按 3.0s 重算——这条留着是为了让"账的前提"和"码的行为"钉在同一处。
     */
    @Test
    public void aMissileThatNeverOpensItsEyeIsReclaimedByExpiryNotByTimeout() {
        Missiles.Missile m = muzzle(START_X, START_Y, 0f, -MUZZLE_SPEED);
        bearingUp(START_X, START_Y, spec.radarHalfDeg + 20f, 30f);   // 雷达锥外 ⇒ 不装订 ⇒ 永不开眼
        int steps = 0;
        int flags;
        do {
            flags = frame(m);
        } while (!MissileBehavior.isRetired(flags) && ++steps < 300);
        assertTrue("跑满 300 步没回收", (flags & MissileBehavior.FLAG_GONE) != 0);
        assertEquals("闭眼的弹不该自爆", 0, flags & MissileBehavior.FLAG_SELF_DESTRUCT);
        assertEquals("回收时机不是 maxLifeSec", spec.maxLifeSec, m.life, STEP + 1e-4f);
        assertEquals("闭眼段不该累视场空计时", 0f, m.seekT, 0f);
    }

    /** 开眼之后的空视场才是那条 {@code seekTimeoutSec} 的自爆：计时从**进圈那一刻**起算。 */
    @Test
    public void anEmptyFieldSelfDestructsOnceTheEyeIsOpen() {
        Missiles.Missile m = coasting(START_X, START_Y, 0f, -MUZZLE_SPEED);
        bearingFromAxis(m, spec.seekerHalfDeg + 20f, spec.seekerRange - 5f);   // 圈内、锥外，永远锁不上
        int steps = 0;
        int flags;
        do {
            flags = frameAt(m, STEP, FAR);
        } while ((flags & MissileBehavior.FLAG_SELF_DESTRUCT) == 0 && ++steps < 300);
        assertTrue("跑满 300 步没自爆", (flags & MissileBehavior.FLAG_SELF_DESTRUCT) != 0);
        assertTrue("自爆时机 " + m.life + "s 偏离超时 " + spec.seekTimeoutSec + "s 超过一步",
                Math.abs(m.life - spec.seekTimeoutSec) <= STEP + 1e-4f);
    }

    // ---- 六、派生互斥：每条完毕态之后目标都得能被重新锁上 ------------------------------------

    @Test
    public void detonatingReleasesItsTarget() {
        retireHolderBy(PATH_DETONATE);
        assertNextMissileCanLockIt();
    }

    @Test
    public void selfDestructingReleasesItsTarget() {
        retireHolderBy(PATH_SELF_DESTRUCT);
        assertNextMissileCanLockIt();
    }

    @Test
    public void expiringReleasesItsTarget() {
        retireHolderBy(PATH_EXPIRED);
        assertNextMissileCanLockIt();
    }

    @Test
    public void goingOffscreenReleasesItsTarget() {
        retireHolderBy(PATH_OFFSCREEN);
        assertNextMissileCanLockIt();
    }

    @Test
    public void relockingOntoAnotherTargetReleasesTheOldOne() {
        retireHolderBy(PATH_RELOCKED);
        assertNextMissileCanLockIt();
    }

    @Test
    public void clearingThePoolReleasesEveryTarget() {
        retireHolderBy(PATH_CLEARED);
        assertNextMissileCanLockIt();
    }

    /**
     * 满池淘汰：被淘汰那枚以**字段副本**的形式交给调用方播自毁动画（R22：不结算），槽位本身被新弹占走。
     * 单独一条是因为它需要一个只容两格的小池（{@link #pool} 的 4 格不够"满"）。
     *
     * <p>⚠ 两条反直觉的形状，都是"对象池"三个字的本义，但断言写错就会永远红：
     * <ul>
     *   <li>{@link Missiles#evicted} 是池外的独立对象，比的是**字段**，不是对象身份；</li>
     *   <li>被淘汰的那个**实例会被新弹就地复用**（free-list 后进先出，腾出的槽立刻被占回去），
     *       所以 {@code holder == replacement}，而 {@code holder.seq} 在淘汰之后已经是新弹的号。
     *       要断"副本描述的是被淘汰那一枚"，必须在淘汰前把号存下来。</li>
     * </ul>
     */
    @Test
    public void evictingTheHolderReleasesItsTarget() {
        Missiles tiny = new Missiles(2);
        Missiles.Missile holder = coastingIn(tiny, START_X, START_Y, 0f, -MUZZLE_SPEED);
        Enemies.Enemy held = foe(START_X, START_Y - 30f);
        lockAt(holder, held);
        long evictedSeq = holder.seq;
        tiny.spawn();                                                     // 占满
        Missiles.Missile replacement = tiny.spawnEvictingOldest();        // 淘汰 holder
        assertNotSame(holder, tiny.evicted);
        assertEquals("交接的副本描述的不是被淘汰那一枚", evictedSeq, tiny.evicted.seq);
        assertEquals("副本没带上目标槽 ⇒ Game 侧引爆时无从知道谁被松开",
                slotOf(held), tiny.evicted.targetSlot);
        assertEquals(1, tiny.evictTotal());

        // 补成"开眼的滑行段"再走一步：mode0 下新出膛的那枚只会装订，用它验松手是问错了人
        settle(replacement, START_X, START_Y, 0f, -MUZZLE_SPEED);
        openEye(replacement);
        beh.beginFrame(tiny, foes, spec, run);
        int flags = beh.advance(replacement, spec, STEP, foes, W, H, FAR);
        assertTrue("满池淘汰之后这一格还是锁不上 ⇒ 上一代的戳留下了（flags=" + flags + "）",
                (flags & MissileBehavior.FLAG_ACQUIRED) != 0);
        assertSame(held, foes.objAt(replacement.targetSlot));
    }

    private static final int PATH_DETONATE = 0, PATH_SELF_DESTRUCT = 1, PATH_EXPIRED = 2,
            PATH_OFFSCREEN = 3, PATH_RELOCKED = 4, PATH_CLEARED = 5;

    /**
     * 让一枚**持有目标**的弹走完毕态的第 {@code path} 条路，并按 {@code Game} 的接线把它移出池。
     *
     * <p>⚠ 引爆那条顺手把"被这一发打死的敌人"标成 {@code hp = 0}：它不然会同时是下一枚弹
     * 视野里最近的可锁目标，那时"下一枚锁上了别的东西"就分不清是正常竞争还是这条没测到。
     *
     * <p>⚠ mode0 之后被占的那一格必须摆进 {@code seekerRange} 圈里（这里取 30px）：互斥发生在
     * **导引头**那一段，{@link #assertNextMissileCanLockIt} 要用开眼滑行段的弹去验"这一格松开了"，
     * 而半径外的目标本来就不可能锁上——那种场合"没锁上"既可能是泄漏也可能是判据，等于什么都没钉。
     */
    private void retireHolderBy(int path) {
        Missiles.Missile holder = path == PATH_RELOCKED
                ? coasting(START_X, START_Y, 0f, -MUZZLE_SPEED)
                : muzzle(START_X, START_Y, 0f, -MUZZLE_SPEED);
        Enemies.Enemy held = foe(START_X, START_Y - 30f);
        lockAt(holder, held);
        int holderIndex = indexOf(pool, holder);
        if (path == PATH_RELOCKED) {
            foe(START_X, START_Y - 20f);                          // 更近的可锁目标 ⇒ 换锁
            assertTrue((frameAt(holder, 0f, FAR) & MissileBehavior.FLAG_RELOCKED) != 0);
            assertNotSame(held, foes.objAt(holder.targetSlot));
            return;                                               // 换锁的弹还在池里，只有槽位要还
        }
        switch (path) {
            case PATH_DETONATE: {
                Enemies.Enemy face = foe(START_X, START_Y - 6f);   // 引信内
                assertTrue((frame(holder) & MissileBehavior.FLAG_DETONATE) != 0);
                face.hp = 0;
                break;
            }
            case PATH_SELF_DESTRUCT:
                holder.targetSlot = Missiles.Missile.NO_TARGET;   // 有目标时 seekT 会被清零
                holder.seekT = spec.seekTimeoutSec;
                assertTrue((frame(holder) & MissileBehavior.FLAG_SELF_DESTRUCT) != 0);
                break;
            case PATH_EXPIRED:
                holder.life = spec.maxLifeSec;
                assertTrue((frame(holder) & MissileBehavior.FLAG_GONE) != 0);
                break;
            case PATH_OFFSCREEN:
                holder.y = START_Y + 200f;
                holder.vy = MUZZLE_SPEED;
                beh.beginFrame(pool, foes, spec, run);
                assertTrue((beh.advance(holder, spec, STEP, foes, W, H, 40)
                        & MissileBehavior.FLAG_GONE) != 0);
                break;
            case PATH_CLEARED:
                pool.clear();
                assertEquals(0, pool.activeCount());
                return;                                           // 已经不在表里了，无需 killAt
            default:
                throw new AssertionError("unknown path " + path);
        }
        pool.killAt(holderIndex);                                 // Game 侧：isRetired ⇒ killAt
    }

    /**
     * 下一帧另一枚弹必须能锁上那个目标。锁不上 = 占用状态泄漏。
     *
     * <p>⚠ 这一枚走的是**开眼滑行段**，不是射前雷达：mode0 下雷达只装订、不占格，用它来验
     * "这一格松开了"根本碰不到互斥那条判据。{@code dt = 0} 同一个理由——要钉的是选靶，不是位移。
     */
    private void assertNextMissileCanLockIt() {
        Missiles.Missile next = coasting(START_X, START_Y, 0f, -MUZZLE_SPEED);
        frameAt(next, 0f, FAR);
        assertTrue("新的这一枚什么都没锁到 ⇒ 该格仍被上一枚占着", next.targetSlot >= 0);
        assertSame(foes.activeAt(0), foes.objAt(next.targetSlot));
    }

    // ---- 七、目标引用：槽号会被复用，所以必须带代次戳 ----------------------------------------

    /**
     * 代次戳存在的全部理由：敌人池按实例复用，"同一格上的新敌人"和"旧敌人"**是同一个 Java 对象**
     * （{@code old == fresh}），于是任何"比引用"的写法都会把复用后的新敌人当成旧目标继续导引。
     * 唯一能分辨两代的是 {@link Enemies.Enemy#born}。
     */
    @Test
    public void anEnemySpawnedIntoAReusedSlotIsNotTheOldTarget() {
        Missiles.Missile m = coasting(START_X, START_Y, 0f, -MUZZLE_SPEED);
        Enemies.Enemy old = foe(START_X, START_Y - 30f);
        long oldBorn = old.born;
        lockAt(m, old);
        int slot = m.targetSlot;
        assertSame(old, foes.objAt(m.targetSlot));

        foes.killAt(indexOf(foes, old));
        Enemies.Enemy fresh = foe(START_X, START_Y - 30f);
        assertEquals("前提没了：free-list 没把这一格还回来，这条测不到复用", slot, slotOf(fresh));
        assertSame("池不按实例复用 ⇒ 这条测的是另一件事了", old, fresh);
        assertTrue("代次戳没往前走 ⇒ 后面那个断言是拿自己跟自己比", fresh.born != oldBorn);

        int flags = frame(m);
        assertEquals("旧弹还在把复用同槽的新敌人当目标导引",
                Missiles.Missile.NO_TARGET, m.targetSlot);
        assertEquals("本帧不许就地重锁：戳还留着，方向是保守的（宁可少锁一次，也不会双锁）",
                0, flags & MissileBehavior.FLAG_RELOCKED);

        // 下一枚同样得是"开眼 + 圈内"：mode0 的射前雷达根本不参与互斥，用它验这条是问错了人
        Missiles.Missile second = coasting(START_X, START_Y + 10f, 0f, -MUZZLE_SPEED);
        frameAt(second, 0f, FAR);
        assertSame("下一帧才锁 ⇒ 这一帧的戳没失效，或失效过头了", fresh, foes.objAt(second.targetSlot));
    }

    // ---- 八、调用契约：容量对得上、每帧开头得调 beginFrame ----------------------------------

    @Test
    public void advanceWithoutAFrameBeginFailsLoudly() {
        Missiles.Missile m = muzzle(START_X, START_Y, 0f, -MUZZLE_SPEED);
        try {
            beh.advance(m, spec, STEP, foes, W, H, FAR);
            throw new AssertionError("漏调 beginFrame 应该当场炸，而不是把视场当成全向继续跑");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("beginFrame"));
        }
    }

    @Test
    public void anEnemyPoolOfAnotherCapacityFailsLoudly() {
        Enemies other = new Enemies(ENEMY_CAP + 1);
        try {
            beh.beginFrame(pool, other, spec, run);
            throw new AssertionError("位图按敌人容量建，容量换了必须炸，不能靠调用方记住");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("capacity"));
        }
    }

    /**
     * 速度带必须包含出膛速度（§1.6 那条 ⚠）。{@code stallSpeed / thrust / dragK / bulletSpeed}
     * 是四张独立旋钮，带被调空时**不报错、不 NaN**，只是速率变成一个与 {@code thrust} 无关的常数
     * ——又一种静默失效。现值 120 ≤ 220 ≤ 240。
     */
    @Test
    public void everyGuidedWeaponLaunchesInsideTheSpeedBand() {
        int guided = 0;
        float floor = spec.stallSpeed;
        float ceiling = spec.thrust / spec.dragK;
        for (Balance.Weapon w : Balance.weapons) {
            if (!w.guided) continue;
            guided++;
            assertTrue(w.name + " 的出膛速度 " + w.bulletSpeed + " 落在速度带 [" + floor + ", "
                            + ceiling + "] 之外 ⇒ clamp 会把它压成一个与 thrust 无关的常数",
                    w.bulletSpeed >= floor && w.bulletSpeed <= ceiling);
        }
        assertTrue("一把制导武器都没有 ⇒ 这条在空转", guided > 0);
    }

    // ---- 九、格斗导弹：第二条流（簇 II C2）--------------------------------------------------

    /**
     * 发射判据的三条半边一次钉住：半径、半角、"取最近"。口径出处是他逐字的 L36897（plain，
     * UTC 2026-09-28T22:58:27.676Z＝本地 09-29 06:58:27）：「发射条件是飞机前向60度内且距离满足
     * 小于等于x时。x取50，若机头前半径50、圆心角60度的扇形内有没有被格斗弹锁定的敌机，
     * <b>锁定最近的敌机</b>」——半径 50 与"取最近"都是他的数；
     * **"圆心角 60° ⇒ 半角 30°" 这个折半是我的换算**（判据全程按半角走，同 {@code seekerHalfDeg} 那条）。
     *
     * <p>四次摆位各挡一种错法：轴上但在半径外＝射程无限；半径内但在锥外**而且它才是全场最近**＝
     * "先挑近的、再看角度"（表现是朝屏幕侧面出弹）；血已归零那只同轴同锥且更近＝把还没摘表的
     * 尸体当目标；最后两只都在扇形里，一个偏轴但近、一个正轴但远＝nearest 那条排序。
     */
    @Test
    public void theLaunchGateHandsBackTheNearestEnemyInsideItsSector() {
        dog.beginFrame(dogPool, foes, spec, run);
        assertEquals("场上没人 ⇒ 一枚都不许出", -1, dog.tryLaunch(START_X, START_Y, foes, spec));
        bearingUp(START_X, START_Y, 0f, spec.dogfightRange + 5f);          // 轴上，但在半径外
        assertEquals("半径也算进锁定 ⇒ 这张卡变成了射程无限的第二把主炮",
                -1, dog.tryLaunch(START_X, START_Y, foes, spec));
        bearingUp(START_X, START_Y, spec.dogfightHalfDeg + 10f, 20f);      // 半径内、锥外，而且全场最近
        assertEquals("先取近者再看角度 ⇒ 它会朝屏幕侧面出弹",
                -1, dog.tryLaunch(START_X, START_Y, foes, spec));
        bearingUp(START_X, START_Y, 0f, 30f).hp = 0;                       // 锥内、半径内、更近，但血已归零
        assertEquals(-1, dog.tryLaunch(START_X, START_Y, foes, spec));
        Enemies.Enemy near = bearingUp(START_X, START_Y, 20f, 44f);
        bearingUp(START_X, START_Y, 0f, 49f);                              // 更贴轴线，但更远
        assertEquals("挑的不是扇形里最近那一只 ⇒ 跨过眼前去打后面那只",
                slotOf(near), dog.tryLaunch(START_X, START_Y, foes, spec));
    }

    /**
     * "50" 这个数的口径钉在源头：它必须仍然等于 <b>5 倍机长</b>，而本仓对机长的读法是
     * **受击直径 = 2 × {@code Balance.player.radius}**。他先从「x暂定五倍飞机长度」（L36568，plain，
     * UTC 2026-09-28T22:10:55.534Z＝本地 09-29 06:10:55）改口到「x取50」（L36897），
     * 50 只在受击半径 5 这一档上刚好等于 5×机长（另外两个候选：精灵盒 11 ⇒ 55、机头跨度 16 ⇒ 80）。
     *
     * <p>这条不是冗余：改 {@code player.radius} 会同时挪动受击圈与画面，唯独 {@code dogfightRange}
     * 是一个写死的浮点——没有任何编译期依赖会替你响。
     */
    @Test
    public void itsLaunchRangeIsStillFiveHullLengths() {
        assertEquals("发射半径与 5×机长脱钩 ⇒ Balance.Missile.dogfightRange 那条口径变了",
                2f * Balance.player.radius * 5f, spec.dogfightRange, 0f);
    }

    /**
     * 「一只敌机可以同时挂一锁＋二锁＋格斗锁是工程设计，弹体池建议分家」（L37356，plain，
     * UTC 2026-09-28T23:52:47.500Z＝本地 09-29 07:52:47）⇒ 占用戳**只读本池**。
     *
     * <p>反面也要钉：同一条流内部仍是一敌一锁。第二帧那一次不能省——同一帧返回 -1 可能只是
     * {@code acquire} 回填的戳在起作用，而下一帧的 -1 才证明戳是由"池里那枚弹此刻持有的目标"
     * 重建的（那才是分家之后仍然成立的那半条，也是这条流不需要发明节拍字段的全部理由）。
     */
    @Test
    public void theLaunchGateYieldsToItsOwnLocksButNotToTheOtherStream() {
        Enemies.Enemy prey = bearingUp(START_X, START_Y, 0f, 40f);
        Missiles.Missile std = coasting(START_X, START_Y, 0f, -MUZZLE_SPEED);
        lockAt(std, prey);                        // 普通弹的锁挂在**标准流**那一套表上
        frame(std);
        dog.beginFrame(dogPool, foes, spec, run);
        assertEquals("跨池排他了 ⇒ 已挂普通锁的目标没人补位，而近身问题正是格斗弹来解的",
                slotOf(prey), dog.tryLaunch(START_X, START_Y, foes, spec));
        Missiles.Missile m = dogPool.spawn();
        settle(m, START_X, START_Y, 0f, -MUZZLE_SPEED);
        dog.armDogfight(m, foes, slotOf(prey));
        assertEquals("同一帧刚锁上就再派一枚 ⇒ 一敌一锁没接住",
                -1, dog.tryLaunch(START_X, START_Y, foes, spec));
        dog.beginFrame(dogPool, foes, spec, run);
        assertEquals(-1, dog.tryLaunch(START_X, START_Y, foes, spec));
    }

    /**
     * 装配＝落锁＋开眼在**同一步**里做完：L36897「锁定最近的敌机」（锁在发射那一刻由火控解出）、
     * L37468（plain，UTC 2026-09-29T00:17:58.473Z＝本地 08:17:58）「战机判断是否开眼」
     * （开眼权在发射方，弹体自己没有这条判断）、L37155「格斗弹还有应该全程锁」。
     *
     * <p>后半那句才是这条用例的重点：**第一步安静得没有任何事件**。若实现把锁留到下一帧补做
     * （普通弹那条路），这里会多出一个 {@code FLAG_ACQUIRED}——表现上看不出差别，但"生来带锁"
     * 就变成了"每一枚都先有一帧是空的"，而 L37155 说得很死：不许有那一帧。
     */
    @Test
    public void everyDogfightMissileIsBornLockedWithItsEyeOpen() {
        Enemies.Enemy prey = bearingUp(START_X, START_Y, 0f, 45f);
        Missiles.Missile m = armedDog();
        assertTrue("装配没落锁 ⇒ 这是一枚无的弹", m.targetSlot >= 0);
        assertEquals(slotOf(prey), m.targetSlot);
        assertEquals("落的是代次戳不对的那一只", prey.born, m.targetBorn);
        assertTrue(m.seekerOpen);
        assertEquals("锁着目标的第一步不该报任何事件", MissileBehavior.FLAG_NONE, dogStep(m));
        assertEquals(slotOf(prey), m.targetSlot);
    }

    /**
     * 漏装配是**编程错误**，不是一种运行状态，所以当场抛、不静默补锁：后者会把"每枚格斗弹生来带锁"
     * 这条设定做成概率事件（扇形里有敌人时它自己会锁上，没有时它就是一枚直飞弹）。
     */
    @Test
    public void anUnarmedDogfightMissileFailsLoudlyInsteadOfLockingLate() {
        bearingUp(START_X, START_Y, 0f, 40f);
        dog.beginFrame(dogPool, foes, spec, run);
        Missiles.Missile m = dogPool.spawn();                  // 带着 RADAR_PENDING 出膛＝没装配
        settle(m, START_X, START_Y, 0f, -MUZZLE_SPEED);
        try {
            dog.advance(m, spec, STEP, foes, W, H, FAR);
            throw new AssertionError("漏装配该当场炸，静默补锁会让它看起来像另一种飞行段");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("unarmed"));
        }
    }

    /** 两条流的入口各守各的道：串了流、漏了本帧的 beginFrame、把 -1 当槽号传进来，全部当场炸。 */
    @Test
    public void theLaunchAndArmEntryPointsGuardTheirOwnLane() {
        Missiles.Missile m = muzzle(START_X, START_Y, 0f, -MUZZLE_SPEED);
        try {
            beh.tryLaunch(START_X, START_Y, foes, spec);
            throw new AssertionError("标准流上没有发射判据这条扇形，串过来必须炸");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("dogfight stream"));
        }
        try {
            beh.armDogfight(m, foes, 0);
            throw new AssertionError("标准流的实例装不了格斗弹");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("dogfight stream"));
        }
        MissileBehavior fresh = new MissileBehavior(ENEMY_CAP, MissileBehavior.STREAM_DOGFIGHT);
        try {
            fresh.tryLaunch(START_X, START_Y, foes, spec);
            throw new AssertionError("本帧没 beginFrame：占用戳还是上一帧的，同帧刚锁走的那只会再吃一枚");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("beginFrame"));
        }
        dog.beginFrame(dogPool, foes, spec, run);
        try {
            dog.armDogfight(m, foes, -1);
            throw new AssertionError("-1 是调用方漏了判空，静默装配会造出一枚无的弹");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("slot"));
        }
    }

    /**
     * 「格斗弹还有应该全程锁」（L37155，plain，UTC 2026-09-28T23:37:28.439Z＝本地 09-29 07:37:28）
     * ⇒ 导引头扫描那半边被钳掉，只留引信（{@link #itsFuzeStaysArmedEvenThoughItsSearchIsClamped}）。
     *
     * <p>对照组是这条用例的一半：同一份 {@link ShopRun}（一张卡没买）、同一套几何，普通弹会换锁。
     * 不写那一句的话，诱饵根本没进视场时"格斗弹没换"也是绿的——那是空转，不是断言。
     */
    @Test
    public void aDogfightMissileNeverHopsOntoACloserEnemy() {
        Enemies.Enemy prey = bearingUp(START_X, START_Y, 0f, 45f);
        Missiles.Missile m = armedDog();
        Enemies.Enemy decoy = bearingFromAxis(m, 18f, 25f);     // 更近、在锥内、在半径内
        Missiles.Missile std = coasting(m.x, m.y, 0f, -MUZZLE_SPEED);
        lockAt(std, prey);
        assertTrue("对照组没换锁 ⇒ 这只诱饵根本没进导引头，下面那条断言是空转",
                (frameAt(std, 0f, FAR) & MissileBehavior.FLAG_RELOCKED) != 0);
        assertEquals("对照组换到了诱饵身上（几何前提成立）", slotOf(decoy), std.targetSlot);
        assertEquals(MissileBehavior.FLAG_NONE, dogStepAt(m, 0f));
        assertEquals("全程锁被扫描改写 ⇒ 它去追那只更近的了", slotOf(prey), m.targetSlot);
    }

    /**
     * 钳掉的只有"搜索"那半边，引信照旧：{@code scanFoes} 里 {@code if (!seek) continue;} 排在
     * fused 之后。写反的失效方向是**打不中**——一枚贴上敌机的格斗弹因为不换目标就永远不结算，
     * 而它在画面上正好糊在目标身上，看起来像命中延迟。
     */
    @Test
    public void itsFuzeStaysArmedEvenThoughItsSearchIsClamped() {
        bearingUp(START_X, START_Y, 0f, 45f);
        Missiles.Missile m = armedDog();
        float fuseRange = Balance.enemies[Balance.Enemy.STRAIGHT].hitRadius()  // 与 foe() 摆进去的那一圈同口径
                + m.size * 0.5f + spec.fusePad;
        bearingFromAxis(m, 0f, fuseRange - 0.5f);              // 贴进引信圈
        bearingFromAxis(m, 25f, fuseRange + 0.5f);             // 界外那一侧（0 步长 ⇒ 不会自己飘进去）

        int flags = dogStepAt(m, 0f);
        assertEquals("贴脸也不结算 ⇒ 钳扫描顺手把引信也钳了",
                MissileBehavior.FLAG_DETONATE, flags);
    }

    /**
     * 「格斗弹脱锁应直接自爆」（L37108，plain，UTC 2026-09-28T23:30:06.025Z＝本地 09-29 07:30:06）
     * ＋「两次锁定只在普通导弹上……滑行段脱锁是普通导弹的锁定机制带来的，**是推论，不是设计**」
     * ⇒ 这条流不读 {@code seekTimeoutSec} 那 0.5s（那是普通弹的中段形状）。
     *
     * <p>三条退场路**同时**成立时才看得出序：寿命已到、人已出界、目标刚没。他 L37468 要的就是
     * 这一处"集中判断"，而集中判断若把自爆排在寿命／出界之后，表现是目标死了的弹多飞半屏再炸。
     */
    @Test
    public void losingTheLockSelfDestructsWithoutWaitingForTheTimeout() {
        Enemies.Enemy prey = bearingUp(START_X, START_Y, 0f, 45f);
        Missiles.Missile m = armedDog();
        assertEquals(MissileBehavior.FLAG_NONE, dogStep(m));
        prey.hp = 0;                                    // 血归零还没摘表：liveTarget 那半边
        m.life = spec.maxLifeSec;
        m.x = -FAR;
        int flags = dogStepAt(m, 0f);
        assertTrue("脱锁没当场自爆 ⇒ 它会带着空锁把剩下的寿命飞完（flags=" + flags + "）",
                (flags & MissileBehavior.FLAG_SELF_DESTRUCT) != 0);
        assertEquals("自爆去等了那 0.5s ⇒ 那是普通弹的规则（0 步长下 seekT 该原封不动）",
                0f, m.seekT, 0f);
        assertEquals("寿命／出界抢了自爆的位 ⇒ 集中判断那处的判序排错了",
                0, flags & MissileBehavior.FLAG_GONE);
        assertTrue(MissileBehavior.isRetired(flags));
    }

    /** 手里还有目标时出界＝回收，不是自爆：两条退场路的分工在第二条流上同样成立。 */
    @Test
    public void flyingOffTheScreenWithALiveLockIsReclaimedNotSelfDestructed() {
        Enemies.Enemy prey = bearingUp(START_X, START_Y, 0f, 45f);
        Missiles.Missile m = armedDog();
        m.x = -FAR;
        prey.x = m.x;                                   // 目标跟着出去：锁仍然作数
        prey.y = m.y + 40f;
        int flags = dogStepAt(m, 0f, 0);
        assertEquals(MissileBehavior.FLAG_GONE, flags);
    }

    /**
     * 「自带高过载…并且能叠加最大过载卡」（L36568 / L36897 两个版本里都有这句）⇒ 两个数各钉一个等式：
     * <b>出厂那道闸</b>＝1040（不是普通弹的 520），<b>买一级机动过载之后</b>＝1040＋370（不是 520＋370，
     * 也不是"卡对整个第二条流无效"）。
     *
     * <p>摆位照 {@link #stallFloorCapsTheTurnRateInsteadOfDrilling} 那一套（偏轴 25°、横向 150px/s，
     * 位置与速度一起跟着弹轴走），差别只在**距离取 20px 而不是 40px**：PN 的钳前指令与距离成反比，
     * 40px 上它实测只有 8.42°/步——出厂那道 8.28° 刚好压得住，抬一级（11.22°）就压不住了，
     * 那时候"最大转角"读的是指令而不是闸，等式钉不住。20px 是"还能躲开引信圈"（实测 15px 会在第 11 步
     * 结算）**又**足以压满加过成的那道闸的那一档。导航常数这条流是 5（他「N'先取5」L37617），
     * 指令因此是普通弹的 5/3 倍——那也是它能压满的原因。
     */
    @Test
    public void itsOverloadGateStartsFromItsOwnNumberAndStillTakesTheCard() {
        Enemies.Enemy chase = bearingUp(START_X, START_Y, 0f, 40f);
        float stdCap = spec.maxLatAccel / spec.stallSpeed * STEP;            // 普通弹出厂那道
        float dogCap = spec.dogfightLatAccel / spec.stallSpeed * STEP;       // 这条流出厂那道
        assertTrue("这条流的出厂闸不比普通弹高 ⇒ 「自带高过载」根本没落，下面的等式没有可比性",
                dogCap > stdCap);
        assertEquals("无卡读数：" + Math.toDegrees(dogCap) + "°/步", dogCap,
                steerForTurns(reArm(chase), chase, dogCap, 20f), 1e-4f);
        assertTrue("机动过载卡第一级就被拒 ⇒ 这条用例的前提变了",
                run.buyUpgrade(Balance.ShopCard.HANDLING));
        float carded = (spec.dogfightLatAccel + run.latAccelBonus()) / spec.stallSpeed * STEP;
        assertEquals("加成不是叠在 1040 上（叠在普通弹的 520 上会得到 "
                        + Math.toDegrees((spec.maxLatAccel + run.latAccelBonus()) / spec.stallSpeed * STEP)
                        + "°/步，完全不吃卡则是 " + Math.toDegrees(dogCap) + "°/步）",
                carded, steerForTurns(reArm(chase), chase, carded, 20f), 1e-4f);
    }

    /** 清场重摆：把目标放回扇形正中，再走一遍真实的"判据→出膛→装配"。 */
    private Missiles.Missile reArm(Enemies.Enemy prey) {
        dogPool.clear();
        prey.x = START_X;
        prey.y = START_Y - 40f;
        prey.vx = 0f;
        prey.vy = 0f;
        prey.hp = 20;
        return armedDog();
    }

    /** 上面那条的摆位循环：每步把目标钉回"偏轴 25°、dist px"并给它一个垂直弹轴的横速。 */
    private float steerForTurns(Missiles.Missile m, Enemies.Enemy chase, float cap, float dist) {
        float best = 0f;
        for (int s = 0; s < 170; s++) {                   // 170 步 = 2.83s < maxLifeSec
            keepBearing(m, chase, 25f, dist);
            float len = speed(m);
            chase.vx = -m.vy / len * 150f;
            chase.vy = m.vx / len * 150f;
            float before = (float) Math.atan2(m.vy, m.vx);
            int flags = dogStep(m);
            assertEquals("第 " + s + " 步就退场了（flags=" + flags + "）⇒ 摆位没能撑满这条用例",
                    MissileBehavior.FLAG_NONE, flags);
            float turned = Math.abs(angleDelta(m.vx, m.vy, (float) Math.cos(before),
                    (float) Math.sin(before)));
            assertTrue("第 " + s + " 步转了 " + Math.toDegrees(turned) + "°，超过这道闸 "
                            + Math.toDegrees(cap) + "° ⇒ 过载上限整个失效（会变成钻头）",
                    turned <= cap + 1e-4f);
            if (turned > best) best = turned;
        }
        return best;
    }

    /**
     * 「动力段固定为1秒，不吃二次加速」（L36897）。这条流**不换目标**，所以"发现新目标时重开推力"
     * 那个触发器对它结构性不成立；而 {@code reIgnite} 在 {@code beginFrame} 里对它恒 false 是第二道闸。
     * 两道闸都要有人验：撤掉任何一道，这条用例都得红。
     *
     * <p>目标每步钉回"正前方 45px"⇒ 它永远追不上，于是这一枚能一路飞到寿命到点：
     * 动力段在 {@code dogfightBoostSec}（60 步）结束、此后绝不回头、收尾是<b>静默销毁</b>而不是自爆
     * （它手里一直有目标）。
     */
    @Test
    public void itsBoostEndsOnceAndTheIgnitionCardCannotReopenIt() {
        for (int n = 0; n < Balance.shop.seekerRangeMaxLevel; n++) {
            run.buyUpgrade(Balance.ShopCard.IGNITION);
        }
        assertTrue("前提：那张卡本身是开着的（否则这条在空转）", run.reIgnitionOn());
        Enemies.Enemy chase = bearingUp(START_X, START_Y, 0f, 45f);
        Missiles.Missile m = armedDog();
        assertEquals(Missiles.Missile.PH_BOOST, m.phase);
        int steps = 0;
        int boostSteps = 0;
        boolean coasted = false;
        int flags;
        do {
            keepBearing(m, chase, 0f, 45f);
            flags = dogStep(m);
            steps++;
            assertEquals("二次点火摸到了第二条流", 0, flags & MissileBehavior.FLAG_IGNITED);
            assertEquals("它先换了目标 ⇒ 这条用例的前提没了", slotOf(chase), m.targetSlot);
            if (m.phase == Missiles.Missile.PH_BOOST) {
                assertFalse("滑行段之后又回到动力段 ⇒ 偷偷重开了推力", coasted);
                boostSteps++;
            } else {
                coasted = true;
            }
        } while (!MissileBehavior.isRetired(flags));
        assertTrue("全程没进过滑行段 ⇒ 动力段那条判据根本没被考验到", coasted);
        assertEquals("动力段不是 1 秒（" + boostSteps + " 步）",
                Math.round(spec.dogfightBoostSec / STEP), boostSteps);
        assertEquals("收尾该是寿命到点的静默销毁：" + flags, MissileBehavior.FLAG_GONE, flags);
        assertEquals(spec.maxLifeSec, m.life, STEP);
    }

    /**
     * 簇 II 那两张导引卡涨不到发射扇形上：二次点火卖的是<b>开眼之后</b>的导引头半径，机动过载卖的
     * 是导引头的夹角与<b>弹体</b>的过载，而"机头前半径50、圆心角60度"（L36897）是**发射判据**。
     * 让它们跟涨的一次性后果是"能不能自动出弹"变成成长量——那该是另一张卡的货，本仓今天没有。
     */
    @Test
    public void theGuidanceCardsDoNotReachIntoItsLaunchSector() {
        for (int n = 0; n < Balance.shop.seekerRangeMaxLevel; n++) {
            run.buyUpgrade(Balance.ShopCard.IGNITION);
        }
        for (int n = 0; n < Balance.shop.handlingMaxLevel; n++) {
            run.buyUpgrade(Balance.ShopCard.HANDLING);
        }
        bearingUp(START_X, START_Y, 0f, spec.dogfightRange + 5f);
        bearingUp(START_X, START_Y, spec.dogfightHalfDeg + 10f, 20f);
        dog.beginFrame(dogPool, foes, spec, run);
        assertEquals(-1, dog.tryLaunch(START_X, START_Y, foes, spec));
    }

    // ---- 助手 -------------------------------------------------------------------------------

    /** 刚出膛的一枚：还挂着 {@code RADAR_PENDING}，用来测射前雷达。 */
    private Missiles.Missile justFired(float x, float y, float vx, float vy) {
        Missiles.Missile m = pool.spawn();
        settle(m, x, y, vx, vy);
        return m;
    }

    /** 出膛但**不**走射前锁（这些用例要自己决定"这一枚到底持不持目标"）。 */
    private Missiles.Missile muzzle(float x, float y, float vx, float vy) {
        Missiles.Missile m = justFired(x, y, vx, vy);
        m.targetSlot = Missiles.Missile.NO_TARGET;
        return m;
    }

    /** 滑行段的一枚：视场在转、推力已断，多数判据只在这一段成立。⚠ 顺手把**眼开好**——
     *  mode0 之后"滑行段"不等于"能看见"（{@link Missiles.Missile#seekerOpen} 才是门），
     *  这一组用例测的就是导引头与导引段，闭眼那条路交给 {@link #coastingBlind} 与装订段的用例。 */
    private Missiles.Missile coasting(float x, float y, float vx, float vy) {
        return openEye(muzzle(x, y, vx, vy));
    }

    /** 滑行段但**眼还没开**：mode0 第三段（装订直飞）的形状，除 {@code seekerOpen} 外与 {@link #coasting} 全同。 */
    private Missiles.Missile coastingBlind(float x, float y, float vx, float vy) {
        Missiles.Missile m = muzzle(x, y, vx, vy);
        m.phase = Missiles.Missile.PH_COAST;
        m.boostT = spec.boostSec;
        return m;
    }

    /** 把这枚已出膛的弹推到"滑行段 + 导引头已开眼"，不动其它字段（池外的弹也用这一句补状态）。 */
    private Missiles.Missile openEye(Missiles.Missile m) {
        m.targetSlot = Missiles.Missile.NO_TARGET;
        m.phase = Missiles.Missile.PH_COAST;
        m.boostT = spec.boostSec;
        m.seekerOpen = true;
        return m;
    }

    /** {@link #coasting} 的"从指定池出膛"版（满池淘汰那条要小池）。 */
    private Missiles.Missile coastingIn(Missiles from, float x, float y, float vx, float vy) {
        Missiles.Missile m = from.spawn();
        settle(m, x, y, vx, vy);
        return openEye(m);
    }

    private void settle(Missiles.Missile m, float x, float y, float vx, float vy) {
        Balance.Weapon w = Balance.weapons[Balance.Weapon.MISSILE];
        m.x = x; m.y = y;
        m.px = x; m.py = y;
        m.vx = vx; m.vy = vy;
        m.size = w.size;
        m.damage = w.damage * 1.15f;
        m.weaponId = w.id;
    }

    private int frame(Missiles.Missile m) {
        return frameAt(m, STEP, FAR);
    }

    /**
     * 走一遍 {@code Game.fireDogfight} 的真实路径：本帧 {@code beginFrame} → {@code tryLaunch} →
     * 出膛 → 装配。**调用前得先把敌人摆好**（它要的是"扇形里确有可锁目标"这个前提，摆不出来直接炸）。
     *
     * <p>为什么非要走 {@code tryLaunch} 而不是手写槽号：装配点与发射判据是这对入口唯一合法的接法，
     * 自己拼 {@code targetSlot} 就绕过了"火控解出那一只"这半条设定（L36897）。
     */
    private Missiles.Missile armedDog() {
        dog.beginFrame(dogPool, foes, spec, run);
        int slot = dog.tryLaunch(START_X, START_Y, foes, spec);
        assertTrue("扇形里没有可锁目标 ⇒ 这条用例摆错了位", slot >= 0);
        WeaponFire.Template t = new WeaponFire.Template();
        WeaponFire.fillTemplate(t, Balance.weapons[Balance.Weapon.MISSILE], 1f, 1f, 0, 0f, 0);
        Missiles.Missile m = WeaponFire.fireMissile(dogPool, t, START_X, START_Y, 0f);
        assertEquals("出膛速度不是真表那一格 ⇒ 下面所有转角上界的换算都算错了",
                MUZZLE_SPEED, speed(m), 1e-4f);
        dog.armDogfight(m, foes, slot);
        return m;
    }

    private int dogStep(Missiles.Missile m) {
        return dogStepAt(m, STEP, FAR);
    }

    private int dogStepAt(Missiles.Missile m, float dt) {
        return dogStepAt(m, dt, FAR);
    }

    /** {@link #frameAt} 的第二流版：池、行为、戳全部换成 {@link #dogPool}／{@link #dog}。 */
    private int dogStepAt(Missiles.Missile m, float dt, int margin) {
        dog.beginFrame(dogPool, foes, spec, run);
        return dog.advance(m, spec, dt, foes, W, H, margin);
    }

    /**
     * 推进一枚弹 {@code dt} 秒。
     *
     * <p><b>边界类用例必须传 {@code dt = 0}。</b>{@link MissileBehavior#advance} 先位移、后做
     * 引信与视场判定（那是对的：判据该读这一步之后的位置），于是一枚以 220px/s 飞行的弹每步
     * 靠近 3.7px——把敌人摆在"正好半个弹长在界外"的位置，位移会自己把它推进界内，
     * 测出来的边界就不是 {@code Balance} 里那几个数。取 0 步长就把这一步变成纯谓词求值，
     * 边界两侧各钉一点，钉的才是判据本身。
     */
    private int frameAt(Missiles.Missile m, float dt, int margin) {
        beh.beginFrame(pool, foes, spec, run);
        return beh.advance(m, spec, dt, foes, W, H, margin);
    }

    private Enemies.Enemy foe(float x, float y) {
        Enemies.Enemy e = foes.spawn();
        e.hp = e.maxHp = 20;
        e.x = x;
        e.y = y;
        e.radius = Balance.enemies[Balance.Enemy.STRAIGHT].hitRadius();
        return e;
    }

    /** 从发射点看**屏幕正上方**偏 {@code deg} 度、距离 {@code dist} 的那个位置放一只敌人。 */
    private Enemies.Enemy bearingUp(float fromX, float fromY, float deg, float dist) {
        double t = Math.toRadians(deg);
        return foe((float) (fromX + dist * Math.sin(t)), (float) (fromY - dist * Math.cos(t)));
    }

    /** 从弹的**当前速度轴**偏 {@code deg} 度、距离 {@code dist} 处放一只敌人。 */
    private Enemies.Enemy bearingFromAxis(Missiles.Missile m, float deg, float dist) {
        float len = speed(m);
        double t = Math.toRadians(deg);
        float ax = m.vx / len, ay = m.vy / len;
        return foe(m.x + (float) (ax * Math.cos(t) - ay * Math.sin(t)) * dist,
                m.y + (float) (ax * Math.sin(t) + ay * Math.cos(t)) * dist);
    }

    /** 把已有的那只敌人挪到"当前弹轴偏 deg 度、距离 dist"处（永远追不上的目标用）。 */
    private void keepBearing(Missiles.Missile m, Enemies.Enemy e, float deg, float dist) {
        float len = speed(m);
        double t = Math.toRadians(deg);
        float ax = m.vx / len, ay = m.vy / len;
        e.x = m.x + (float) (ax * Math.cos(t) - ay * Math.sin(t)) * dist;
        e.y = m.y + (float) (ax * Math.sin(t) + ay * Math.cos(t)) * dist;
    }

    private void lockAt(Missiles.Missile m, Enemies.Enemy e) {
        m.targetSlot = slotOf(e);
        m.targetBorn = e.born;
    }

    private int slotOf(Enemies.Enemy e) {
        for (int i = 0; i < foes.activeCount(); i++) {
            if (foes.activeAt(i) == e) return foes.slotOfActive(i);
        }
        throw new AssertionError("这只敌人不在活跃表里");
    }

    private static int indexOf(Missiles ms, Missiles.Missile m) {
        for (int i = 0; i < ms.activeCount(); i++) {
            if (ms.activeAt(i) == m) return i;
        }
        throw new AssertionError("这枚弹不在池里");
    }

    private static int indexOf(Enemies es, Enemies.Enemy e) {
        for (int i = 0; i < es.activeCount(); i++) {
            if (es.activeAt(i) == e) return i;
        }
        throw new AssertionError("这只敌人不在表里");
    }

    private static float speed(Missiles.Missile m) {
        return (float) Math.sqrt(m.vx * m.vx + m.vy * m.vy);
    }

    /** 两个方向向量之间的夹角（带符号、绕回 {@code [-π,π]}）。 */
    private static float angleDelta(float ax, float ay, float bx, float by) {
        float cross = ax * by - ay * bx;
        float dot = ax * bx + ay * by;
        return (float) Math.atan2(cross, dot);
    }
}
