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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.flexiatom.pixelraider.core.Time;
import com.flexiatom.pixelraider.game.BulletPool.Bullet;

import org.junit.Test;

/**
 * 弹链几何（提案 bullet-chain-geometry）。
 *
 * 算术大部分用**合成武器**跑：{@code Balance.Weapon} 的构造器是包内可见的，所以这里能造出
 * 干净的数，断言里不必夹一层浮点噪声，也不必把六把枪的真值抄进测试（抄表 = 第二个真源，
 * 表一改就红成假警报）。真表只在"扫全表""整步量化""撞车"这几条里出现，那里断的是**不变量**
 * 与**可达档上的具体 C**，不是随手挑的数。
 *
 * 合成武器取 {@code 弹长 10 ÷ 弹速 100 = 0.1s = 6 个固定步}：间隔本身正好是整数步，所以
 * {@code interval} 这条量化路径在测试里是"原样通过"而不是"被修过"；周期取 0.55s ⇒
 * {@code C = floor(5.5) = 5}，比值离整数半格，不会被两处取整的 EPS 影响。
 * **正好落在整数比值上的那种周期**反而是要单独测的东西（见 {@link #anExactMultipleKeepsItsSlot}）——
 * 真表里就有这种档（脉冲 @1.2× 射速 ⇒ 周期 7 步、比值 6.999999），躲开它等于把坑留给线上。
 */
public class BulletChainTest {

    private static final float EPS = 1e-5f;
    /** 合成武器的时隙间隔：绘制弹长 10 ÷ 弹速 100 = 六个固定步。 */
    private static final float IV = 0.1f;
    /** 合成武器的开火周期 ⇒ C = 5、比值 5.5（不在整数边界上，见类注释）。 */
    private static final float PERIOD = 0.55f;
    private static final int SLOTS = 5;
    /** 测试用屏幕：与 {@code Game} 的 (逻辑宽, logicH 常见值, 边距) 同量级即可，不参与断言。 */
    private static final int SCREEN_W = 240, SCREEN_H = 533, MARGIN = 16;
    private static final float NOSE_X = 120f, NOSE_Y = 300f;

    private static Balance.Weapon weapon(int id, float speed, float len, float period) {
        Balance.Weapon w = new Balance.Weapon(id, "T" + id, 0);
        w.bulletSpeed = speed;
        w.size = len;
        w.drawLenRatioY = 1f;      // 绘制弹长 == size ⇒ 间隔 = len/speed
        w.fireGap = period;
        w.pellets = 1;
        w.damage = 1;
        w.color = 1;
        return w;
    }

    private static Balance.Weapon synthetic(int id) {
        Balance.Weapon w = weapon(id, 100f, 10f, PERIOD);
        // 前提自查：合成武器的物理间隔必须正好是 IV，而 IV 必须是整数个固定步——否则取整会介入，
        // 下面所有按 SLOTS=5 写的断言测的就不是我以为的那个布局了。
        assertEquals("合成武器的物理间隔必须正好是 IV", IV, w.size / w.bulletSpeed, 1e-6f);
        assertEquals("IV 必须是整数个固定步", 0f,
                IV / Time.STEP - Math.round(IV / Time.STEP), 1e-3f);
        return w;
    }

    // ---- 排布闭合 -------------------------------------------------------------------------------

    /** 有余数：前 y 个时隙各 x+1 发、其余 x 发，加起来必须正好 N（这是那条例数除法的全部内容）。 */
    @Test
    public void layoutClosesOverTheRemainder() {
        BulletChain c = new BulletChain();
        c.layout(synthetic(0), 17, PERIOD);                    // C=5，N=17 ⇒ x=3 y=2
        assertEquals(SLOTS, c.slots());
        assertEquals(4, c.columns());
        int sum = 0;
        for (int k = 0; k < c.slots(); k++) sum += c.pelletsAt(k);
        assertEquals("逐时隙发数加起来必须等于 N，少一发就是静默丢弹", 17, sum);
        assertEquals(4, c.pelletsAt(0));
        assertEquals(4, c.pelletsAt(1));
        assertEquals(3, c.pelletsAt(2));
        assertEquals(3, c.pelletsAt(4));
        assertEquals(17, c.totalPellets());
    }

    /** 整除：所有时隙都是 x 发，列数正好 x，不存在"半满的末列"。 */
    @Test
    public void layoutClosesExactlyWhenDivisible() {
        BulletChain c = new BulletChain();
        c.layout(synthetic(1), 10, PERIOD);                    // C=5，N=10 ⇒ x=2 y=0
        assertEquals(2, c.columns());
        for (int k = 0; k < c.slots(); k++) {
            assertEquals("整除时每个时隙都该是 x 发", 2, c.pelletsAt(k));
        }
    }

