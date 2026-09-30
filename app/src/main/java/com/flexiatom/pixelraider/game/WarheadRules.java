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

import com.flexiatom.pixelraider.game.Warheads.Warhead;

/**
 * 战斗部几何与节拍（纯函数，JVM 可测）：连续杆亮线的端点、逐格积分（含平方阻力）、
 * 扫掠带接触采样、破碎、tick 计数。
 *
 * 形状照 {@link EnemyBehavior}：**只写状态、不碰渲染**。{@code Game} 侧只留"按这里算出的结果
 * 执行动作"（扣血、{@code killEnemy}、撒粒子），因为 {@code Game} 是渲染类（静态块 {@code new Paint}
 * ⇒ JVM 直调必 {@code ExceptionInInitializerError}），它内部的记账在单测里是**结构性零覆盖**的。
 * 所以判据全部下沉到这里——留在 {@code Game} 里的那几行，是没有任何自动化证据的部分。
 *
 * ⚠ **本类不存角度，也不在格子里存长度**。杆的方向每步由速度现推单位法向
 * （{@link #rodEnds}），半长走 {@link #rodHalfLen}（{@link Balance.Missile#rodLength} 是它的**满长**）。
 * 理由：把角度写进字段就会有人
 * 写 {@code angle = axis + 90f}，而 {@code axis} 来自 {@code atan2} 是**弧度**，于是
 * {@code 90 rad mod 2π = 116.6°} ⇒ 杆歪 26.6°，而命中与绘制读同一个错数、**自洽地同时错**，
 * 任何一致性测试都抓不到。端点式（四个 float）从结构上不给这类错误留位置。
 */
public final class WarheadRules {

    /** {@link #rodEnds} 的输出：{@code [x1, y1, x2, y2]}。 */
    public static final int ROD_ENDS_FLOATS = 4;

    private WarheadRules() { }

    /**
     * 亮线此刻的**半长**——R17 那句"逐渐展开"的唯一真源。
     *
     * <p>三处读者必须走这里：{@link #sampleContact}（命中）、{@link #spawnShards}（破碎时碎片
     * 沿杆六等分的跨度）、{@code Game.drawWarheads}（绘制）。写第二次就会"画得长但打得短"，
     * 而那种错在截图上只是"杆看起来正常"，只有分数对不上。
     *
     * <p>⚠ 生长必须比寿命快得多：{@code rodLifeSec} 只有 0.2s，按 {@code life/maxLife} 铺开的话
     * 杆要到临碎那一刻才满长，玩家永远看不见展开。判据与数值都挂在
     * {@link Balance.Missile#rodGrowSec} 上。
     *
     * <p>⚠ 上界由网格覆盖钉死，而且**位移与半长抢的是同一个圆**（扫掠带最远的角 =
     * {@code hypot(整步位移, 半长)}，不是相加）：{@code hypot(t, half) ≤ 覆盖半径 − (最大敌半径 + 命中 pad) = 31.5}
     * ⇒ 现档 {@code t = 16.0}（全额继承后）下 {@code half ≤ 27.1}、{@code rodLength ≤ 54.2}。
     * 过界的表现是**杆尖擦到的敌人静默不掉血**。
     * {@code WarheadRulesTest} 从代码读这三个数钉它，测试里不重述字面量。
     */
    public static float rodHalfLen(Warhead w, Balance.Missile spec) {
        return rodHalfLenAt(w.life, spec);
    }

    /**
     * 同一个真源，但把"此刻"换成传进来的那个时刻——扫掠带要的是**一步的两端各自铺到多长**
     * （{@link #sampleContact} 用 {@code w.life} 与 {@code w.life - dt}）。
     *
     * <p>负数输入返回 0，不返回负半长：那会让带变成"两侧都不许碰"的怪形状。
     */
    public static float rodHalfLenAt(float life, Balance.Missile spec) {
        float half = spec.rodLength * 0.5f;
        if (spec.rodGrowSec <= 0f) return half;
        float k = life / spec.rodGrowSec;
        if (k <= 0f) return 0f;
        return k >= 1f ? half : half * k;
    }

