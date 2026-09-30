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
 * 开关 {@code Balance.missile.reIgnitionOnAcquire} 默认关。
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
     * 再次点火）」（UTC 2026-09-29T19:35:24.098Z＝本地 09-30 03:35:24）⇒ 只有
     * {@link Balance.Missile#reIgnitionOnAcquire} 置位才会出现，基础弹只锁不点。
     */
    public static final int FLAG_IGNITED = 4;
    /** 引信 / 撞敌：展开战斗部，该发记一次命中。 */
    public static final int FLAG_DETONATE = 8;
    /** 视场空太久：自爆。**同样**展开战斗部，但不记命中。 */
    public static final int FLAG_SELF_DESTRUCT = 16;
    /** 寿命到点或飞出画面：静默回收，不展开战斗部。 */
    public static final int FLAG_GONE = 32;

    /** {@link #scanFoes} 的输出：{@code [0]} = 引信碰到的槽号，{@code [1]} = 视场内最近可锁槽号。 */
    private static final int OUT_FUSED = 0;
    private static final int OUT_PICKED = 1;

    /** 按敌人 obj 槽号索引的占用戳。槽号 &lt; 本数组长度那条前提由 {@link #beginFrame} 当场断言。 */
    private final int[] occupiedStamp;
    private final int[] scanOut = new int[2];
    private int frame;

    /** 每帧从 spec 现算一次的三个数：省掉每枚弹各自两次三角一次除法，也保证全帧用同一份。 */
    private float radarCos2 = -1f;
    private float seekerCos2 = -1f;
    private float balanceSpeed = -1f;

    public MissileBehavior(int enemyCapacity) {
        if (enemyCapacity <= 0) throw new IllegalArgumentException("enemyCapacity must be > 0");
        occupiedStamp = new int[enemyCapacity];
    }

    /**
     * 每帧一次：帧号自增，并把"哪些敌人槽已被别的导弹占着"从零重建。
     *
     * <p>O(活跃导弹数) 一次遍历、零分配。之所以能不清空数组：判占用读的是
     * {@code stamp[slot] == frame}，下一帧帧号不同 ⇒ 旧戳自动失效。
     *
     * @param foes 用来断言容量匹配——{@link #advance} 会拿敌人的 obj 槽号来索引本类的数组，
     *             两者容量不同就是数组越界，这条不该靠调用方记住
     */
    public void beginFrame(Missiles missiles, Enemies foes, Balance.Missile spec) {
        if (foes.capacity() != occupiedStamp.length) {
            throw new IllegalStateException("enemy capacity moved: pool " + foes.capacity()
                    + " but this behavior was built for " + occupiedStamp.length);
        }
        frame++;
        if (frame == 0) {           // 2^32 帧一次：不清就会把上一轮的旧戳当成本帧的占用
            for (int i = 0; i < occupiedStamp.length; i++) occupiedStamp[i] = 0;
            frame = 1;
        }
        radarCos2 = cos2(spec.radarHalfDeg);
        seekerCos2 = cos2(spec.seekerHalfDeg);
        balanceSpeed = spec.thrust / spec.dragK;
        for (int i = 0; i < missiles.activeCount(); i++) {
            int slot = missiles.activeAt(i).targetSlot;
            if (slot >= 0) occupiedStamp[slot] = frame;
        }
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
     * @param margin 出界余量，与 {@code BulletPool.stepAndCompact} 同一个口径
     * @return {@link #FLAG_NONE} 或上述事件位的位或；调用方见 {@link #FLAG_DETONATE}
     *         {@link #FLAG_SELF_DESTRUCT} {@link #FLAG_GONE} 三者之一就把这枚移出池
     */
    public int advance(Missiles.Missile m, Balance.Missile spec, float dt, Enemies foes,
                       int logicW, int logicH, int margin) {
        int flags = FLAG_NONE;
        if (balanceSpeed < 0f) {
            // 三个缓存值只在 beginFrame 里算。漏调的话后果是"视场变成全向"加"速率被夹成 -1"，
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
        if (m.targetSlot == Missiles.Missile.RADAR_PENDING) {
            m.targetSlot = Missiles.Missile.NO_TARGET;
            scanFoes(m, foes, 0f, -1f, true, radarCos2, spec, Float.POSITIVE_INFINITY);
            int slot = scanOut[OUT_PICKED];
            if (slot >= 0) {
                bind(m, foes.objAt(slot), spec, dt);
                ax = m.vx / speed;          // 装订改写了弹轴，本步后面的积分必须用新轴
                ay = m.vy / speed;
            }
        }

        // ---- 相位：只管推不推力（L41661 之后它**不再**管索敌；空视场计时读的也是"眼"）----------
        if (m.phase == Missiles.Missile.PH_BOOST) {
            m.boostT += dt;
            if (m.boostT >= spec.boostSec) {
                m.phase = Missiles.Missile.PH_COAST;
                m.boostT = spec.boostSec;      // 夹住，别让"上一段还剩多久"变成负数
            }
        }

        Enemies.Enemy tgt = liveTarget(m, foes);
        boolean coasting = m.phase == Missiles.Missile.PH_COAST;
        if (m.seekerOpen) {                 // 闭眼的装订段本来就不看 ⇒ 不累空视场，否则装订段一长就出膛即自爆
            if (tgt == null) m.seekT += dt;
            else m.seekT = 0f;
        }

        // ---- 导引：比例导引（L41661「预测模型换比例导引」）。只改方向，不改速率；开眼前整段跳过 ------
        // 旧律是纯追踪：把弹轴**对到目标此刻的位置**，那条只收敛到"视线本身"＝尾追，追不上横向
        // 速度高的怪。PN 收敛到**常值视线角**＝提前量，这才是他要的"预测"。
        //   λ̇ = (r × v_rel) ÷ |r|²   —— 视线角速率，由相对运动学**解析**给出，不存上一步的视线角
        //   V_c = −(r · v_rel) ÷ |r|  —— 接近速度
        //   a_cmd = N′ · V_c · λ̇  ⇒  固定弹速下 ω_cmd = a_cmd ÷ |v| = N′ · (V_c ÷ |v|) · λ̇
        // 符号约定与旧的旋转一致：这里的双叉积 {@code rx·rvy − ry·rvx} 与下面旋转矩阵
        // {@code (ax·cos t − ay·sin t, ax·sin t + ay·cos t)} 是同一个正方向，不用换号。
        if (m.seekerOpen && tgt != null) {
            float rx = tgt.x - m.x;
            float ry = tgt.y - m.y;
            float d2 = rx * rx + ry * ry;
            if (d2 > 0f) {                     // 完全重合时视线未定义：本步不转，下一帧再算
                float d = (float) Math.sqrt(d2);
                float rvx = tgt.vx - m.vx;     // 相对速度＝目标减弹
                float rvy = tgt.vy - m.vy;
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
                    float omega = spec.navConstant * (closing / vFloor) * lamDot;
                    float omegaMax = spec.maxLatAccel / vFloor;         // 过载上限＝「机动过载」那张卡卖的旋钮
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
            if (ux * ux + uy * uy <= spec.seekerRange * spec.seekerRange) m.seekerOpen = true;
        }

        // ---- 三路判据（顺序见方法头）----------------------------------------------------
        // 引信不分相位（装订段直接撞上敌机也算撞，"直接撞敌"是三条引爆路之一）；导引头扫描只读**开没开眼**
        // ——闭眼那一段不索敌是 mode0 的本义，而"抵达装订点即扫描"**不看相位**：L41661 第一行
        // 「在抵达发射单元描述的位置后扫描附近敌机，发现后就转向，开眼，锁定最近的目标」。
        // ⚠ 旧码在这里还多要一道 `coasting`（那是我按 R9「再次点火」补的推导），实测它把第四段饿死：
        // B3 档 48 发的导引步数总和为 0（池件 §28.2）⇒ 该半条作废。
        scanFoes(m, foes, ax, ay, m.seekerOpen, seekerCos2, spec,
                spec.seekerRange * spec.seekerRange);
        if (scanOut[OUT_FUSED] >= 0) return flags | FLAG_DETONATE;
        if (m.seekT >= spec.seekTimeoutSec) return flags | FLAG_SELF_DESTRUCT;
        if (m.life >= spec.maxLifeSec) return flags | FLAG_GONE;
        if (m.x < -margin || m.x > logicW + margin || m.y < -margin || m.y > logicH + margin) {
            return flags | FLAG_GONE;
        }

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
                // 点火」。开关 = {@link Balance.Missile#reIgnitionOnAcquire}，默认关（基础弹不点火）。
                if (spec.reIgnitionOnAcquire) {
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
        float tau = leadTime(m, e.x - m.x, e.y - m.y, e.vx, e.vy, spec, dt);
        if (tau >= 0f) {
            m.unitX = e.x + e.vx * tau;
            m.unitY = e.y + e.vy * tau;
        } else {
            m.unitX = e.x;
            m.unitY = e.y;
        }
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
     * 拦截时间 τ\*：|目标匀速外推后离出膛点多远| 第一次被"弹在 τ 内能飞多远"追平的那一刻。
     * 递推照 {@link #advance} 里那套 Euler 一步不差地重放（同样的 thrust / dragK / boostSec /
     * 两头夹），于是"装订段能飞多远"只有一份真源——解出一个物理上飞不到的 τ\* 是没意义的。
     *
     * @return 追平的时刻（秒），弹寿命内追不上则 -1（交给 {@link #bind} 退化）。dt ≤ 0 时**不递推**，
     *         直接 -1：那是边界用例那种"把这一步的位移拿掉、只评判据"的步长，递推下去是死循环。
     */
    private static float leadTime(Missiles.Missile m, float dx, float dy, float evx, float evy,
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
                if (boostT >= spec.boostSec) {
                    phase = Missiles.Missile.PH_COAST;
                    boostT = spec.boostSec;
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
