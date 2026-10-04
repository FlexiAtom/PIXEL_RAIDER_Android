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

/**
 * 伤害结算规则（纯函数）——**「护甲倍率 → 取整 → 下限」这三步只在这里做一次**。
 *
 * [规格] 撞小怪 = 最大生命 50%，自爆怪贴脸 = 60%，Boss 撞击 = 35%，Boss 子弹 = 20，小怪子弹 = 12。
 * **撞机类随生命上限缩放，子弹类固定点数**——这两类不能混在一个算法里：
 * 前者在玩家点了成长树/升级后会变，后者永远不会。
 *
 * <p>2026-10-04 伤害系统重做（档二）：原先散落各处的取整与下限共**三种写法**（机炮/弹体的
 * {@code max(1, round(浮点))}、碎片的 {@code max(1, 整除)}、承受侧的
 * {@code max(contactFloor, round(乘状态层))}）收进 {@link #resolve} 一个出口，
 * 于是"这一击打几折、怎么变成整数"再也没有第二个地方可以改。
 *
 * <p>{@link #MUL} 是**源 × 护甲**表，纵向是打出这一击的东西、横向是被打的东西带哪种护甲。
 * 分成两个维度是必须的：同一种护甲对机炮是铁板、对格斗弹是纸，而同一把格斗弹打无护甲目标
 * 又该照原样出血——"谁打的"和"打在什么上面"是两句话，揉进一个 if 就再也问不出来了。
 */
public final class DamageRules {

    private DamageRules() { }

    // ---- 伤害的源（9 条结算路径 / 10 档）----------------------------------------------------
    //
    // 档比路径多一条是有意的：弹体撞击那**一条**代码路径（{@code Game.settleImpact}）按弹池分成
    // {@link #SRC_MISSILE_BODY} 与 {@link #SRC_DOGFIGHT_BODY} 两档——合并两档就写不出"仅格斗弹
    // 百分百"那句裁定。所以这里**不是**"一一对应"，别再把它数成 10 条路径。
    //
    // 编号只在 MUL 表的行注释里出现一次，行序即这些常量的值——改顺序就是改语义，所以
    // {@link #MUL} 的维度由 {@code DamageTableTest} 钉住，不靠运行时校验兜。

    /** 弹链那一发（机炮类）。结算入口 {@link WeaponFire#damageOf(BulletPool.Bullet)}。 */
    public static final int SRC_SHOT = 0;
    /** 普通导弹的弹体撞击档（没买连续杆卡时那一发炸在引信碰到的那一只身上）。 */
    public static final int SRC_MISSILE_BODY = 1;
    /**
     * 格斗弹的弹体撞击档。它必须与 {@link #SRC_MISSILE_BODY} **分档而不是合并**：
     * 他 2026-10-03 的逐字是「目前仅格斗弹对子弹护甲的伤害是百分百」「其他武器（目前是除了
     * 格斗弹以外的所有）…为百分之0」——这一格 100% 是整张表里唯一的非零，两流合并就写不出这句话。
     *
     * <p>两流的身份在**池**上（{@link MissileBehavior#STREAM_DOGFIGHT}，理由见那里的注释），
     * 弹身上没有布尔；所以本档由 {@code Game.stepPool} 按"这枚弹在哪个池里"传给结算点。
     */
    public static final int SRC_DOGFIGHT_BODY = 2;
    /** 连续杆亮线（每次 tick 的完整伤害）。 */
    public static final int SRC_ROD = 3;
    /** 亮线破碎后的碎片（杆值的四分之一）。 */
    public static final int SRC_SHARD = 4;
    /** 超载扫描。 */
    public static final int SRC_OVERLOAD = 5;
    /** 炸弹（基准来自**被打那一只**的生命上限，见 {@link #bombDamage}）。 */
    public static final int SRC_BOMB = 6;
    /** 敌方子弹 → 玩家。 */
    public static final int SRC_ENEMY_BULLET = 7;
    /** 小怪撞机（含自爆怪贴脸，两者只差在比例，不差在源）。 */
    public static final int SRC_CONTACT = 8;
    /** Boss 撞机。 */
    public static final int SRC_BOSS_CONTACT = 9;

    // ---- 护甲的种类 ------------------------------------------------------------------------

    /** 没有护甲：今天的场上人人都是这一档，MUL 的这张列就是重构前的行为本身。 */
    public static final int ARMOR_NONE = 0;
    /**
     * 子弹护甲（用户 2026-10-03 命名的两种护甲之一）：只穿在**敌弹**上，等 #67「对弹机制」把
     * 敌弹升格成有血量的实体时才有持有者。今天没有任何东西带它，所以这一列还不参与任何结算——
     * 但它现在就该写成他的原话，否则将来加持有者时会照着一张没表意的表做决定。
     *
     * <p>⚠ 他说的第二种「普通护甲」**故意不进表**：数值与载体他还没给（见
     * {@code ~/.Athena/…/pool/armor-two-kinds.md} 的三个待问项）。加一列全 1.0 是**假表**——
     * 那会被读成"他裁了百分之百"。等数到手再加，一行数组的事。
     */
    public static final int ARMOR_BULLET = 1;

