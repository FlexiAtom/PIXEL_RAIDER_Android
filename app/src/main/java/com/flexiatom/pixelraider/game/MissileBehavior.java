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

/**
 * 导弹仿真（纯算术，不 import android）：发射单元装订 ＋ 两段视野锁定 ＋ 比例导引 ＋ 三路引爆判据。
 *
 * <p>一枚普通弹的一生按他口述的四段走（L38831 plain ＋ L40027 plain 裁的 mode0）：
 * ① 射前雷达锥（半角 45°、**无限长半径**）里有敌机 ⇒ 出膛；② 用匀速 CV 解拦截时间、
 * 把弹轴装订到那个预测位置（发射单元）；③ 点火加速**沿这条轴直飞**——这一段不索敌、不转向、
 * 不占目标；④ 进 {@code Balance.missile.seekerRange} 那个圈 ⇒ **开眼**，此后才在
 * 「半角 30° × 半径 50」的扇形里扫描、锁定、转向。发现那一刻的**二次点火不再默认存在**——
 * L41661 第一行括号里那句「（如果没有二次点火卡就没有再次点火）」把它变成了商品，
 * 开关从 {@code Balance.Missile} 的布尔移到了 {@link ShopRun#reIgnitionOn()}（簇 II 落码，2026-10-01）。
 *
 * <p>③那一段的"直飞"是**没有卡的时候**才成立：{@link ShopRun#midCourseOn()} 置位后，闭眼段
 * 也按同一条 PN 律转向**装订的那一只**（仍不扫描、仍不占目标）。中段引导＝射前装订这一步的升级，
 * 他自己的口径（2026-09-30 直发）「中段引导卡做成雷达锁定的升级」＋「中段引导卡是独立新卡」。
 *
 * ⚠ **transcript 行号不是稳定坐标**：一次 /rewind 会让后续行号整体漂移。本轮裁 mode0 的那句
 * 早期被记成 L40092，生效版是 **L40027**（plain，UTC 2026-09-29T16:01:19.845Z ＝本地 09-30 00:01:19）。
 * 对不上号时**以 UTC ＋ 逐字原文为准**，行号只当方便翻找的书签。
 *
 * 形状照 {@link EnemyBehavior}：**只写状态、返回事件位**，刷战斗部、扣血、撒粒子都是
 * {@code Game} 的事。"这枚导弹会不会追不上目标还越转越快"这种问题必须能在单测里跑 200 步钉死。
 *
 * ⚠ 与 {@code EnemyBehavior} 唯一的结构差别：本类**有状态**（一张 occupancy 位图 + 帧号）。
 * 目标互斥是派生的——占用与否一律不写在敌人身上，而是每帧从活跃导弹重建（见 {@link #beginFrame}）。
 * 所以它不能是静态工具类，必须由 {@code Game} 持一个实例，并按 {@code enemies.capacity()} 构造。
 *
 * 三个设计决定，都是被具体故障逼出来的：
 *
 * <ul>
 *   <li><b>不存角度。</b>弹体轴 = 当前速度方向，导引时直接旋转向量。全类没有任何一个"角度"变量，
 *       于是"弧度轴上写 {@code + 90f}"那一类错误（{@code WarheadRules} 类注释里那条 116.6° 事故）
 *       在结构上无处发生。代价是必须挡住零速：见 {@link #advance} 开头那句抛。</li>
 *   <li><b>互斥用派生、不用计数器。</b>撤销点实在太多——引爆 / 自爆 / 回收 / 满池淘汰 /
 *       再锁换目标 / 目标先死 / {@code resetRun} 一共七处，漏一处就是"该目标永久不可锁且没有任何
 *       报错"。位图每帧从零重建 ⇒ **没有需要撤销的状态**，结构上不可能泄漏。</li>
 *   <li><b>目标引用带代际戳。</b>导弹持的是敌人的 **obj 槽号**（不是活跃表下标——末位交换只动
 *       {@code active[]}，槽号在敌人存活期内才稳定）。但槽会被 free-list 复用 ⇒ 光有槽号会
 *       悬垂到"后来生成进同一槽的新敌人"，必须 {@code slot} 与 {@link Enemies.Enemy#born} 同时相等。</li>
 * </ul>
 *
 * 每枚弹每帧的开销：一趟敌人遍历（≤ {@code Balance.wave.maxAlive} = 26，含引信与视场两件事）、
 * 一次 sqrt（弹速）、锁定时再多一次 sqrt + 1~3 次三角。⚠ **索敌不走 {@link SpatialGrid}**：
 * {@code query} 的覆盖半径只有 {@code ring×CELL = 48px}，而导引头的射程动辄几百像素——网格只会
 * 给出"视野内的一小撮"，那是**静默漏锁**。直接扫活跃表既正确又比网格便宜（26 个候选 vs 建表+查询）。
 * 网格只留给战斗部的近距接触采样（那条覆盖够，见 {@link WarheadRules#sampleContact}）。
 */
public final class MissileBehavior {

    public static final int FLAG_NONE = 0;

    /** 开眼后导引头**首次**锁定（{@code NO_TARGET} → 持有目标）。闭眼那一段不产生这条：那段不扫描。 */
    public static final int FLAG_ACQUIRED = 1;
    /** 导引头换锁到一个**不同的**目标。 */
    public static final int FLAG_RELOCKED = 2;
    /**
     * 再次点火（方案里编号 R9）：他自敲的原话「锁定最近的目标，再次点火」。
     * 证据 = 记录 L14750（kind=feedback，UTC 2026-09-25T09:19:51Z / 本地 09-25 17:19，
     * 导弹仿真的整段需求都是他这一条自敲的字）；本文件下文再指这条一律短回写作「L14750」。
     * ⚠ 与 {@link #FLAG_RELOCKED} **同批置位**这层是我的实现选择，他没有对应措辞。
     * ⚠ 这一位**现在是买出来的**：L41661 第一条给他自己的原话加了括号「（如果没有二次点火卡就没有
     * 再次点火）」（UTC 2026-09-29T19:35:24.098Z＝本地 09-30 03:35:24）⇒ 只有买了二次点火卡才会出现，
     * 基础弹只锁不点。开关读 {@link ShopRun#reIgnitionOn()}，每帧在 {@link #beginFrame} 解析一次。
     */
    public static final int FLAG_IGNITED = 4;
    /** 引信 / 撞敌：展开战斗部，该发记一次命中。 */
    public static final int FLAG_DETONATE = 8;
    /** 丢了打下去的意义：自爆（普通弹 = 视场空太久；格斗弹 = 脱锁）。**同样**展开战斗部，但不记命中。 */
    public static final int FLAG_SELF_DESTRUCT = 16;
    /** 寿命到点或飞出画面：静默回收，不展开战斗部。 */
    public static final int FLAG_GONE = 32;

