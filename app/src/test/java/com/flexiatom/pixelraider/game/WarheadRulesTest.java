package com.flexiatom.pixelraider.game;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.flexiatom.pixelraider.core.Time;
import org.junit.Test;

/**
 * 连续杆战斗部的**几何**与**节拍**（提案 missile-warhead-sim §1.4/§1.5）。
 *
 * <p>这一族判据全在纯类里，是因为 {@code Game} 是渲染类（静态块 {@code new Paint} ⇒ JVM 直调必
 * {@code ExceptionInInitializerError}）：结算段留在 {@code Game} 的那几行**结构性零覆盖**，
 * 能钉的只有"结果怎么算出来"。所以 {@code WarheadRules} 承担全部可算的部分，
 * 剩下"按结果执行动作"（扣血、{@code killEnemy}）只能靠审查 + 真机看分数。
 */
public final class WarheadRulesTest {

    private static final int W = 240;
    private static final int H = 560;
    private static final int MAX_ALIVE = Balance.wave.maxAlive;
    private static final float STEP = Time.STEP;

    /**
     * 杆**铺开到底**时的半长——覆盖类判据要用的是这个数，而不是 {@code rodLength/2}：
     * 真值一旦经由 {@link WarheadRules#rodHalfLen} 流过，测试就跟着走；写成字面量等于把真源
     * 搬进测试，改了生长曲线它照样绿（这条测试就失去说"漏判了"的资格）。
     */
    private static float fullRodHalfLen() {
        Warheads.Warhead w = new Warheads.Warhead();
        w.life = Balance.missile.rodLifeSec;
        return WarheadRules.rodHalfLen(w, Balance.missile);
    }

    // ---- 杆的几何：端点式，不存角度 --------------------------------------------------------

    /**
     * 垂直性钉据（复核 C4 的正面半边）。{@code axis = (0,-1)} ⇒ 端点必须是 {@code (±rodLength/2, 0)}。
     *
     * <p>这条是"格子里不存角度"那个改法的回归网：一旦有人把 {@code angle} 加回来并写
     * {@code axis + 90f}（弧度轴上 = 90 **弧度** ⇒ {@code mod 2π = 116.6°}），杆歪 26.6°，
     * 而命中与绘制读同一个错数、自洽地同时错——只有这条会红。
     */
    @Test
    public void rodIsExactlyPerpendicularToItsAxis() {
        float half = Balance.missile.rodLength / 2f;
        float[] out = new float[WarheadRules.ROD_ENDS_FLOATS];
        assertTrue(WarheadRules.rodEnds(120f, 300f, 0f, -240f, half, out));
        assertEquals(120f + half, out[0], 1e-3f);
        assertEquals(300f, out[1], 1e-3f);
        assertEquals(120f - half, out[2], 1e-3f);
        assertEquals(300f, out[3], 1e-3f);
        // 斜轴也一样：垂直 ⇒ 端点连线与速度点乘为零
        assertTrue(WarheadRules.rodEnds(0f, 0f, 3f, -4f, half, out));
        float ex = out[0] - out[2], ey = out[1] - out[3];
        assertEquals(0f, ex * 3f + ey * -4f, 1e-2f);
        assertEquals(2 * half, (float) Math.sqrt(ex * ex + ey * ey), 1e-3f);
    }

    /** 零速没有法向可言：返回 false，**不猜**"那就正右方吧"（那是把 90° 安静写进屏幕）。 */
    @Test
    public void zeroVelocityRodReportsNoNormalInsteadOfGuessingOne() {
        float[] out = new float[WarheadRules.ROD_ENDS_FLOATS];
        for (float v : new float[]{0f, -0f}) {
            assertFalse(WarheadRules.rodEnds(100f, 100f, v, v, 17f, out));
        }
    }

    /**
     * 扫掠带的两个**退化形状**必须与它们取代的老判据逐像素同值：
     * ① {@code halfWidth0 = halfWidth1 = 0} ⇒ 碎片质点这一步走过的线段；② 位移为 0 ⇒ 此刻那根
     * 垂直于速度的线段。
     *
     * <p>这两条是"杆与碎片共用一个判据"的前提：判据只有一处，{@code warheadHitPad} 与那条网格
     * 覆盖界才真的只有一处要管（{@link #rodAndShardShareOneHitRadiusFormula} 立的是同一个立场）。
     * 老判据两条用例的每一条断言都原样搬到这里，一条没丢（两端半长写成同一个值＝矩形＝老形状）。
     */
    @Test
    public void theSweptBandDegeneratesToTheSegmentThenToTheRodLine() {
        // ① 两端都是 0：位移 (0,0)→(20,0) ⇒ 带就是这条线段。相切：圆心到带最近距离恰为 r
        assertTrue(WarheadRules.sweptBandHitsCircle(0f, 0f, 20f, 0f, 1f, 0f, 0f, 0f, 10f, 3f, 3f));
        // 擦边：差 0.1px 就不算
        assertFalse(WarheadRules.sweptBandHitsCircle(0f, 0f, 20f, 0f, 1f, 0f, 0f, 0f, 10f, 3.1f, 3f));
        // 穿过：带中段过圆心
        assertTrue(WarheadRules.sweptBandHitsCircle(0f, 0f, 20f, 0f, 1f, 0f, 0f, 0f, 10f, 0f, 1f));
        // 两端是帽不是墙：垂足落在带外，最近的是端点
        assertTrue(WarheadRules.sweptBandHitsCircle(0f, 0f, 20f, 0f, 1f, 0f, 0f, 0f, 21f, 0f, 1.5f));
        assertFalse(WarheadRules.sweptBandHitsCircle(0f, 0f, 20f, 0f, 1f, 0f, 0f, 0f, 24f, 0f, 1.5f));
        // 碎片出生那一步（位移为零）⇒ 退化成质点对圆，不许除零
        assertTrue(WarheadRules.sweptBandHitsCircle(10f, 10f, 10f, 10f, 1f, -1f, 0f, 0f, 10f, 11f, 1f));
        assertFalse(WarheadRules.sweptBandHitsCircle(10f, 10f, 10f, 10f, 1f, -1f, 0f, 0f, 10f, 11.5f, 1f));

        // ② 位移为 0 ＋ 两端半长都是 10：速度沿 y ⇒ 这根杆沿 x 铺开，就是"此刻那根线段"
        assertTrue(WarheadRules.sweptBandHitsCircle(5f, 5f, 5f, 5f, 0f, -240f, 10f, 10f, 14f, 5f, 1f));
        assertFalse(WarheadRules.sweptBandHitsCircle(5f, 5f, 5f, 5f, 0f, -240f, 10f, 10f, 16.5f, 5f, 1f));
        // 沿**位移方向**差一点也不算：带长度为零，不是圆盘
        assertFalse(WarheadRules.sweptBandHitsCircle(5f, 5f, 5f, 5f, 0f, -240f, 10f, 10f, 5f, 12f, 1f));
    }

