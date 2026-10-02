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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.flexiatom.pixelraider.game;

import com.flexiatom.pixelraider.core.Time;

/**
 * 弹链（纯算术，不 import android）：把「一代 N 发弹丸」排成**固定时隙 × 若干列**，
 * 而不是在同一时刻叠在同一个点上。
 *
 * 为什么要它：先前多发弹丸是**同角重叠**画在机头上的一条线——命中循环逐发独立，所以那一串
 * 是沿线的 N 次独立命中，DPS 按 N 放大而屏幕上只有一条线（可读性错觉 + 一次吃掉 N 格池）。
 * 弹链把"多发"同时表达在**空间**（列）与**时间**（时隙）上：出膛点跟随机头，链因此会弯。
 *
 * 排布是**横向优先**的整除分配。**归属分两半，别糊在一起**：「多列子弹同时射击，并且最后一列
 * 不一定排满」那十四个字是用户第一手逐字（transcript L14614，2026-09-25T08:41:39.956Z，
 * `toolUseResult.feedback`/`status=rejected`），而**下面这个块里的全部词汇与符号是我的**
 * （时隙 / 周期 / 整除 / 弹速 / 绘制 / 列数 / 恒等 / 相切——这八个词在本会话的<b>五条</b>第一手通道
 * （plain / queued_command / feedback / answers-FREE / answers-OPT）里各 0 命中；2026-09-27 现跑，
 * 全库仅「绘制」有 1 次命中，在另一个会话 2026-09-22T08:02:00.688Z，内容是让我"绘制修改结果"，与弹链无关）。
 * 闭合那一行（{@code base·columns + rem = N}）也是我推的，不是他给的判据——他同一条记录里举的算例
 * 写着「后7-y=7-3=5次」，7−3 真值是 4 ⇒ 那条算例本身有笔误。
 * <pre>
 * interval    = 走完一个绘制弹长所需的时间，**向上取整到整数个固定步**  ← 见下
 * 一列容量 C   = max(1, floor(周期 / interval))   ← 同列相邻两发首尾相切不重叠
 * columns     = min(MAX_COLUMNS, max( min(N, COLUMN_CAP), ceil(N / C) ))
 *               ↑审美上限      ↑时间预算逼出来的最少列数（见下"两条上界"）
 * used        = ceil(N / columns)                 ← 本代真正占用的时隙数
 * base = N / columns,  rem = N % columns
 * 第 k 时隙发数 = (k &lt; base) ? columns : rem     ← 前 base 个时隙各满列，末尾剩 rem
 *               （那 rem 发占哪几列：居中，见 {@link #step}）
 * 闭合：base·columns + rem = N                   ← 发数永不丢，这条由测试钉住
 * </pre>
 *
 * 「横向优先」是本轮（#55）改掉的旧律。旧律是**时间优先**——先把一列的 C 格填满才开下一列
 * （{@code x = N/C, y = N%C, columns = x + (y>0?1:0)}），于是只要 {@code N ≤ C} 就恒有
 * {@code columns == 1}：整代挤在同一列的连续时隙里，张角因此永远是 0。六把枪的基础 N
 * （脉冲 1／激光 1／散射 5／导弹 2／电弧 1／回旋 2）**全都 ≤ 自己的 C**，所以他看到的现象是
 * 准确的——「pellets 每级加一后弹列未分开」：加一级只是把同一条竖线延长，屏幕上没有长出第二列。
 * 新律把横向当成第一预算：N 发就是 N 列（到 {@link #COLUMN_CAP} 为止），"每级加一"每一级
 * 多一根并列的弹链，散射基础立刻是一张 5 列 44° 的真扇面。裁定逐字（AskUserQuestion，
 * 2026-10-01）：修到哪一层 =「两条都改」（分配律 ＋ 列间距），分开量 =「空 1 弹宽」。
 *
 * 两条上界不是重复：{@code COLUMN_CAP} 是**审美上限**（一发一列，铺到它为止），
 * {@code ceil(N/C)} 是**时间预算逼出的最少列数**——列数若低于它，排放窗口 {@code used·interval}
 * 就会跨过开火周期，下一代就绪时本代末尾的时隙还没发出去，被 {@code beginGeneration} 的游标
 * 归零<b>静默吃掉</b>。所以取两者的大值；大值仍装不下才抛（{@link #layout}）。
 *
 * 「向上取整到整数个固定步」是 2026-09-25 实现时新加的一条，**它改掉的正是用户逐字给的判据**
 * （L14337，2026-09-25T07:19:15.178Z，answers-**FREE**「固定，但是取同子弹长度间隔需要的时间间隔」
 * ⇒ {@code interval = 弹长/弹速} 这个纯物理值）。这不是"实现细节顺带偏离"，是我对他的裁定做了一次
 * 实质修改，理由与代价都记在下面，改的人要认这笔账。
 * 理由：弹链只在固定步长上排放（{@code Time.STEP = 1/60}，一渲染帧最多
 * 5 步），所以**步长的整数倍是唯一能被表达的时间**。两条后果：
 * <ul>
 *   <li><b>比一步还短的间隔不存在</b>——{@code while (acc >= interval)} 会在一帧里连发两三个
 *       时隙，那几发出膛点相同、速度相同，从此永久重叠成一发。六把枪里有三把落在一步以下
 *       （脉冲 0.73 步、激光 0.66 步、散射 0.90 步），照原式算恰好让这三把退化成弹链要修掉的老毛病。</li>
 *   <li><b>非整步的间隔会抖</b>——导弹原值 1.855 步：实测发射节奏在"隔 1 帧"与"隔 2 帧"之间
 *       交替，落在"隔 1 帧"那一档时两发只相距 3.7px，而弹长 6.8px ⇒ 照样重叠。回旋（1.569 步）
 *       同理，电弧（1.02 步）每约 50 帧漏一帧。</li>
 * </ul>
 * 取整的方向是<b>上</b>，因为"相切不重叠"是几何要求——"相切"这个词是我的，但它服务的两条原文都是
 * 用户逐字：L14337（answers-**FREE**）要求间隔「取同子弹长度间隔需要的时间」，L14614
 * （feedback/rejected）要求「多列子弹同时射击，并且最后一列不一定排满」⇒ 同列两发不许叠。
 * 四舍五入会把电弧留在弹长以下
 * （实测间距 5.0 &lt; 弹长 5.1）。代价只有链更疏，而且各武器差得很不均：同列相邻两发的实测间距
 * 相对弹长分别多出 散射 +11%、导弹 +8%、回旋 +28%、脉冲 +37%、激光 +52%、<b>电弧 +96%</b>
 * （电弧的物理间隔是 1.02 步，正好卡在"再一格就翻倍"的位置上）。DPS 一点不变——射速由周期决定，
 * 与 C 无关，变的只是链的疏密。
 *
 * 于是各武器的一列容量 C（{@code BulletChainTest} 扫真表钉住这些数）：
 * <pre>
 *   脉冲 8  激光 5  散射 18  导弹 13  电弧 7  回旋 11      （基础周期）
 * </pre>
 * C 在新律下**不再是列数的闸门**，只管"一列叠几发"：{@code N ≤ C} 时 {@code used == 1}，
 * 整代一步打完（纯横向）；{@code N > C·COLUMN_CAP} 才需要更多列来分摊时间。激光恒 {@code N=1}
 * ⇒ 恒单列（它不吃扳机，见下）。
 *
 * 周期取**有效冷却**（{@code fireGap / 射速乘子}），不是基础 fireGap。这一条同样是让"相切"成立的
 * 关键：乘子大 ⇒ 周期短 ⇒ C 小 ⇒ 列数多（前面变宽），而不是同列两发糊成一条实线；乘子小
 * （迟滞/寒潮）⇒ 周期长 ⇒ 一代弹链摊得更开。而 {@code interval} 只由弹长与弹速决定，不吃乘子
 * ⇒ "相切"这件事与射速无关，变的只是列数。
 *
 * ⚠ 排放窗口 {@code C·interval} 只保证**不超出周期一步以上**，不保证严格小于周期：比值落在
 * 整数上时 EPS 会放行最后一格（真表达档：脉冲 @成长树 1.2× 射速 ⇒ 比值 6.999999 ⇒ C=7）。
 * 于是最后一个时隙可能与"下一代就绪"撞在同一步上，而 {@code beginGeneration} 会把游标归零——
 * 撞车时**必须先推进链再开下一代**，否则整代末尾那个时隙（最多 {@code COLUMN_CAP} 发）会被
 * 静默吃掉。那条顺序写在 {@code Game.step} 里并由 {@code BulletChainTest} 的撞车用例钉住。
 * 横向优先之后这条更容易撞上（{@code used} 可以正好等于 C），所以那条顺序**不是历史包袱**。
 *
 * 列的几何：横向偏移 {@code (j − (columns−1)/2)·2·size}（列中心距 = 2 弹宽 ⇒ 相邻两列之间
 * <b>空出整整一个弹宽</b>，整组居中）+ 张角 {@link WeaponFire#pelletAngle}（"中心那发绝不偏"
 * 沿用旧 {@code spreadAngles} 的口径）。旧律的间距是 1 弹宽——那是"边缘相切、零空隙"，
 * 两列看起来仍是一条带，正是 #55 的另一半病因。
 * ⇒ 单列武器恒为正上方一条链；多列武器既横向排开又按本武器张角张开。
 *
 * 末尾那个<b>短档</b>（{@code rem} 发那一次）在 {@link #step} 里**按列号居中**，不是从 0 号列开始：
 * 不居中的话 N=9 的第二次齐射就是一发孤零零打在扇面最外左列，看着像 bug 而不是"还剩一发"。
 * 居中之后它落在最中间那一（或两）列上，张角也一并回到接近 0——短档读起来是"收窄的同一次齐射"。
 *
 * 布局只在**购买 / 换枪 / 有效冷却变化**时算一次（{@link #matches} 是三条浮点/整型比较）。
 * 每帧路径上只有比较与加法；两次整型除法只出现在**时隙真的到点**的那一帧（窗口起点与发数），
 * 而绝大多数帧根本走不到那里。
 */
