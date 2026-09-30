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
 * 玩法数值总表（**逻辑只读这里，不在别处写字面量**）。
 *
 * 为什么要集中：规格给的数值有一部分是"确定的"（伤害档位、2 秒连击窗、30 秒超载冷却、
 * 对数增长公式），另一部分根本没给（各武器具体射速、各敌人 hp/速度/得分、掉落概率细节）。
 * 混在代码里改一个数要翻三个文件，边跑边调就成了瞎改；集中成一张表后，调平衡只动这一个文件。
 *
 * 每条都标了来源：
 * - `[规格]` 文档明写的，改之前先确认是不是真的可以改；
 * - `[推导]` 由规格给的量算出来的（比如波次敌人总数）；
 * - `[可调]` 规格未给、这里先给一个起点值，等真机跑起来调。
 *
 * 字段故意是 **非 final**：调试面板/自动化跑测要在运行期改表，改完下一帧生效。
 * 类与分组对象是 final，防止整表被替换掉导致逻辑读到两份数。
 */
public final class Balance {

    private Balance() { }

    public static final Wave wave = new Wave();
    public static final Score score = new Score();
    public static final Player player = new Player();
    public static final Damage damage = new Damage();
    public static final Overload overload = new Overload();
    public static final Combo combo = new Combo();
    public static final Shield shield = new Shield();
    public static final Bullet bullet = new Bullet();
    public static final Missile missile = new Missile();
    public static final Drop drop = new Drop();
    public static final Status status = new Status();
    public static final Wingman wingman = new Wingman();
    public static final Grade grade = new Grade();
    public static final Growth growth = new Growth();
    public static final Shop shop = new Shop();
    public static final ShopCard[] shopCards = ShopCard.makeAll();
    public static final Enemy[] enemies = Enemy.makeAll();
    public static final Boss[] bosses = Boss.makeAll();
    public static final Weapon[] weapons = Weapon.makeAll();

    // ---- 波次 ---------------------------------------------------------------------

    public static final class Wave {
        /** [规格] 敌人数对数增长：5 + floor(n^0.72 × 3)，线性会在 15 波后超出清场能力。 */
        public int base = 5;
        public double exponent = 0.72;
        public int multiplier = 3;
        /** [规格] 每 5 波一个 Boss，3 种 Boss 轮换。 */
        public int bossEvery = 5;
        public int bossCount = 3;
        /** [规格] 40 波 8 星区，每 5 波一个星区。 */
        public int maxWave = 40;
        public int wavesPerZone = 5;
        /** [可调] 同屏存活上限。超出后排队，等场上腾位再刷——不是不刷。 */
        public int maxAlive = 26;
        /** [可调] 相邻两次出怪间隔秒数，随波次略微收紧。 */
        public float spawnGap = 0.55f;
        public float spawnGapTightenPerWave = 0.006f;
        public float spawnGapMin = 0.22f;
        /** [可调] 首波宽限：开局给一点准备时间。 */
        public float firstWaveDelay = 1.1f;
        /** [可调] 波次横幅（= 准备期）时长，与清场后的休息时间。 */
        public float bannerSec = 1.4f;
        public float intermissionSec = 1.9f;
    }

    // ---- 得分 ---------------------------------------------------------------------

    /**
     * [规格] 击杀得分随波次加成、连击倍率、暴击放大。三者是乘的三个独立因子，
     * 波次与暴击的具体倍率规格没给，这里给起点值（连击倍率在 {@link ComboMeter}）。
     */
    public static final class Score {
        /** [可调] 每波 +4%：第 20 波 1.76 倍，够让后期怪"更值钱"又不至于分数爆炸。 */
        public float waveBonusPerWave = 0.04f;
        /** [可调] 暴击击杀的分数倍率。 */
        public float critKillMul = 1.5f;
    }

    // ---- 玩家 ---------------------------------------------------------------------

    public static final class Player {
        /** [规格] 护盾 100 点、与血量同构的第二管血。 */
        public int maxHp = 100;
        public int maxShield = 100;
        public float moveSpeed = 150f;           // [可调] 逻辑像素/秒
        public float keyMoveSpeed = 168f;        // [可调] 键盘比手指快一点，触屏有摩擦
        /** [规格] 受击无敌 0.7 秒。 */
        public float invulnSec = 0.7f;
        /** [规格] 撞机伤害随生命上限缩放，所以只用比例。 */
        public float radius = 5f;
        /** [可调] 血条残影追赶速度（比例/秒）：2.0 = 满血掉到空血约 0.5 秒收拢。 */
        public float trailPerSec = 2.0f;                // [可调] 命中半径（逻辑像素）
        public int startCoins = 0;
        public int startBombs = 1;               // [可调] 起始带一枚，让"炸弹入栏"立刻可体验
        /** [可调] 炸弹"重伤全场"扣的是**生命上限**的比例，不是固定伤害（规格 §掉落物）。 */
        public float bombHpRatio = 0.5f;
        /**
         * [可调] 触屏拖动的欠账上限（秒）：手指位移先记账，战机按限速逐帧消费，最多欠这么多。
         *
         * <p>没有这个额度，一次快速甩动会在停手之后继续把战机拖过半屏。
         */
        public float dragCatchupSec = 0.12f;
    }

    // ---- 碰撞伤害 -----------------------------------------------------------------

    /** [规格] 撞机类随最大生命缩放（比例），子弹类固定点数。 */
    public static final class Damage {
        public float gruntHitRatio = 0.50f;
        public float bursterHitRatio = 0.60f;    // 自爆怪贴脸
        public float bossHitRatio = 0.35f;
        public int bossBullet = 20;
        public int gruntBullet = 12;
        public int contactFloor = 1;             // [推导] 保证任何比例下都掉血，避免 0 伤
    }

    // ---- 超载 ---------------------------------------------------------------------

    /** [规格] 冷却制 30 秒，不是击杀蓄能制——蓄能制下玩家无法规划。 */
    public static final class Overload {
        public float cooldownSec = 30f;
        public int sweepDamage = 45;             // [可调] 全屏扫描式清场的伤害
        public float sweepSec = 0.5f;            // [可调] 扫描持续
        /** [规格] hit-stop：重击时世界定格 30~50ms，但震屏继续衰减。 */
        public float hitStopMin = 0.030f;
        public float hitStopMax = 0.050f;
        public float shakeSec = 0.35f;
    }

    // ---- 连击 ---------------------------------------------------------------------

    /** [规格] 2 秒窗口内连杀累积倍率，上限 +200%。 */
    public static final class Combo {
        public float windowSec = 2f;
        public int maxBonusPercent = 200;
        public int steps = 20;                   // [推导] 计到 20 连即满，之后持平
    }

    // ---- 护盾 ---------------------------------------------------------------------

    /** [规格] 点数制，先扣盾再扣血，不随时间衰减；破碎只在真正归零时给 +0.5s 短无敌。 */
    public static final class Shield {
        public float breakInvulnSec = 0.5f;
        public int fromRepairItem = 35;          // [规格] 护盾道具 +35
        public int fromHpOverfullSmall = 25;     // [规格] 血包溢出 +25
        public int fromHpOverfullBig = 50;       // [规格] 大血包 +50
        public int fromUpgrade = 50;             // [规格] 升级项 +50（2026-09-25 起商店按这一格**全额**入账、
                                                 // 允许越过 maxShield，见 PlayerState#addShieldBeyondCap）
    }

    // ---- 子弹 ---------------------------------------------------------------------