    /** 位移与速度同时为零 ⇒ 没有带可言，返回 false，**不猜**"那就当个点吧"（那会白送一个 r 的圆盘）。 */
    @Test
    public void aWarheadWithNeitherStepNorVelocityReportsNoBand() {
        assertFalse(WarheadRules.sweptBandHitsCircle(5f, 5f, 5f, 5f, 0f, 0f, 17f, 17f, 5f, 5f, 3f));
    }

    /**
     * 生长期里带是**梯形**：位移为零时取步初半长（此刻画的就是这么短），有位移时沿位移线性铺开。
     *
     * <p>这条立的是他 2026-09-30 的逐字判词：「杆没长满时按满长扫与视觉效果不符，属于代码与设计不符」。
     * 上一版把整步按**步末**半长等宽扫 ⇒ 杆尖白多盖一个楔形，而屏幕上那一刻的杆还没长到那儿。
     * 两条断言各钉一半：① 没在走 ⇒ 用步初；② 在走 ⇒ 用插值，且**步末那端宽、步初那端窄**。
     */
    @Test
    public void theGrowingBandIsATrapezoidNotARectangleOfTheEndingWidth() {
        // ① 位移为零 ⇒ 带的可及处就是**此刻那根**（静止着长大的杆，扫到最远就是步末那个长度）
        assertTrue("静止采样时半长取小了（步末那根画到 17，判据只认 4）",
                WarheadRules.sweptBandHitsCircle(5f, 5f, 5f, 5f, 0f, -240f, 4f, 17f, 12f, 5f, 1f));
        assertFalse("静止采样时多盖了：此刻这根只有 17 的半长",
                WarheadRules.sweptBandHitsCircle(5f, 5f, 5f, 5f, 0f, -240f, 4f, 17f, 23.5f, 5f, 1f));

        // ② 位移 (0,0)→(20,0)，半长 4 → 17：中点允许 10.5，靠步末那端允许更多
        assertTrue("带中点被算窄了（插值写成了步初？）",
                WarheadRules.sweptBandHitsCircle(0f, 0f, 20f, 0f, 1f, 0f, 4f, 17f, 10f, 10.4f, 1f));
        assertFalse("带中点按步末半长等宽扫 ⇒ 又变回矩形（楔形过覆盖复活）",
                WarheadRules.sweptBandHitsCircle(0f, 0f, 20f, 0f, 1f, 0f, 4f, 17f, 10f, 12.5f, 1f));
        assertTrue("靠步末那端没铺开（插值写成了步初？）",
                WarheadRules.sweptBandHitsCircle(0f, 0f, 20f, 0f, 1f, 0f, 4f, 17f, 20f, 16.4f, 1f));
        assertFalse("靠步初那端多盖了（那一头只有 4px 半长）",
                WarheadRules.sweptBandHitsCircle(0f, 0f, 20f, 0f, 1f, 0f, 4f, 17f, 0f, 6.5f, 1f));
    }

    /** 杆与碎片**共用**同一条命中半径（两处 pad 就是"改一处不改另一处"的那类静默漂移）。 */
    @Test
    public void rodAndShardShareOneHitRadiusFormula() {
        Enemies.Enemy e = new Enemies.Enemy();
        e.radius = 4.5f;
        float viaRule = WarheadRules.hitRadius(e, Balance.missile);
        assertEquals(e.radius + Balance.missile.warheadHitPad, viaRule, 1e-6f);
    }

    // ---- 展开：半长随 life 从 0 铺到满长（R17「逐渐展开」） --------------------------------

    /**
     * R17 那句"逐渐展开"的可执行形式：**出生时为 0 → 单调不减 → 到 {@code rodGrowSec} 恰满长 →
     * 此后不再长**。
     *
     * <p>最后一半尤其要紧：生长与寿命是**两个独立的数**，一旦没有钳制，{@code rodGrowSec} 被调到
     * 比 {@code rodLifeSec} 还小时杆会在寿命内一路长过 {@code rodLength}，直接把
     * {@link #warheadReachStaysInsideTheGridsCoverageRing} 钉的那条网格覆盖界顶穿——而顶穿的
     * 表现是"杆尖擦到的敌人静默不掉血"，屏幕上看什么都不对。
     */
    @Test
    public void theRodUnfoldsFromZeroToItsFullLengthAndStopsThere() {
        Balance.Missile spec = Balance.missile;
        float full = spec.rodLength * 0.5f;
        Warheads.Warhead w = new Warheads.Warhead();

        w.life = 0f;
        assertEquals("出生那一刻杆长度必须为 0（R17 的'展开'从点开始）", 0f,
                WarheadRules.rodHalfLen(w, spec), 1e-6f);

        float prev = -1f;
        for (float life = 0f; life <= spec.rodLifeSec + spec.rodGrowSec; life += STEP) {
            w.life = life;
            float half = WarheadRules.rodHalfLen(w, spec);
            assertTrue("半长随 life 必须单调不减，" + life + "s 处回退了", half >= prev);
            assertTrue("半长不得超过满长（钳制失效会顶穿网格覆盖界）", half <= full + 1e-6f);
            prev = half;
        }
        w.life = spec.rodGrowSec;
        assertEquals(full, WarheadRules.rodHalfLen(w, spec), 1e-6f);
        w.life = spec.rodLifeSec * 3f;               // 远超寿命：仍不许长
        assertEquals(full, WarheadRules.rodHalfLen(w, spec), 1e-6f);
    }