public final class BulletChain {

    /**
     * [可调] 定长数组容量与横向上限。越界是**数值表配置错误**，{@link #layout} 直接抛、不静默钳
     * （先例 {@code SpatialGrid.insert}；"静默截断"正是本项目最恨的失败模式）。
     *
     * <p>余量按可达的**极限档**算，不按默认档算（{@code BulletChainTest} 把这段推导钉住）：
     * <pre>
     * 时隙上界 = 周期最长 ÷ 间隔：射速乘子最低档 0.75×0.9×0.9 = 0.6075（迟滞×寒潮×风暴），
     *            散射 0.30/0.6075 ÷ 一步 = 29  ← bound；导弹 22、回旋 18、脉冲 13、电弧 12、激光 8
     *            （C 在新律下是"一列容量"，{@code used ≤ C} 才是数组用量，所以这条 bound 照旧。）
     * 列数上界 = 两个来源取大：审美上限 {@link #COLUMN_CAP} = 8，或时间预算逼出的 ceil(N/C)。
     *            后者按**可达**档位实算最大 7（脉冲 @1.62× ⇒ C=5、电弧 @1.62× ⇒ C=4，N=26），
     *            所以正常玩每一把枪都停在 8 列。但扫描档里它更大：激光按 @1.944× 且强行给满发数
     *            ⇒ C=2 ⇒ 逼出 13 列，那条组合两重都不达（1.944 已随商店射速卡退役、激光又不吃发数卡），
     *            留在扫描里是因为"今后谁把这两条例外加回来"正是它先炸的场景 ⇒ 16 这个硬顶**不是纸面余量**。
     *            发数上界的来历：射速乘子上界 1.35×1.2 = 1.62（商店卡不再贡献射速：裁定日 2026-09-25 =
     *            L14292 answers-FREE「射速本身固定，但是子弹每次+1」，落地日 2026-09-26；
     *            "改卖发数"这四个字是我的措辞，不是他的），且扳机取消满级后按收入上界贪婪叠满
     *            lv24 ⇒ N = 25 + 基础。
     *            （激光 N 恒 1，因为它不吃扳机——这条例外是用户第一手逐字：L14337 answers-FREE
     *            「子弹类武器都是这套，也就是除激光外的所有」＋ L14357 answers-FREE「激光的规则是接触到
     *            激光的扣固定血，只吃+几伤害，不存在射速一说」。旧律里它是"26/3 = 9 列"那条潜在越界者，
     *            新律里它只有被强行喂满发数才碰得到横向预算（上面那条 13 列）⇒ 数组容量同样
     *            **不依赖那条例外**。）
     * </pre>
     * 于是取 40 / 16：时隙留 38% 余量，列数在审美上限之上再留 2 倍硬顶。两个数都是**静态可达上界**，
     * 不是"当前表跑出来的值"——今后把某把枪的 {@code fireGap} 或弹速改到越界，应该让它在这里炸。
     */
    public static final int MAX_SLOTS = 40;
    public static final int MAX_COLUMNS = 16;