    /** {@link #scanFoes} 的输出：{@code [0]} = 引信碰到的槽号，{@code [1]} = 视场内最近可锁槽号。 */
    private static final int OUT_FUSED = 0;
    private static final int OUT_PICKED = 1;

    /**
     * 本实例驱动的是**哪一流弹**。他裁「弹体池建议分家」＋「跨池不排他是工程设计」（逐字见
     * {@link Balance.Missile} 的格斗弹那一段，第一手 L37356，UTC 2026-09-28T23:52:47.500Z＝本地
     * 09-29 07:52:47）⇒ 两流各自一个池、各自一个本类实例、各自一份 {@link #occupiedStamp}。
     *
     * <p>为什么做成**实例的身份**而不是弹上的一个布尔：占用位图与四个有效值本来就是实例状态，
     * 挂一个布尔到弹上就得让每个读取点都判一次流别——那是把"两个名词"重新焊回"一个名词的两个值"，
     * 也正是 {@link Missiles.Missile#boundSlot} 与 {@link Missiles.Missile#targetSlot} 必须分立
     * 的同一条理由。分家之后"普通弹锁着的敌机，格斗弹照样能锁"结构性成立。
     */
    public static final int STREAM_STANDARD = 0;
    /** 格斗弹那一流：自带高过载与高舵效、不吃二次点火、**生来带锁**（装配点见 {@link #armDogfight}）、
     *  全程锁不换目标、手里一空就自爆（口径三条各有所指，逐字见 {@link Balance.Missile} 的格斗弹段）。 */
    public static final int STREAM_DOGFIGHT = 1;

    /** 按敌人 obj 槽号索引的占用戳。槽号 &lt; 本数组长度那条前提由 {@link #beginFrame} 当场断言。 */
    private final int[] occupiedStamp;
    private final int[] scanOut = new int[2];
    private final int stream;
    private int frame;

    /** 每帧从 spec 现算一次的四个数：省掉每枚弹各自两次三角一次除法，也保证全帧用同一份。 */
    private float radarCos2 = -1f;
    /**
     * 开眼之后"看得着谁"那个扇形的半角余弦平方。**两流各自的扇形在这里解析**：标准流是导引头
     * （{@code seekerHalfDeg} ＋ 机动过载卡的夹角加成），格斗流是**发射判据那一条扇形本身**
     * （见 {@link #launchCos2} 那条换算）。
     */
    private float seekerCos2 = -1f;
    /**
     * 格斗弹**发射扇形**的半角余弦平方：{@link #tryLaunch} 的判据，也是格斗流 {@link #seekerCos2}
     * 的值。他的字是「机头前半径50、圆心角60度的扇形」——**一条**扇形，发射与发射后的导引共用它，
     * 所以这里只换算一次，两处的值结构性相同。
     */
    private float launchCos2 = -1f;
    private float balanceSpeed = -1f;
    /**
     * 本帧的**有效**导引头半径／最大过载／导航常数／动力段／两个开关：出厂值 ＋ 簇 II 各卡的增量，
     * 只在 {@link #beginFrame} 里解析一次，{@link #advance} 全程只读这几个字段。
     *
     * <p>为什么解析点在行为类而不在 {@code Game} 或 {@code ShopRun}：这三个量原本是
     * {@code beginFrame} 逐帧从 spec 重算的（{@code radarCos2} 那三个），卡加成走同一条路
     * 才能保住"热改一档数值下一帧就生效"和"全帧同一份"两条；反过来若在开火端把加成写进
     * {@code Balance.missile}，那是把**局内成长**烙进全局配置表——下一局开局、以及只读 spec
     * 的单测都会拿到上一局的值。
     *
     * <p>⚠ 这一组数**按本实例的 {@link #stream} 解析**，不按弹解析：两流分家之后一枚弹属于哪一流
     * 由"它在哪个池里"唯一决定，所以本帧这一组数对池里每一枚都成立。把它改成"每枚弹各自一组"
     * 就等于把分家又合回去，{@link #STREAM_STANDARD} 那条注释说的正是这件事。
     */
    private float seekerRangeEff = -1f;
    private float maxLatAccelEff = -1f;
    private float navEff = -1f;
    private float boostEff = -1f;
    private boolean reIgnite;
    private boolean midCourse;
    /**
     * 这一流的锁**换不换目标**。一条设定同时管着两件事，因为它们是同一句话的两面：
     *
     * <ul>
     *   <li><b>true（普通弹）</b>：开眼之后每帧都在视场里挑最近的可锁目标，所以会有
     *       "锁上了又换一只"（{@link #FLAG_RELOCKED}）与"脱锁滑行后再锁"（他的四段设计）。</li>
     *   <li><b>false（格斗弹）</b>：他逐字「格斗弹还有应该全程锁」（L37155）⇒ 一生只认发射时
     *       那一只（{@link #armDogfight} 写进去的那只），扫描只保留**引信**那一半；
     *       同一条设定的另一面是「脱锁应直接自爆」（L37108）⇒ 手里空了就是目标死了，
     *       不必等那 0.5s 的计时（那条在 {@code L37108} 同一条消息里被他明划成普通弹的规则：
     *       「两次锁定只在普通导弹上」）。两半的落点见 {@link #retirementOf}。</li>
     * </ul>
     */
    private boolean reacquire;

    public MissileBehavior(int enemyCapacity) {
        this(enemyCapacity, STREAM_STANDARD);
    }

    /**
     * @param stream {@link #STREAM_STANDARD}（默认那一流，读 {@link Balance.Missile} 的出厂档
     *               ＋ 卡的增量）或 {@link #STREAM_DOGFIGHT}（读 {@code Balance.Missile} 里带
     *               {@code dogfight} 前缀的那一组自带档，见 {@link #beginFrame} 那个分支）。越界一律抛：
     *               静默按标准档跑的话，"这枚弹其实没吃到它该吃的那一档"是查不出来的那种错。
     */
    public MissileBehavior(int enemyCapacity, int stream) {
        if (enemyCapacity <= 0) throw new IllegalArgumentException("enemyCapacity must be > 0");
        if (stream != STREAM_STANDARD && stream != STREAM_DOGFIGHT) {
            // 消息写英文：EmbeddedFontTest 只要求**上屏**的字面量有字模，异常消息进 logcat。
            throw new IllegalArgumentException("unknown missile stream: " + stream
                    + " -- expected STREAM_STANDARD(0) or STREAM_DOGFIGHT(1)");
        }
        this.stream = stream;
        occupiedStamp = new int[enemyCapacity];
    }

    /** 本实例是哪一流（测试与 {@code Game} 的记账要用它分清两池）。 */
    public int stream() {
        return stream;
    }