    /**
     * 生长必须**显著快于**寿命，否则"逐渐展开"在屏幕上读不出来：杆要到临碎那一刻才满长，
     * 玩家看到的是一根一直在变短的线。这条判据用**不写死数值**的形式钉住——只要求一个整周期
     * 的节拍内已铺开大半。
     *
     * <p>{@code rodGrowSec × 2 ≤ rodLifeSec} 是**最弱**的可接受形式（至少留半个周期是满长的）；
     * 现值 0.06s 对 0.2s，实际余量比这宽得多。
     */
    @Test
    public void growthFinishesWellBeforeTheRodBreaksUp() {
        Balance.Missile spec = Balance.missile;
        assertTrue("rodGrowSec=" + spec.rodGrowSec + " 与 rodLifeSec=" + spec.rodLifeSec
                        + " 的关系不成立 ⇒ 杆要临碎才满长，展开在屏幕上读不出来"
                        + "（判据来自本测试，改数值前先想清楚玩家看得见什么）",
                spec.rodGrowSec * 2f <= spec.rodLifeSec);
        // 展开必须在**第一次结算之前**基本铺开，否则"打得到"的跨度比"看得到"的小一个节拍。
        assertTrue("rodGrowSec 比一个结算节拍还长 ⇒ 首次计费时杆还没铺开",
                spec.rodGrowSec <= spec.tickSec);
    }

    /** {@code rodGrowSec} 取 0 或负数 = 关掉生长，一出生就满长（这条支路也得有读数）。 */
    @Test
    public void turningGrowthOffMeansFullLengthFromBirth() {
        Balance.Missile off = new Balance.Missile();
        off.rodGrowSec = 0f;
        float full = off.rodLength * 0.5f;
        Warheads.Warhead w = new Warheads.Warhead();
        for (float life : new float[]{0f, 0.01f, off.rodLifeSec}) {
            w.life = life;
            assertEquals(full, WarheadRules.rodHalfLen(w, off), 1e-6f);
        }
    }

    // ---- 覆盖不变量：三个数一律从代码读 ----------------------------------------------------

    /**
     * 网格只保证"中心距 ≤ {@code ring×CELL} 的候选必进结果"，**不做距离判定**（
     * {@link SpatialGrid#query} 自己写了这句话）⇒ 杆尖擦到的敌人如果落在 ring 外，就是
     * "画面上该中没中"的静默漏判。这条界必须由单测钉住，而且**三个数不许写字面量**：
     * 写了就等于把真源搬进测试，改了真值它照样绿。
     *
     * <p>⚠ 判据换成扫掠带之后，这项里多了一个**单步位移**：带是以 {@code (px,py)→(x,y)} 为中线的，
     * 所以它最远能伸到当前位置**后方一整步**的地方。忘了这一项的话，快的战斗部会在"看不见的那一步"
     * 里漏判，症状与前一条一模一样。
     *
     * <p>⚠ 杆与碎片**各算各的**：把两者的最大值混在一起（最长的杆配最快的碎片）会造出一个
     * 没有任何战斗部拥有的形状，然后这条界会红得没有道理。
     */
    @Test
    public void warheadReachStaysInsideTheGridsCoverageRing() {
        float half = fullRodHalfLen();
        float pad = Balance.missile.warheadHitPad;
        float maxRadius = 0f;
        for (Balance.Enemy s : Balance.enemies) maxRadius = Math.max(maxRadius, s.hitRadius());
        for (Balance.Boss b : Balance.bosses) maxRadius = Math.max(maxRadius, b.hitRadius());
        float cover = SpatialGrid.ringFor(SpatialGrid.DEFAULT_MAX_CENTER_DISTANCE) * SpatialGrid.CELL;
        // 杆：带的最远的角 = hypot(整步位移, 半长)
        float rodReach = (float) Math.hypot(rodStepTravel(), half) + maxRadius + pad;
        // 碎片：带退化成线段，长度就是位移，没有横向
        float shardReach = shardStepTravel() + maxRadius + pad;
        assertTrue("杆的扫掠带角点 + 最大敌半径 + 命中 pad = " + rodReach + " 超出网格覆盖 " + cover
                + " ⇒ 会有敌人被擦到却查不到（SpatialGrid.query 不做距离判定，漏了不会响）", rodReach < cover);
        assertTrue("碎片的扫掠线段 + 最大敌半径 + 命中 pad = " + shardReach + " 超出网格覆盖 " + cover,
                shardReach < cover);
        // 反证这条界不是空话：网格常量与杆长都参与，任一被调大就该红
        assertTrue("网格 ring 竟然不是 1：那条 3×3 的代价假设变了",
                SpatialGrid.ringFor(SpatialGrid.DEFAULT_MAX_CENTER_DISTANCE) >= 1);
    }

    /**
     * 同一条界的**上界读数**：杆长加到多少就会开始漏判。写死成"必须还差得远"，
     * 免得日后有人把 {@code rodLength} 当成"想调多长调多长"的旋钮而没人拦。
     * ⚠ 位移与杆长抢的是同一个圆（{@code hypot}，不是相加），所以这里开方而不是减法——
     * 用减法会把上界算小（31.5−16 = 15.5 半长 vs 真值 27.1 半长），白扔掉一半的可用杆长。
     */
    @Test
    public void rodLengthIsTheOnlyOneOfTheThreeNearItsLimit() {
        float maxRadius = 0f;
        for (Balance.Enemy s : Balance.enemies) maxRadius = Math.max(maxRadius, s.hitRadius());
        for (Balance.Boss b : Balance.bosses) maxRadius = Math.max(maxRadius, b.hitRadius());
        float cover = SpatialGrid.ringFor(SpatialGrid.DEFAULT_MAX_CENTER_DISTANCE) * SpatialGrid.CELL;
        float slack = cover - maxRadius - Balance.missile.warheadHitPad;
        float halfLimit = (float) Math.sqrt(slack * slack - rodStepTravel() * rodStepTravel());
        float rodLength = 2f * fullRodHalfLen();     // 实际会铺到多长，走真源不查表
        assertTrue("杆长余量已经只剩 " + (2 * halfLimit - rodLength) + "px："
                        + "rodLength 上界是 " + 2 * halfLimit,
                rodLength < 2 * halfLimit);
    }

    /** 杆出生那一刻的单步位移：弹的平衡速度**全额**＋抛出项（叠加，不再有倍乘，见 {@code spawnRod}）。 */
    private static float rodStepTravel() {
        Balance.Missile sp = Balance.missile;
        return (sp.thrust / sp.dragK + sp.rodEjectSpeed) * Time.STEP;
    }

    /**
     * 碎片的单步位移：杆的前向分量与横向抛撒**同向相加**＝最坏。
     * ⚠ 定常步长 {@link Time#STEP} 是这两条界成立的前提（规格：固定 60Hz，机器差不掉速率）。
     */
    private static float shardStepTravel() {
        return rodStepTravel() + Balance.missile.shardSpeed * Time.STEP;
    }

    // ---- 节拍：帧率无关 ---------------------------------------------------------------------