    /** x 可以为 0（N < C，六把枪的**默认档**全是这种）：实现不许拿 x 当除数。 */
    @Test
    public void fewerPelletsThanSlotsMakesASingleChain() {
        BulletChain c = new BulletChain();
        c.layout(synthetic(3), 2, PERIOD);                     // C=5，N=2 ⇒ x=0 y=2 ⇒ 1 列
        assertEquals(1, c.columns());
        assertEquals(SLOTS, c.slots());
        assertEquals(1, c.pelletsAt(0));
        assertEquals(1, c.pelletsAt(1));
        assertEquals(0, c.pelletsAt(2));
        assertEquals(0, c.pelletsAt(4));
        assertEquals("单列必须正对上方", 0f, c.columnAngle(0), 0f);
        assertEquals("单列不该有横向偏移", 0f, c.columnOffset(0), 0f);
    }

    /** N ≤ 0 是调用方的错误，但链不该因此少发或炸掉：按 1 发处理。 */
    @Test
    public void zeroPelletsIsOnePelletNotZero() {
        BulletChain c = new BulletChain();
        c.layout(synthetic(4), 0, PERIOD);
        assertEquals(1, c.totalPellets());
        BulletPool pool = new BulletPool(8);
        c.beginGeneration(template(Balance.weapons[Balance.Weapon.PULSE]));
        assertEquals(1, drain(c, pool));
    }

    // ---- 时隙与周期的关系 -----------------------------------------------------------------------

    /** 一代的排放窗口必须落在周期之内（否则两代叠在一起），又不能浪费掉一整格。 */
    @Test
    public void generationWindowFitsThePeriodWithoutWastingASlot() {
        float[] periods = {0.1f, 0.14f, 0.26f, 0.55f, 1f};
        for (float period : periods) {
            BulletChain c = new BulletChain();
            Balance.Weapon w = weapon(20, 100f, 10f, period);
            c.layout(w, 3, period);
            float window = c.slots() * c.slotInterval();
            assertTrue("排放窗口 " + window + " 超出周期一步：那一格会撞上下一代的触发步",
                    window <= period + Time.STEP);
            assertTrue("周期还装得下一个时隙却没排：C 算小了", window + c.slotInterval() > period - EPS);
        }
    }

    /**
     * 亚步长的间隔取整到**一步**（向上取整的下界），于是排放节奏是"每一步一个时隙"——
     * 一步之内绝不连发两发，因为那两发出膛点与速度都相同，会永久重叠成一条弹。
     */
    @Test
    public void subStepIntervalBecomesExactlyOneStepPerSlot() {
        BulletChain c = new BulletChain();
        Balance.Weapon fast = weapon(21, 4000f, 1f, 0.5f);      // len/speed = 1/4000 ≪ 一步
        assertTrue("前提不成立：这条测的必须是亚步长的武器", fast.size / fast.bulletSpeed < Time.STEP);
        c.layout(fast, 4, 0.5f);
        assertEquals(Time.STEP, c.slotInterval(), 0f);
        BulletPool pool = new BulletPool(64);
        c.beginGeneration(template(Balance.weapons[Balance.Weapon.PULSE]));
        for (int step = 0; step < 20 && c.remaining() > 0; step++) {
            int made = c.step(Time.STEP, NOSE_X, NOSE_Y, pool, null);
            assertTrue("第 " + step + " 步连发了 " + made + " 发：同点出膛 = 永久重叠", made <= 1);
        }
    }

    /**
     * 取整方向是**上**：任何一把真枪的实测弹距都不许短于绘制弹长。
     *
     * <p>这条守的是"相切不重叠"里"不重叠"那一半。四舍五入会让电弧留在 5.0 &lt; 5.1px（差 2%，
     * 但裁定要的是不重叠，不是差不多）；导弹与回旋更糟——它们的物理间隔是 1.855 / 1.569 步，
     * 不量化就"隔一帧发一次"，实测弹距 3.7px 与 4.3px，都远短于 6.8px 的弹长。
     */
    @Test
    public void quantizationAlwaysRoundsUpToAWholeStep() {
        for (Balance.Weapon w : Balance.weapons) {
            float len = w.size * w.drawLenRatioY;
            BulletChain c = new BulletChain();
            c.layout(w, Math.max(1, w.pellets), w.fireGap);
            float steps = c.slotInterval() / Time.STEP;
            assertEquals(w.name + " 的间隔不是整数个固定步", Math.round(steps), steps, 1e-3f);
            float spacingPx = c.slotInterval() * w.bulletSpeed;
            assertTrue(w.name + " 实测弹距 " + spacingPx + " 短于绘制弹长 " + len,
                    spacingPx >= len - 1e-3f);
            assertTrue(w.name + " 的间隔比弹长长出一步以上：取整取过头了",
                    spacingPx < len + w.bulletSpeed * Time.STEP);
        }
    }