    /**
     * 源 × 护甲的倍率表。逐行对照 {@link #SRC_SHOT}…{@link #SRC_BOSS_CONTACT} 的顺序。
     *
     * <p>{@code BULLET} 列的读法：他裁的是「仅格斗弹百分百、其他武器百分之**0 并且忽略**」。
     * "0"与"忽略"在这里是同一个数——{@link #resolve} 见 0 直接还 0、**不吃下限**，
     * 于是"打到了没伤害"与"根本没打到"在数值上合一；将来 #67 决定要不要建那条命中判据时，
     * 读的也是这一格（{@code mulOf(src, ARMOR_BULLET) > 0} 就是"该不该检测"），不必再抄一份规则。
     *
     * <p>⚠ {@code SRC_ROD} / {@code SRC_SHARD} 两行的 0 是**待裁**而不是已定：杆与碎片是
     * 战斗部，出自哪一流在它生出来之后就问不出来了（它们住在共用池里），所以"格斗弹的 AoE
     * 算不算格斗弹"这一格我按"其他武器"的字面填 0，等他改。
     */
    private static final float[][] MUL = {
            { 1f, 0f },   // SRC_SHOT
            { 1f, 0f },   // SRC_MISSILE_BODY
            { 1f, 1f },   // SRC_DOGFIGHT_BODY   ← 他逐字的那一格
            { 1f, 0f },   // SRC_ROD             （待裁）
            { 1f, 0f },   // SRC_SHARD           （待裁）
            { 1f, 0f },   // SRC_OVERLOAD
            { 1f, 0f },   // SRC_BOMB
            { 1f, 0f },   // SRC_ENEMY_BULLET
            { 1f, 0f },   // SRC_CONTACT
            { 1f, 0f },   // SRC_BOSS_CONTACT
    };

    /**
     * 表的两条维度。声明放在 {@link #MUL} **之后**是必须的（Java 不许静态字段前向引用），
     * 也由 {@code DamageTableTest} 把它和表的实际长度钉在一起——这样新增一源忘了扩表，
     * 或者扩表忘了改那个"10"，都在测试里变红，而不是在 {@code MUL[source][armor]} 里越界。
     */
    public static final int SRC_COUNT = MUL.length;
    public static final int ARMOR_COUNT = MUL[0].length;

    /** 这一源打这一型护甲打几折。表本身不公开，读它只为了判"该不该检测"。 */
    public static float mulOf(int source, int armor) {
        return MUL[source][armor];
    }

    /**
     * 唯一的结算出口：倍率 → 取整 → 下限。
     *
     * <p>次序是有意的：**倍率先于取整**。反过来（先取整再打折）会让"激光基础 1 点 × 0.5 折"
     * 与"5 点 × 0.5 折"都掉到同一个下限上，卡面与场上就又开始对不上。
     *
     * <p>{@code mul <= 0} 直接还 0、**不落下限**，这条是整张表的存在理由：下限的本意是
     * "打到了就别掉 0 点"（他 2026-09-25 那句「接触到破碎碎片的扣四分之一（向下取整，最小为1）」
     * 里的"最小为 1"就是这个下限），而"百分之 0"要的正好是 0 点。让两者共用一条 {@code max} 
     * 就得到"0% 的护甲其实每次都掉 1 点"——那是这条重构唯一的失败模式。
     *
     * @param base 这一击的**基准点数**（浮点，未打折）：可以是 {@code WeaponFire} 算好的含暴击
     *             浮点伤，也可以是 {@code maxHp × 比例} 这种"基准来自目标"的乘积
     */
    public static int resolve(int source, int armor, float base) {
        float mul = MUL[source][armor];
        if (mul <= 0f) return 0;
        return scaleRoundFloor(base, mul, floorFor(source));
    }

    /**
     * 下限按源分两种，**不是漏合并**：承受侧三种读 {@link Balance.Damage#contactFloor}（他为
     * "撞机别掉 0 点"设的旋钮），攻击侧恒为 1（每一发子弹至少一格血，与撞机无关）。
     * 合并成一个数就等于让"改撞机下限"顺带改了机炮。
     */
    private static int floorFor(int source) {
        switch (source) {
            case SRC_ENEMY_BULLET:
            case SRC_CONTACT:
            case SRC_BOSS_CONTACT:
                return Balance.damage.contactFloor;
            default:
                return 1;
        }
    }

    /**
     * 整张表里**唯一**一处「乘 → {@code Math.round} → 下限」。{@link #resolve} 走它，
     * {@link #scaleTaken} 也走它——这两条路必须共用同一个取整口径，否则"四舍六入五取偶上位"
     * 这件事会在其中一条路上悄悄换个脾气。
     */
    private static int scaleRoundFloor(float base, float mul, int floor) {
        int v = Math.round(base * mul);
        return v < floor ? floor : v;
    }