    /**
     * [可调] 横向审美上限：一代最多铺开几列，超过它才往时间（时隙）上叠。
     *
     * <p>8 的来历是**画布**不是池：逻辑宽 240，最宽的那把枪弹体 4px ⇒ 列间距 8px ⇒ 满 8 列
     * 总宽 56px（±28），机头贴边时机头外侧那几列出膛点就在屏外。再宽就成了一条横辐而不是扇面。
     */
    public static final int COLUMN_CAP = 8;

    /** 浮点除法的兜底格子，只用于两次取整，见 {@link #layout}。 */
    private static final float EPS = 1e-4f;

    private final int[] pelletsAt = new int[MAX_SLOTS];
    private final float[] colOffset = new float[MAX_COLUMNS];
    private final float[] colAngle = new float[MAX_COLUMNS];

    /** 布局的三个输入，{@link #matches} 靠它们判断"要不要重算"。见类注释末尾。 */
    private int layoutWeaponId = -1;
    private int layoutPellets;
    private float layoutPeriod = -1f;

    private float interval;
    private int slots;
    private int periodSlots;
    private int columns;
    private int totalPellets;

    /** 这一代的模板：伤害与暴击在 {@link #beginGeneration} 这一刻冻结，整代共用一次判定。 */
    private final WeaponFire.Template tpl = new WeaponFire.Template();