    /**
     * [规格] 玩家弹与敌弹各一个池，互不抢额度。满池时两侧的处理**相反**且都是刻意的：
     * 玩家侧淘汰最老的一发（按下去的那一下永远会出弹），敌弹侧拒签新弹（不踢已在途的弹，
     * 否则玩家躲了半天的那发会凭空消失）。因此两个容量的算式不同，见各自注释。
     */
    public static final class Bullet {
        /**
         * [可调] 玩家弹池容量 = 在屏代数 × 一代弹丸数，取六把武器里最紧的那条，**按玩法开、不按内存开**。
         * <pre>
         * 在屏代数 = 过屏时间 ÷ 有效周期；过屏时间 = logicH ÷ 弹速；
         * 有效周期 = 基础 fireGap ÷ 射速乘子上界 1.62（狂热 1.35 × 成长树 0.04×5 = 1.20）；
         * 一代弹丸数 N = 基础 pellets + 扳机等级，扳机取消满级后有两个收入口径：
         *   钱全压这一张 → lv17 → N=18；贪婪叠满 → lv24 → N=25。按 N=25 这一档开。
         * 逐武器（N × 过屏时间 ÷ <b>连续</b>周期，533 屏）：
         *   脉冲 25×1.269/0.0864 = 367 ← bound   电弧 277   散射 246   回旋 227   导弹 222   激光 15.5
         * 367 上取到 32 的倍数 = 384。
         * </pre>
         * 上面那段是**设计期推导**；这条 bound 现在有可执行版本
         * （{@code BulletAndGridTest.playerBulletPeakAtTheIncomeCapBuildFitsThePool}），它比注释准，
         * 因为连续周期 0.0864s 那一档在 60Hz 固定步长下<b>按不出来</b>（过冲被钳回 0 ⇒ 实测 0.1s）。
         * 按最高屏 + 出界余量、量化周期现算的峰值是 <b>350 发（脉冲）</b> ⇒ 384 有 34 发余量。
         * 两条挂账因此改口径：
         * <ul>
         *   <li>⚠(a)「384 站在激光不吃扳机这条例外上」——例外本身仍只在
         *       {@code ShopRun.pelletBonus} 定义一处，但<b>不再靠注释兜着</b>：激光若被放进适用范围，
         *       现算峰值变 375，那条用例的"余量至少一代"会当场红（375 距 384 只剩 9 < 25）。</li>
         *   <li>⚠(b)「560 屏 385.5 溢出、修法是 416」——那个 385.5 同样是连续周期的保守值，
         *       真实射速下 560 屏也<b>不溢出</b>，416 这一档不必抬。</li>
         * </ul>
         * 两条后续：① {@code missile-warhead-sim} 之后导弹改走独立池，那 222 从这里挪出去，
         * 但 bound 仍是脉冲，不用重算；② 画质档不缩池（{@code QualityProfile} 只管粒子预算与缩放），
         * 所以低端机上跑的也是这个 384 —— 代价是每帧最多 384 次网格查询与 drawRect，真机帧时间未取证。
         */
        public int capacity = 384;
        /**
         * [可调] 敌弹池容量。**算式与 {@link #capacity} 不同，别照抄**：玩家侧的 bound 是"产出速率"
         * （满了会淘汰玩家自己的弹，所以必须按极限开），敌弹侧满了是**拒签新弹**（少一发看不出来），
         * 于是这里按用户口径「整屏排满」开（**L14413** answers-**FREE**，2026-09-25T07:51:41.353Z 逐字「与玩家同口径（整屏排满）」），再用产出上界反证它真够不到。两个口径都写：
         * <pre>
         * ① 视觉口径（取这一条当值）：敌弹绘制边长 hostileSize 3，最高屏 LOGIC_H_MAX 560
         *    ⇒ 一列排满 = ceil(560/3) = 187。
         * ② 产出口径（证明 ① 够不到 ⇒ 饱和不会发生）：两条路径**互斥**，取 max 而不是相加，
         *    因为 {@code WaveDirector.waveTotalFor} 在 Boss 波只放 1 只怪、大小怪永不同屏。
         *      小怪：只有射手（fireGap 1.6）与精英（2.2）开火、各 1 发/次，全 26 只都是射手
         *            （{@code Balance.wave.maxAlive}）⇒ 26 ÷ 1.6 × hostileLife 6 = 98。
         *      Boss： 堡垒二阶段 9+3=12 发 ÷ (1.7×phase2GapMul 0.7) × bossLife 8 = 81。
         *      max = 98。
         * </pre>
         * ①187 > ②98 ⇒ 取 187 上整到 32 的倍数 = **192**（余量是 ② 的 2 倍：今后把射手 fireGap
         * 调小、或给小怪加"一次多发"，仍不至于开始拒弹）。两处产出都用 {@code maxLife} 当"绝不在屏内
         * 更久"的上界，而实际寿命被出界回收截得更短（弹速 118 从屏中落到下沿只要 2.2s），所以 ② 本身
         * 也是一份偏保守的账。HUD 调试行的 "H" 就是敌弹活跃数，真机贴没贴顶可以直接读。
         */
        public int enemyCapacity = 192;
        /**
         * [可调] 弹的寿命（秒）。子弹池里的 {@code life} 是**从 0 往上加的计时器**，
         * 生成时只能写这一个字段，别去写 {@code life}——把它一起赋成 maxLife 的话
         * 下一帧就判过期，敌弹在屏幕上永远看不到（2026-09-24 真机踩到）。
         * 玩家弹先前是 {@code WeaponFire} 里的字面量 {@code 4f}，弹链改造时挪进来：导弹要自己
         * 的一套寿命，与玩家弹不是同一个数，留在开火代码里就是"按武器分支改寿命"的第二个真源。
         */
        public float playerLife = 4f;
        public float hostileLife = 6f;
        public float bossLife = 8f;
        /** [可调] 绘制边长：小怪弹比 Boss 弹小一档，压迫感来自密度而不是体积。 */
        public float hostileSize = 3f;
        public float bossSize = 4f;
        /** [可调] 敌弹命中判定在玩家半径外的补偿：决定"擦边算不算中"。 */
        public float hostileHitPad = 2f;
    }

    /**
     * 导弹仿真表：两段视野 + 牛顿运动学 + 连续杆战斗部（方案里编号 R9）。
     *
     * <p>出处是**用户原话**，不是规格文档。L14750（驳回方案时的反馈自敲，`feedback`/status=rejected，
     * UTC 2026-09-25T09:19:51.727Z ＝<b>本地</b> 09-25 17:19）：
     * 「…导弹做仿真设计，射前锁定，不锁定已被其他导弹锁定的目标，朝目标方向运动（需要做符合牛顿力学
     * 的减速），然后以导弹正对方向60度夹角为导弹视场，锁定最近的目标，再次点火…若视场内无敌人则自爆，
     * 整个过程都要做仿真」。早一格的 L14369（answers-**FREE**，UTC 07:34:57.595Z ＝本地 15:34）已说过
     * 「导弹做仿真动力段设计，判定自己无法命中任何一个的自爆」。
     *
     * <p>本类不 import android：数值进、数值出，{@code MissileBehavior}/{@code WarheadRules} 读它。
     *
     * 全部是 [可调]，但**旋钮之间有不等式**，改任何一个都要回来看：
     * <pre>
     * ① 速度带必须包含出膛速度：stallSpeed ≤ weapons[MISSILE].bulletSpeed ≤ thrust ÷ dragK。
     *    现值 120 ≤ 220 ≤ 960（B3 之后上界从 240 抬到 960，出膛速度 220 不再贴着天花板）。带被调空时不报错、不 NaN，只是速率变成一个与 thrust 无关的常数
     *    （MissileBehavior 里那个 clamp 会静默生效）⇒ 又一种静默失效，
     *    由 MissileBehaviorTest.speedBandContainsTheMuzzleSpeed 钉着。
     *    临界：thrust &lt; stallSpeed × dragK = 120 × 1.25 = 150 时带即空。
     * ② **可数的量当真源，时长从它推**：rodLifeSec / shardLifeSec 都写成 (ticks + 0.5) × tickSec。
     *    0.16 ÷ 0.08 恰好等于 2.0，而 0.16 与 0.08 都不是二进制精确数 ⇒ 两个裸浮点各写一次，
     *    tick 次数就落在浮点比较的边界上，可能随常量折叠或 float↔double 提升在 2 与 3 之间抖。
     *    那 0.5 个 tick 的余量让节拍数不再依赖边界运气。
     * </pre>
     */
    public static final class Missile {

        // ---- 两段视野 ---------------------------------------------------------------

        /**
         * [可调] **射前**雷达半角（度）。90 这个数与"其实是两个45度"的换算**都是他的字**：
         * L14779（AskUserQuestion 自由作答，answers-**FREE**，UTC 2026-09-25T09:33:09.768Z ＝本地
         * 09-25 17:33）逐字「增加一个战机雷达数值，目前先填90度（这个同60度的语义，其实是两个45度）」
         * ⇒ 字段存半角 45（方案里编号 R14）。
         * 锥轴恒为屏幕正上方 (0,-1)：机头不随机体转（{@code Game} 里每帧 rotate 已删），
         * 所以雷达方向是常量，不随移动方向摆。
         */
        public float radarHalfDeg = 45f;
        /**
         * [可调] **飞行中**导引头半角（度）；锥轴是当前速度方向。
         * 他的字只到 L14750「以导弹正对方向60度夹角为导弹视场」（逐字见类头），那里没有"半角"；
         * "总夹角 60° = 两个 30°"这套说法与换算**是我的措辞**——我把它摆成选项、他挑了这一档
         * （L14773，answers-**OPT**，UTC 2026-09-25T09:29:00.463Z ＝本地 09-25 17:29：
         * 「总夹角 60°（半角 30°）」）⇒ 决策是他的、话是我说的（方案里编号 R10）。
         */
        public float seekerHalfDeg = 30f;
        /**
         * [可调] **导引头扇形的半径上界**（逻辑像素）：视场是「前方总夹角 60°、半径 50 的扇形」
         * 这两件一起——他的原话 L40027（plain，UTC 2026-09-29T16:01:19.845Z ＝本地 09-30 00:01:19）
         * 「导弹视场是导弹前方60°圆心角、半径50的扇形」。
         * 它同时是**开眼门**的半径：抵达装订点 ≤ 本值才开眼（同一条 L40027 裁的"装订段不转向"
         * 与 §23 那份四段设计里的「抵达发射单元描述的位置后扫描附近敌机」）。
         * ⚠ 射前雷达那一边**没有**半径上界（{@link #radarHalfDeg} 那条锥是无限长半径，他的字
         * L38831「机头90度角、无限长半径的扇形区域内有敌机时发射」）——两个锥只差这一个数，别顺手共用。
         */
        public float seekerRange = 50f;

        // ---- 运动学 -----------------------------------------------------------------

