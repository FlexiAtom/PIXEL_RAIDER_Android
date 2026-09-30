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
 * 开火（纯算术）：把「一把武器的形态 + 此刻的伤害账」翻译成**一发**子弹。
 *
 * 「一代 N 发怎么排」不在这里——那是 {@link BulletChain} 的事。本类只负责出膛这一刻把模板
 * 写进池，所以它一次只发一发，且**不做任何除法**（角度由调用方按列号现算或读缓存）。
 *
 * 弹道形态先于颜色（规格 §零：形态先于颜色是像素游戏的可读性底线）：
 * - 单发（脉冲/激光/电弧）：正上方一条线；
 * - 多弹丸（散射/导弹/回旋）：按 {@link Balance.Weapon#spreadDeg} 对称张开——张的是**列与列之间**，
 *   所以只有当一代排成多列时才看得见（一代排不进两列时整代就是一条竖链，见 {@link BulletChain}）。
 *
 * 非制导弹**一律直飞**：弹道在出膛这一刻定死，之后只由 {@code BulletPool.stepAndCompact} 积分。
 * 先前那套"导弹每帧由 {@code steerHoming} 修正方向"是假追踪（指数逼近、无转弯率上限、
 * 无视目标视场），2026-09-25 提案 unguide-bullets 已整体删除。真仿真的导弹走
 * {@link Missiles} 独立池与 {@link MissileBehavior}（提案 missile-warhead-sim）：它同样在这里
 * 出膛（继承弹速与方向），但**之后怎么飞**不由弹的积分决定。
 *
 * 随机数由调用方给（{@code roll01}）：本类不 new Random，测试才能钉住"这次是不是暴击"。
 * 暴击固定 ×2（[可调]，成长树里的"暴击 +2%"加的是 {@code critPercent}，不是倍率）。
 */
public final class WeaponFire {

    public static final float CRIT_MULTIPLIER = 2f;

    private WeaponFire() { }

    /**
     * 一代弹丸共用的模板：出膛这一刻算一次，整代每一发都读同一份。
     *
     * <p>为什么要有这个类而不是把参数一路透传：弹链把"一次开火"拆成了横跨 {@code C} 个时隙的
     * 一段时间，而暴击判定、伤害乘子、弹速这些**必须在整代之间保持一致**——否则同一代里的
     * 弹会因时机不同而伤害不同，玩家看到的"一条链"内部就不诚实了。把这一代 freeze 成一份
     * 数据结构，比让调用方记住"这些数要复用"可靠。
     */
    public static final class Template {
        /** 弹速（像素/秒），已含弹速修正。 */
        public float speedPx;
        /** 已含攻击乘子、固定加伤与暴击倍率的最终伤害（结算端仍走 {@link #damageOf} 取整）。 */
        public float damage;
        public float size;
        public float maxLife;
        public int color;
        public int weaponId;
        /** 这一代的暴击判定结果：判定一次，整代共享。 */
        public boolean crit;
        /**
         * 走哪张池：true = {@link Missiles}（仿真体，之后由 {@link MissileBehavior} 导引），
         * false = {@link BulletPool}（直飞）。唯一读者是 {@link BulletChain#step} 的那个分支——
         * 全项目**只有那一处**按制导与否分流，所以它是模板的一个字段而不是让链去查武器表：
         * 链在开火那一刻已经把 {@code w} 丢了。
         */
        public boolean guided;

        void set(Template o) {
            speedPx = o.speedPx;
            damage = o.damage;
            size = o.size;
            maxLife = o.maxLife;
            color = o.color;
            weaponId = o.weaponId;
            crit = o.crit;
            guided = o.guided;
        }
    }

    /**
     * 算出这一代的模板。
     *
     * @param speedMul   [可调] 弹速修正（状态层/升级都从这里进）
     * @param attackMul  [规格 §五] 攻击类乘数（成长树满级 1.15）。
     *                   在出膛这一刻乘进 {@code damage}：先买卡后打出的弹不吃新卡，这是弹的诚实记账。
     * @param flatDamage [2026-09-25] 局内火力卡的**固定加伤**（点/发）。加在乘子之后、暴击之前：
     *                   百分比那条路被按发取整吃掉了（激光基础 1 点，十级 +20% 仍是 1 点），
     *                   定值这条路买一级就一定多掉一格血，卡面上的 +1 点与结算才是同一个数。
     * @param roll01     0..1 的暴击判定值
     * @param critBonus  [规格 §五] 成长树的暴击**百分点**加成（不是倍率），与武器基础暴击率相加后判定
     */
    public static void fillTemplate(Template out, Balance.Weapon w,
                                    float speedMul, float attackMul, int flatDamage,
                                    float roll01, int critBonus) {
        boolean crit = isCrit(roll01, w.critPercent + critBonus);
        float mul = attackMul <= 0f ? 1f : attackMul;    // 配置错误当作 1，不要让一枪打成 0 伤
        float dmg = w.damage * mul + flatDamage;
        out.speedPx = w.bulletSpeed * (speedMul <= 0f ? 1f : speedMul);
        out.damage = crit ? dmg * CRIT_MULTIPLIER : dmg;
        out.size = w.size;
        out.maxLife = Balance.bullet.playerLife;
        out.color = w.color;
        out.weaponId = w.id;
        out.crit = crit;
        out.guided = w.guided;
    }

    /**
     * 出膛一发。
     *
     * <p>池满则淘汰最老的一发：玩家按下去的那一下永远会出弹，不存在"整轮零发"。因此
     * 返回值恒不为 null，也不需要调用方判空——**发数由 {@link BulletChain} 的布局决定，
     * 不由池的余量决定**。先前那个"受 16 槽硬顶静默截断"的实现（{@code made < pellets}
     * 却照样记账）是本项目最恨的失败模式，随 {@code ANGLES} 一起删掉了。
     *
     * @param deg 出膛方向：0° = 屏幕正上方（内部转成数学角）
     */
    public static BulletPool.Bullet fireOne(BulletPool pool, Template t, float x, float y, float deg) {
        BulletPool.Bullet b = pool.spawnEvictingOldest(true);
        float rad = muzzleRad(deg);
        b.x = x; b.y = y;
        b.px = x; b.py = y;
        b.vx = (float) Math.cos(rad) * t.speedPx;
        b.vy = (float) Math.sin(rad) * t.speedPx;
        b.maxLife = t.maxLife;
        b.size = t.size;
        b.damage = t.damage;
        b.crit = t.crit;
        b.color = t.color;
        b.weaponId = t.weaponId;
        return b;
    }

    /**
     * 出膛一枚仿真弹（制导武器走这条）。与 {@link #fireOne} 同一条角度口径、同一份模板，
     * 差别只在**之后**：这枚弹进了 {@link Missiles} 池，飞行由 {@link MissileBehavior} 逐步决定，
     * 不再是"定死直飞到 {@code maxLife}"。
     *
     * <p>没有 {@code maxLife} 可写——仿真体的寿命是 {@link Balance.Missile#maxLifeSec} 这一个数，
     * 写进池里就是第二个真源（提案 ② 的教训同形）。
     *
     * <p>满池时池会淘汰最老的一枚并留下自毁动画（{@link Missiles#evicted}；R22 裁的是不结算），所以返回值恒不为 null：
     * 玩家按下去的那一下永远会出弹，与 {@link #fireOne} 同一条约定。
     */
    public static Missiles.Missile fireMissile(Missiles pool, Template t, float x, float y, float deg) {
        Missiles.Missile m = pool.spawnEvictingOldest();
        float rad = muzzleRad(deg);
        m.x = x; m.y = y;
        m.px = x; m.py = y;
        m.vx = (float) Math.cos(rad) * t.speedPx;
        m.vy = (float) Math.sin(rad) * t.speedPx;
        m.size = t.size;
        m.damage = t.damage;
        m.crit = t.crit;
        m.color = t.color;
        m.weaponId = t.weaponId;
        return m;
    }

    /**
     * 弹道角 → 弧度。**"0° = 屏幕正上方"这条口径只在这里出现一次**：直飞与仿真两条出膛路径
     * 都得用它，否则其中一条改了另一条不会跟着改，玩家会看到同一把枪的两个方向。
     * 内部转数学角：0° 在屏幕上是右，减去 90° 才是上。
     */
    private static float muzzleRad(float deg) {
        return (float) Math.toRadians(deg - 90f);
    }

    /**
     * 第 {@code i} 列（共 {@code n} 列）的弹道角：以 0°（正上方）为对称中心铺开。
     *
     * <p>单发必为 0°——散射的中心那发绝不能偏，否则玩家瞄准线会整体歪。这条口径从旧的
     * {@code spreadAngles} 原样搬来，只是改成"一次算一列"，于是没有临时数组、也没有 16 上限。
     */
    public static float pelletAngle(int i, int n, float spreadDeg) {
        if (n <= 1) return 0f;
        float step = spreadDeg / (n - 1);
        return -spreadDeg / 2f + step * i;
    }

    /** roll 落在暴击率之内即暴击；{@code critPercent} 超 100 当作 100。 */
    public static boolean isCrit(float roll01, int critPercent) {
        int pct = critPercent > 100 ? 100 : critPercent;
        if (pct <= 0) return false;
        return roll01 < pct / 100f;
    }

    /**
     * 伤害结算：取整，至少 1。暴击 ×2 在出膛那一刻已经乘进 {@code damage}，这里只做浮点→整数。
     *
     * <p>参数是**裸浮点**而不是 {@code Bullet}：玩家弹、连续杆亮线、碎片三处结算必须走同一个函数。
     * 签名只吃 {@code Bullet} 的旧版本让战斗部拿不到它，于是 {@code max(1, round(d))} 会被抄第二份
     * ——那正是本仓立规要防的第二个真源（复核 DA-4）。
     */
    public static int damageOf(float damage) {
        int d = Math.round(damage);
        return d < 1 ? 1 : d;
    }

    /** 玩家弹那一侧的糖：口径完全在 {@link #damageOf(float)}，本函数不额外做任何决定。 */
    public static int damageOf(BulletPool.Bullet b) {
        return damageOf(b.damage);
    }
}