    private int next;
    private float acc;

    /**
     * 当前布局是否就是这三个输入的结果。
     *
     * <p>三个数**完全决定**几何：张角/弹宽/绘制长度/弹速全从 {@code weaponId} 查表得到，
     * 扳机卡的加成已经算进 {@code pellets}，射速乘子已经算进 {@code period}。所以这里不必
     * 比较武器表的字段，比这三个数就够。
     *
     * <p>{@code period} 用**精确浮点相等**判断是刻意的：它是两个表值相除，同样的输入逐位相同，
     * 而乘子只有离散档位（1.0 / 0.6075 / 1.62…）。于是"每帧一次比较"实际是"每帧一次不成立"，
     * 而不是"每帧都重算"。
     */
    public boolean matches(Balance.Weapon w, int pellets, float period) {
        return layoutWeaponId == w.id && layoutPellets == pellets && layoutPeriod == period;
    }

    /** 固定时隙间隔（秒）——恒为 {@link Time#STEP} 的整数倍，这正是"每帧最多发一个时隙"的保证。 */
    public float slotInterval() { return interval; }

    /** 这一代**实际占用**的时隙数（横向优先之后它可以小于 {@link #periodSlots()}）。 */
    public int slots() { return slots; }

    /** 一列的容量 C = 周期装得下几个时隙。排放窗口必须落在它里面，见 {@link #layout}。 */
    public int periodSlots() { return periodSlots; }

    public int columns() { return columns; }

    /** 这一代计划出的总发数（= 布局时给的 N，未截断）。 */
    public int totalPellets() { return totalPellets; }

    public int pelletsAt(int k) { return pelletsAt[k]; }