    /**
     * 每帧一次：帧号自增，并把"哪些敌人槽已被别的导弹占着"从零重建。
     *
     * <p>O(活跃导弹数) 一次遍历、零分配。之所以能不清空数组：判占用读的是
     * {@code stamp[slot] == frame}，下一帧帧号不同 ⇒ 旧戳自动失效。
     *
     * @param foes 用来断言容量匹配——{@link #advance} 会拿敌人的 obj 槽号来索引本类的数组，
     *             两者容量不同就是数组越界，这条不该靠调用方记住
     * @param run  本局的商店持有量：簇 II 那**三张导引卡**的加成在这里换算成有效值，全场只读这一次。
     *             ⚠ 第四张（格斗导弹）在这里**没有任何读数**：它买的是一整个流与一个发射判据，
     *             自带的那组参数在 {@link Balance.Missile} 的 {@code dogfight*} 前缀里，买断后无级可叠
     *             （开关读 {@link ShopRun#dogfightOn()}，落点在 {@code Game} 的出弹处）
     */
    public void beginFrame(Missiles missiles, Enemies foes, Balance.Missile spec, ShopRun run) {
        if (foes.capacity() != occupiedStamp.length) {
            throw new IllegalStateException("enemy capacity moved: pool " + foes.capacity()
                    + " but this behavior was built for " + occupiedStamp.length);
        }
        frame++;
        if (frame == 0) {           // 2^32 帧一次：不清就会把上一轮的旧戳当成本帧的占用
            for (int i = 0; i < occupiedStamp.length; i++) occupiedStamp[i] = 0;
            frame = 1;
        }
        // 射前锥**不跟涨**：它的半角是 L38831 那句「无限长半径的扇形区域」里的形状，本来就没有
        // 半径可言，把机动过载卡的角度加成加在它上面会让"能不能出弹"变成成长量——那是另一件事。
        radarCos2 = cos2(spec.radarHalfDeg);
        // 格斗弹的发射扇形同样不跟涨（理由与上一条同形，见 Balance.Missile.dogfightHalfDeg 那条口径）：
        // 让成长卡决定"能不能自动出弹"是另一件事，本仓今天没有那张卡。
        launchCos2 = cos2(spec.dogfightHalfDeg);
        balanceSpeed = spec.thrust / spec.dragK;
        if (stream == STREAM_DOGFIGHT) {
            // 它吃到的**只有**最大过载那一条卡（他的字「自带高过载…并且能叠加最大过载卡」）。
            // 二次点火那两条落点在它身上不存在：它的导引头从**装配那一刻**就开着（锁与开眼都由
            // {@link #armDogfight} 在出膛同帧写好，{@link #tryLaunch} 那条扇形就是它的视野），
            // 而机动过载卖的是**导引头**的夹角与**导引头**的半径——
            // 那是普通弹"开眼之后"的那条扇形，与格斗弹发射判据这条是两个名词、两个字段。
            seekerCos2 = launchCos2;
            seekerRangeEff = spec.dogfightRange;
            maxLatAccelEff = spec.dogfightLatAccel + run.latAccelBonus();
            navEff = spec.dogfightNavConstant;
            boostEff = spec.dogfightBoostSec;
            reIgnite = false;          // 「没有二次点火卡就没有再次点火」对它更是天然成立
            midCourse = true;          // 「引导为持续引导」：它本来就没有闭眼段
            reacquire = false;         // 「全程锁」（L37155），两面共用这一条，见字段注释
        } else {
            // 机动过载卖的是**总夹角**（他「收」的口径 60°→84°），比较用半角，所以折半进来。
            seekerCos2 = cos2(spec.seekerHalfDeg + 0.5f * run.seekerArcDegBonus());
            seekerRangeEff = spec.seekerRange + run.seekerRangeBonus();
            maxLatAccelEff = spec.maxLatAccel + run.latAccelBonus();
            navEff = spec.navConstant;
            boostEff = spec.boostSec;
            reIgnite = run.reIgnitionOn();
            midCourse = run.midCourseOn();
            reacquire = true;
        }
        for (int i = 0; i < missiles.activeCount(); i++) {
            int slot = missiles.activeAt(i).targetSlot;
            if (slot >= 0) occupiedStamp[slot] = frame;
        }
    }