    /**
     * 节拍的焊死点：同一世界时长在 60fps 与 30fps 下结算次数**必须相同**（方案里编号 R12）。
     *
     * <p>⚠ 归属：「固定节拍 0.08s（帧率无关）」整串是我给的选项标签——L14779 answers（OPT），
     * utc 2026-09-25T09:33:09.768Z、本地 2026-09-25 17:33:09，他从我的标签里挑了这一档
     * ⇒ 决策是他的，但 0.08 这个数与"帧率无关"这条理由都是我的措辞；他的字面到不了"节拍隔多久"
     * 这一层（他原话的完整坐标见 {@link #shardDamageIsAQuarterOfTheRodFlooredToAtLeastOne} 上方）。
     *
     * <p>这条禁止日后把 {@code ticksDue} 改成"每 N 帧一次"——那会变成帧率相关伤害，
     * 而规格要求低端机 ≥45fps，于是"机器越差、导弹伤害越低"。
     */
    @Test
    public void tickCountIsTheSameAtSixtyAndThirtyFrames() {
        int slow = runTicks(1f / 30f, 0.8f);
        int fast = runTicks(STEP, 0.8f);
        assertEquals("30fps 与 60fps 的结算次数不一样 ⇒ 节拍又变成帧相关了", slow, fast);
        assertTrue("0.8s 一个 tick 都没结，这条空转了", slow > 0);
    }

    /**
     * 采样率与节拍**无关**（复核 C1，本步实现形状因它而变）。小怪沿杆方向的弦长只有
     * {@code 2r}，与迎面 RUSHER 的闭合速度 180px/s 一除 = 0.05s < {@code tickSec}：
     * 只在 tick 那一刻测接触，随机相位下有 37.5% 的概率整段穿过去而不掉血。
     *
     * <p>所以判据是"每步采样 + 每 tick 计费"：这里构造一段**比一个 tick 还短**的接触，
     * 60fps 的逐步采样照样结到一次伤害。
     */
    @Test
    public void contactShorterThanTheBeatIsStillBilled() {
        Balance.Missile spec = Balance.missile;
        Enemies foes = new Enemies(MAX_ALIVE + 2);
        Enemies.Enemy e = foes.spawn();
        e.hp = e.maxHp = 100;
        e.radius = Balance.enemies[Balance.Enemy.STRAIGHT].hitRadius();
        e.x = 120f;
        e.y = 200f;
        SpatialGrid grid = new SpatialGrid(W, H, foes.capacity(),
                SpatialGrid.DEFAULT_MAX_CENTER_DISTANCE);
        int[] cand = new int[MAX_ALIVE + 4];

        Warheads pool = new Warheads(8, foes.capacity());
        Warheads.Warhead w = pool.spawn();
        w.kind = Warheads.Warhead.KIND_ROD;
        w.damage = 5;
        w.size = 4f;
        w.maxLife = spec.rodLifeSec;
        // 杆横着扫过敌心：只有几步在命中带内（接触时长 < tickSec）
        w.x = 120f; w.y = e.y - 3f * e.radius;
        w.vx = 0f; w.vy = 180f;

        int billed = 0;
        int steps = (int) (0.25f / STEP);              // 三个 tick 的窗口
        for (int s = 0; s < steps; s++) {
            grid.clear();
            grid.insert(foes.slotOfActive(0), e.x, e.y);
            WarheadRules.advance(w, STEP, spec);
            if (WarheadRules.expired(w)) break;
            WarheadRules.sampleContact(w, foes, grid, cand, spec, STEP);
            for (int t = WarheadRules.ticksDue(w, STEP, spec.tickSec); t > 0; t--) {
                int mask = WarheadRules.takeContact(w);
                while (mask != 0) {
                    int slot = Integer.numberOfTrailingZeros(mask);
                    mask &= ~(1 << slot);
                    foes.objAt(slot).hp -= w.damage;
                    billed++;
                }
            }
        }
        assertTrue("这段 0.05s 量级的接触一次都没结到 ⇒ 又退回'只在 tick 瞬间测'了", billed > 0);
        assertTrue("敌人没掉血", foes.objAt(foes.slotOfActive(0)).hp < 100);
    }

    /** 累计器**保留余数**：节拍会因 0.08s 不是 1/60 的整数倍而在单步抖 5,5,4,5，但平均速率精确。 */
    @Test
    public void tickResidualIsKeptSoTheAverageRateIsExact() {
        Warheads pool = new Warheads(4, 8);
        Warheads.Warhead w = pool.spawn();
        int total = 0;
        for (int s = 0; s < 600; s++) total += WarheadRules.ticksDue(w, STEP, Balance.missile.tickSec);
        assertEquals("10 秒内跳的次数偏离 秒/tickSec", 10f / Balance.missile.tickSec, (float) total, 0.5f);
        assertTrue("余数被丢掉了（归零）⇒ 长期节拍会漂", w.tickAcc >= 0f && w.tickAcc < Balance.missile.tickSec);
    }