    public float columnOffset(int c) { return colOffset[c]; }

    public float columnAngle(int c) { return colAngle[c]; }

    /** 还没发的时隙数。0 = 这一代排完。 */
    public int remaining() {
        int r = slots - next;
        return r < 0 ? 0 : r;
    }

    /**
     * 算一次布局。**不动发射进度**（{@code next/acc} 归 {@link #beginGeneration} 管）——
     * "换枪不清链"与"换枪不清已飞的弹"是同一条口径：已经排出去的那一代不该被下一次布局抹掉。
     *
     * <p>列数取「审美上限」与「时间预算逼出的最少列数」的大值（{@code used ≤ C} 是本方法的
     * 出口不变式）；每列间距 2 弹宽，见 {@link #columnPitch}。
     *
     * @param pellets 这一代的弹丸总数 N（≥1；调用方负责加扳机卡的加成）
     * @param period  当前的**有效**开火周期（秒）= {@code fireGap / 射速乘子}
     * @throws IllegalStateException 周期或弹速/弹长非法，或布局超出定长数组／时隙装不进周期——
     *                               那说明数值表被改坏了，不是运行时该吞的事
     */
    public void layout(Balance.Weapon w, int pellets, float period) {
        int n = pellets < 1 ? 1 : pellets;
        float len = w.size * w.drawLenRatioY;          // 绘制弹长：与 Game.drawShots 同一个数
        float speed = w.bulletSpeed;
        // 弹速为 0 会让 interval 爆炸；这类配置错误同样是抛，不是"这一代不发弹"。
        if (speed <= 0f || len <= 0f || period <= 0f) {
            throw new IllegalStateException("chain needs positive speed/len/period: "
                    + w.name + " speed=" + speed + " len=" + len + " period=" + period);
        }
        float raw = len / speed;                       // 走完一个绘制弹长所需的物理时间
        // 间隔向上取整到整数个固定步：非整步的间隔表达不出来，只会抖出重叠（见类注释）。
        // 两处取整都带 EPS，因为**真表的除法确实会掉在整数下方一格**：0.14f/1.2f 一步 = 6.999999、
        // 0.38f/1.2f 两步 = 18.999998（1.2 是成长树射速档，今天就可达）。裸 floor 会把回旋的
        // 19 个时隙读成 18 个——不多不少，就是一格平白无故地没了。
        int steps = (int) Math.ceil(raw / Time.STEP - EPS);
        if (steps < 1) steps = 1;                  // 亚步长间隔的最短可表达形式就是一步
        float iv = steps * Time.STEP;
        int c = (int) Math.floor(period / iv + EPS);
        if (c < 1) c = 1;                              // 周期短于一个间隔：只能全靠横向铺开（used 必为 1）
        if (c > MAX_SLOTS) {
            throw new IllegalStateException("chain slots " + c + " > MAX_SLOTS=" + MAX_SLOTS
                    + ": " + w.name + " period=" + period + " interval=" + iv);
        }
        int want = n < COLUMN_CAP ? n : COLUMN_CAP;   // 一发一列，铺到审美上限
        int need = (n + c - 1) / c;                   // 时间预算逼出的最少列数（窗口必须落在周期里）
        int cols = Math.min(MAX_COLUMNS, Math.max(want, need));
        int used = (n + cols - 1) / cols;
        if (used > c) {
            // 到了数组硬顶还是装不下：宁可抛，不静默丢发——丢发在账本上和"这一代少买一级"同形。
            throw new IllegalStateException("chain window " + used + " slots > period capacity " + c
                    + " at MAX_COLUMNS=" + MAX_COLUMNS + ": "
                    + w.name + " pellets=" + n + " interval=" + iv);
        }
        layoutWeaponId = w.id;
        layoutPellets = n;
        layoutPeriod = period;
        interval = iv;
        periodSlots = c;
        slots = used;
        columns = cols;
        totalPellets = n;
        int base = n / cols;
        int rem = n % cols;
        for (int k = 0; k < used; k++) {
            pelletsAt[k] = k < base ? cols : rem;     // 闭合 base·cols + rem = N
        }
        for (int j = 0; j < cols; j++) {
            colOffset[j] = (j - (cols - 1) / 2f) * columnPitch(w);
            colAngle[j] = WeaponFire.pelletAngle(j, cols, w.spreadDeg);
        }
    }