    /** out[0]=新护盾 out[1]=新生命 out[2]=1 表示护盾**这次真正归零**（触发破碎短无敌）。 */
    public static void applyToPlayer(int shield, int hp, int rawDamage, float takenMul, int[] out) {
        int dmg = scaleTaken(rawDamage, takenMul);
        int absorbed = shield < dmg ? shield : dmg;
        int newShield = shield - absorbed;
        int newHp = hp - (dmg - absorbed);
        out[0] = newShield < 0 ? 0 : newShield;
        out[1] = newHp < 0 ? 0 : newHp;
        // 破盾判的是"从有到无"，不要求溢出：伤害刚好等于剩余盾时盾同样碎了，不该少给那 0.5 秒无敌。
        out[2] = (shield > 0 && newShield == 0) ? 1 : 0;
    }

    /**
     * 状态层乘算后至少掉 1 点。
     * ⚠ takenMul() 在原型里定义了却从未被调用——铁壁/脆化只显示图标不生效。
     *   这里它是**唯一**的伤害入口，护盾与血量结算前必须过它。
     *
     * <p>它**不读 MUL 表**，因为状态层是**被打那一方的**属性，与"谁打的"无关；
     * 护甲倍率则是攻击侧与护甲型的交集。两者各自一条路、共用 {@link #scaleRoundFloor}，
     * 混成一次乘积会让"铁壁 + 子弹护甲"这种组合无法分别调参。
     */
    public static int scaleTaken(int raw, float takenMul) {
        return scaleRoundFloor(raw, takenMul, Balance.damage.contactFloor);
    }

    /** 撞机伤害：按最大生命比例，含该种怪的 contactBonus 修正。 */
    public static int contactDamage(Balance.Enemy e, int maxHp) {
        return resolve(SRC_CONTACT, ARMOR_NONE, maxHp * ratioFor(e));
    }

    /** 自爆怪只有在**贴脸**时才吃 60%，非贴脸的擦碰按普通撞机。 */
    public static int contactDamageAt(Balance.Enemy e, int maxHp, boolean pointBlank) {
        float ratio = e.id == Balance.Enemy.BURSTER && pointBlank
                ? Balance.damage.bursterHitRatio
                : ratioFor(e);
        return resolve(SRC_CONTACT, ARMOR_NONE, maxHp * ratio);
    }

    private static float ratioFor(Balance.Enemy e) {
        return Balance.damage.gruntHitRatio + e.contactBonus;
    }

    public static int bossContactDamage(int maxHp) {
        return resolve(SRC_BOSS_CONTACT, ARMOR_NONE, maxHp * Balance.damage.bossHitRatio);
    }

    /** 子弹类固定点数：Boss 弹 20、小怪弹 12。**这是基准值，不是结算值**——出膛时写进弹身，
     *  打到玩家那一刻才过 {@code SRC_ENEMY_BULLET} 那一档的结算（见 {@code Game.hostileShotsAgainstPlayer}）。 */
    public static int bulletDamage(boolean fromBoss) {
        return fromBoss ? Balance.damage.bossBullet : Balance.damage.gruntBullet;
    }

    /** 超载扫描：固定大伤害，随状态层不缩放（它是玩家主动释放，不是承受）。 */
    public static int overloadDamage() {
        return resolve(SRC_OVERLOAD, ARMOR_NONE, Balance.damage.sweepDamage);
    }

    /**
     * 炸弹"重伤全场"：扣的是**被打那一只自己**的生命上限比例，不是固定点数，也不随玩家走。
     *
     * <p>这条算式原先写在 {@code Game.detonateBomb} 里（{@code max(1, round(maxHp × ratio))}），
     * 与超载那条**同形而不同基准**：超载是全屏同一个数、炸弹是每只各算各的。基准来自目标这件事
     * 是护甲表要能表达的第三种读法，所以它得住在表这边而不是散在调用点。
     */
    public static int bombDamage(int targetMaxHp) {
        return resolve(SRC_BOMB, ARMOR_NONE, targetMaxHp * Balance.damage.bombHpRatio);
    }

    /**
     * 护盾来源的增量提示：满盾时不能谎报 +35。
     * [规格] 增量提示显示**实际增加量**。
     * 上限由调用方传：成长树把护盾上限抬到 150 之后，读 {@link Balance.player} 的 100 会算错实收。
     *
     * <p>**商店护盾卡不走这里**（它全额入账、可越限，见 {@link PlayerState#addShieldBeyondCap}）——
     * 不是漏改：这张卡的卡面在点下去之前就把收益写死了，按剩余空间打折就是"卡面写了收益、场上少给"。
     */
    public static int shieldGainActuallyApplied(int currentShield, int maxShield, int wanted) {
        int room = maxShield - currentShield;
        return wanted > room ? Math.max(0, room) : wanted;
    }
}