    @Test
    public void zeroOrNegativeBeatFailsLoudly() {
        Warheads pool = new Warheads(4, 8);
        Warheads.Warhead w = pool.spawn();
        try {
            WarheadRules.ticksDue(w, STEP, 0f);
            throw new AssertionError("tickSec=0 应该当场炸，而不是无限循环或静默跳过");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    /**
     * 「可数的量当真源、时长从它推」这条对**杆和碎片都**成立（复核 C2：原方案只点名了 rod，
     * {@code shardLifeSec = 0.25f} 是漏掉的那一个——float32 下 15×STEP 恰好舍入到 0.25，
     * 于是第 3 个 tick 与到期撞在同一步，tick 数取决于接线顺序，一次引爆 12 还是 18 点）。
     */
    @Test
    public void lifeSpansItsTickCountWithRoomToSpare() {
        assertLifeMatchesTicks("rod", Balance.missile.rodTicks, Balance.missile.rodLifeSec,
                Balance.missile.tickSec);
        assertLifeMatchesTicks("shard", Balance.missile.shardTicks, Balance.missile.shardLifeSec,
                Balance.missile.tickSec);
    }

    private static void assertLifeMatchesTicks(String what, int ticks, float lifeSec, float tickSec) {
        assertEquals(what + " 的时长结不出它标称的 tick 数", ticks, (int) Math.floor(lifeSec / tickSec));
        float margin = lifeSec - ticks * tickSec;
        assertTrue(what + " 的寿命余量只有 " + margin + "，不足 0.4 个 tick ⇒ 到期与最后一个 tick"
                + "撞在同一步，tick 数将取决于接线顺序", margin >= 0.4f * tickSec);
        assertTrue(what + " 的寿命多出一整个 tick 以上：白让战斗部多活一步",
                margin < tickSec);
    }

    /**
     * 到期那一步**晚于**最后一次结算：先结完账再碎/再回收，反过来就少一跳。
     *
     * <p>步内顺序照 {@code Game.stepWarheads}：{@code advance} → {@code expired} 则**先摘表**
     * （不再结算）→ 才 {@code ticksDue}。rod 与 shard 各调一次、各自显式传自己那一对数——
     * 不写"根据 ticks 反推 life"那种选择，因为 {@code rodTicks == shardTicks} 哪天会成立
     * （两个都是可调整数），那时它会重复测同一边、静默漏掉另一边。
     *
     * <p>"到期时不会正好压着一个还没结的 tick"这一半由
     * {@link #lifeSpansItsTickCountWithRoomToSpare} 的余量 ≥0.4 个 tick 钉住，这里不重复立据。
     */
    @Test
    public void theLastTickHappensBeforeTheRodExpires() {
        assertLastTickBeforeExpiry("rod", Balance.missile.rodTicks, Balance.missile.rodLifeSec,
                Balance.missile.tickSec);
        assertLastTickBeforeExpiry("shard", Balance.missile.shardTicks, Balance.missile.shardLifeSec,
                Balance.missile.tickSec);
    }

    private static void assertLastTickBeforeExpiry(String what, int ticks, float lifeSec, float tickSec) {
        Warheads pool = new Warheads(4, 8);
        Warheads.Warhead w = pool.spawn();
        w.maxLife = lifeSec;
        int seen = 0;
        int steps = 0;
        while (!WarheadRules.expired(w)) {
            assertTrue(what + " 跑了 1000 步还没到期：这条在空转", ++steps <= 1000);
            WarheadRules.advance(w, STEP, Balance.missile);
            if (WarheadRules.expired(w)) break;
            seen += WarheadRules.ticksDue(w, STEP, tickSec);
        }
        assertEquals(what + " 到期时的结算次数不对", ticks, seen);
    }

    // ---- 伤害：一条链，不许有第二处口径 ----------------------------------------------------

    /**
     * 碎片 = 杆的 1/4，向下取整、最小 1。六个点值取自方案 1.5 的聚合表（那张表是我按他的规则
     * 算出来的，不是他给的数）。
     *
     * <p>出处是他的原话（非选项）：L14750 feedback(status=rejected)，utc 2026-09-25T09:19:51.727Z、
     * 本地 2026-09-25 17:19:51，逐字「接触到破碎碎片的扣四分之一（向下取整，最小为1）」；
     * 同一句里「接触到亮线的扣完整伤害」支撑杆那半边（方案里编号 R9）。
     *
     * <p>扫描里的两条不变量刻意**不重述实现**（`== max(1, d/4)` 是把被测函数抄一遍）：
     * 「不超过四分之一，除非 min 1 接管」写成 `4·d' ≤ max(4, d)`，再加一条单调不减。
     */
    @Test
    public void shardDamageIsAQuarterOfTheRodFlooredToAtLeastOne() {
        assertEquals(1, WarheadRules.shardDamage(1));
        assertEquals(1, WarheadRules.shardDamage(4));
        assertEquals(1, WarheadRules.shardDamage(7));
        assertEquals(2, WarheadRules.shardDamage(8));
        assertEquals(3, WarheadRules.shardDamage(14));
        assertEquals(4, WarheadRules.shardDamage(16));
        for (int d = 1; d <= 64; d++) {
            int q = WarheadRules.shardDamage(d);
            assertTrue("碎片伤害出现 0，min 1 那条失效", q >= 1);
            assertTrue("杆 " + d + " 点时碎片给到 " + q + " 点，超过了四分之一", 4 * q <= Math.max(4, d));
            assertTrue("杆越狠碎片越弱：d=" + d, q <= WarheadRules.shardDamage(d + 1));
        }
    }

    /**
     * {@code crit} 必须一路透传（复核 DA-2）：{@code killEnemy} 的 1.5× 分数走这个布尔，
     * 伤害里烘的是 ×2 的**伤害**、不是分数。缺这个字段 = 导弹击杀永久丢 1.5 倍分。
     */
    @Test
    public void critSurvivesTheTripFromMuzzleToRod() {
        Missiles missiles = new Missiles(4);
        Missiles.Missile m = missiles.spawn();
        m.damage = 6.9f;
        m.crit = true;
        m.size = 4f;
        m.vx = 0f; m.vy = -240f;
        Warheads pool = new Warheads(4, 8);
        Warheads.Warhead rod = WarheadRules.spawnRod(pool, m, Balance.missile);
        assertNotNull(rod);
        assertTrue("rod 没继承弹的 crit", rod.crit);
        assertEquals(WeaponFire.damageOf(m.damage), rod.damage);
        assertEquals(m.size, rod.size, 0f);
        // 全额继承 ＋ 抛出项沿弹轴（叠加，不是倍乘）：-240 的弹 ⇒ 杆 -240-1
        assertEquals(-240f - Balance.missile.rodEjectSpeed, rod.vy, 1e-3f);
        assertEquals(0f, rod.vx, 1e-3f);
    }

    /** 碎片继承杆的 crit 与颜色：它们从 rod 派生，不是第三个来源。 */
    @Test
    public void shardsInheritCritAndColorFromTheRodTheyCameFrom() {
        Warheads pool = new Warheads(16, 8);
        Warheads.Warhead rod = pool.spawn();
        rod.kind = Warheads.Warhead.KIND_ROD;
        rod.x = 100f; rod.y = 200f;
        rod.vx = 0f; rod.vy = -84f;
        rod.damage = 8;
        rod.color = 0xFF123456;
        rod.crit = true;
        int made = WarheadRules.spawnShards(pool, rod, Balance.missile);
        assertEquals(Balance.missile.shardCount, made);
        for (int i = 0; i < pool.activeCount(); i++) {
            Warheads.Warhead f = pool.activeAt(i);
            if (f == rod) continue;
            assertEquals(Warheads.Warhead.KIND_SHARD, f.kind);
            assertTrue(f.crit);
            assertEquals(0xFF123456, f.color);
            assertEquals(WarheadRules.shardDamage(8), f.damage);
            assertEquals(Balance.missile.shardLifeSec, f.maxLife, 1e-6f);
            assertEquals(Balance.missile.shardSize, f.size, 1e-6f);
        }
    }

    /**
     * **破碎必须先读杆自己的字段**（本步实现时踩到的坑，形状照 {@code Missiles.copyInto}）。
     *
     * <p>槽位是 free-list 后进先出：{@code Game} 侧的接线是"先 {@code killAt} 再破碎"
     * （反过来末位交换会把新生的一片搬到当前下标上、白少走一帧），于是杆自己就是被弹回来的
     * 第一个槽——第一片的 {@code reset()} 把 {@code rod.x/y/vx/vy/damage} 当场清零。
     * 不快照的话剩下五片全从 (0,0) 以零速度生出来，玩家看到一团堆在原点的碎片。
     */
    @Test
    public void fragmentingAfterTheRodWasReapedStillUsesTheRodsOwnState() {
        Warheads pool = new Warheads(16, 8);
        Warheads.Warhead rod = pool.spawn();
        rod.kind = Warheads.Warhead.KIND_ROD;
        rod.x = 137f; rod.y = 211f;
        rod.vx = 21f; rod.vy = -63f;
        rod.damage = 12;
        rod.color = 0xFFABCDEF;
        rod.crit = true;
        int rodIndex = 0;                                  // 只有一只，活跃表下标 0
        pool.killAt(rodIndex);                             // 先把槽还回 free-list（Game 的接线顺序）
        int made = WarheadRules.spawnShards(pool, rod, Balance.missile);
        assertEquals(made, pool.activeCount());
        for (int i = 0; i < pool.activeCount(); i++) {
            Warheads.Warhead f = pool.activeAt(i);
            assertTrue("碎片出生点塌回原点 ⇒ spawnShards 又去读已被 reset() 的杆了",
                    Math.abs(f.x - 137f) < 40f && Math.abs(f.y - 211f) < 40f);
            assertTrue("碎片零速 ⇒ 继承的杆速被清零了", f.vx * f.vx + f.vy * f.vy > 0f);
            assertEquals(WarheadRules.shardDamage(12), f.damage);
        }
    }

    /**
     * 六等分沿**杆**铺开、两侧交错，且**外圈外冲更大**。
     *
     * <p>后半句才是"同侧三片不会互相穿过"的根据，所以判据是"按出生位置 {@code |u|} 升序排开后，
     * 横向速度 {@code |vx|} 严格递增"——不是去比排序后两个位置的大小（那只会重复
     * "出生点不重合"那条已经断过的话，看着像证了物理其实没证）。
     */
    @Test
    public void shardsFragmentAlongTheRodLineNotAlongTheAxis() {
        Warheads pool = new Warheads(16, 8);
        Warheads.Warhead rod = pool.spawn();
        rod.kind = Warheads.Warhead.KIND_ROD;
        rod.x = 120f; rod.y = 300f;
        rod.vx = 0f; rod.vy = -84f;          // 沿轴朝上 ⇒ 杆是水平的
        rod.damage = 8;
        // 现实中碎片只在到期那一刻生出，而 rodLifeSec 远大于 rodGrowSec ⇒ 那根杆总是满长的。
        // 这里不写死满长值，照旧从被测函数读：生长形状将来改了，六等分的跨度得跟着真值走。
        rod.life = Balance.missile.rodGrowSec;
        float half = WarheadRules.rodHalfLen(rod, Balance.missile);
        assertTrue("半长为 0 ⇒ 出生点全塌在弹位上，这条测试会空转", half > 0f);
        int made = WarheadRules.spawnShards(pool, rod, Balance.missile);
        assertEquals(Balance.missile.shardCount, made);
        float[] us = new float[made];
        float[] lateral = new float[made];
        int n = 0;
        for (int i = 0; i < pool.activeCount(); i++) {
            Warheads.Warhead f = pool.activeAt(i);
            if (f == rod) continue;
            assertEquals("碎片偏离了杆所在的那条线", 300f, f.y, 1e-3f);
            assertEquals("前向分量必须原样继承杆速", -84f, f.vy, 1e-3f);
            us[n] = (f.x - 120f) / half;
            lateral[n] = f.vx;                       // 法向是 (1,0) ⇒ vx 就是横向外冲
            n++;
        }
        assertEquals(Balance.missile.shardCount, n);
        for (int i = 1; i < n; i++) {                // 插入排序：按带符号 u 升序，两侧交错才看得见
            for (int j = i; j > 0 && us[j - 1] > us[j]; j--) {
                float t = us[j]; us[j] = us[j - 1]; us[j - 1] = t;
                t = lateral[j]; lateral[j] = lateral[j - 1]; lateral[j - 1] = t;
            }
        }
        for (int i = 1; i < n; i++) {
            assertTrue("同侧两片出生点重合或互穿：u=" + us[i - 1] + " 与 " + us[i],
                    us[i] > us[i - 1] + 1e-3f);
        }
        float sum = 0f;
        for (float u : us) sum += u;
        assertEquals("六片不关于弹轴对称（两侧交替写坏了）", 0f, sum, 1e-3f);
        // 外冲随出生位置单调：|u| 更大的一片必须飞得更开，否则外圈会追上内圈那一片、互相穿过。
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (Math.abs(us[j]) - Math.abs(us[i]) <= 1e-4f) continue;
                assertTrue("外冲不随位置递增：|u| " + Math.abs(us[i]) + "→" + Math.abs(us[j])
                                + " 而 |外冲| " + Math.abs(lateral[i]) + "→" + Math.abs(lateral[j]),
                        Math.abs(lateral[i]) < Math.abs(lateral[j]));
            }
        }
    }