    /**
     * 相邻两列的中心距 = 2 个弹宽 ⇒ 列与列之间恰好<b>空出一个弹宽</b>。
     *
     * <p>旧律是 1 弹宽（边缘相切、零空隙），两列在屏幕上读起来还是同一条带——那是 #55 的另一半
     * 病因（"弹列未分开"）。裁定（2026-10-01，AskUserQuestion）逐字：分开量按「空 1 弹宽」。
     */
    private static float columnPitch(Balance.Weapon w) {
        return w.size * 2f;
    }

    /**
     * 开火这一刻冻结整代并**从头开始**排放：伤害、颜色、弹体尺寸、武器号、暴击标志。
     * 复制而不是持引用——"整代共享一次暴击判定"是约定，不能靠调用方自觉。
     */
    public void beginGeneration(WeaponFire.Template t) {
        tpl.set(t);
        next = 0;
        acc = 0f;
    }

    /**
     * 推进链：把到点的时隙发出去。因为 {@code interval} 现在是整数个固定步，而 {@code Game} 每步
     * 喂进来的正是 {@code Time.STEP}（定格时更少），**一步最多冒一个时隙**——这正是"相邻两发不重叠"
     * 的另一半保证。留 {@code while} 而不写 {@code if}：它同时是"步长将来改了也不会丢发"的兜底。
     *
     * <p>两张池按 {@link WeaponFire.Template#guided} 分流，**全项目只有这一处**按制导与否分叉：
     * 制导弹进 {@link Missiles}（之后由 {@link MissileBehavior} 逐步导引），其余进
     * {@link BulletPool}（定死直飞）。分叉读的是代模板而不是武器表——链在开火那一刻已经把
     * {@code w} 丢了，而"这一代是哪把枪"必须与伤害/暴击同一份冻结值。
     *
     * <p>返回值是**两条分支的合计**，所以调用方的出膛记账（{@code stats.addShots}）只需一处。
     * 这不是省事：fired 必须是所有可能被记 hit 的实体的父集，分两处记就有漏一处的那条路，
     * 而漏掉导弹侧的后果是 {@code shotsHit > shotsFired} 被 {@code Rating.clamp01} 静默钳成
     * "命中率 100%"——全链路没有任何地方会红。
     *
     * @param noseX,noseY 机头位置——**每帧重读**，所以边移动边开火时链是弯的
     * @param missiles    制导弹池；本代没有制导弹时允许为 null
     * @return 这一帧实际出膛的发数（0 是常态：大多数帧根本没有时隙到点）
     * @throws IllegalStateException 这一代是制导弹但没给池——那是接线错误，不是"这一代不发弹"
     */
    public int step(float dt, float noseX, float noseY, BulletPool pool, Missiles missiles) {
        if (next >= slots) return 0;
        if (tpl.guided && missiles == null) {
            throw new IllegalStateException("guided generation weaponId=" + tpl.weaponId
                    + " but no Missiles pool was given");
        }
        acc += dt;
        int made = 0;
        // while 最多 MAX_SLOTS 次，且 dt 恒定 ⇒ 没有除法、没有分配
        while (next < slots && acc >= interval) {
            acc -= interval;
            int n = pelletsAt[next++];
            int start = (columns - n) / 2;        // 短档居中；n ≤ columns，故 start+n-1 恒不越列
            for (int i = 0; i < n; i++) {
                int col = start + i;
                float x = noseX + colOffset[col];
                float deg = colAngle[col];
                if (tpl.guided) WeaponFire.fireMissile(missiles, tpl, x, noseY, deg);
                else WeaponFire.fireOne(pool, tpl, x, noseY, deg);
                made++;
            }
        }
        return made;
    }

    /** 清场：换局用。已经出膛的弹不归这里管（它们在池里）。 */
    public void clear() {
        next = slots;
        acc = 0f;
    }
}