        /**
         * [可调] 推力加速度（px/s²），只沿弹体轴 —— 转向不改速率，改速率的只有推力与阻力。
         * ⚠ 300 → **1200** 是他点的 B3 档：L38117（plain，UTC 2026-09-29T11:30:15.015Z＝本地 19:30:15）
         * 「那结果很明显了，B3」（B3 ＝ thrust 1200 ＋ boostSec 1.0，选项标签是我写的），
         * 再到 L40806（plain，UTC 2026-09-29T17:47:29.504Z＝本地 09-30 01:47:29）「要落B3」才动手。
         * 动手的代价与收益实测在池件 §27（真码口径：48% → 75%，其中精英 0/8 → 8/8）。
         */
        public float thrust = 1200f;
        /** [可调] 线性阻力系数（1/s），{@code a = -k·v} ⇒ 平衡速度 = thrust ÷ dragK = 960（B3 之后）。 */
        public float dragK = 1.25f;
        /**
         * [可调] 最大法向加速度（px/s²）。转弯率 {@code ω_max = maxLatAccel ÷ max(|v|, stallSpeed)} ⇒
         * **速度越低转得越急**，"先减速再拐过去追"是从参数里自然长出来的行为，不是写死的规则。
         * ⚠ B3 把巡航钳从 240 抬到 960 ⇒ 巡航段的 ω_max 从 124°/s 掉到 **31°/s**（480px/s 时 62°/s、
         * 落到失速地板 120 时到顶 248°/s；转弯半径在巡航段是 {@code v²/a_lat} = **1772px**，三倍屏高）。
         * ⚠ **但真码实测：这个字段在当前制导几何下从来没饱和过。** 6 敌种 × 8 横向落点 = 48 发的
         * fixture 里，"这一步实际转角 ≥ 0.99×允许转角"的步数在 lat = 520／2000／5000／20000 四档下
         * **全是 0**，48 发的分类（命中／自爆／寿命／出界）逐格一模一样（见池件 §27 的 out5 读数）。
         * 原因是导引头只在**距装订点 50px 内**开眼，开眼后到结算只剩 3.5～9.8 步（B3 档），
         * 而弹轴又是射前 CV 装订好的 ⇒ 一帧就能对齐，用不到转弯率上限；打不到的那批是
         * **目标跑出了那颗 50px 的泡**（横摆组 最近点均值 31.6px、精英旧档 59.3px），不是拐不过去。
         * ⇒ **「机动过载」这张（簇 II **拟增**、尚未落码的）卡现在买不到东西**，
         * 它该重新定价或改卖别的（池件 §27 把它列为待裁）。
         * 基线**维持 520**：这不是"520 有效"，而是"520 与 20000 等价"，挑低的那档留给卡面有上升空间。
         * ⚠ 口径限制：fixture 是单弹对单敌、敌从屏顶进场；多目标互斥（{@code targetSlot} 位图）
         *   与更深进场几何下这条结论是否仍成立，未测。
         * ⚠ **这段读数是纯追踪（true pursuit）时代的**：L41661「预测模型换比例导引」把导引律换成
         * PN 之后，指令角速度由 {@link #navConstant}×视线角速率给出、不再由"对齐视线"给出，
         * 那条"一步就转到位"的短路也随之删除 ⇒ "从未饱和"这个**前提**没了，饱和与否要重测。
         * 原文照抄留在这里，是为了保住"这张卡当初为什么无货"的证据链（池件 §27、§28.2）。
         */
        public float maxLatAccel = 520f;
        /**
         * [可调] 比例导引律的**导航常数 N′**：{@code a_cmd = N′·V_c·λ̇}（{@code V_c}＝接近速度、
         * {@code λ̇}＝视线角速率），固定弹速下即 {@code ω_cmd = N′·(V_c÷|v|)·λ̇}。
         *
         * <p>出处：他的裁定只到「预测模型换比例导引」这一句（L41661 第二条，kind=plain，
         * UTC 2026-09-29T19:35:24.098Z＝本地 09-30 03:35:24）⇒ **导引律是他的；N′ 的取值、以及
         * "λ̇ 用相对运动学解析给出、不存历史量"这套实现是我的**（方案里编号 R24）。
         *
         * <p>3 是经典下界那一档（线性化模型里 {@code N′ > 2} 才对非机动目标收敛），5 收敛更快但
         * 更早撞上 {@link #maxLatAccel} 那道过载闸。取值由 48 发 fixture 的命中率定，见池件 §30。
         */
        public float navConstant = 3.0f;
        /**
         * [可调] 失速地板：既是速度下界，也是 ω 的分母下界。没有它这条改动会自己造一个荒谬行为——
         * "锁着但追不上"的那批弹**不会**被 {@link #seekTimeoutSec} 收掉（那条只管空视场的弹），
         * 只能活到 {@link #maxLifeSec} = 3.0。B3 档的真实衰减（按 {@code MissileBehavior:217} 那一步的
         * 显式 Euler 现算，不是解析解）：boost 1.0s 收尾 **750.8px/s**（还没到巡航钳 960），此后纯滑行
         * 再过 2.0s ⇒ 无地板 **60px/s** ⇒ ω_max = 520/60 = 8.7 rad/s = **496°/s ⇒ 每步转 8.3°**；
         * 滑行满 3.0s ⇒ 无地板 17px/s ⇒ 每步 29°。地板把这两个荒谬数钉回 120 ⇒ 每步 4.1°。
         * （旧 thrust 300 档的对应读数是末速 5.6px/s ⇒ 每步 88°——B3 把它缓和了一个量级，但量级仍然荒谬。）
         * 这不是 NaN，是行为错到荒谬，而且恰好在"打不到的那批弹"身上触发。物理含义：低于这个速度弹翼失效、
         * 拐不过去，失速的弹就该直着飞出去、由 maxLife 收尾，而不是靠一个越来越猛的转弯假装还能导引。
         */
        public float stallSpeed = 120f;
        /**
         * [可调] 单次点火时长（秒）：0.18 这个数**没有任何一条他的原文**，是我替他定的；
         * **1.0 是他点的 B3 档带来的**（L38117「那结果很明显了，B3」＝thrust 1200 ＋ boostSec 1.0 一体，
         * L40806「要落B3」才落码）——B3 不是"推力单独抬"，那一档的名字就是这两个数。
         * ⚠ **这一条的"首段不承担导引"已被 L41661 作废**：他那句逐字是「在抵达发射单元描述的位置后
         * 扫描附近敌机，发现后就转向，开眼，锁定最近的目标」⇒ **扫描只看开没开眼，不看相位**，
         * 旧的 `coasting` 门槛（我按 L14750「再次」两字补的推导）撤掉了。撤它的理由有实测：那道门槛
         * 在 B3 档把第四段饿死——48 发的导引步数总和为 0（池件 §28.2／§29.1 第 1 条）。
         * ⚠ 但**空视场计时读的仍是"眼开没开"，不是相位**（{@code MissileBehavior:184}）。
         * ⚠ 旧的连带算术"无锁弹寿命 = boostSec + seekTimeoutSec = 0.68s、
         * {@link #capacity} 按它推"在 mode0（L40027）之后**不再成立**：开眼之前那一整段既不搜索也不累
         * seekT，所以 0.68s 不再是任何一枚弹的寿命上界，容量的账改按 {@code maxLifeSec} 走（见那条注释）。
         * ⚠ B3 之后这一条更要盯住：boost 1.0s 内飞掉 **544px**（收尾 750.8px/s），一枚弹在点火段
         * 就能穿过大半张战斗区（屏高 560）；"点火时长"与"过屏时长"从此同量级，别再把 boost 当瞬时段。
         */
        public float boostSec = 1.0f;
        /**
         * [可调] 开眼后锁定（含改锁）要不要**顺带再点一次火**。默认 **false**。
         *
         * <p>他是把它当**商品**裁的：L41661（plain，UTC 2026-09-29T19:35:24.098Z ＝本地 09-30 03:35:24）
         * 第一行末尾那句括号「（如果没有二次点火卡就没有再次点火）」⇒ 基础弹不点火，"再次点火"是
         * **二次点火卡**卖的东西。这与「摘除式是我的意图」（同一条记录第六行）是同一件事的两半：
         * 今天默认存在的行为要从基础弹上摘下来挂到卡上。
         *
         * <p>⚠ 出处分层：「锁定最近的目标，再次点火」最初是他 L14750（feedback）的口述，当时我把它
         * 实现成默认（方案编号 R9）；L41661 把它改成了**买来的**，所以这里是一个开关而不是删掉那段码——
         * 开关的另一头（true）就是卡上线后该走的路径（{@code MissileBehavior:254-269}）。
         */
        public boolean reIgnitionOnAcquire;
        /**
         * [可调] 导引头连续空视场多久就自爆（秒）：0.5 这个数与"搜不到才爆"这条折中**是我的**。
         * 他的字面 L14750「若视场内无敌人则自爆」与「不锁定、直飞，看到再锁」直接矛盾（直飞的第一帧
         * 视场里就是空的 ⇒ 出膛即爆）——是我把这个冲突摆成选项，他挑了这一档（L14773 的 [OPT]
         * 「不锁定、直飞，看到再锁」，同那条记录）⇒ 决策是他的、"直飞/看到再锁"这套措辞是我的
         * （方案里编号 R11）。
         */
        public float seekTimeoutSec = 0.5f;
        /**
         * [可调] 引信余量（逻辑像素）：{@code d ≤ 敌radius + size/2 + pad} 即引爆。
         * 与 {@code bullet.hostileHitPad} 同一种"擦边算不算中"的补偿。
         * ⚠ 它也决定了失速弹的杆能不能吃到第二个 tick：够到沿轴敌圆至少要走出 size/2+pad = 5px。
         */
        public float fusePad = 3f;
        /** [可调] 硬兜底寿命（秒）：到点静默回收，**不**展开战斗部（这区别于三路引爆）。 */
        public float maxLifeSec = 3.0f;

        // ---- 战斗部节拍（真源是"跳几次"，时长由它推）--------------------------------