    /**
     * 真表里确实有三把枪落在一步以下、三把落在"整数步之间"——向上取整不是假想敌，
     * 而是六把枪全都要过的关卡。改过任何一个 {@code fireGap}/弹速/弹长就得回来看这条。
     */
    @Test
    public void realTableNeedsTheRoundingOnEveryWeapon() {
        int subStep = 0;
        for (Balance.Weapon w : Balance.weapons) {
            float rawSteps = w.size * w.drawLenRatioY / w.bulletSpeed / Time.STEP;
            if (rawSteps < 1f) subStep++;
            // 没有任何一把的物理间隔正好是整数步（"正好"到 1e-3 步），所以取整必然介入
            assertTrue(w.name + " 的物理间隔已经是整数步了，把这条测试改成断具体档",
                    Math.abs(rawSteps - Math.round(rawSteps)) > 1e-3f);
        }
        assertEquals("脉冲 0.73 步、激光 0.66 步、散射 0.90 步", 3, subStep);
    }

    /**
     * 周期正好是间隔的整数倍时**不许掉一格**。float 除法真会把它压到整数下方：
     * 脉冲 @1.2× 射速（成长树满档）⇒ 周期 0.14f/1.2f = 7 步，而 {@code period/interval} 算出来是
     * 6.999999 ⇒ 裸 floor 得 6，一代白白少排一个时隙。
     */
    @Test
    public void anExactMultipleKeepsItsSlot() {
        Balance.Weapon pulse = Balance.weapons[Balance.Weapon.PULSE];
        float period = pulse.fireGap / 1.2f;
        BulletChain c = new BulletChain();
        c.layout(pulse, 1, period);
        assertEquals("前提：这条测的必须是周期与间隔成整数倍的档",
                7f, period / c.slotInterval(), 1e-4f);
        assertEquals("整除的周期不许被浮点舍入吃掉一格", 7, c.slots());
        // 反方向的陷阱：物理间隔正好是两步时，ceil 也不许多跳一步
        BulletChain two = new BulletChain();
        Balance.Weapon exact = weapon(22, 300f, 10f, 0.19f);     // 10/300 = 1/30 s = 恰好两步
        assertEquals("前提：1/30 秒必须是恰好两步", 2f, (exact.size / exact.bulletSpeed) / Time.STEP, 1e-4f);
        two.layout(exact, 3, 0.19f);
        assertEquals("向上取整不许把已经整步的间隔再抬一格", 2f, two.slotInterval() / Time.STEP, 1e-5f);
    }

    // ---- 三触发器 -------------------------------------------------------------------------------

    /** 布局的三个输入（武器号 / 弹丸数 / 有效周期）各自变一点都必须重算，全不变则不许重算。 */
    @Test
    public void layoutCacheTracksExactlyItsThreeInputs() {
        Balance.Weapon a = synthetic(30);
        Balance.Weapon b = synthetic(31);
        BulletChain c = new BulletChain();
        assertFalse("没布局过之前不该 matches", c.matches(a, 6, PERIOD));
        c.layout(a, 6, PERIOD);
        assertTrue(c.matches(a, 6, PERIOD));
        assertFalse("换枪 ⇒ 重算", c.matches(b, 6, PERIOD));
        assertFalse("买卡（弹丸数变）⇒ 重算", c.matches(a, 7, PERIOD));
        assertFalse("射速状态变（周期变）⇒ 重算", c.matches(a, 6, PERIOD / 1.62f));
        // matches 认的是 id 而不是对象：换一把表里同样的武器对象不该触发重算
        assertTrue(c.matches(synthetic(30), 6, PERIOD));
    }