    /** 单数 shardCount 是数值表配置错误：两侧交替会剩一片无处安放 ⇒ 当场炸，不静默少生。 */
    @Test
    public void oddShardCountFailsLoudly() {
        Balance.Missile spec = new Balance.Missile();
        spec.shardCount = 5;
        Warheads pool = new Warheads(16, 8);
        Warheads.Warhead rod = pool.spawn();
        rod.vx = 0f; rod.vy = -84f;
        try {
            WarheadRules.spawnShards(pool, rod, spec);
            throw new AssertionError("单数 shardCount 应该抛");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("shardCount"));
        }
    }

    /**
     * 池满时**拒生**而不是淘汰：少几片碎片玩家看不出来，但账要留在
     * {@link Warheads#refuseTotal()} 上（"看不出来的截断"与"静默的截断"不是一回事）。
     *
     * <p>两个档位都要钉：只剩一点空位 ⇒ 少生几片；一格不剩 ⇒ 一片不生。杆自己永远不被踢掉。
     */
    @Test
    public void fullWarheadPoolRefusesInsteadOfEvictingAndKeepsTheCount() {
        int count = Balance.missile.shardCount;
        Warheads pool = new Warheads(count, 8);
        Warheads.Warhead rod = pool.spawn();
        rod.kind = Warheads.Warhead.KIND_ROD;
        rod.vx = 0f; rod.vy = -84f;
        rod.damage = 8;
        long before = pool.refuseTotal();
        assertEquals(count - 1, WarheadRules.spawnShards(pool, rod, Balance.missile));
        assertEquals("少生一片却没记账", before + 1, pool.refuseTotal());
        assertEquals(count, pool.activeCount());
        assertSame(rod, pool.activeAt(0));              // 杆没被踢掉

        while (pool.spawn() != null) { }                // 一格不剩（这条循环本身就是拒生的一笔记账）
        long mid = pool.refuseTotal();
        assertEquals(0, WarheadRules.spawnShards(pool, rod, Balance.missile));
        assertEquals("满池时第一次被拒就该收手，而不是把 6 片逐个撞一遍空池",
                mid + 1, pool.refuseTotal());
        assertSame(rod, pool.activeAt(0));
    }

    /**
     * 一步跨过整条命中带的战斗部，仍然必须切断它**扫过**的敌人。
     *
     * <p>⚠ 这条取代了 2026-09-29 立的 {@code perStepDisplacementStaysInsideTheNarrowestHitBand}
     * （"每步最坏位移 &lt; 最窄命中带"的采样密度不等式，当时余量 1.97×，B3 后靠把杆速倍乘压到
     * 0.2 才保住 1.5×）。那条不等式的前提是"每步只采一个**瞬间**的形状"，而他把这个前提判死了
     * （2026-09-30：「你有一个假设，位移不连续，至少在视觉上不连续，此前提错误，因为设计显然不是
     * 这样设计的」）⇒ 60Hz 每帧画一根线，玩家读到的就是一条扫过的带；判据不比画面连续，是我实现
     * 造出来的空隙。于是"把杆速压下来救采样"这类权衡整体作废，换成**判据自己追上位移**。
     *
     * <p>所以这里立的是行为判据而不是数值不等式：造一个**两步都够不着**的敌人（圆心到上一步那根线、
     * 到这一步这根线的距离都大于命中半径 ⇒ 老写法必然漏），走过一步之后 {@code contact} 位必须亮。
     * 对照组两条一起写死，免得日后 pad 或杆长被调大、这个"空隙"根本不存在了，这条还照样绿。
     *
     * <p>杆（带＝有宽度）与碎片（带＝一条线段）分别走一遍：它们是同一个方法的两个退化形状，
     * 只验一个等于另一半没证据。
     */
    @Test
    public void aStepThatOvershootsTheHitBandStillCutsWhatItSwept() {
        Balance.Missile spec = Balance.missile;
        float minRadius = Float.MAX_VALUE;
        for (Balance.Enemy s : Balance.enemies) minRadius = Math.min(minRadius, s.hitRadius());
        for (Balance.Boss b : Balance.bosses) minRadius = Math.min(minRadius, b.hitRadius());
        float r = minRadius + spec.warheadHitPad;              // 最窄命中带的一半
        float cruise = spec.thrust / spec.dragK;               // 杆出生那一刻的对地速度

        // ---- 杆：沿 +y 走满一步 ----
        Enemies foes = new Enemies(MAX_ALIVE + 2);
        Enemies.Enemy e = foes.spawn();
        e.hp = e.maxHp = 100;
        e.radius = minRadius;
        e.x = 120f;
        e.y = 100f + cruise * Time.STEP * 0.5f;                // 摆在这一步的正中间
        SpatialGrid grid = new SpatialGrid(W, H, foes.capacity(),
                SpatialGrid.DEFAULT_MAX_CENTER_DISTANCE);
        grid.insert(foes.slotOfActive(0), e.x, e.y);
        int[] cand = new int[MAX_ALIVE + 4];

        Warheads pool = new Warheads(8, foes.capacity());
        Warheads.Warhead rod = pool.spawn();
        rod.kind = Warheads.Warhead.KIND_ROD;
        rod.maxLife = spec.rodLifeSec;
        rod.x = 120f; rod.y = 100f;
        rod.px = rod.x; rod.py = rod.y;
        rod.vx = 0f; rod.vy = cruise;

        // 对照：敌人离**这一步的两端**都比命中半径远 ⇒ 采瞬间的老写法必漏
        float half = WarheadRules.rodHalfLen(rod, spec);        // life=0 ⇒ 0，先按"此刻那根线段"最坏情形看
        assertTrue("对照组空转：敌人离出发位置只有 " + (e.y - rod.py) + "，老判据本来就能打中",
                Math.abs(e.y - rod.py) - half > r);
        float ahead = rod.y + cruise * Time.STEP;
        assertTrue("对照组空转：敌人离落点只有 " + (ahead - e.y) + "，老判据本来就能打中",
                ahead - e.y > r);

        WarheadRules.advance(rod, Time.STEP, spec);
        WarheadRules.sampleContact(rod, foes, grid, cand, spec, Time.STEP);
        assertTrue("杆一步跨过了它正对着的敌人却没记账 ⇒ 又退回采瞬间了（位移 "
                        + cruise * Time.STEP + "px vs 命中半径 " + r + "px）",
                rod.contact != 0);

        // ---- 碎片：带退化成线段，同一步隙也得切断 ----
        Warheads.Warhead shard = pool.spawn();
        shard.kind = Warheads.Warhead.KIND_SHARD;
        shard.maxLife = spec.shardLifeSec;
        shard.x = 120f; shard.y = 100f;
        shard.px = shard.x; shard.py = shard.y;
        shard.vx = 0f; shard.vy = cruise + spec.shardSpeed;
        e.y = 100f + shard.vy * Time.STEP * 0.5f;
        grid.clear();
        grid.insert(foes.slotOfActive(0), e.x, e.y);
        assertTrue("碎片对照组空转：出发位置离敌人 " + (e.y - shard.py) + "px 已在命中半径内",
                e.y - shard.py > r);
        WarheadRules.advance(shard, Time.STEP, spec);
        WarheadRules.sampleContact(shard, foes, grid, cand, spec, Time.STEP);
        assertTrue("碎片质点一步跨过敌人心却没记账", shard.contact != 0);

        // ---- 反向：带不是圆盘，侧向超出半长的敌人一律不记账 ----
        Warheads.Warhead wide = pool.spawn();
        wide.kind = Warheads.Warhead.KIND_ROD;
        wide.maxLife = spec.rodLifeSec;
        wide.x = 120f; wide.y = 100f;
        wide.px = wide.x; wide.py = 100f - cruise * Time.STEP;
        wide.vx = 0f; wide.vy = cruise;
        wide.life = spec.rodGrowSec;                           // 满长，排除"还没铺开"这个混淆项
        e.x = 120f + WarheadRules.rodHalfLen(wide, spec) + r + 6f;
        e.y = wide.y - cruise * Time.STEP * 0.5f;               // 恰好在这一步的带当中
        grid.clear();
        grid.insert(foes.slotOfActive(0), e.x, e.y);
        WarheadRules.sampleContact(wide, foes, grid, cand, spec, Time.STEP);
        assertEquals("侧向超出杆半长的敌人被记了账 ⇒ 扫掠带被写成了圆盘（白送一整条杆长的杀伤）",
                0, wide.contact);
    }

    /**
     * 上面那条钉的是纯几何；这条钉**接线**——{@link WarheadRules#sampleContact} 真的把
     * {@code rodHalfLenAt(life - dt)} 当步初交出去了，而不是把步末那个值传两遍。
     *
     * <p>写法与 {@link #theGrowingBandIsATrapezoidNotARectangleOfTheEndingWidth} 同一个判词
     * （他 2026-09-30「杆没长满时按满长扫与视觉效果不符」），差别在这条走的是采样入口：
     * 出生第一步的杆（半长只铺到 4.7px）擦着杆尖过去的敌人**不许**记账，而**同一个敌人**在杆
     * 满长之后必须记账——两半合起来才排除"距离本来就不够"这个假绿。
     */
    @Test
    public void contactSamplingUsesTheStepStartLengthToo() {
        Balance.Missile spec = Balance.missile;
        float cruise = spec.thrust / spec.dragK;
        Enemies foes = new Enemies(MAX_ALIVE + 2);
        Enemies.Enemy e = foes.spawn();
        e.hp = e.maxHp = 100;
        e.radius = 4.5f;
        e.x = 120f + 7.5f;                                   // 侧向离中线 7.5px
        e.y = 100.5f;                                        // 靠这一步的**起端**
        SpatialGrid grid = new SpatialGrid(W, H, foes.capacity(),
                SpatialGrid.DEFAULT_MAX_CENTER_DISTANCE);
        grid.insert(foes.slotOfActive(0), e.x, e.y);
        int[] cand = new int[MAX_ALIVE + 4];

        Warheads pool = new Warheads(8, foes.capacity());
        Warheads.Warhead rod = pool.spawn();
        rod.kind = Warheads.Warhead.KIND_ROD;
        rod.maxLife = spec.rodLifeSec;
        rod.x = 120f; rod.y = 100f;
        rod.px = rod.x; rod.py = rod.y;
        rod.vx = 0f; rod.vy = cruise;

        WarheadRules.advance(rod, Time.STEP, spec);           // life = 1/60 ⇒ 半长只铺到 4.72
        assertTrue("这一步的起端半长应该是 0（刚出生），前提变了这条测试就没在钉东西",
                WarheadRules.rodHalfLenAt(rod.life - Time.STEP, spec) == 0f);
        WarheadRules.sampleContact(rod, foes, grid, cand, spec, Time.STEP);
        assertEquals("生长期按步末半长等宽扫 ⇒ 杆尖那个楔形又白送出去了（屏幕上这里还没有杆）",
                0, rod.contact);

        // 同一个敌人、杆已满长 ⇒ 必须打中（排除"本来就够不着"这种假绿）
        rod.life = spec.rodGrowSec;
        rod.px = 120f; rod.py = 100f - cruise * Time.STEP;
        WarheadRules.sampleContact(rod, foes, grid, cand, spec, Time.STEP);
        assertTrue("满长的杆擦着 7.5px 的侧向距离扫过去却不记账 ⇒ 判据自己缩了",
                rod.contact != 0);
    }

    /**
     * 接触位图：每步 OR 进来、tick 取走并清零，各位之间不互相覆盖。
     *
     * <p>槽 31 是**唯一一位能同时钉住"最高可用位没写坏"与"32 的容量上限被守住"**的取值：
     * {@code 1 << 32 == 1}，所以 {@link Warheads} 的构造期断言必须挡住 33 以上（见
     * {@link #enemyCapacityAbove32FailsLoudly}）。
     */
    @Test
    public void contactBitmaskAccumulatesAcrossStepsAndClearsOnTake() {
        Warheads pool = new Warheads(4, 32);
        Warheads.Warhead w = pool.spawn();
        WarheadRules.markContact(w, 0);
        WarheadRules.markContact(w, 7);
        WarheadRules.markContact(w, 31);
        int mask = WarheadRules.takeContact(w);
        assertEquals(0, w.contact);
        for (int slot : new int[]{0, 7, 31}) {
            assertTrue("槽 " + slot + " 的接触丢了", (mask & (1 << slot)) != 0);
        }
        assertEquals("位图里多出了没标记过的位（回绕或串位）", (1 | (1 << 7) | (1 << 31)), mask);
        assertEquals(0, WarheadRules.takeContact(w));
    }

    /**
     * 敌人容量 &gt; 32 时**构造期就炸**：{@code Warhead.contact} 是一个 {@code int} 位图，
     * {@code 1 << 32 == 1} ⇒ 第 33 号槽的接触会静默记到 0 号敌人身上（掉血掉在隔壁那一只，
     * 画面与账目各说一套，没有任何地方会红）。这条不测就只能靠注释，而注释守不住数值表。
     */
    @Test
    public void enemyCapacityAbove32FailsLoudly() {
        try {
            new Warheads(8, 33);
            throw new AssertionError("容量 33 越过了 int 位图的边界却没人拦");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("32"));
        }
        new Warheads(8, 32);      // 上界本身必须放行，否则这条会退化成"永远红"
    }

    // ---- 助手 -------------------------------------------------------------------------------

    /** 以固定 dt 喂满一段世界时长，返回结算总次数（{@link #tickCountIsTheSameAtSixtyAndThirtyFrames}）。 */
    private static int runTicks(float dt, float seconds) {
        Warheads pool = new Warheads(4, 8);
        Warheads.Warhead w = pool.spawn();
        int n = 0;
        int steps = (int) Math.round(seconds / dt);
        for (int s = 0; s < steps; s++) n += WarheadRules.ticksDue(w, dt, Balance.missile.tickSec);
        return n;
    }
}