        /**
         * [可调] 伤害结算节拍（秒）。0.08 这个数与"帧率无关（机器越差不该伤害越低）"这条理由
         * **都是我的**——出自我写的选项标签，他挑了这一档（L14779 的 [OPT]「固定节拍 0.08s
         * （帧率无关）」，同那条记录）⇒ 方案里编号 R12 挂在我名下。
         * 他的字只到 L14750「接触到亮线的扣完整伤害」与「接触到破碎碎片的扣四分之一（向下取整，
         * 最小为1）」——要按接触结算这件事是他的，怎么计时是我定的。
         * 它不是 STEP=1/60 的整数倍 ⇒ 单步节拍呈 5,5,4,5 抖动；累计器保留余数，只抖相位不抖平均速率。
         * ⚠ 节拍与**接触采样**是两件事：采样每步做、计费每 tick 做（见 {@code Warhead} 的 contact 位图）。
         */
        public float tickSec = 0.08f;
        /** [可调] 亮线破碎前结算几次。 */
        public int rodTicks = 2;
        /** [推导] = (rodTicks + 0.5)·tickSec = 0.2s。别改成裸浮点，见类头 ②。 */
        public float rodLifeSec = (rodTicks + 0.5f) * tickSec;
        /** [可调] 碎片结算几次。 */
        public int shardTicks = 3;
        /** [推导] = (shardTicks + 0.5)·tickSec = 0.28s。 */
        public float shardLifeSec = (shardTicks + 0.5f) * tickSec;

        // ---- 连续杆战斗部 ------------------------------------------------------------

        /**
         * [可调] 亮线**全长**（逻辑像素）：中心在弹位、垂直弹轴、两端各伸出半长。
         * 覆盖面：杆的"中心可命中跨度" = rodLength + 2×小怪半径 = 34 + 9 = **43px** ⇒
         * 小怪相切时中心距 9px，一刀最多盖 **5 个中心**；叠上"每 tick 完整伤害 × 两个 tick"
         * 就是导弹真正的强度来源（比一发子弹的账面伤害大得多，见方案 §1.5 的聚合账）。
         * ⚠ 上限受网格覆盖约束，且**位移与半长抢同一个圆**（判据是扫掠带，见
         * {@code WarheadRules#sweptBandHitsCircle}）：{@code hypot(整步位移, rodLength/2) + 最大敌半径
         * 14.5 + 命中 pad 2 ≤ 覆盖半径 48}。现档位移 16.0（杆全额继承弹速）⇒ 上界是
         * {@code rodLength ≈ 54.2}，现值 34 有余量 20.2；越界的表现是**杆尖擦到的敌人静默不掉血**。
         * 这条由 {@code WarheadRulesTest} 从代码读三个数钉住，不许在测试里重述字面量。
         */
        public float rodLength = 34f;
        /** [可调] 亮线从 0 展开到 {@link #rodLength} 满长所需的秒数。⚠ **必须远小于 {@link #rodLifeSec}**：按 {@code life/rodGrowSec} 铺开，若生长时间接近寿命，杆要到临碎那一刻才满长，"逐渐展开"在屏幕上读不出来。现值 0.06s 约合 3.6 步，比一个 {@link #tickSec} 还短 ⇒ 出膛后第一次结算之前就已铺开，读到的是"甩开"。取 0 或负数 = 不生长（一出生就满长）。 */
        public float rodGrowSec = 0.06f;
        /**
         * [可调] 连续杆**相对弹体**被抛出的速度（px/s）。杆的对地速度 = 弹的对地速度 ＋ 本值×弹轴
         * 单位向量（{@link WarheadRules#spawnRod}），是叠加的一项、不是倍乘。
         *
         * <p>出处：他 L41661 第四条「战斗部是伤害，弹速是弹速，我无法理解你是如何联系起来的，
         * 如果你指连续杆，那么我请问，这不就是在运动的小车上丢出一个小球，属于直线减速问题」
         * ⇒ 倍乘在他的模型里是错的：弹速是**继承量**，不是伤害的函数。本轮（2026-09-30 会话）
         * 他把两个分支都给了：**「杆自身发射速如果是指丢的速度，取1占位，如果指导弹给的动能，
         * 继承导弹的」**⇒ 本值＝1 占位、继承系数＝1（系数写死在码里，不成为可调量）。
         *
         * <p>⚠ 被本字段取代的旧 {@code rodSpeedMul}（0.2，倍乘）带着三段历史：0.35→0.2 是 L40806
         * 落 B3 的**连带修档**（巡航钳 240→960 会把杆的前抛上界从 84 抬到 336px/s，把当时那条
         * "每步位移 &lt; 命中带"的采样守护从 1.97× 压到 1.20×），三档取舍挂在池件 §27。
         * 那条守护随"判据改成扫掠带"一起作废（理由见 {@link WarheadRules#sweptBandHitsCircle}
         * 与池件 §32），所以这个倍乘量连同它的全部权衡一起消失——**不是**我把 §27 那三档选了
         * 一档，是那张表不再存在。
         *
         * <p>⚠ 1px/s 时这一项**不可观测**（每步 0.017px），抛出方向我取"沿原弹轴正向"，保住现有
         * "杆往前抛、横着切"的形状；横向张开仍归 {@link #rodGrowSec} 那套，不重复计一份横向运动。
         */
        public float rodEjectSpeed = 1f;
        /**
         * [可调] 战斗部的**平方律**阻力系数：{@code a = -Cd·v·|v|}。一个步长上的精确解是
         * {@code v ← v ÷ (1 + Cd·|v|·dt)}（只缩不转），所以每步位移随速度自然变短＝他说的"直线减速"。
         *
         * <p>出处：他本轮给的字面是**「Cd（阻力系数）取0.002」**；**但读法（平方律）是我的**。
         * 同一个数放在线性律 {@code a = -k·v}（本仓导弹阻力的写法，{@link #dragK}）下，0.2s 只把
         * 960 拉到 959.6、掉 **0.04%**＝等于没减速，与他自己那句"属于直线减速问题"矛盾；
         * 放在平方律下拉到 693.6、掉 **27.7%**，射程 192px→162px。只有平方律让这个数做出事情，
         * 所以按平方律实现。读数：池件 §31.2、{@code reference/evidence/launchunit/out12.txt}。
         *
         * <p>⚠ 作用范围也是我的延伸：他答的是**杆**，我把同一个数给了杆与碎片两者（同源材料、
         *   同一条定律），否则会出现"杆在减速、它炸出来的碎片却匀速"这种裂缝。取 0 = 关闭阻力。
         */
        public float warheadDragCd = 0.002f;
        /** [可调] 破碎成几片。方向 = 杆的法向两侧交替 + 前向分量（连续杆的物理就是杆向两侧张开成环）。 */
        public int shardCount = 6;
        /** [可调] 碎片速度（px/s），与杆继承的弹速无关——破碎是瞬间的横向抛撒。 */
        public float shardSpeed = 130f;
        /** [可调] 碎片绘制边长。⚠ **不参与命中判定**——碎片按质点算，命中半径是
         *  {@code 敌radius + warheadHitPad}（与亮线同一条），两个形状共用一个判据才只有一处 pad 要调。 */
        public float shardSize = 2f;
        /**
         * [可调] 战斗部擦边命中敌人的补偿（逻辑像素）：判据是"敌心到**本步扫掠带**的距离 ≤ 敌半径 + 本值"。
         * ⚠ 它进 {@link #rodLength} 那条网格覆盖约束（{@code hypot(整步位移, rodLength/2) + 最大敌半径 +
         *   本值 ≤ 48}），调大本值与调长杆一样会把候选查询推出覆盖圈 ⇒ 静默漏判。
         * 与 {@code bullet.hostileHitPad} 同量但**不是同一个数**：那是敌弹擦边命中玩家，
         * 这是战斗部擦边命中敌人，两者不该被同一次调参同时改动。
         */
        public float warheadHitPad = 2f;

        // ---- 两张池的容量（按玩法开，不按内存开）------------------------------------