    /**
     * 射速乘子把周期压短 ⇒ C 必须跟着变小、列数跟着变多。
     *
     * <p>这条是"用**有效**周期而不是基础 fireGap 布局"的理由本身：拿基础周期的布局排完要
     * {@code C·interval} = 0.5s，而狂热之后实际周期只有 0.34s ⇒ 上一代还没排完下一代就开了，
     * 两代的弹在同一条线上叠起来——正是弹链要修掉的那个毛病。
     */
    @Test
    public void fasterFireRateNarrowsTheChainIntoMoreColumns() {
        BulletChain base = new BulletChain();
        base.layout(synthetic(32), 17, PERIOD);
        float fastPeriod = PERIOD / 1.62f;
        BulletChain fast = new BulletChain();
        fast.layout(synthetic(32), 17, fastPeriod);
        assertTrue("乘子变大 C 必须变小", fast.slots() < base.slots());
        assertTrue("C 变小 ⇒ 同 N 下列数变多", fast.columns() > base.columns());
        assertTrue("拿基础周期的布局会跨过实际周期（这就是不能用基础周期的证据）",
                base.slots() * base.slotInterval() > fastPeriod);
        int sum = 0;
        for (int k = 0; k < fast.slots(); k++) sum += fast.pelletsAt(k);
        assertEquals("变密之后仍然一发不丢", 17, sum);
    }

    // ---- 排放 -------------------------------------------------------------------------------

    /** 不喂时间就不推进：这一条守的是"暂停必须整体冻结"，不需要任何专门的暂停代码。 */
    @Test
    public void feedingNoTimeEmitsNothing() {
        BulletChain c = new BulletChain();
        c.layout(synthetic(33), 5, PERIOD);
        c.beginGeneration(template(Balance.weapons[Balance.Weapon.PULSE]));
        BulletPool pool = new BulletPool(16);
        assertEquals(0, c.step(0f, NOSE_X, NOSE_Y, pool, null));
        assertEquals(c.slots(), c.remaining());
        assertEquals(0, pool.activeCount());
    }

    /** 排完之后继续喂时间也不许再冒出发来（下一代由 beginGeneration 重新开）。 */
    @Test
    public void drainedChainStaysSilent() {
        BulletChain c = new BulletChain();
        c.layout(synthetic(34), 5, PERIOD);
        c.beginGeneration(template(Balance.weapons[Balance.Weapon.PULSE]));
        BulletPool pool = new BulletPool(16);
        assertEquals(5, drain(c, pool));
        assertEquals(0, c.remaining());
        for (int i = 0; i < 600; i++) assertEquals(0, c.step(Time.STEP, NOSE_X, NOSE_Y, pool, null));
    }

    /** clear 之后这一代剩下的时隙作废，但**已出膛的弹**不归链管（它们在池里继续飞）。 */
    @Test
    public void clearAbortsTheRestOfTheGenerationOnly() {
        BulletChain c = new BulletChain();
        c.layout(synthetic(35), 5, PERIOD);
        c.beginGeneration(template(Balance.weapons[Balance.Weapon.PULSE]));
        BulletPool pool = new BulletPool(16);
        assertEquals(1, emitOne(c, pool, NOSE_X));
        c.clear();
        assertEquals(0, c.remaining());
        assertEquals("清完就该一帧都不发", 0, c.step(Time.STEP, NOSE_X, NOSE_Y, pool, null));
        assertEquals(1, pool.activeCount());
    }

    /** 出膛点每帧重读机头 ⇒ 边移动边开火，链是弯的（"链会弯"那条卖点的唯一实现处）。 */
    @Test
    public void chainBendsWithTheNose() {
        BulletChain c = new BulletChain();
        c.layout(synthetic(36), 3, PERIOD);                     // N=3 < C=5 ⇒ 1 列，每时隙 1 发
        c.beginGeneration(template(Balance.weapons[Balance.Weapon.PULSE]));
        BulletPool pool = new BulletPool(16);
        for (int i = 0; i < 3; i++) {
            float nose = 100f + i * 7f;
            assertEquals(1, emitOne(c, pool, nose));
            assertEquals("第 " + i + " 发出膛时的机头位置", nose, pool.activeAt(i).x, EPS);
        }
    }

    /** 一代内部只 roll 一次暴击：整代的 crit 标志与伤害必须完全相同。 */
    @Test
    public void oneGenerationSharesOneCritRoll() {
        BulletChain c = new BulletChain();
        c.layout(synthetic(37), 5, PERIOD);
        WeaponFire.Template t = new WeaponFire.Template();
        Balance.Weapon pulse = Balance.weapons[Balance.Weapon.PULSE];
        WeaponFire.fillTemplate(t, pulse, 1f, 1f, 0, 0.01f, 0);      // roll 落在 8% 暴击率之内
        assertTrue(t.crit);
        c.beginGeneration(t);
        BulletPool pool = new BulletPool(16);
        assertEquals(5, drain(c, pool));
        for (int i = 0; i < 5; i++) {
            assertTrue(pool.activeAt(i).crit);
            assertEquals(t.damage, pool.activeAt(i).damage, 0f);
        }
    }