    /**
     * 亮线的两个端点：中心在 {@code (x,y)}、**垂直弹轴**、半长 {@code halfLen}。
     *
     * @param vx,vy 杆的速度（= 弹速全额继承 ＋ 抛出项，沿原弹轴；见 {@link #spawnRod}）
     * @param out   长度 ≥ {@link #ROD_ENDS_FLOATS}
     * @return false = 速度为零，**没有法向可言**。此时不猜方向（"零速 ⇒ 正右方"是那种安静把
     *         90° 写进屏幕的错），调用方应跳过本格的绘制与命中。
     */
    public static boolean rodEnds(float x, float y, float vx, float vy, float halfLen, float[] out) {
        float len2 = vx * vx + vy * vy;
        if (len2 <= 0f) return false;
        float inv = halfLen / (float) Math.sqrt(len2);
        // 单位法向 = (-vy, vx)/|v|，先例：Game.drawParticles 里那行 nx = -vy / m, ny = vx / m
        float nx = -vy * inv, ny = vx * inv;
        out[0] = x + nx;
        out[1] = y + ny;
        out[2] = x - nx;
        out[3] = y - ny;
        return true;
    }

    /**
     * 战斗部**本步扫过的带**与敌圆是否相交。
     *
     * <p>带 = 以 {@code (px,py)→(x,y)} 这段位移为中线、沿速度法向两侧各伸出 {@code halfWidth}
     * 的矩形；敌圆半径用"圆心到矩形的最近距离 ≤ r"补上（旋转到带的本地正交系后两次 clamp，
     * 不开方、不存角度）。
     *
     * <p>⚠ **为什么不是"此刻那个形状 vs 圆"（旧写法）**：他 2026-09-30 的纠正是——位移**在设计里
     * 就是连续的**，至少在视觉上连续：60Hz 每帧画一根线，玩家读到的是这条线扫过一整条带，
     * 不是 12 次独立闪现。"两帧之间的空隙"是我把判据钉在采样瞬间造出来的实现产物，不是设计属性。
     * 全额继承弹速（{@link Balance.Missile#rodEjectSpeed} 那条）之后杆每步走 16px、而全体敌人
     * 与 Boss 里**最窄的命中带只有 13.0px**（读数 {@code out12.txt}）⇒ 旧写法会漏掉整条带子里的敌人。
     *
     * <p>两个退化形状共用这一条，所以杆与碎片**只有一个判据**、也就只有一处 pad 与一条覆盖界要管
     * （{@link #hitRadius}）：
     * <ul>
     *   <li>{@code halfWidth0 = halfWidth1 = 0} ⇒ 碎片质点这一步走过的**线段**；</li>
     *   <li>位移为 0（出膛第一步、或 {@code dt = 0} 的用例）⇒ **此刻**那根垂直于速度的线段／那个点，
     *       与旧判据逐像素同值。</li>
     * </ul>
     *
     * <p>⚠ **两侧不等宽：带是一个梯形，不是矩形**。杆在 {@link Balance.Missile#rodGrowSec} 里从 0
     * 铺到满长，所以一步扫过的真形状是"步初半长 → 步末半长"的**梯形**；把它写成矩形（整步按步末
     * 半长等宽）就在杆尖多盖出一个楔形——**与画面不符**。他 2026-09-30 逐字判过这条：
     * 「杆没长满时按满长扫与视觉效果不符，属于代码与设计不符」⇒ 判据按 {@code halfWidth0}
     * （步初）到 {@code halfWidth1}（步末）沿位移线性插值。⚠ 别把它"简化"回矩形：矩形是
     * 白送一片杀伤，而这片杀伤在屏幕上对应的是**那一时刻还没画出来的杆尖**。
     *
     * <p>⚠ 插值只沿位移方向做，所以贴着一斜边（步末那一侧最斜）的敌人会被**少算一个 cosine**
     * （现档最大斜度 ≈ (17−12.3)/16 ⇒ 误差 ≤ 0.06px）。刻意选"往画面内收"而不是一步测两次取并集：
     * 并集就是那个被他判掉的矩形。
     *
     * @param vx,vy 本格速度：位移为零时用它定带的朝向（位移为零 ⇒ 长度为零，方向仍要有）
     * @param halfWidth0,halfWidth1 **本步两端**各自的半长（生长完成后两者相等 ⇒ 退化成矩形）
     * @return false = 位移与速度同时为零，**没有带可言**——不猜方向，理由同 {@link #rodEnds}
     */
    public static boolean sweptBandHitsCircle(float px, float py, float x, float y,
                                              float vx, float vy,
                                              float halfWidth0, float halfWidth1,
                                              float cx, float cy, float r) {
        float dx = x - px, dy = y - py;
        float l2 = dx * dx + dy * dy;
        float ax, ay;
        if (l2 > 0f) {
            float il = 1f / (float) Math.sqrt(l2);
            ax = dx * il;
            ay = dy * il;
        } else {
            float s2 = vx * vx + vy * vy;
            if (s2 <= 0f) return false;
            float il = 1f / (float) Math.sqrt(s2);
            ax = vx * il;
            ay = vy * il;
        }
        float halfLen = 0.5f * (float) Math.sqrt(l2);
        float rx = cx - (px + x) * 0.5f;
        float ry = cy - (py + y) * 0.5f;
        float along = rx * ax + ry * ay;              // 位移方向（= 速度方向）
        float side = -rx * ay + ry * ax;              // 法向 = 杆的方向，与 rodEnds 同一套
        // 位移为零时 t 取 1 ⇒ 用"此刻"那个半长（步初步末同值，除非调用方给了 dt）
        float t = halfLen > 0f ? clamp(along + halfLen, 0f, 2f * halfLen) / (2f * halfLen) : 1f;
        float allowed = halfWidth0 + (halfWidth1 - halfWidth0) * t;
        float gapA = along - clamp(along, -halfLen, halfLen);
        float gapS = side - clamp(side, -allowed, allowed);
        return gapA * gapA + gapS * gapS <= r * r;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * 战斗部的命中半径 = 敌半径 + {@link Balance.Missile#warheadHitPad}。
     *
     * <p>杆与碎片**共用这一条**，而且 {@code WarheadRulesTest} 的战斗部覆盖不变量也读它——
     * pad 若分成两处写，"杆半长 + 敌半径 + pad ≤ ring×CELL"那条界就会跟着真值一起漂。
     */
    public static float hitRadius(Enemies.Enemy e, Balance.Missile spec) {
        return e.radius + spec.warheadHitPad;
    }

    /**
     * 本步的接触采样：把**本步扫过的带**碰到的敌人 obj 槽 OR 进 {@link Warhead#contact}。
     *
     * <p>⚠ **每步调，不是每 tick 调**（与 {@link #ticksDue} 刻意分开）。结算节拍是 0.08s，
     * 而小怪沿杆方向穿过亮线只要 9px ÷ 180px/s = 0.05s ⇒ 只在 tick 那一刻测，随机相位下有
     * 37.5% 的概率"整段穿过去而两个瞬间都不在接触区"——画面上就是"该中没中"。
     *
     * <p>⚠ "每步采一个**瞬间**的形状"这条已经废掉：现在每步采的是**带**
     * （{@link #sweptBandHitsCircle}），因为位移在设计里就是连续的。所以 {@link Warhead#px}/
     * {@link Warhead#py} 从"只为拖尾"升级成判据的输入——**任何跳过 {@link #advance} 的写法都会让
     * 带退化成零长度**，那种错读起来像"杆忽然不打人了"。
     *
     * <p>{@link SpatialGrid#query} 只负责"不漏"、**不做距离判定**，所以过滤是调用方的义务：
     * 网格里是**帧首快照**，而 {@code killEnemy} 不摘表（摘表延后到 {@code reapDead}），
     * 同一步内"血已归零但还在活跃表"的敌人必然出现在候选里。
     * ⚠ 带比点大，候选查询的覆盖界也跟着变大（位移的一半要算进去），那条界由
     * {@code WarheadRulesTest.warheadReachStaysInsideTheGridsCoverageRing} 钉住。
     *
     * @param cand 帧内复用的候选数组（长度 ≥ {@code Balance.wave.maxAlive + 4}）
     * @param dt   本步步长：算**步初**那根的半长要用 {@code w.life - dt}。⚠ 漏传（或传 0）等于
     *             把带退化成"此刻那根线段"，生长期的敌人就会又变成"该中没中"。
     */
    public static void sampleContact(Warhead w, Enemies foes, SpatialGrid grid, int[] cand,
                                     Balance.Missile spec, float dt) {
        float half0 = 0f, half1 = 0f;
        if (w.kind == Warhead.KIND_ROD) {
            half1 = rodHalfLen(w, spec);
            half0 = rodHalfLenAt(w.life - dt, spec);
        }
        int n = grid.query(w.x, w.y, cand);
        for (int j = 0; j < n; j++) {
            Enemies.Enemy e = foes.objAt(cand[j]);
            if (e == null || e.hp <= 0) continue;
            if (sweptBandHitsCircle(w.px, w.py, w.x, w.y, w.vx, w.vy, half0, half1,
                    e.x, e.y, hitRadius(e, spec))) {
                markContact(w, cand[j]);
            }
        }
    }

    /** 位号 = 敌人 obj 槽号。⚠ 槽号必须 &lt; 32，那条前提由 {@link Warheads} 构造期断言守住。 */
    public static void markContact(Warhead w, int enemySlot) {
        w.contact |= 1 << enemySlot;
    }

    /** 取出本 tick 要结算的位图并**清零**：下一次 tick 只结这之间新碰到的。 */
    public static int takeContact(Warhead w) {
        int mask = w.contact;
        w.contact = 0;
        return mask;
    }

    /**
     * 本步该结几次账。**保留余数** ⇒ 平均节拍与帧率无关。
     *
     * <p>⚠ 归属：{@code 0.08} 这个节拍数与"帧率无关"这条理由都是**我的措辞**——我在 AskUserQuestion
     * 给的选项标签「固定节拍 0.08s（帧率无关）」他挑了这一档（L14779 answers-OPT，
     * utc 2026-09-25T09:33:09.768Z ⇒ 本地 2026-09-25 17:33:09：决策是他的、字是我的）。
     * 他的原话只到「接触到亮线的扣完整伤害」（L14750，完整坐标见 {@link #shardDamage}），
     * 里面没有节拍数；方案里编号 R12 只是我的索引，别拿它当他的裁定。
     *
     * <p>禁止改写成"每 N 帧一次"：{@code tickSec = 0.08} 不是 {@code Time.STEP = 1/60} 的整数倍
     * （差 4.8 步），取整就变成帧率相关伤害——低端机 45fps 与高端机 120fps 打出的总伤不一样。
     * 单步里节拍会呈 5,5,4,5 步抖动，但累计器带余数 ⇒ 只抖相位、不抖速率。
     */
    public static int ticksDue(Warhead w, float dt, float tickSec) {
        if (tickSec <= 0f) throw new IllegalStateException("tickSec must be positive: " + tickSec);
        w.tickAcc += dt;
        int n = 0;
        while (w.tickAcc >= tickSec) {
            w.tickAcc -= tickSec;
            n++;
        }
        return n;
    }

    /**
     * 逐格积分：先落笔（{@code px/py} 供绘制拖尾**与接触带**），再位移、再记龄、最后衰减速度。
     *
     * <p>位移用**步初**速度、阻力用**步初**速率，两步都只错 {@code O(dt²)}（960px/s 起算：
     * 本步位移 16.0px vs 精确 15.98px）；顺序反过来写会让第一步的位移少掉一整块。
     *
     * <p>阻力是平方律 {@code a = -Cd·v·|v|} 的**一个步长上的精确解**：
     * {@code v ← v ÷ (1 + Cd·|v|·dt)}——只缩不转，所以杆走的是他那句"直线减速"里的直线，
     * 方向不受力影响。读法与量级的账在 {@link Balance.Missile#warheadDragCd}。
     */
    public static void advance(Warhead w, float dt, Balance.Missile spec) {
        w.px = w.x;
        w.py = w.y;
        w.x += w.vx * dt;
        w.y += w.vy * dt;
        w.life += dt;
        float cd = spec.warheadDragCd;
        if (cd > 0f) {
            float speed = (float) Math.sqrt(w.vx * w.vx + w.vy * w.vy);
            if (speed > 0f) {
                float k = 1f / (1f + cd * speed * dt);
                w.vx *= k;
                w.vy *= k;
            }
        }
    }

    public static boolean expired(Warhead w) {
        return w.life >= w.maxLife;
    }

    /**
     * 生出亮线：中心 = 弹位，速度 = **弹的速度全额继承 ＋ {@code rodEjectSpeed} 沿弹轴**
     * （伽利略叠加，不是倍乘——他 L41661「战斗部是伤害，弹速是弹速…这不就是在运动的小车上丢出
     * 一个小球，属于直线减速问题」，本轮补的数见 {@link Balance.Missile#rodEjectSpeed}），
     * 伤害 = {@link WeaponFire#damageOf(float)}（与非制导弹**同一个函数**，不在这儿抄第二份
     * {@code max(1, round)}），{@code crit} 从弹透传（分数走它，伤害里没烘）。
     *
     * <p>⚠ 叠加而不是倍乘有个可见的后果：杆从一开始就带着弹的巡航速度（B3 后 960px/s），
     *   每步 16px——判据因此必须按**扫掠带**算，否则这根快杆反而比慢杆更漏判。见
     *   {@link #sweptBandHitsCircle}。
     *
     * <p>弹零速时抛出方向无定义：那种弹本来也走不到起爆（{@code MissileBehavior} 在同一条
     * 前提上直接抛），这里只把叠加项省掉，不猜方向。
     *
     * @return 池满则 null——**少一根杆，玩家看不出来**，但 {@link Warheads#refuseTotal()} 会记账
     */
    public static Warhead spawnRod(Warheads pool, Missiles.Missile m, Balance.Missile spec) {
        Warhead w = pool.spawn();
        if (w == null) return null;
        w.kind = Warhead.KIND_ROD;
        w.x = m.x;
        w.y = m.y;
        w.px = w.x;
        w.py = w.y;
        w.vx = m.vx;
        w.vy = m.vy;
        float speed = (float) Math.sqrt(m.vx * m.vx + m.vy * m.vy);
        if (speed > 0f && spec.rodEjectSpeed != 0f) {
            float k = spec.rodEjectSpeed / speed;
            w.vx += m.vx * k;
            w.vy += m.vy * k;
        }
        w.damage = WeaponFire.damageOf(m.damage);
        w.size = m.size;
        w.color = m.color;
        w.crit = m.crit;
        w.maxLife = spec.rodLifeSec;
        return w;
    }

    /**
     * 亮线到期 ⇒ 破碎。方向 = 杆的**法向两侧交替** + 继承杆的前向速度（连续杆的物理就是杆
     * 向两侧张开成环），外圈外冲更大 ⇒ 同侧三片不会互相穿过。
     *
     * <p>⚠ **杆的字段必须在第一次 {@code pool.spawn()} 之前全部读进局部量**。槽位是 free-list
     * 后进先出：调用方若先 {@code killAt} 再破碎，杆自己就是被弹出来的第一个槽，第一片的
     * {@code reset()} 会把 {@code rod.x/y/vx/damage} 当场清零 ⇒ 剩下五片全从原点 (0,0) 以零速度
     * 生出来。这不是理论风险，是这条接线的默认形状（"先摘表再补生"是为了不让新片被末位交换
     * 搬到当前下标上、白少走一帧）。同一类坑的先例是 {@code Missiles.copyInto}：淘汰那枚也是
     * 先抄副本再把槽还回去。
     *
     * @return 实际生出的片数（池满会少几片；{@link Warheads#refuseTotal()} 记账）
     * @throws IllegalStateException {@code shardCount} 为单数——两侧交替会剩一片无处安放，
     *         那是数值表配置错误，静默少生一片是本项目最恨的失败模式
     */
    public static int spawnShards(Warheads pool, Warhead rod, Balance.Missile spec) {
        int count = spec.shardCount;
        if ((count & 1) != 0) {
            throw new IllegalStateException("shardCount must be even, got " + count);
        }
        if (count == 0) return 0;
        float len2 = rod.vx * rod.vx + rod.vy * rod.vy;
        if (len2 <= 0f) {
            throw new IllegalStateException("rod without velocity cannot fragment: 法向未定义");
        }
        float rx = rod.x, ry = rod.y, rvx = rod.vx, rvy = rod.vy;
        int rodDamage = rod.damage;
        int color = rod.color;
        boolean crit = rod.crit;
        float len = (float) Math.sqrt(len2);
        float ax = rvx / len, ay = rvy / len;          // 弹轴单位向量
        float nx = -ay, ny = ax;                       // 单位法向
        int pairs = count / 2;
        // 碎片沿**这根杆此刻的实际跨度**六等分——不是满长：到期瞬间的杆有多长，环就张多大。
        float half = rodHalfLen(rod, spec);
        int made = 0;
        for (int k = 0; k < pairs; k++) {
            for (int side = -1; side <= 1; side += 2) {
                Warhead f = pool.spawn();
                if (f == null) return made;
                // 出生点沿杆六等分（±1/6, ±3/6, ±5/6 个半长）：两侧交错、互不重叠
                float u = side * (2f * k + 1f) / count;
                f.kind = Warhead.KIND_SHARD;
                f.x = rx + nx * u * half;
                f.y = ry + ny * u * half;
                f.px = f.x;
                f.py = f.y;
                // 外圈外冲更大 ⇒ 同侧三片不会互相穿过
                float lateral = side * ((k + 1f) / pairs) * spec.shardSpeed;
                f.vx = rvx + nx * lateral;
                f.vy = rvy + ny * lateral;
                f.damage = shardDamage(rodDamage);
                f.size = spec.shardSize;
                f.color = color;
                f.crit = crit;
                f.maxLife = spec.shardLifeSec;
                made++;
            }
        }
        return made;
    }

    /**
     * 碎片伤害扣四分之一：他自敲的反馈里逐字是「接触到破碎碎片的扣四分之一（向下取整，最小为1）」
     * （L14750 kind=feedback(status=rejected)，utc 2026-09-25T09:19:51.727Z ⇒ 本地
     * 2026-09-25 17:19:51）⇒ **向下取整、最小 1** 也是他的字面，不是我替自己加的保险。
     * 方案里编号 R9（导弹仿真本体）只是索引。
     *
     * <p>从杆的整数值派生、不是第三个伤害来源：这条结构约束是我的记账，他没有说过。
     */
    public static int shardDamage(int rodDamage) {
        int d = rodDamage / 4;
        return d < 1 ? 1 : d;
    }
}