        /**
         * [可调] 导弹池容量。算式（⚠ 单位别搞错：91.5 是**每秒发射数**，不是发数）：
         * <pre>
         * 出膛率上界 = N ÷ period = 26 ÷ 0.284s = 91.5 枚/秒；
         *   26 = 该枪满档弹丸数（基础 2 + 扳机卡取消满级后的收入上界 lv24）；
         *   0.284s = 导弹的有效周期 = fireGap 0.46 ÷ 射速乘子上界 1.62（狂热 1.35 × 成长树 1.20）。
         * 并发上界 = 91.5 × maxLifeSec 3.0s = 274.7，另加在场敌数 26 作缓冲 ⇒ 合计 300.7；
         * 512 是 **1.7× 余量**，不是"刚好够"。
         * </pre>
         * ⚠ **这段乘子换了，是 L40027 改 mode0 的连带后果**（原文口径见下面两条）：mode1 时代这本账是
         * {@code 91.5 × 0.68s(= boostSec + seekTimeoutSec) = 62} 加 {@code 26}，因为"扫不到目标的弹"
         * 一定在 0.68s 内自爆。mode0 之后那一格换成了**装订段在飞的弹**：它在开眼之前既不占目标、
         * 也不累 seekT（空视场自爆那条读的是导引头开没开眼，见 {@code MissileBehaviorTest} 的
         * {@code aMissileThatNeverOpensItsEyeIsReclaimedByExpiryNotByTimeout} 与
         * {@code anEmptyFieldSelfDestructsOnceTheEyeIsOpen} 两条），而射前锥按 L38831 是**无限长半径**
         * ⇒ 一枚弹的寿命上界就是 {@code maxLifeSec}，与它锁没锁上无关。反过来"有锁弹 ≤ 在场敌数"
         * 那一格也不再是独立上界（它被 {@code rate × maxLifeSec} 包住），留着只当缓冲。
         * ⚠ 上面这条 91.5 是**每秒发射数**，不是发数——把它当发数是这份文件曾经犯过的错。
         * ⚠ **B3（L40806「要落B3」）之后 300.7 变成了上界而不是预算**：巡航钳抬到 960 后，一枚始终
         * 没开眼的直飞弹在 58 步 = **0.967s** 就冲出屏顶被 {@code MissileBehavior} 的出界判据收掉
         * （出膛点 y=500、margin=16、显式 Euler 现算，里程 519.3px——{@code boostSec} 那 1.0s 还没跑完），
         * 那一档的真实并发 ≈ {@code 91.5 × 0.967 = 88.5}，不是 274.7。
         * ⚠ **但 512 不因这条估计而降**：真码 fixture 在 B3 档下 48 发里**没有一枚活到 {@code maxLifeSec}**
         * （全部在 ≤1.05s 内命中或出界，见池件 §27 的 out5），照实测算并发只需 ≈88 格；但那个 fixture 是
         * 单弹对单敌、敌从屏顶进场，不含"目标向下折返把弹拖住"的多弹并发，而下面的守护用例
         * {@code MissilesTest.spawnRateBoundLeavesHeadroomInThePool} 是**按 {@code maxLifeSec} 现算**的
         * ⇒ 要按实测收紧容量，得连那条用例的口径一起改，本轮不动它（512 是对象数组，多留的格 ≈50KB，
         * 用它换"寿命上界那条永远成立"是划算的）。
         * 这条 bound 由 {@code MissilesTest.spawnRateBoundLeavesHeadroomInThePool} 从 {@link Balance}
         * 现算复验：周期、乘子、寿命、敌数、每代发数（读 {@code ShopRun.pelletBonus}）五项全部现读，
         * 唯一手抄的是"收入上界那档扳机等级 24"——取消满级之后发数由钱包决定，那条收入推导
         * 不是这一个字段能表达的，算式写在那条用例的注释里。
         * 满池淘汰最老者**只播自毁动画、不结算**（R22，L20551「做个自毁动画，然后静默处理也是可以的」）
         * ⇒ 永不拒发、不静默吞掉玩家按下去的那一下；R13 那条「自爆也展开」（L14773）只管**空视场**自爆。
         * ⚠ 后半句**是我的措辞与理由**：他的字面只到 L14750「若视场内无敌人则自爆」，
         * 那里既没有"多余弹直飞"，也没有"不能吞掉输入"这层意思。
         */
        public int capacity = 512;
        /**
         * [可调] 战斗部池容量。一次引爆的**格·秒**驻留 = 1×rodLifeSec(0.2) + 6×shardLifeSec(0.28) = 1.88，
         * 并发格数 = 1.88 × 引爆率 D，而每枚弹最多引爆一次 ⇒ D ≤ 出膛率 91.5/s
         * ⇒ 绝对上界 1.88 × 91.5 = **172 格** ⇒ 256 是 1.4× 余量，且在最坏导弹吞吐下仍成立。
         * 满则**拒生**：少几片碎片，玩家看不出来（与敌弹池同一条口径）。
         */
        public int warheadCapacity = 256;
    }

    // ---- 掉落 ---------------------------------------------------------------------

    public static final class Drop {
        /** [规格] 芯片：击杀 20%（受幸运/贫瘠影响）、Boss 必掉 3、第 2 波起每波保底 1 枚。 */
        public float chipChance = 0.20f;
        public int bossChips = 3;
        public int chipGuaranteeFromWave = 2;
        public int chipGuaranteeCount = 1;
        /** [可调] 金币与道具掉率。 */
        public float coinChance = 0.55f;
        public int coinPerKillBase = 3;
        public float powerupChance = 0.05f;
        public float hpChance = 0.10f;
        /** [可调] 掉落物绘制边长。网格是 7×7，这里必须是它的整数倍，否则最近邻缩放像素宽窄不均。 */
        public int drawSize = 7;
        /** [可调] 捡取半径与吸引半径：吸引先于捡取，玩家"靠近就被拽走"是掉落的正反馈。 */
        public float pickupRadius = 8f;
        public float pullRadius = 26f;
        /** [规格 §道具栏] 维修道具回 40 生命；血包同值，溢出转护盾。 */
        public int hpItemHeal = 40;
        /** [可调] 炸弹掉率：入栏不自动炸（规格明写），所以它可以比状态道具更稀有。 */
        public float bombChance = 0.02f;
    }

    // ---- 状态层 -------------------------------------------------------------------

    /** [规格] 拾取层 12 秒；环境层由地带决定。两层相乘，不互相覆盖。 */
    public static final class Status {
        public float pickupSec = 12f;
        public float frenzyRateMul = 1.35f;      // 狂热：射速
        public float frenzySpeedMul = 1.15f;     // 狂热：移速
        public float lagRateMul = 0.75f;         // 迟滞
        public float lagSpeedMul = 0.85f;
        public float ironTakenMul = 0.70f;       // 铁壁 -30%
        public float brittleTakenMul = 1.40f;    // 脆化 +40%
        public float luckyDropMul = 1.5f;        // 幸运
        public float barrenDropMul = 0.6f;       // 贫瘠
        public float swiftSpeedMul = 1.25f;      // 迅捷
        public float stickySpeedMul = 0.8f;      // 粘滞
        public float shieldBonusMul = 1.2f;      // 铁壁额外护盾回复
        /** 环境层（后 4 星区）。 */
        public float coldSpeedMul = 0.85f;
        public float coldRateMul = 0.9f;
        public float forgeTakenMul = 1.25f;
        public float stormDropMul = 0.7f;
        public float stormRateMul = 0.9f;
        public float voidSpeedMul = 0.85f;
        public float voidTakenMul = 1.3f;
    }

    // ---- 僚机 ---------------------------------------------------------------------

    /** [规格] 最多 2 架，共用玩家正前方最近的目标；三连引爆改为**全局计数**。 */
    public static final class Wingman {
        public int maxCount = 2;
        public int burstHits = 3;
        public int burstRadius = 26;
        public int burstDamage = 2;
        public float fireGap = 0.42f;
        public int bulletDamage = 2;
        public float bulletSpeed = 260f;
        public float orbitDist = 16f;
    }

    // ---- 结算评级（五维归一的参照值）--------------------------------------------------

    /**
     * [可调] 五维归一的参照值与权重。规格只说"各自归一、加权合成字母等级"，没给参照——
     * 参照值决定"什么成绩算 S"，是真机上要调的第一批数，所以一个字都不写进逻辑里。
     *
     * 参照取"打到中段"而不是"打满 40 波"：绝大多数玩家死在 10~20 波，拿 40 波当满分
     * 会让所有人都挤在 D，等级失去指导意义。
     */
    public static final class Grade {
        /** 生存参照：清完这么多波算满分（死亡按 deathWaves 折算成"少清几波"）。 */
        public float survivalWaves = 20f;
        /** [可调] 一次死亡抵掉几波——死亡惩罚挂在生存维度上，不另开一维。 */
        public float deathCostWaves = 2f;
        public float killRef = 280f;
        /** 效率直接就是命中率，不设参照：0.5 的命中读 0.5，不做"及格线拉平"。 */
        public float overloadRef = 6f;
        public float comboRef = 20f;
        public float wSurvival = 0.30f;
        public float wKill = 0.25f;
        public float wEfficiency = 0.15f;
        public float wOverload = 0.10f;
        public float wStyle = 0.20f;
        /** 字母等级下限（从高到低），{@code Rating.letterFor} 按此表逐个比。 */
        public float atS = 0.85f;
        public float atA = 0.70f;
        public float atB = 0.52f;
        public float atC = 0.34f;
    }

    // ---- 成长树（局外）--------------------------------------------------------------

    /**
     * [规格] 6 项永久属性 × 5 级：攻击 +3% / 射速 +4% / 护盾 +10 / 机动 +3% / 暴击 +2% / 炸弹 +1；
     * 消耗 3→5→7→9→11，单项满级 35、全部点满 210 芯片。
     *
     * 这里只放"每级加多少"和价格阶梯，**等级本身不在表里**（那是 {@link GrowthTree} 的状态）。
     * 分开有两个好处：调试面板改一档数值下一帧就生效，不必担心旧倍率留在字段里；
     * 而"满级 +15%、与商店卡 +20% 叠乘成 1.38"这类结论就从同一张表算出来，不会两处各写一份。
     */
    public static final class Growth {
        /** [规格] 项数与等级上限——数组长度由它们决定，所以是编译期常量，不给运行期改。 */
        public static final int ITEMS = 6;
        public static final int MAX_LEVEL = 5;
        public static final int ATTACK = 0;
        public static final int RATE = 1;
        public static final int SHIELD = 2;
        public static final int MOBILE = 3;
        public static final int CRIT = 4;
        public static final int BOMB = 5;
        /** [规格] 逐级价格阶梯：买第 k 级花 {@code COST[k]}，五格加起来正好 35。 */
        public static final int[] COST = {3, 5, 7, 9, 11};

        public float attackPerLevel = 0.03f;
        public float ratePerLevel = 0.04f;
        public int shieldPerLevel = 10;
        public float mobilePerLevel = 0.03f;
        public int critPerLevel = 2;
        public int bombPerLevel = 1;
    }