    /** 整代的伤害/弹速在 beginGeneration 就冻住了：之后改模板也改不动已排的弹。 */
    @Test
    public void templateIsCopiedNotReferenced() {
        BulletChain c = new BulletChain();
        c.layout(synthetic(38), 2, PERIOD);
        WeaponFire.Template t = template(Balance.weapons[Balance.Weapon.PULSE]);
        float frozen = t.damage;
        c.beginGeneration(t);
        t.damage = 999f;
        t.size = 77f;
        BulletPool pool = new BulletPool(16);
        assertEquals(2, drain(c, pool));
        for (int i = 0; i < 2; i++) {
            assertEquals(frozen, pool.activeAt(i).damage, 0f);
            assertEquals(Balance.weapons[Balance.Weapon.PULSE].size, pool.activeAt(i).size, 0f);
        }
    }

    // ---- 列几何 -------------------------------------------------------------------------------

    /** 多列既横向居中排开、又按本武器张角对称张开，两样都必须左右对称。 */
    @Test
    public void columnsAreSymmetricAboutTheAimLine() {
        BulletChain c = new BulletChain();
        Balance.Weapon w = synthetic(40);
        // 弹宽改成 4 是为了让"列间距 = 1 弹宽"可测；同时把绘制长度比例补回去，
        // 绘制弹长仍是 10 ⇒ 间隔仍是六个固定步 ⇒ C 仍是 5。这两个字段分开之后才看得出
        // 链的几何吃的是 size、节奏吃的是 size×ratio。
        w.size = 4f;
        w.drawLenRatioY = 2.5f;
        w.spreadDeg = 44f;
        c.layout(w, 17, PERIOD);                                 // C=5 ⇒ 4 列
        assertEquals("改弹宽不该改节奏（绘制弹长没变）", IV, c.slotInterval(), 1e-6f);
        assertEquals(4, c.columns());
        float sumOffset = 0f;
        float sumAngle = 0f;
        for (int j = 0; j < c.columns(); j++) {
            sumOffset += c.columnOffset(j);
            sumAngle += c.columnAngle(j);
            assertEquals("列间距必须是 1 弹宽",
                    j * w.size - (c.columns() - 1) / 2f * w.size, c.columnOffset(j), 1e-4f);
        }
        assertEquals("整组必须居中于瞄准线", 0f, sumOffset, 1e-4f);
        assertEquals("张角必须左右对称", 0f, sumAngle, 1e-3f);
        assertEquals("最外一列吃满张角的一半", -22f, c.columnAngle(0), 1e-3f);
        assertEquals(22f, c.columnAngle(c.columns() - 1), 1e-3f);
    }

    @Test
    public void pelletAngleIsZeroForASingleColumnAndSymmetricOtherwise() {
        assertEquals(0f, WeaponFire.pelletAngle(0, 1, 44f), 0f);
        assertEquals(0f, WeaponFire.pelletAngle(0, 1, 0f), 0f);
        assertEquals(-30f, WeaponFire.pelletAngle(0, 3, 60f), 1e-4f);
        assertEquals(0f, WeaponFire.pelletAngle(1, 3, 60f), 1e-4f);
        assertEquals(30f, WeaponFire.pelletAngle(2, 3, 60f), 1e-4f);
        assertEquals("两列没有中心发，各偏半角", -22f, WeaponFire.pelletAngle(0, 2, 44f), 1e-4f);
        assertEquals(22f, WeaponFire.pelletAngle(1, 2, 44f), 1e-4f);
    }

    // ---- 越界必须炸，不许静默丢发 ---------------------------------------------------------------