    /**
     * 格斗弹的**发射判据**（只有 {@link #STREAM_DOGFIGHT} 的实例有意义）：机头前那条
     * 「半径 50、圆心角 60 度的扇形」（他的逐字口径，见 {@link Balance.Missile#dogfightRange}）里
     * 有没有**还没被本流锁走**的目标；有则给出其中最近那一只的敌人 obj 槽号。
     *
     * <p>三条判据各自挡住一种失效：
     * <ul>
     *   <li><b>扇形</b>（轴 = 常量屏幕正上方、半角 {@link Balance.Missile#dogfightHalfDeg}、
     *       半径 {@link Balance.Missile#dogfightRange}）＝"打得着才发射"。没有这条，买了卡的玩家
     *       会朝着一整屏的敌人每帧出弹，那把"近战补位"变成了第二把主炮。</li>
     *   <li><b>跳过被本流占用的槽</b>＝一敌一锁。这条是**整个发射端没有节拍字段**的全部理由：
     *       同一只敌机在被打掉那份锁之前不会再吃一枚，所以场上格斗弹的数量自限在
     *       {@code Balance.wave.maxAlive} 一条线上（容量推导见 {@link Balance.Missile#dogfightCapacity}）。
     *       占用位图只读本池的 {@code targetSlot} ⇒ 普通弹锁着的目标**照样能发射**，那条是他裁的
     *       工程设计（「跨池不排他是工程设计」，L37356）。</li>
     *   <li><b>{@code hp > 0}</b>＝血已归零但还没摘表的那只不算目标，与 {@link #scanFoes} 同一条口径。</li>
     * </ul>
     *
     * <p>取**最近**那只（不是"扇形里第一个遇到的"）：格斗弹卖的是近身补位，跨过眼前这只去打后面那只
     * 是反着读需求。这条排序是我的实现，他只说了"最近的目标"那一半（L14750 说的是锁定端）。
     *
     * <p>⚠ 必须排在 {@link #beginFrame} 之后调用（读的是本帧的占用戳与 {@link #launchCos2}），
     * 并且**排在本流全部 {@code advance} 之后**才自洽：{@code advance} 里的 {@link #acquire} 会当场
     * 回填本帧的戳，晚一步发射就能看见"这一帧刚被锁走的那些"，否则同一帧会朝同一只出两枚。
     *
     * @param x,y 机头位置（发射点）——扇形圆心，与 {@code Game} 出膛点同一个数
     * @return 可发射目标的槽号；扇形内没有可锁目标 ⇒ -1，调用方据此**不发**（不是"随便发一枚"）
     */
    public int tryLaunch(float x, float y, Enemies foes, Balance.Missile spec) {
        if (stream != STREAM_DOGFIGHT) {
            // 消息写英文：EmbeddedFontTest 只要求**上屏**的字面量有字模。
            throw new IllegalStateException("tryLaunch is the dogfight stream's launch gate, but this "
                    + "behavior is stream " + stream);
        }
        if (launchCos2 < 0f) {
            throw new IllegalStateException("MissileBehavior.beginFrame was not called this frame");
        }
        float maxD2 = spec.dogfightRange * spec.dogfightRange;
        int best = -1;
        float bestD2 = Float.MAX_VALUE;
        for (int i = 0; i < foes.activeCount(); i++) {
            Enemies.Enemy e = foes.activeAt(i);
            if (e == null || e.hp <= 0) continue;
            int slot = foes.slotOfActive(i);
            if (occupiedStamp[slot] == frame) continue;                 // 本流已经有一只弹锁着它
            float dx = e.x - x;
            float dy = e.y - y;
            float d2 = dx * dx + dy * dy;
            if (d2 > maxD2) continue;
            // 锥轴是**常量屏幕正上方**：与射前雷达同一条口径，机头不随机体转（见 dogfightHalfDeg）。
            if (!inCone(0f, -1f, launchCos2, dx, dy, d2)) continue;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = slot;
            }
        }
        return best;
    }

    /**
     * 把 {@link #tryLaunch} 挑出的那一只**装进**刚出膛的格斗弹：落锁 + 开眼，同一刻、不等下一帧。
     *
     * <p>为什么要有这个方法，而不是让弹带着 {@code RADAR_PENDING} 出膛、由 {@link #advance} 自己补锁：
     * 他那三条口径合起来把"补锁"这条路判死了——
     * <ul>
     *   <li>L36897「锁定最近的敌机」＝锁在**发射那一刻**由火控解出，不是弹进视场才发现；</li>
     *   <li>L37468「导弹在接近后才开眼，怎么判断是否开眼？**战机判断**」＝开眼权在发射方，
     *       弹体自己没有"要不要开眼"这条判断；</li>
     *   <li>L37155「格斗弹还有应该全程锁」＝从出膛到命中手里必须有目标，走"下一帧补做"那条路
     *       就凭空造出一帧没锁的格斗弹。</li>
     * </ul>
     * 于是装配点必须与发射点同帧同类，这个方法就是那唯一的装配点；{@code advance} 里遇到裸弹
     * 直接抛（见那里那条注释），不静默兜底。
     *
     * <p>{@code seekerOpen = true} 写在 {@link #acquire} 之前：两者都在本步内生效，先后无观察差异，
     * 但读起来是"先开眼、再落锁"这条 L37468 的因果。开眼之后引信、导引、逐帧追锁全部照常，
     * 差别只由 {@code stream} 在 {@link #beginFrame} 里解析出来的那组有效值给出。
     *
     * @param slot 必须是 {@link #tryLaunch} 本帧返回的槽号（≥0）；传 -1 是调用方的判空漏了，直接炸
     */
    public void armDogfight(Missiles.Missile m, Enemies foes, int slot) {
        if (stream != STREAM_DOGFIGHT) {
            // 消息写英文：EmbeddedFontTest 只要求**上屏**的字面量有字模。
            throw new IllegalStateException("armDogfight belongs to the dogfight stream, but this "
                    + "behavior is stream " + stream);
        }
        if (slot < 0) {
            throw new IllegalArgumentException("cannot arm a dogfight missile with slot " + slot
                    + " -- tryLaunch returns -1 when nothing is lockable, and the caller must not spawn then");
        }
        m.seekerOpen = true;
        acquire(m, foes, slot);
    }

    /**
     * 推进一枚导弹一步。
     *
     * <p>⚠ 判序**写死**在这里，不靠状态机图的行序：① 引信 → ② 视场空自爆 → ③ 回收 → ④ 重新索敌。
     * 状态机图把"再锁 → 引信 → 自爆 → GONE"画成从上到下的四条箭头，照那个顺序实现的话，一枚已经
     * 贴到敌人脸上的弹，若同一帧视场里还有个更近的可锁敌人，就会走"再次点火"分支、从眼前的敌人身上
     * 掠过去。L14750 里他自敲的「锁定最近的目标，再次点火，命中接近后展开连续杆战斗部」与「若视场内
     * 无敌人则自爆」是**一枚弹的生命周期叙述**：再锁、命中、自爆各有各的触发语，叙述的先后并不等于
     * 每帧谁先判。⚠ 把叙述顺序换算成 if-else 优先级、以及"命中即返回"这条规矩，都是我的推导，他没有
     * 对应措辞。所以这里反过来排：①②③ 任一命中即本步结束；④ 只在前三条都不成立时才做。
     *
     * <p>③ 排在 ① 之后是刻意的：寿命到点**又**已在引信距离的弹该展开战斗部，而不是静默消失。
     *
     * <p>②③ 两步现在合在 {@link #retirementOf} 一处回答（L37468 要的"集中判断"），本方法的判序不变：
     * 引信先、退场次、重新索敌最后。
     *
     * @param margin 出界余量，与 {@code BulletPool.stepAndCompact} 同一个口径
     * @return {@link #FLAG_NONE} 或上述事件位的位或；调用方见 {@link #FLAG_DETONATE}
     *         {@link #FLAG_SELF_DESTRUCT} {@link #FLAG_GONE} 三者之一就把这枚移出池
     */
    public int advance(Missiles.Missile m, Balance.Missile spec, float dt, Enemies foes,
                       int logicW, int logicH, int margin) {
        int flags = FLAG_NONE;
        if (balanceSpeed < 0f) {
            // 本帧的缓存值只在 beginFrame 里算。漏调的话后果是"视场变成全向"加"速率被夹成 -1"，
            // 那种错读起来像物理写坏了，所以在这里当场炸，不留给下游猜。
            throw new IllegalStateException("MissileBehavior.beginFrame was not called this frame");
        }
        m.px = m.x;
        m.py = m.y;
        m.life += dt;

        float speed = (float) Math.sqrt(m.vx * m.vx + m.vy * m.vy);
        if (speed <= 0f) {
            // 弹体轴 = 速度方向。零速时方向无定义，而这里**没有任何东西会报错**：不抛的话
            // 下一步会安静地把弹轴当成 (1,0)，一枚该往上飞的弹从此朝屏幕正右方飞。
            // ⚠ 下面这条消息写英文是有意的：EmbeddedFontTest 扫 src/main/java 的**全部**字符串
            // 字面量并要求内嵌点阵子集里有字模，而异常消息只进 logcat、永不上屏。照中文写会红那道闸门。
            throw new IllegalStateException("missile without velocity: seq=" + m.seq
                    + " vx=" + m.vx + " vy=" + m.vy
                    + " -- muzzle speed reads Balance.weapons[...].bulletSpeed, so a zero there"
                    + " is a table misconfiguration, not 'this one fires nothing'");
        }
        float ax = m.vx / speed;
        float ay = m.vy / speed;

        // ---- 射前雷达 ＋ 装订发射单元 ---------------------------------------------------
        // 出膛那一刻没做：发射方是 BulletChain，它不认识敌人，也不该认识（换枪/买卡那三条触发器
        // 是它全部的输入）。所以出膛先挂 RADAR_PENDING，由本方法在下一帧补做——延迟一个固定步
        // (1/60s ≈ 16ms)，玩家看不出来，而锥轴仍是常量屏幕正上方（机头不随机体转）。
        // ⚠ 这一帧只**装订**、不**锁定**：他的四段设计（L38831 plain，UTC 2026-09-29T13:21:15.566Z）
        // 把"锁定"排在第四段「发现后就转向，开眼，锁定」，而 L40027（plain，UTC 16:01:19.845Z＝本地
        // 09-30 00:01:19）裁「mode0明显是设计啊…要改mode1为0」＝装订段一路直飞。于是这一段**不占目标**
        // （不写 occupiedStamp）：场上可以有若干枚弹朝同一个装订点飞，进圈之后才竞争。
        // ⚠ 以上整段只描述**普通弹**。格斗弹根本不会带着 RADAR_PENDING 进到这里（锁在发射那一刻
        // 就由火控写好），所以对它而言这条 if 是恒假的；下面那个分支体就是用来兜住"漏装配"的。
        if (m.targetSlot == Missiles.Missile.RADAR_PENDING) {
            if (stream == STREAM_DOGFIGHT) {
                // 格斗弹**没有闭眼段**，也就没有"出膛先挂 RADAR_PENDING、下一帧补做"这回事：
                // 目标由战机火控在发射那一刻解出并写进弹体（{@link #armDogfight}），见 L36897
                // 「若机头前半径50、圆心角60度的扇形内有没有被格斗弹锁定的敌机，锁定最近的敌机」
                // 与 L37468「战机判断是否开眼」。所以走到这里 = 发射方漏装配，是**编程错误**而不是
                // 一种运行状态 —— 绝不静默补锁：那样会把"每枚格斗弹生来带锁"这条设定做成概率事件。
                throw new IllegalStateException("dogfight missile reached advance unarmed: seq=" + m.seq
                        + " -- the launcher must call armDogfight right after spawn");
            }
            m.targetSlot = Missiles.Missile.NO_TARGET;
            scanFoes(m, foes, 0f, -1f, true, radarCos2, spec, Float.POSITIVE_INFINITY);
            int slot = scanOut[OUT_PICKED];
            if (slot >= 0) {
                Enemies.Enemy bound = foes.objAt(slot);
                bind(m, bound, spec, dt);
                m.boundSlot = slot;     // 中段引导要逐帧追的就是这一只（不占目标，见字段注释）
                m.boundBorn = bound.born;
                ax = m.vx / speed;      // 装订改写了弹轴，本步后面的积分必须用新轴
                ay = m.vy / speed;
            }
        }

        // ---- 相位：只管推不推力（L41661 之后它**不再**管索敌；空视场计时读的也是"眼"）----------
        if (m.phase == Missiles.Missile.PH_BOOST) {
            m.boostT += dt;
            if (m.boostT >= boostEff) {
                m.phase = Missiles.Missile.PH_COAST;
                m.boostT = boostEff;       // 夹住，别让"上一段还剩多久"变成负数
            }
        }

        Enemies.Enemy tgt = liveTarget(m, foes);
        boolean coasting = m.phase == Missiles.Missile.PH_COAST;
        if (m.seekerOpen) {                 // 闭眼的装订段本来就不看 ⇒ 不累空视场，否则装订段一长就出膛即自爆
            if (tgt == null) m.seekT += dt;
            else m.seekT = 0f;
        }

        // ---- 导引：比例导引（L41661「预测模型换比例导引」）。只改方向，不改速率 --------------------------------
        // 旧律是纯追踪：把弹轴**对到目标此刻的位置**，那条只收敛到"视线本身"＝尾追，追不上横向
        // 速度高的怪。PN 收敛到**常值视线角**＝提前量，这才是他要的"预测"。
        //   λ̇ = (r × v_rel) ÷ |r|²   —— 视线角速率，由相对运动学**解析**给出，不存上一步的视线角
        //   V_c = −(r · v_rel) ÷ |r|  —— 接近速度
        //   a_cmd = N′ · V_c · λ̇  ⇒  固定弹速下 ω_cmd = a_cmd ÷ |v| = N′ · (V_c ÷ |v|) · λ̇
        // 符号约定与旧的旋转一致：这里的双叉积 {@code rx·rvy − ry·rvx} 与下面旋转矩阵
        // {@code (ax·cos t − ay·sin t, ax·sin t + ay·cos t)} 是同一个正方向，不用换号。
        //
        // ⚠ 开眼段导引到**锁到的目标**，闭眼段只有在买了中段引导之后才导引，且对象是**装订的那一只**
        // （{@link Missiles.Missile#boundSlot}）：它不扫描、不置 seekerOpen、不进占用位图，买的
        // 只是"别再按出膛方向直飞"。无卡时闭眼段仍然是 mode0 那句「一路直飞」（L40027）。
        Enemies.Enemy steer = m.seekerOpen ? tgt : (midCourse ? boundTarget(m, foes) : null);
        if (steer != null && !m.seekerOpen) reunit(m, steer, spec, dt);
        if (steer != null) {
            float rx = steer.x - m.x;
            float ry = steer.y - m.y;
            float d2 = rx * rx + ry * ry;
            if (d2 > 0f) {                     // 完全重合时视线未定义：本步不转，下一帧再算
                float d = (float) Math.sqrt(d2);
                float rvx = steer.vx - m.vx;     // 相对速度＝目标减弹
                float rvy = steer.vy - m.vy;
                float closing = -(rx * rvx + ry * rvy) / d;
                // V_c ≤ 0 ⇒ 这一拍根本在拉开距离：指令归零、直着飞，由 maxLife/出界收尾。
                // ⚠ 这条退化是我的实现选择（PN 的标准形式在 V_c<0 时会把指令**翻号**，那在弹海流里
                // 会表现为"目标一减速导弹就反着拐"），他没有对应措辞。
                if (closing > 0f) {
                    // ω_max 的分母下界不是装饰：一枚 B3 弹滑行 2.0s 后按线性阻力只剩 60px/s，
                    // 届时 ω_max = 8.7rad/s ⇒ 每步转 8.3°，导弹原地变成钻头。下面 :231 那条速度
                    // clamp 已经把 |v| 兜在 stallSpeed 上，这里同一道闸再走一遍：它读的是**上一步**
                    // 的速度，万一出膛速度本身低于地板（不变式① 现在不让，将来可能），
                    // 第一帧就会用到那个未经 clamp 的分母。
                    float vFloor = Math.max(speed, spec.stallSpeed);
                    float lamDot = (rx * rvy - ry * rvx) / d2;
                    float omega = navEff * (closing / vFloor) * lamDot;
                    float omegaMax = maxLatAccelEff / vFloor;   // 过载上限＝「机动过载」那张卡卖的旋钮
                    if (omega > omegaMax) omega = omegaMax;
                    else if (omega < -omegaMax) omega = -omegaMax;
                    float t = omega * dt;
                    float c = (float) Math.cos(t);
                    float s = (float) Math.sin(t);
                    float nx = ax * c - ay * s;
                    float ny = ax * s + ay * c;
                    float nl = (float) Math.sqrt(nx * nx + ny * ny);    // 归一化：float 误差会慢慢改掉速率
                    ax = nx / nl;
                    ay = ny / nl;
                }
            }
        }

        // ---- 速率：推力只沿弹轴，阻力线性，两头夹住 --------------------------------------
        float sp = speed + (coasting ? 0f : spec.thrust * dt) - spec.dragK * speed * dt;
        if (sp < spec.stallSpeed) sp = spec.stallSpeed;
        if (sp > balanceSpeed) sp = balanceSpeed;
        m.vx = ax * sp;
        m.vy = ay * sp;
        m.x += m.vx * dt;
        m.y += m.vy * dt;

        // ---- ④ 开眼门：抵达发射单元描述的位置 ----------------------------------------------
        // 「在抵达发射单元描述的位置后扫描附近敌机」（L38831）。判据是"进了半径 50 的圈"，不是
        // "沿装订轴越过了 P"：B3 那档每步位移 12.5~16px 已经大于引信半径 9.5px，过点那种判据
        // 在采样粒度上就结构性采不到（实测开眼后 4/4 致命落空，见池件 §25.1 D1）。
        if (!m.seekerOpen && m.unitSet) {
            float ux = m.unitX - m.x;
            float uy = m.unitY - m.y;
            if (ux * ux + uy * uy <= seekerRangeEff * seekerRangeEff) m.seekerOpen = true;
        }

        // ---- 三路判据（顺序见方法头）----------------------------------------------------
        // 引信不分相位（装订段直接撞上敌机也算撞，"直接撞敌"是三条引爆路之一）；导引头扫描只读**开没开眼**
        // ——闭眼那一段不索敌是 mode0 的本义，而"抵达装订点即扫描"**不看相位**：L41661 第一行
        // 「在抵达发射单元描述的位置后扫描附近敌机，发现后就转向，开眼，锁定最近的目标」。
        // ⚠ 旧码在这里还多要一道 `coasting`（那是我按 R9「再次点火」补的推导），实测它把第四段饿死：
        // B3 档 48 发的导引步数总和为 0（池件 §28.2）⇒ 该半条作废。
        // ⚠ 搜索那半边还要 `reacquire`：格斗弹是**全程锁**（L37155），开着眼也不许换目标。这道闸
        // 只掐 OUT_PICKED，引信那半边不受影响（scanFoes 里 `if (!seek) continue;` 排在 fused 之后）——
        // 所以脱锁前贴着目标打中照打，⑤ 那段对它恒为空转，不必再补一条流别判断。
        scanFoes(m, foes, ax, ay, m.seekerOpen && reacquire, seekerCos2, spec,
                seekerRangeEff * seekerRangeEff);
        if (scanOut[OUT_FUSED] >= 0) return flags | FLAG_DETONATE;
        int retire = retirementOf(m, spec, logicW, logicH, margin);
        if (retire != FLAG_NONE) return flags | retire;

        // ---- ⑤ 索敌锁定（＋按卡决定是否再次点火）------------------------------------------
        if (m.seekerOpen) {
            int slot = scanOut[OUT_PICKED];
            if (slot >= 0 && slot != m.targetSlot) {
                boolean first = m.targetSlot == Missiles.Missile.NO_TARGET;
                acquire(m, foes, slot);
                m.seekT = 0f;
                // 开眼后第一次"发现"记 ACQUIRED，换目标才记 RELOCKED。
                flags |= first ? FLAG_ACQUIRED : FLAG_RELOCKED;
                // 「再次点火」现在是**买出来的**：L41661 第一行括号里那句「如果没有二次点火卡就没有再次
                // 点火」。开关 = {@link ShopRun#reIgnitionOn()}，本帧在 beginFrame 解析成 reIgnite。
                if (reIgnite) {
                    m.phase = Missiles.Missile.PH_BOOST;
                    m.boostT = 0f;
                    flags |= FLAG_IGNITED;
                }
            }
        }
        return flags;
    }

    /** {@link #advance} 的读法用：这一步之后该把这枚弹移出池吗（三条引爆/回收路共用一条）。 */
    public static boolean isRetired(int flags) {
        return (flags & (FLAG_DETONATE | FLAG_SELF_DESTRUCT | FLAG_GONE)) != 0;
    }

    // ---- 内部 -----------------------------------------------------------------------------

    /**
     * **集中**回答"这枚弹本步该不该退场、以什么方式退场"，返回 {@link #FLAG_NONE} 表示不退。
     * 他的原话（L37468 plain，UTC 2026-09-29T00:17:58.473Z＝本地 08:17:58）：「所以应该有个
     * 集中判断一个导弹是否应该自毁的逻辑，这个与导弹开眼后是否自爆无关」——
     * 这句点的正是"各判各的会漏"：开眼后自爆是**导引头**的判据（视场里没人），而"该不该退场"
     * 本来还有一串与视场无关的原因（寿命、出界）。原先这四条并列在 {@code advance} 尾部，
     * 格斗弹要的"脱锁即自爆"再塞进去就是第五条 if ⇒ 收成一个方法。
     *
     * <p>两条脱锁路的**口径不同**，也是他分开裁的：
     * <ul>
     *   <li><b>普通弹</b>读 {@code seekT}：L37155「发射锁一次，然后脱锁滑行，接近后二锁，如果一锁
     *       没锁上一样是滑行时脱锁，结论是一个」⇒ "空着"要**持续**
     *       {@link Balance.Missile#seekTimeoutSec} 才判死，因为脱锁滑行本来就是它的中段。</li>
     *   <li><b>格斗弹</b>读 {@code targetSlot}：L37108「格斗弹脱锁应直接自爆」＋ L37155「格斗弹
     *       还有应该全程锁」⇒ 手里一空就是脱锁，**同帧**判死，不计时。</li>
     * </ul>
     * 分这两路用的开关就是 {@link #reacquire}：它已经是"这条流允不允许换新目标"的唯一读数，
     * 一条设定管两面（不换手 ＋ 空了就自爆）。再开一个布尔给它做恒反影子是多余的设计。
     *
     * <p>自爆与静默销毁的分工照旧：{@link #FLAG_SELF_DESTRUCT} 是"锁丢了、按失效表现收场"，
     * {@link #FLAG_GONE} 是"没打成就回收"。寿命与出界两条对两种流同口径——它们回答的是"这枚弹
     * 打完了"，不是"它的锁丢了"。
     */
    private int retirementOf(Missiles.Missile m, Balance.Missile spec,
                             int logicW, int logicH, int margin) {
        if (reacquire) {
            if (m.seekT >= spec.seekTimeoutSec) return FLAG_SELF_DESTRUCT;
        } else if (m.targetSlot == Missiles.Missile.NO_TARGET) {
            return FLAG_SELF_DESTRUCT;         // 格斗弹：目标一没（含被同帧的 liveTarget 摘掉）就死
        }
        if (m.life >= spec.maxLifeSec) return FLAG_GONE;
        if (m.x < -margin || m.x > logicW + margin || m.y < -margin || m.y > logicH + margin) {
            return FLAG_GONE;
        }
        return FLAG_NONE;
    }

    /**
     * 一趟敌人遍历同时给出两件事：**引信碰到的敌人**与**视场内最近的可锁敌人**。
     * 合成一趟是因为分开就是每帧两遍 26 个敌人；两件事用的距离本来就要各算一次。
     *
     * @param ax,ay    方向轴（射前 = 屏幕正上方常量；导引头 = 当前速度单位向量）
     * @param seek     false = 这一段不搜索（{@code outPicked} 恒为 -1）。**现在只有"闭眼"这一件事能让它
     *                 为 false**：L41661 第一行「在抵达发射单元描述的位置后扫描附近敌机」把扫描门定成
     *                 开没开眼，与相位无关。⚠ 旧口径（false 也涵盖 boost 段）是我按 L14750「再次点火」
     *                 里那两字"再次"补的推导——它的出处链在 L14369（kind=answers-FREE，UTC
     *                 2026-09-25T07:34:57Z／本地 09-25 15:34，自敲「并且导弹做仿真动力段设计」）与
     *                 L14773（kind=answers，UTC 2026-09-25T09:29:00Z／本地 09-25 17:29，他挑的
     *                 「不锁定、直飞，看到再锁」讲的是**无可锁目标时**直飞）里本来就只是旁证；
     *                 实测它在 B3 档把第四段饿死（48 发导引步数总和 0，池件 §28.2）⇒ 作废。
     *                 引信那半不受它影响（"直接撞敌"是三条引爆路之一，同一条结算路走自爆）——
     *                 ⚠ "引信不分相位"从头到尾是我的推导，他没谈过引信分不分相位。
     * @param cosHalf2 半角余弦的**平方**：锥判定用 {@code dot² ≥ cos²·d²}，不开方、不调 acos
     * @param maxD2    可锁的**距离上界平方**。导引头传 {@code seekerRange²}（半径 50 的扇形，
     *                 L40027），射前雷达传 {@code +∞}——那条是他 L38831 的字面「无限长半径的扇形区域」。
     *                 引信那一半不受它影响（它本来就在贴脸的距离上）。
     */
    private void scanFoes(Missiles.Missile m, Enemies foes, float ax, float ay,
                          boolean seek, float cosHalf2, Balance.Missile spec, float maxD2) {
        int fused = -1;
        int picked = -1;
        float best = Float.MAX_VALUE;
        float fuseR = m.size * 0.5f + spec.fusePad;
        for (int i = 0; i < foes.activeCount(); i++) {
            Enemies.Enemy e = foes.activeAt(i);
            if (e == null || e.hp <= 0) continue;              // 血已归零的不算锁、也不算撞（还没摘表）
            int slot = foes.slotOfActive(i);
            float dx = e.x - m.x;
            float dy = e.y - m.y;
            float d2 = dx * dx + dy * dy;
            float rr = e.radius + fuseR;
            if (fused < 0 && d2 <= rr * rr) fused = slot;
            if (!seek) continue;
            if (d2 > maxD2) continue;
            if (slot != m.targetSlot && occupiedStamp[slot] == frame) continue;   // 被别的导弹占着 ⇒ 跳过它
            if (!inCone(ax, ay, cosHalf2, dx, dy, d2)) continue;
            if (d2 < best) {
                best = d2;
                picked = slot;
            }
        }
        scanOut[OUT_FUSED] = fused;
        scanOut[OUT_PICKED] = picked;
    }

    /**
     * 装订发射单元：算出"该沿哪条轴飞、飞到哪一点"，并把弹轴一次性对过去（速率不动）。
     *
     * <p>只读目标此刻的位置与瞬时速度（匀速 CV）——{@code EnemyBehavior} 报的就是这个瞬时值，
     * 横摆怪的下一帧加速度没人知道。用目标的**真实未来运动**解 τ\* 是上帝视角，游戏里写不出来。
     *
     * <p>⚠ CV 的代价是残差：机动怪在 τ\* 内会跑出半个正弦，实测最坏 27.9px（横摆档，池件
     * {@code reference/evidence/launchunit/out3.txt:41}）——这个数必须小于 {@code seekerRange}，
     * 否则开眼门结构性失效。50 这个圈刚好包住它，"刚好"就是这条判据的取值理由。
     *
     * <p>无解（目标快过弹、或在弹的寿命内追不上）时退化成"装订到它此刻的位置"：至少 ④ 那个圈
     * 还有个圆心，不至于变成一枚永远闭眼的弹。**这条退化是我的实现选择**，他没有对应措辞。
     */
    private void bind(Missiles.Missile m, Enemies.Enemy e, Balance.Missile spec, float dt) {
        reunit(m, e, spec, dt);
        m.unitSet = true;
        float dx = m.unitX - m.x;
        float dy = m.unitY - m.y;
        float d = (float) Math.sqrt(dx * dx + dy * dy);
        if (d > 0f) {
            float sp = (float) Math.sqrt(m.vx * m.vx + m.vy * m.vy);
            m.vx = dx / d * sp;
            m.vy = dy / d * sp;
        }
    }

    /**
     * 重解装订点：把 {@link Missiles.Missile#unitX}／{@link Missiles.Missile#unitY} 挪到目标
     * 的 CV 外推拦截点上（无解退化成它此刻的位置）。**不动弹轴**——这是它与 {@link #bind} 的全部
     * 分工：装订那一步可以一次性把轴掰过去（出膛即朝那个点飞），而中段引导买的是"飞行途中持续修正"，
     * 修正必须经过过载那道闸，否则这张卡就顺带把 {@code maxLatAccel} 变成了摆设。
     *
     * <p>为什么闭眼段也要逐帧刷：④ 那道开眼门比的是"离装订点多近"，而横摆怪在装订段里能跑出
     * 半个正弦（实测残差最坏 27.9px，见 {@link #bind} 那条）。不刷的话，中段引导把弹领到了
     * **目标**跟前，开眼门却在等它靠近一个**早已过期的圆心**——买卡反而可能更晚开眼。
     */
    private void reunit(Missiles.Missile m, Enemies.Enemy e, Balance.Missile spec, float dt) {
        float tau = leadTime(m, e.x - m.x, e.y - m.y, e.vx, e.vy, spec, dt);
        if (tau >= 0f) {
            m.unitX = e.x + e.vx * tau;
            m.unitY = e.y + e.vy * tau;
        } else {
            m.unitX = e.x;
            m.unitY = e.y;
        }
    }

    /**
     * 拦截时间 τ\*：|目标匀速外推后离出膛点多远| 第一次被"弹在 τ 内能飞多远"追平的那一刻。
     * 递推照 {@link #advance} 里那套 Euler 一步不差地重放（同样的 thrust / dragK / {@link #boostEff} /
     * 两头夹），于是"装订段能飞多远"只有一份真源——解出一个物理上飞不到的 τ\* 是没意义的。
     *
     * <p>它是**实例方法**而不是静态工具：动力段那个数现在是按流解析的有效值（格斗流的
     * {@code dogfightBoostSec} 与普通弹的 {@code boostSec} 同名不同物），从 spec 现取会解出另一流的里程。
     *
     * @return 追平的时刻（秒），弹寿命内追不上则 -1（交给 {@link #bind} 退化）。dt ≤ 0 时**不递推**，
     *         直接 -1：那是边界用例那种"把这一步的位移拿掉、只评判据"的步长，递推下去是死循环。
     */
    private float leadTime(Missiles.Missile m, float dx, float dy, float evx, float evy,
                           Balance.Missile spec, float dt) {
        if (dt <= 0f) return -1f;
        float balance = spec.thrust / spec.dragK;
        float v = (float) Math.sqrt(m.vx * m.vx + m.vy * m.vy);
        float flown = 0f;
        float t = 0f;
        float boostT = m.boostT;
        int phase = m.phase;
        int steps = (int) Math.ceil(spec.maxLifeSec / dt);
        for (int i = 1; i <= steps; i++) {
            if (phase == Missiles.Missile.PH_BOOST) {
                boostT += dt;
                if (boostT >= boostEff) {
                    phase = Missiles.Missile.PH_COAST;
                    boostT = boostEff;
                }
            }
            v += (phase == Missiles.Missile.PH_COAST ? 0f : spec.thrust * dt) - spec.dragK * v * dt;
            if (v < spec.stallSpeed) v = spec.stallSpeed;
            if (v > balance) v = balance;
            t += dt;
            flown += v * dt;
            float gx = dx + evx * t;
            float gy = dy + evy * t;
            if ((float) Math.sqrt(gx * gx + gy * gy) <= flown) return t - dt * 0.5f;
        }
        return -1f;
    }

    /**
     * 锥内：目标在轴前（dot &gt; 0）且与轴的夹角 ≤ 半角。
     * 用平方比是因为 {@code cosHalf2} 已经预计算，比较时不必对每个候选开方。
     * 边界取"算内"（{@code ≥}）：半角那条线上锁得住，测试按 29.9°/30.1° 两侧钉。
     */
    public static boolean inCone(float ax, float ay, float cosHalf2, float dx, float dy, float d2) {
        float dot = ax * dx + ay * dy;
        return dot > 0f && dot * dot >= cosHalf2 * d2;
    }

    /** 半角（度）→ 余弦平方。90° 以上余弦为负，平方之后会把"背后"也判进锥内——所以这里挡掉。 */
    private static float cos2(float halfDeg) {
        // ⚠ 半角 ≥90° 时 cos 为负，而 dot² 判据只看平方 ⇒ 弹体正后方的敌人也会算进锥内（视野变全向）。
        // 消息写英文：EmbeddedFontTest 要求 main 里的非 ASCII **字面量**都有点阵字模，而这里永不上屏。
        if (halfDeg < 0f || halfDeg >= 90f) {
            throw new IllegalStateException("cone half angle must be in [0,90), got " + halfDeg
                    + " -- at 90 or more, dot^2 also matches enemies dead behind the missile");
        }
        float c = (float) Math.cos(Math.toRadians(halfDeg));
        return c * c;
    }

    /**
     * 当前的目标还作数吗。三条判据缺一不可：{@link Enemies#isLive}（还在表里）、
     * {@link Enemies.Enemy#born}（还是那一只，不是复用同槽的后来者）、{@code hp > 0}（还活着）。
     *
     * <p>失效时顺手把 {@code targetSlot} 归 {@link Missiles.Missile#NO_TARGET}。
     * ⚠ 但**不**清本帧的占用戳：那格这一帧仍算"被占"，于是旧持有者要等下一帧才可能锁回同槽的
     * 新敌人。方向是保守的——宁可少锁一次，也不会双锁。
     */
    private static Enemies.Enemy liveTarget(Missiles.Missile m, Enemies foes) {
        int slot = m.targetSlot;
        if (slot < 0) return null;
        Enemies.Enemy e = foes.objAt(slot);
        if (e != null && foes.isLive(slot) && e.born == m.targetBorn && e.hp > 0) return e;
        m.targetSlot = Missiles.Missile.NO_TARGET;
        m.targetBorn = 0L;
        return null;
    }

    /**
     * 中段引导（簇 II）读的那一只：射前雷达装订的目标还在不在。判据与 {@link #liveTarget} 逐条同，
     * 差别只在**它不参与占用**——装订只回答"往哪飞"，锁与占是开眼之后的事（mode0 那半条设计）。
     *
     * <p>所以这里失效时清的是 {@code boundSlot}，绝不清 {@code targetSlot}：后者是"当前锁定的目标"，
     * 在开眼段由 {@link #liveTarget} 自己管。两个字段一旦共用一条清理路径，装订段就会替导引头
     * 把目标摘掉，表现为"锁上了又立刻掉"。
     */
    private static Enemies.Enemy boundTarget(Missiles.Missile m, Enemies foes) {
        int slot = m.boundSlot;
        if (slot < 0) return null;
        Enemies.Enemy e = foes.objAt(slot);
        if (e != null && foes.isLive(slot) && e.born == m.boundBorn && e.hp > 0) return e;
        m.boundSlot = Missiles.Missile.NO_TARGET;
        m.boundBorn = 0L;
        return null;
    }

    /** 锁定：存 (槽号, 代次戳) 一对，并**立刻**回填占用位图。 */
    private void acquire(Missiles.Missile m, Enemies foes, int slot) {
        Enemies.Enemy e = foes.objAt(slot);
        m.targetSlot = slot;
        m.targetBorn = e.born;
        // ⚠ 这一行是本类唯一"看起来在写状态、其实只是给同帧第二枚弹看"的动作：beginFrame 的戳
        // 只反映帧首持有的目标，不回填的话同一帧里两枚弹会锁到同一个敌人——他自敲的
        // 「不锁定已被其他导弹锁定的目标」（L14750，同 FLAG_IGNITED 那条）排除的正是这种双锁。
        occupiedStamp[slot] = frame;
    }
}