    // ---- 升级商店（规格 §升级商店：11 张卡，每波三选一）------------------------------------

    /**
     * 商店的全局档位。卡面数字全部从这里派生（{@code ShopRules.perLevelOf → coreValue}），
     * 所以调完平衡不必再去 11 处文案里逐个改——那正是"显示 +2%、实际 +3%"的来源。
     */
    public static final class Shop {
        /** [规格] 11 张卡、每波三选一。 */
        public static final int CARDS = 11;
        public static final int OFFER = 3;
        /**
         * 「这张卡没有等级上限」的哨兵，两种来源共用：一次性补给卡（买多少次都按固定量），
         * 与 2026-09-26 起取消了满级的火力 / 扳机两张养成卡。
         *
         * <p>⚠ 它**不再**是角标的闸门：拿它判"要不要画 Lv"会连带把 4 张补给卡一起误伤
         * （它们本来就不该画等级）。判"该不该画 Lv"请读 {@code ShopRules.showsLevel}。
         */
        public static final int UNLIMITED = -1;
        /** 满级的价格哨兵，与 {@code GrowthTree.PRICE_MAXED} 同义：负数 = 不可购买。 */
        public static final int PRICE_MAXED = -1;

        /**
         * 火力强化卡每级的**固定加伤**（点/发）。
         *
         * <p><b>取消"每级 +2%、总上限 +20%"（规格原写的数）是用户 2026-09-25 的裁定，两条第一手
         * 逐字</b>——L12803（`queued_command`/`origin.kind=human`，UTC 2026-09-24T16:43:17.604Z
         * ＝<b>本地</b> 09-25T00:43）「…火力强化取消百分比和上限，改为固定的+1…」＋ L14223
         * （纯文本自敲，09-25T06:52:47.030Z）「需要把火力强化卡的10满级上限取消…每次+1」。
         * ⚠ 日期口径：本仓注释记的都是**本地日**（Asia/Shanghai，+08），而 transcript 的
         * `timestamp` 是 UTC——这条按 UTC 尺读会误判成 09-24。
         *
         * <p>⚠ <b>归属分两半</b>：「取消百分比」「改为固定的+1」「取消上限」三个动作是他的字；
         * 而下面这段<b>为什么</b>取消是我的推导，他没有给过理由——那个百分比活不到结算里：伤害按发
         * 取整（{@code WeaponFire.damageOf}），激光基础 1 点，十级叠满也只有 1.2 → 四舍五入仍是
         * 1 点，卡面写了收益而场上纹丝不动。定值 +1 走加法，买一级就每发多掉一格血，玩家能立刻看见。
         */
        public int damageFlatPerLevel = 1;
        /**
         * 扳机卡每级的**通用增量**：买一级就是这个数 +1，进弹链的 {@code N}，见 {@code BulletChain.layout}。
         *
         * <p>这个字段的形状就是用户那条裁定的直接产物（L23955，纯文本自敲，UTC 2026-09-26T09:35:09.178Z
         * ＝本地 17:35，逐字）「射速卡卖的是一个值在购买后加一，对于子弹，是每次开火多一发子弹，对于
         * 导弹（以连续杆线举例），是多一发导弹，是否理解核心？」⇒ 卡只卖**一个抽象数值**，
         * **语义由各武器档各自解释**，存储层只有这一个数、没有第二份。
         *
         * <p>⚠ 命名归属：「弹丸」「pellet」「发/级」「pelletPerLevel」这套词**是我的**，他从没用过——
         * 他口中这张卡叫"射速卡"（卡名已按 2026-09-26 的改名裁定改成「每级加一」，见 {@code ShopCard}
         * 那张表）。所以 {@code pelletBonus} 这个读法在**子弹档**是准确的，在**导弹档**只是同一数值
         * 的第二种解释；别把它读成"这张卡的定义就是发数"，那是把四种解释之一当成了定义。
         *
         * <p><b>2026-09-25 起这张卡不再加射速。</b>两条逐字原文——
         * L14292（AskUserQuestion 作答，07:00:45Z，answers-**FREE**）「射速本身固定，但是子弹每次+1」、
         * L14614（驳回方案时的反馈，08:41:39Z，`feedback`/`status=rejected`）
         * 「每张卡+1个子弹/伤害，只需要在购买后计算一次即可」。
         * 于是原本那个 0.05 的射速乘子字段删掉了（"改卖发数"这五个字是执行者的措辞，不是用户原话）。
         * 删它的技术理由：固定步长模拟把射速量化了——实测周期 = {@code ceil(cd/STEP)·STEP}，乘子的档位
         * 一旦落进同一个量化格就完全不改变场上行为（激光在乘子上界 1.62 附近连吃十几级都是
         * 20 发/秒）——"花了钱什么都没发生"第三种形态。离散量（发数、枚数）量化吃不动它。
         *
         * <p>⚠ 激光不吃这一档。例外**只在** {@code ShopRun.pelletBonus} 定义一处；
         * 卡面无法按武器显示不同数字（{@code ShopRules.Snapshot} 不带武器字段），
         * 所以这条例外只能靠 tip 文案表达，见 {@code ShopCard} 的 TRIGGER 那行。
         */
        public int pelletPerLevel = 1;
        /** 暴击按**百分点**加，和成长树 {@code Growth.critPerLevel} 同一口径。 */
        public int critPerLevel = 3;
        public int critMaxLevel = 5;
        public float speedPerLevel = 0.05f;
        public int speedMaxLevel = 5;
        public int hullPerLevel = 20;
        public int hullMaxLevel = 5;
        public float magnetPerLevel = 0.30f;
        public int magnetMaxLevel = 3;
        public float coinPerLevel = 0.25f;
        public int coinMaxLevel = 4;
        /** 一次性补给卡的固定量。 */
        public int repairAmount = 40;
        /** [规格] 清屏卡改成给 3 枚炸弹：商店开在刚清完场的时刻，立即清屏等于炸了个寂寞。 */
        public int salvoBombs = 3;
        /** 护盾卡的加盾量与成长树/道具共用 {@link Shield#fromUpgrade}，这里不再写一份。 */

        /** prio 拐点：血量低于这个比例之后保命卡的优先级开始陡升（规格"快死了不该抽到养成卡"）。 */
        public float panicHpRatio = 0.35f;
        /** 长线养成卡的优先级随波次增长的斜率分母：第 N 波时基础优先级 = N / 这个数。 */
        public float growthWaveScale = 20f;
        /** prio 上的随机抖动幅度。0 = 完全按缺口排序（三选一会变成固定菜单），太大又退回纯随机。 */
        public float prioJitter = 0.08f;
    }

    /**
     * 一张升级卡的静态定义：卡面文案、图标、价格阶梯。
     *
     * <p>"每级加多少、能加几级"**不在这里**，而在 {@link Shop} 的字段上，由
     * {@code ShopRules.perLevelOf/maxLevelOf} 按 id 现读。两处各存一份的话，热调一档数值
     * 就会让卡面写 +2%、实际结算 +3%——那是规格点名的"显示与结算不一致"这一类 bug。
     */
    public static final class ShopCard {
        public static final int FIREPOWER = 0, TRIGGER = 1, PRECISION = 2, THRUSTS = 3, HULL = 4,
                REPAIR = 5, SHIELD = 6, SALVO = 7, SURGE = 8, MAGNET = 9, GREED = 10;

        public final int id;
        public final String name;
        /** 一句话效果说明（卡面上的 tip）。 */
        public final String tip;
        /** [规格] {@code desc} 保留但不显示——它与核心数字高度重复，占的是同一块视线。 */
        public final String desc;
        /** {@link com.flexiatom.pixelraider.gfx.SpriteSheets} 的图标 id。 */
        public final int iconId;
        /** 第 k 级花 {@code priceBase + priceStep * k}；{@code priceStep = 0} 就是固定价补给卡。 */
        public final int priceBase;
        public final int priceStep;

        ShopCard(int id, String name, String tip, String desc, int iconId,
                 int priceBase, int priceStep) {
            this.id = id;
            this.name = name;
            this.tip = tip;
            this.desc = desc;
            this.iconId = iconId;
            this.priceBase = priceBase;
            this.priceStep = priceStep;
        }