    @Test
    public void tooManySlotsFailsLoudlyInsteadOfClamping() {
        BulletChain c = new BulletChain();
        Balance.Weapon w = weapon(41, 600f, 10f, 1f);        // 间隔 = 一步 ⇒ C = 60 > MAX_SLOTS
        try {
            c.layout(w, 4, 1f);
            fail("时隙越界必须抛：静默钳到 MAX_SLOTS 会把一代挤成一坨而没人知道");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("MAX_SLOTS"));
        }
    }

    @Test
    public void tooManyColumnsFailsLoudlyInsteadOfLosingPellets() {
        BulletChain c = new BulletChain();
        c.layout(synthetic(42), 1, PERIOD);
        assertTrue("先确认 C=5 的前提", c.slots() == SLOTS);
        try {
            c.layout(synthetic(42), 85, PERIOD);             // C=5 ⇒ N=85 要 17 列 > MAX_COLUMNS
            fail("列数越界必须抛：先前 WeaponFire 的 16 槽硬顶就是在这里静默吞掉了玩家那一下");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("MAX_COLUMNS"));
        }
    }

    @Test
    public void nonPositiveGeometryFailsLoudly() {
        BulletChain c = new BulletChain();
        try {
            c.layout(weapon(43, 0f, 10f, PERIOD), 3, PERIOD);
            fail("弹速 0 会让间隔变成无穷大");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("positive"));
        }
        try {
            c.layout(synthetic(44), 3, 0f);
            fail("周期 0 不是一次有效的开火");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("positive"));
        }
    }

    // ---- 真表扫描：容量余量与不变量 --------------------------------------------------------------

    /**
     * 六把枪 × 射速乘子全档 × 弹丸数（基础 与 收入上界）都必须布局成功。
     *
     * <p>{@code MAX_SLOTS / MAX_COLUMNS} 的那段推导注释靠这条兜底：注释可以写错，越界会炸。
     * 扫描**刻意不管"扳机卡不吃激光"那条例外**（给激光也算满档加成），因为那条例外归
     * {@code ShopRun.pelletBonus} 定义；这里要的是"不管例外怎么判都不会越界"的上界。
     *
     * <p>乘子扫到 1.944 —— 那是**商店射速卡还在时**的上界，2026-09-26 扳机改卖发数之后这一档
     * 已经不可达（真上界掉到 1.62）。它留在扫描表里是刻意的：乘子表多扫一格比少扫一格便宜，
     * 而"今后谁把射速乘子加回来"正是这一格会先炸的场景。弹丸数那一档同理取收入上界
     * （等级 × {@code pelletPerLevel}，两个数都现读），与乘子那档是**各自世界的上界再作笛卡尔积**
     * ——比任何一个世界的真实可达档都更严，于是"改表改到越界"一定在这里炸，而不是只在注释里过期。
     */
    @Test
    public void everyWeaponLaysOutAcrossEveryFireRateTier() {
        float[] muls = {0.6075f, 0.75f, 0.9f, 1f, 1.2f, 1.35f, 1.62f, 1.944f};
        int pelletBonus = Balance.shop.pelletPerLevel * MissilesTest.TRIGGER_LEVEL_AT_INCOME_CAP;
        int worstSlots = 0;
        int worstColumns = 0;
        for (Balance.Weapon w : Balance.weapons) {
            for (float mul : muls) {
                float period = w.fireGap / mul;
                for (int n : new int[]{Math.max(1, w.pellets), pelletBonus + Math.max(1, w.pellets)}) {
                    BulletChain c = new BulletChain();
                    c.layout(w, n, period);
                    worstSlots = Math.max(worstSlots, c.slots());
                    worstColumns = Math.max(worstColumns, c.columns());
                    float window = c.slots() * c.slotInterval();
                    // 窗口可以因取整的那一格余量略微超出周期，但**绝不能超出一个时隙**：
                    // 超出一格就意味着撞车时可能有两个时隙还没发，那才是真丢发（见 BulletChain 类注释）。
                    assertTrue(w.name + " 乘子 " + mul + " 的排放窗口超出了一整个时隙",
                            window - period < c.slotInterval());
                    assertTrue(w.name + " 的间隔低于一步", c.slotInterval() >= Time.STEP);
                    assertEquals(w.name + " 乘子 " + mul + " 的间隔不是整数步",
                            Math.round(c.slotInterval() / Time.STEP),
                            c.slotInterval() / Time.STEP, 1e-3f);
                    int sum = 0;
                    for (int k = 0; k < c.slots(); k++) sum += c.pelletsAt(k);
                    assertEquals(w.name + " 发数不闭合", n, sum);
                }
            }
        }
        assertTrue("极限档跑出 " + worstSlots + " 个时隙，已经贴到 MAX_SLOTS=" + BulletChain.MAX_SLOTS,
                worstSlots < BulletChain.MAX_SLOTS);
        assertTrue("极限档跑出 " + worstColumns + " 列，已经贴到 MAX_COLUMNS=" + BulletChain.MAX_COLUMNS,
                worstColumns < BulletChain.MAX_COLUMNS);
    }

    /**
     * 撞车：周期恰好等于 {@code C} 个间隔（真表达档 = 脉冲 @成长树 1.2× 射速）时，最后一个时隙
     * 与"下一代就绪"落在同一步上。{@code Game.step} 把 {@code stepChain} 排在 {@code playerFire}
     * **之前**就是为了这一步——先推进链、再 {@code beginGeneration}，末尾那一档才不会被游标重置吃掉。
     *
     * <p>这条自己搭一个与 {@code Game.step} 同序的循环，断的是"每一代都发满 N 发"。
     * 谁把那两个调用的顺序换回来，这里就会少 {@code N/C} 发（此档 = 3），而它不会有任何报错。
     */
    @Test
    public void theLastSlotSurvivesAVolleyThatBeginsOnTheSameStep() {
        Balance.Weapon pulse = Balance.weapons[Balance.Weapon.PULSE];
        float period = pulse.fireGap / 1.2f;                 // 恰好 7 个固定步
        int n = 26;                                          // 扳机叠满后的收入上界：每档都有 3~4 发
        BulletChain c = new BulletChain();
        c.layout(pulse, n, period);
        assertEquals("前提：这一档的 C 必须正好把窗口顶满周期", 7, c.slots());
        assertEquals("前提：末时隙必须非空，否则撞车撞不到发", n / c.slots(), c.pelletsAt(c.slots() - 1));

        WeaponFire.Template t = template(pulse);
        BulletPool pool = new BulletPool(64);
        c.beginGeneration(t);
        float timer = period;
        int volleys = 0;
        int sinceStart = 0;
        for (int step = 0; step < 60 * 30; step++) {
            sinceStart += c.step(Time.STEP, NOSE_X, NOSE_Y, pool, null);   // 先推进链
            pool.stepAndCompact(Time.STEP, SCREEN_W, SCREEN_H, MARGIN);
            timer -= Time.STEP;
            if (timer <= 0f) {                                       // player.canFire() 的等价判据
                assertEquals("第 " + volleys + " 代末尾被下一代的游标重置吃掉了", n, sinceStart);
                c.beginGeneration(t);
                timer = period;
                sinceStart = 0;
                volleys++;
            }
        }
        assertTrue("这个循环压根没跑到撞车那一步（volleys=" + volleys + "）", volleys >= 20);
    }

    /**
     * 同一列上相邻两发至少拉开一个绘制弹长——"相切"的正面半边，与
     * {@link #quantizationAlwaysRoundsUpToAWholeStep} 一起把"退化成同一点叠发"钉死。
     *
     * <p>不同列之间**不该**拿 Y 间距比较：它们是"同时开火的并行弹链"，本来就该并排在同一高度上，
     * 所以这里按 x 分组比较。"同时开火"是用户逐字（**L14337**，answers-**FREE**，
     * UTC 09-25T07:19:15 ＝本地 09-25 15:19：「所有弹链是同时开火的，不存在这些问题，具体设计同上」）；
     * "并行弹链"这个说法与按 x 分组的判据是我的。
     *
     * <p>**制导武器不在这条里**（step 4 接线后实跑一次，导弹 N=2 报"间距 0.0"，于是看清了
     * 这条判据的适用条件）：弹链的间距**由飞行拉开**——出膛点都在机头，两发的距离差完全等于
     * "前一双多飞了 {@code interval} 那么久"。匀速弹（五把）成立，因为 {@code interval·
     * bulletSpeed ≥ len} 就是这条的几何式，永远成立；导弹会推力与滑行减速，速度**不再是**
     * {@code bulletSpeed}，滑行段的速度只剩 120~133px/s 时间距掉到 4.0~4.4px，而它的绘制长度
     * 按导弹自己那条口径是 4px 的方块（{@code Game.drawMissiles} 不乘 {@code drawLenRatioY}），
     * 不是 6.8px。⇒ 这条对导弹**证伪的不是链，是我拿错了长度**，硬跑只会逼出两种假话：要么在
     * 测试里手写一遍匀速积分（用导弹不用的模型证明链的公式），要么让它红。链的几何式对六把枪
     * 一律成立，那条由 {@link #quantizationAlwaysRoundsUpToAWholeStep} 全表覆盖；制导弹**落在
     * 哪张池**由 {@link EntitiesTest#everyWeaponMakesAtLeastOneBullet} 钉。
     */
    @Test
    public void everyWeaponSpacesItsChainByAtLeastOneBulletLength() {
        BulletPool pool = new BulletPool(Balance.bullet.capacity);
        for (Balance.Weapon w : Balance.weapons) {
            if (w.guided) continue;
            for (int n = 1; n <= 8; n++) {
                pool.clear();
                BulletChain c = new BulletChain();
                c.layout(w, n, w.fireGap);
                c.beginGeneration(template(w));
                assertEquals(n, drain(c, pool));
                float len = w.size * w.drawLenRatioY;
                for (int i = 0; i < pool.activeCount(); i++) {
                    for (int j = i + 1; j < pool.activeCount(); j++) {
                        Bullet a = pool.activeAt(i), b = pool.activeAt(j);
                        if (Math.abs(a.x - b.x) > 1e-4f) continue;
                        assertTrue(w.name + " N=" + n + " 同列两发压在了一起（间距 "
                                        + Math.abs(a.y - b.y) + " < 弹长 " + len + "）",
                                Math.abs(a.y - b.y) >= len - 1e-3f);
                    }
                }
            }
        }
    }

    /**
     * 制导代漏传池 = **接线错误**，不是"这一代恰好不发弹"：当场抛，而且抛在推进链**之前**，
     * 所以修好接线之后同一代照样发满。
     *
     * <p>这条判据存在的理由是"静默少发在账本上看不出来"：{@code step} 的返回值是两条分支的合计，
     * 调用方拿它去记 {@code stats.addShots}。要是在这里安静地返回 0，那一代的发数就从
     * 出膛账上蒸发了，而 {@code shotsHit} 一侧还可能照样记命中——最终被 {@code Rating.clamp01}
     * 钳成"命中率 100%"，全链路没有任何地方会红（复核 DA-6 的正是这条）。
     */
    @Test
    public void aGuidedGenerationWithoutAPoolFailsLoudly() {
        Balance.Weapon missile = Balance.weapons[Balance.Weapon.MISSILE];
        WeaponFire.Template t = template(missile);
        assertTrue("前提：这把枪得走制导分支，否则这条测的是别的东西", t.guided);
        BulletChain c = new BulletChain();
        c.layout(missile, missile.pellets, missile.fireGap);
        c.beginGeneration(t);
        int slotsLeft = c.remaining();
        BulletPool shots = new BulletPool(16);
        try {
            c.step(Time.STEP, NOSE_X, NOSE_Y, shots, null);
            throw new AssertionError("制导代缺 Missiles 池应该抛，而不是返回 0 发");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("no Missiles pool"));
        }
        assertEquals("抛之前链已经推进了 ⇒ 头几档被这个错误吃掉", slotsLeft, c.remaining());
        assertEquals(0, shots.activeCount());

        Missiles missiles = new Missiles(8);
        int made = 0;
        int guard = 0;
        while (c.remaining() > 0) {
            made += c.step(Time.STEP, NOSE_X, NOSE_Y, shots, missiles);
            assertTrue("补上池之后这一代排不完", ++guard < 10_000);
        }
        assertEquals("修好接线之后同一代仍该发满 N 发", missile.pellets, made);
        assertEquals(0, shots.activeCount());
        assertEquals(missile.pellets, missiles.activeCount());
    }

    // ---- 助手 -------------------------------------------------------------------------------

    private static WeaponFire.Template template(Balance.Weapon w) {
        WeaponFire.Template t = new WeaponFire.Template();
        WeaponFire.fillTemplate(t, w, 1f, 1f, 0, 0.99f, 0);      // 0.99：一律不暴击，免得干扰断言
        return t;
    }

    /**
     * 喂时间到这一代排完，返回实发数。步长恒为固定步长，帧顺序与 {@code Game.step} 一致。
     *
     * <p>{@code missiles} 恒传 null：这条helper 服务的用例全是匀速弹（见
     * {@link #everyWeaponSpacesItsChainByAtLeastOneBulletLength} 里"制导武器不在其中"的理由），
     * 而 {@link BulletChain#step} 遇到制导模板缺池会当场抛——那个分支本身就是"漏传池"的钉子。
     */
    private static int drain(BulletChain c, BulletPool pool) {
        int made = 0;
        int guard = 0;
        while (c.remaining() > 0) {
            made += c.step(Time.STEP, NOSE_X, NOSE_Y, pool, null);
            pool.stepAndCompact(Time.STEP, SCREEN_W, SCREEN_H, MARGIN);
            assertTrue("一代排不完（" + guard + " 步）：C·interval 的账或排放循环的条件错了",
                    ++guard < 10_000);
        }
        return made;
    }

    /** 一直喂时间直到冒出一发，返回那一帧的发数。 */
    private static int emitOne(BulletChain c, BulletPool pool, float noseX) {
        int made = 0;
        for (int f = 0; made == 0 && f < 100; f++) {
            made = c.step(Time.STEP, noseX, NOSE_Y, pool, null);
        }
        assertTrue("等不到任何一发排出来", made > 0);
        return made;
    }
}