        static ShopCard[] makeAll() {
            return new ShopCard[] {
                new ShopCard(FIREPOWER, "火力强化",
                        "每发子弹伤害加一点",
                        "每级给所有武器的每发子弹加一点伤害，与成长树的攻击项叠加。",
                        com.flexiatom.pixelraider.gfx.SpriteSheets.ID_SHOP_FIRE, 30, 12),
                new ShopCard(TRIGGER, "每级加一",
                        "每级多一发，激光不吃",
                        "每级加一个数，各武器决定多什么；不改变开火快慢，也不减每发伤害。",
                        // 卡名由用户提出改的（L23984，纯文本自敲，UTC 09-26T09:46:31 ＝**本地 09-26**
                        // 17:46，逐字「我建议改卡名，注释也要同步修改」）；落地在 09-27。
                        // 旧名「快速扳机」与"扳机=射速"这个直觉绑在一起，
                        // 而他 2026-09-26 那条裁定（L23955，见 {@code pelletPerLevel} 的注释）明确
                        // 了这张卡卖的不是射速 ⇒ 名字与语义错位，错位本身就是他指出来的（L23793，
                        // 08:59:03Z 纯文本自敲：「射速卡什么时候卖发散了，我是指解除等级上限的那个射速卡」）。
                        // ⚠ 只改卡名与注释：标识符 TRIGGER / pelletPerLevel / pelletBonus /
                        // CORE_SHOTS / ID_SHOP_RATE 一个字都没动——上面那条 L23984 逐字要的就是
                        // 「改卡名，注释也要同步修改」（方案里编号 R32）。
                        // ⇒ 全仓的**注释与测试断言消息**仍写作「扳机卡」，那是标识符 TRIGGER 的中文对译，
                        // 指的是货架上这张「每级加一」；两边对不上时以这一行为准。
                        // 不做批量替换：L23984 逐字只要"卡名 + 注释"，没要全仓改词。只引可复算且不
                        // 被本条注释自身改写的读数：「扳机」散在 10 个文件，其中**字符串字面量** 6 处 /
                        // 3 个测试文件（BulletAndGridTest、MissilesTest、ShopRulesTest，剥注释后扫）；
                        // 行数不写——它会被这次加进来的两行注释自己改掉，写了当场过期。
                        // 两类不可改：动作叙述（BulletChain「扇形要等扳机把 N 顶过 C」）与他的逐字原文。
                        // 文案的两处用词都是硬闸门逼出来的：①「激光不吃」不能写成「激光除外」——
                        // 「除」不在内嵌字集（EmbeddedFontTest 真读 cmap，缺字即红）；② tip 长度
                        // ≤ 15 字，因为 ShopLayoutTest 钉死 longest×12px ≤ 181px。真正承重的有**两张**
                        // 15 字卡：HULL「生命上限提升，并补上新增的那截」与 SALVO「获得炸弹并入栏，
                        // 由你决定何时放」，各 180px、离闸门只剩 1px。本行 10 字 = 120px、余 61px，
                        // 写到 15 字都不会红（先前这里只点名了 HULL 一张 ⇒ 是一处漏数，不是判错）。
                        // 这条例外必须写在文案里：ShopRules.Snapshot 不带武器字段，卡面在代码层
                        // 无法按武器显示不同数字，玩家唯一的知情渠道就是这一行。
                        com.flexiatom.pixelraider.gfx.SpriteSheets.ID_SHOP_RATE, 34, 16),
                new ShopCard(PRECISION, "会心校准",
                        "暴击率提升",
                        "暴击按两倍伤害结算，与武器自带暴击率相加。",
                        com.flexiatom.pixelraider.gfx.SpriteSheets.ID_SHOP_CRIT, 38, 18),
                new ShopCard(THRUSTS, "机动推进",
                        "移动速度提升",
                        "躲不开的时候，速度就是生命。",
                        // 这一改的两半**不同源、不同日**（2026-09-27 逐子句核归属；本仓注释日期 = 本地日
                        // Asia/Shanghai，transcript 戳是 UTC，比日期要先 +8 再取日）：
                        // ① 提概率 + 降价：用户第一手自敲 L12803（UTC 09-24T16:43 = 本地 09-25T00:43，
                        //    queued_command/origin=human）「…推荐增加速度卡出现的概率并下调价格…」⇒ 本地
                        //    口径 09-25 正确；「速度卡」是他的措辞，卡名实际叫「机动推进」。
                        // ② 另一半只是这个决定生效的前提：触屏走位改成限速跟随，出处 L12104
                        //    （2026-09-24，answers-**OPT**「指针改成限速跟随（推荐）」）⇒ 决策是他的、
                        //    这四个字是我写的选项标签，且**两种口径都是 09-24**（先前整条标 09-25 就是把
                        //    两半缝成一句的后果）。
                        // ③ 下面那句"移动速度同时是指针欠账每秒能放出去多少的上限"（见 DragDebt）
                        //    是**我的推导**，不是任何人的裁定——它解释②为什么让这张卡值那个价。
                        // 原价 30+14k 配原来的低优先级，等于这张卡基本不出现在前期货架上。
                        com.flexiatom.pixelraider.gfx.SpriteSheets.ID_SHOP_SPEED, 22, 10),
                new ShopCard(HULL, "装甲强化",
                        "生命上限提升，并补上新增的那截",
                        "抬上限的同时把新增加的血补上，买了不亏。",
                        com.flexiatom.pixelraider.gfx.SpriteSheets.ID_SHOP_HULL, 40, 18),
                new ShopCard(REPAIR, "应急维修",
                        "立即回复生命，溢出转护盾",
                        "满血时不会出现在货架上。",
                        com.flexiatom.pixelraider.gfx.SpriteSheets.ID_HP, 25, 0),
                new ShopCard(SHIELD, "护盾充能",
                        "护盾点数提升",
                        "满盾时不会出现在货架上。",
                        com.flexiatom.pixelraider.gfx.SpriteSheets.ID_SHIELD, 30, 0),
                new ShopCard(SALVO, "弹药补给",
                        "获得炸弹并入栏，由你决定何时放",
                        "炸弹是清屏并重伤全场的手牌，不是捡到就炸。",
                        com.flexiatom.pixelraider.gfx.SpriteSheets.ID_BOMB, 55, 0),
                new ShopCard(SURGE, "超载重置",
                        "超载扫描立即就绪",
                        "已经转好时不会出现在货架上——那等于卖一张空卡。",
                        com.flexiatom.pixelraider.gfx.SpriteSheets.ID_POWERUP, 40, 0),
                new ShopCard(MAGNET, "磁吸扩容",
                        "吸取半径提升",
                        "金币与芯片掉在弹幕缝里时，半径就是白捡的钱。",
                        com.flexiatom.pixelraider.gfx.SpriteSheets.ID_SHOP_MAGNET, 32, 16),
                new ShopCard(GREED, "贪婪协议",
                        "金币收益提升",
                        "越早买越划算：后面每一波掉的金币都按这个倍率算。",
                        com.flexiatom.pixelraider.gfx.SpriteSheets.ID_COIN, 45, 20),
            };
        }
    }

    // ---- 敌人（6 种基础）-----------------------------------------------------------

    public static final class Enemy {
        public final int id;
        public final String name;
        public int hp;
        public float speed;
        public int score;
        public int spriteSize;
        public float contactBonus;               // [可调] 该种怪撞机的额外比例修正
        public float fireGap;                    // 0 = 不射击
        /** [可调] 该种怪弹速。小怪弹不快，压迫感来自密度。 */
        public float bulletSpeed = 118f;
        public int bulletDamageKind;             // 0 = 小怪弹（固定点数）
        public float burstRadius;                // 自爆怪用
        /** 精灵主色（ARGB 整数，不进 android.graphics）。[规格] 形态先于颜色，颜色只做辨识度。 */
        public int color;

        Enemy(int id, String name, int hp, float speed, int score, int spriteSize) {
            this.id = id;
            this.name = name;
            this.hp = hp;
            this.speed = speed;
            this.score = score;
            this.spriteSize = spriteSize;
        }

        /** id 常量：与 grid/绘制分支一一对应，新增必须同步 SpriteSheets 与 spawn 分支。 */
        public static final int STRAIGHT = 0, WEAVE = 1, SHOOTER = 2, RUSHER = 3, ELITE = 4, BURSTER = 5;

        /**
         * 命中半径。写在这里而不是散在 {@code Game} 的两个 spawn 分支里，是因为
         * **战斗部覆盖不变量**（{@code WarheadRulesTest}）必须能在 JVM 侧读到"场上最大的敌半径"——
         * 那条界是 {@code 杆半长 + 敌半径 + 命中 pad ≤ ring×CELL}，测试自己重述一遍
         * {@code spriteSize/2f} 就是立了第二个真源，改了构造半径的公式它也不会红。
         */
        public float hitRadius() {
            return spriteSize / 2f;
        }

        static Enemy[] makeAll() {
            // [可调] 全部为起点值：hp 太小会让"僚机三连引爆"永远攒不出来，太大则清场拖沓
            Enemy[] a = new Enemy[6];
            a[STRAIGHT] = new Enemy(STRAIGHT, "直线", 2, 62f, 10, 9);
            a[WEAVE] = new Enemy(WEAVE, "横摆", 3, 54f, 14, 9);
            a[SHOOTER] = new Enemy(SHOOTER, "射手", 4, 34f, 20, 9);
            a[RUSHER] = new Enemy(RUSHER, "冲刺", 3, 96f, 24, 9);
            a[ELITE] = new Enemy(ELITE, "精英", 12, 40f, 60, 13);
            a[BURSTER] = new Enemy(BURSTER, "自爆", 5, 74f, 30, 9);
            a[SHOOTER].fireGap = 1.6f;
            a[ELITE].fireGap = 2.2f;
            a[BURSTER].burstRadius = 22f;
            // 主色只用于辨识度：**轮廓**才负责"这是什么怪"，所以四种普通怪共用一个红橙。
            // 颜色数量在这里收敛，是因为 SpriteFactory 的缓存 key 里颜色占一个槽位标签，
            // 标签池只有 16 格（ColorTags.CAPACITY），铺张用色的后果是"换色后画成别人的图"。
            a[STRAIGHT].color = 0xFFFF6A4D;
            a[WEAVE].color = 0xFFFF6A4D;
            a[RUSHER].color = 0xFFFF6A4D;
            a[SHOOTER].color = 0xFFC9A6FF;
            a[ELITE].color = 0xFF6BE8A0;
            a[BURSTER].color = 0xFFFF2D55;
            // 自爆怪的特殊伤害**只走贴脸那条路**（Damage.bursterHitRatio 60%）。
            // 这里若再给 +10%，非贴脸的擦碰就也变成 50+10=60%，和贴脸一模一样，
            // "躲开爆心"这个操作失去意义。
            a[BURSTER].contactBonus = 0f;
            return a;
        }
    }

    // ---- Boss（3 种，每 5 波轮换，带二阶段）------------------------------------------

    public static final class Boss {
        public final int id;
        public final String name;
        public int hp;
        /** [可调] 击杀分。Boss 的分数是"这一波的收官"，比一整套小怪加起来略多即可。 */
        public int score;
        public int contactDamageRatioPercent;    // [规格] Boss 撞击 = 35%
        public int bulletDamage;                 // [规格] Boss 子弹 = 20
        public float phase2AtRatio;              // [可调] 血量低于此比例进二阶段
        public float fireGap;
        public int spriteSize;
        /** [可调] 巡航速度（逻辑像素/秒）。Boss 靠轮廓与弹道分流，不靠比小怪更快。 */
        public float speed;
        /** [可调] 悬停线：距战斗区顶边的像素数。 */
        public int holdY;
        /** [可调] 横向巡游幅度，0 = 定点。 */
        public float amp;
        /** [可调] 一轮齐射的弹数与总张角（度）。张角 0 = 全部平行朝下。 */
        public int pellets;
        public float spreadDeg;
        /** [可调] 弹速。Boss 弹不快，压迫感来自覆盖面而不是反应时间。 */
        public float bulletSpeed;
        /** [可调] 二阶段：射速倍率（<1 更快）与额外弹数。 */
        public float phase2GapMul = 0.7f;
        public int phase2ExtraPellets = 2;
        /** 见 {@link Enemy#color} 的同样理由：颜色共用、轮廓分流。 */
        public int color;

        Boss(int id, String name, int hp) {
            this.id = id;
            this.name = name;
            this.hp = hp;
            this.contactDamageRatioPercent = 35;
            this.bulletDamage = Balance.damage.bossBullet;
            this.phase2AtRatio = 0.5f;
            this.fireGap = 0.9f;
            this.score = 200;
            this.spriteSize = 33;
            this.speed = 26f;
            this.holdY = 46;
            this.amp = 62f;
            this.pellets = 5;
            this.spreadDeg = 44f;
            this.bulletSpeed = 96f;
        }

        public static final int DESTROYER = 0, SPIDER = 1, FORTRESS = 2;

        /**
         * 命中半径：比小怪那条多一个内缩，因为 Boss 的 33px 精灵四角是透明的——按外接方块的
         * 一半算会打出一堆"看着没碰到却掉血"。与小怪共用 {@link Balance.Enemy#hitRadius} 的
         * 理由相同：战斗部覆盖不变量要在 JVM 侧读到真实的最大敌半径，不能在测试里重述公式。
         */
        public float hitRadius() {
            return spriteSize / 2f - 2f;
        }

        static Boss[] makeAll() {
            // [可调] 基准血量；实际血量按波次在 bossHpFor 里长
            Boss[] a = new Boss[] {
                    new Boss(DESTROYER, "毁灭者", 90),
                    new Boss(SPIDER, "蜘蛛", 76),
                    new Boss(FORTRESS, "堡垒", 120),
            };
            a[DESTROYER].color = 0xFFFFD24A;
            a[SPIDER].color = 0xFFC9A6FF;
            a[FORTRESS].color = 0xFF9FE8FF;
            // 三种 Boss 的区分全靠**弹道形状**（规格：形态先于颜色）：
            // 毁灭者 = 朝玩家扇形齐射；蜘蛛 = 慢速追踪丝；堡垒 = 横扫的墙（留一个缺口）。
            a[DESTROYER].pellets = 5; a[DESTROYER].spreadDeg = 44f; a[DESTROYER].fireGap = 1.15f;
            a[DESTROYER].score = 200;
            a[SPIDER].pellets = 3; a[SPIDER].spreadDeg = 18f; a[SPIDER].fireGap = 0.8f;
            a[SPIDER].bulletSpeed = 72f; a[SPIDER].amp = 88f; a[SPIDER].speed = 34f;
            a[SPIDER].holdY = 62;
            a[SPIDER].score = 240;
            a[FORTRESS].pellets = 9; a[FORTRESS].spreadDeg = 120f; a[FORTRESS].fireGap = 1.7f;
            a[FORTRESS].bulletSpeed = 84f; a[FORTRESS].amp = 40f; a[FORTRESS].speed = 18f;
            a[FORTRESS].holdY = 40;
            a[FORTRESS].score = 320;
            a[FORTRESS].phase2ExtraPellets = 3;
            return a;
        }
    }

    // ---- 武器（6 把）---------------------------------------------------------------

    /** [规格] 每把有独立的射速、伤害、暴击率、弹道形态；形态先于颜色。 */
    public static final class Weapon {
        public final int id;
        public final String name;
        public float fireGap;
        public int damage;
        public int critPercent;
        public int pellets;
        public float bulletSpeed;
        /** 0 尖锥 1 长刃 2 扇粒 3 导弹 4 回旋 5 电弧 6 僚机小菱形 */
        public final int shape;
        /** 弹体主色（ARGB 整数，不进 android.graphics，保持纯算术）。 */
        public int color;
        /** [可调] 弹体边长（逻辑像素）。 */
        public float size = 3f;
        /** [可调] 多弹丸时的总张角（度）；单发武器恒为 0。 */
        public float spreadDeg;
        /**
         * [可调] 弹体沿屏幕 Y 的**绘制**长度 = {@link #size} × 这个比例。命中判定用的是 size 的方框，
         * 不是这个长度——它纯粹是"看着多长"。
         * 两个读者共读这一个数：{@code Game.drawShots}（画多长）与 {@code BulletChain.layout}
         * （走完一个弹长要多久 = 弹链的时隙间隔）。分开的后果是"链的相切判据"与"画面上的断档"
         * 各说各话——玩家看到两发叠在一起，而算术说它们正好首尾相接。敌弹不读它（画 size 的正方形）。
         */
        public float drawLenRatioY = 1.7f;
        /**
         * [形态] true = 这一把的弹丸走**独立仿真池**（{@link Balance#missile}），不进 {@code shots} 池。
         * 唯一读者是 {@code BulletChain.step} 的那个分支——全项目只此一处按武器分流，
         * 所以它必须是布尔而不是"看 shape"或"看 id"：后者会在第二把制导武器出现时悄悄漏掉。
         * 为什么不能塞进普通弹池：普通弹一进命中循环就 {@code killAt}（命中即消失），
         * 而仿真体要活过引信、自爆、战斗部三个阶段。
         */
        public boolean guided;

        Weapon(int id, String name, int shape) {
            this.id = id;
            this.name = name;
            this.shape = shape;
        }

        public static final int PULSE = 0, LASER = 1, SHOT = 2, MISSILE = 3, ARC = 4, BOOMER = 5;

        static Weapon[] makeAll() {
            // [可调] 全部是起点值。形态先于颜色：shape 决定画成什么，color 只是辨识度。
            Weapon[] a = new Weapon[6];
            a[PULSE] = new Weapon(PULSE, "脉冲", 0);
            a[PULSE].fireGap = 0.14f; a[PULSE].damage = 3; a[PULSE].critPercent = 8;
            a[PULSE].pellets = 1; a[PULSE].bulletSpeed = 420f;
            a[PULSE].color = 0xFF00E5FF; a[PULSE].size = 3f;

            a[LASER] = new Weapon(LASER, "激光", 1);
            a[LASER].fireGap = 0.09f; a[LASER].damage = 1; a[LASER].critPercent = 4;
            a[LASER].pellets = 1; a[LASER].bulletSpeed = 620f;
            a[LASER].color = 0xFFEFD9FF; a[LASER].size = 2f;
            // 激光是"长刃"形态：唯一一把绘制长度不是 1.7 倍的武器，画出来才是那道束。
            a[LASER].drawLenRatioY = 3.4f;

            a[SHOT] = new Weapon(SHOT, "散射", 2);
            a[SHOT].fireGap = 0.30f; a[SHOT].damage = 2; a[SHOT].critPercent = 10;
            a[SHOT].pellets = 5; a[SHOT].bulletSpeed = 340f;
            a[SHOT].color = 0xFFFFB347; a[SHOT].size = 3f; a[SHOT].spreadDeg = 44f;

            a[MISSILE] = new Weapon(MISSILE, "导弹", 3);
            a[MISSILE].fireGap = 0.46f; a[MISSILE].damage = 6; a[MISSILE].critPercent = 12;
            a[MISSILE].pellets = 2; a[MISSILE].bulletSpeed = 220f;
            a[MISSILE].color = 0xFFFF5C7A; a[MISSILE].size = 4f; a[MISSILE].spreadDeg = 26f;
            a[MISSILE].guided = true;           // 走 missile-warhead-sim 的独立仿真池

            a[ARC] = new Weapon(ARC, "电弧", 5);
            a[ARC].fireGap = 0.26f; a[ARC].damage = 4; a[ARC].critPercent = 15;
            a[ARC].pellets = 1; a[ARC].bulletSpeed = 300f;
            a[ARC].color = 0xFF9BF0FF; a[ARC].size = 3f;

            a[BOOMER] = new Weapon(BOOMER, "回旋", 4);
            a[BOOMER].fireGap = 0.38f; a[BOOMER].damage = 5; a[BOOMER].critPercent = 10;
            a[BOOMER].pellets = 2; a[BOOMER].bulletSpeed = 260f;
            a[BOOMER].color = 0xFF8CFF9E; a[BOOMER].size = 4f; a[BOOMER].spreadDeg = 18f;
            return a;
        }
    }
}
