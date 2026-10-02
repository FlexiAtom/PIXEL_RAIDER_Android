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
 * 本局的商店持有量：每张卡买到第几级，以及由它派生的收益量。
 *
 * <p><b>不落盘</b>——这是局内成长，一局结束（死亡或通关）即清零；局外那套是 {@link GrowthTree}（芯片、存档、
 * 在主菜单的「成长」页上点）。两套经济、两个来源，最后在游戏逻辑里合成，所以它们永远不会
 * 互相覆盖。
 *
 * <p>派生量有**三种单位**，不是全都该做成乘子：乘子（{@link #speedMul()} 这类）、
 * 每级定点数（{@link #damageBonus()} 点/发、{@link #critBonus()} 百分点、
 * {@link #hullBonusTotal()} 点）、以及**离散的发数**（{@link #pelletBonus(int)}）。
 * 后两种存在的理由是"离散量吃不动百分比"，两条证据各在一处：伤害按发取整，所以 +2% 在激光
 * 那把 1 点的枪上是 0；射速被固定步长量化，所以相邻几个乘子档压出同一个实测周期，钱花了
 * 场上纹丝不动。
 *
 * <p>乘子从等级现算而不是买一张就乘一次：连买三次的浮点累积会随购买顺序漂出不同的值，
 * 而"同样三级却不一样强"是没法解释也测不出来的那种错。
 */
public final class ShopRun {

    private final int[] level = new int[Balance.Shop.CARDS];

    /** 给 {@link ShopRules.Snapshot#level} 直接引用（不复制，买完立刻在快照上生效）。 */
    public int[] levels() {
        return level;
    }

    public int levelOf(int cardId) {
        return cardId < 0 || cardId >= level.length ? 0 : level[cardId];
    }

    public boolean maxed(int cardId) {
        int max = ShopRules.maxLevelOf(cardId);
        return max != ShopRules.UNLIMITED && levelOf(cardId) >= max;
    }

    /** 本局一共在商店花掉几级——结算页与"这局玩的是什么 build"的唯一读数。 */
    public int totalLevels() {
        int n = 0;
        for (int v : level) n += v;
        return n;
    }

    /**
     * 记一级并返回它是否被接受。
     *
     * <p>参数是**卡 id**（{@link Balance.ShopCard#FIREPOWER}..{@link Balance.ShopCard#GREED}），
     * 不是货架槽位：槽位每次开架都会重排，把槽位当 id 传进来就是规格 §成长树 那条
     * "点了没反应、钱不扣"警示的商店版本。扣币与真正生效（回血、给炸弹、转好超载）
     * 都在 {@code Game} 那边——这里只承认"这张卡又升了一级"。
     *
     * @return false 表示卡 id 非法或已满级（调用方不该扣钱）
     */
    public boolean buyUpgrade(int cardId) {
        if (cardId < 0 || cardId >= level.length) return false;
        if (maxed(cardId)) return false;
        level[cardId]++;
        return true;
    }

    /** 新一局从零开始。**复用同一个数组**：挂在它上面的快照引用因此不必重新接。 */
    public void reset() {
        for (int i = 0; i < level.length; i++) {
            level[i] = 0;
        }
    }

    // ---- 乘子（只读派生值）----------------------------------------------------------------

    /**
     * 火力强化卡累计的**固定加伤**（点/发）。出膛那一刻加进子弹，见
     * {@link WeaponFire#fillTemplate}。
     *
     * <p>它不是乘子：规格原写的每级 +2% 会被按发取整的伤害量化吃掉（激光基础 1 点时十级全空），
     * 2026-09-25 已改成定值。成长树那条攻击项仍然是乘子，两者在 {@code Game.playerFire} 里
     * 先乘后加——顺序写在 {@link WeaponFire} 的公式里，只有一处。
     */
    public int damageBonus() {
        return Balance.shop.damageFlatPerLevel * levelOf(Balance.ShopCard.FIREPOWER);
    }

    /**
     * 扳机卡累计的**额外弹丸数**（发/次开火）。进 {@code Game.playerFire} 的 {@code pellets}，
     * 由 {@code BulletChain} 按弹链排布——不是同角度重叠，是多列多时隙。
     *
     * <p><b>激光不吃，这条例外只在这里定义一次。</b>它是全项目唯一的"等级 → 发数"读数点，
     * 所以结算、卡面、以及一切推导（弹池 bound、链的数组上界）都必须从这里取；在别处再写一遍
     * {@code if (weaponId == LASER)} 就是第二个真源，将来只会有其中一处跟着数值表动。
     *
     * <p>为什么激光该被排除：它 {@code drawLenRatioY = 3.4}、弹速 620，一根已经占满整条
     * 纵向通道的连续光带再加列，只会把"一道激光"拆成"几道看不清的细线"——形态先于颜色，
     * 激光的形态就是那一条线。玩家侧的知情渠道是卡面 tip（「激光不吃」），因为
     * {@code ShopRules.Snapshot} 不带武器字段，卡面在代码层无法按武器显示不同的数。
     *
     * @param weaponId {@link Balance.Weapon} 的武器 id
     */
    public int pelletBonus(int weaponId) {
        if (weaponId == Balance.Weapon.LASER) return 0;
        return Balance.shop.pelletPerLevel * levelOf(Balance.ShopCard.TRIGGER);
    }

    public float speedMul() {
        return mul(Balance.ShopCard.THRUSTS, Balance.shop.speedPerLevel);
    }

    /** 金币收益乘子：在 {@code applyCollected} 的入账那一刻乘一次，不逐枚四舍五入。 */
    public float coinMul() {
        return mul(Balance.ShopCard.GREED, Balance.shop.coinPerLevel);
    }

    /** 吸取半径乘子（作用于 {@code Drops} 的 pull 半径；pickup 是贴身判定，不参与成长）。 */
    public float magnetMul() {
        return mul(Balance.ShopCard.MAGNET, Balance.shop.magnetPerLevel);
    }

    /** 暴击率的额外**百分点**，与 {@link GrowthTree#critBonusPercent()} 相加（同为百分点才加得对）。 */
    public int critBonus() {
        return Math.round(Balance.shop.critPerLevel * levelOf(Balance.ShopCard.PRECISION));
    }

    /** 装甲卡累计抬高的生命上限（由 {@code Game} 在买入时调 {@code raiseCaps}，这里只给总量）。 */
    public int hullBonusTotal() {
        return Math.round(Balance.shop.hullPerLevel * levelOf(Balance.ShopCard.HULL));
    }

    // ---- 簇 II：导弹机制卡（2026-10-01 实现授权）--------------------------------------------
    // 这四个读数都是**导引参数的增量**，不是乘子也不是百分比：它们要和 Balance.Missile 里的
    // 出厂值相加，而相加的位置只有一处（MissileBehavior.beginFrame 每帧解析有效值）。

    /** 中段引导是**买断**卡（{@code midCourseMaxLevel = 1}），所以这里只有"有没有"、没有"几级"。 */
    public boolean midCourseOn() {
        return levelOf(Balance.ShopCard.MID_COURSE) > 0;
    }

    /**
     * 二次点火开关：这张卡**第一级买的是机制本身**（锁定瞬间再点一次火），后面两级才是半径。
     *
     * <p>它不是出厂行为——他的原话 L14750「锁定最近的目标，再次点火」在 L41661 被自己加了括号
     * 「如果没有二次点火卡就没有再次点火」，所以 {@code Balance.Missile} 里那个
     * {@code reIgnitionOnAcquire} 布尔已删：商品位只从这里读。
     */
    public boolean reIgnitionOn() {
        return levelOf(Balance.ShopCard.IGNITION) > 0;
    }

    /** 导引头视场半径的增量（px），来自二次点火卡：+25/级 ×3。 */
    public float seekerRangeBonus() {
        return Balance.shop.seekerRangePerLevel * levelOf(Balance.ShopCard.IGNITION);
    }

    /** 最大可用过载的增量（px/s²），来自机动过载卡：+370/级 ×4。 */
    public float latAccelBonus() {
        return Balance.shop.latAccelPerLevel * levelOf(Balance.ShopCard.HANDLING);
    }

    /**
     * 视场**总夹角**的增量（度），来自机动过载卡：+6°/级 ×4 ⇒ 60°→84°。
     *
     * <p>这里给的是整角，折半在解析点做（{@code MissileBehavior.beginFrame}）：他「收」的那句
     * 口径是「圆心角 84°」，卡面也按整角印，半角只是实现里的比较量。
     */
    public float seekerArcDegBonus() {
        return Balance.shop.seekerArcDegPerLevel * levelOf(Balance.ShopCard.HANDLING);
    }

    /**
     * 格斗导弹（簇 II 第四件）：同样是**买断**卡，所以这里只有"有没有"、没有"几级"。
     *
     * <p>⚠ 这个开关只回答"要不要开那条发射判据"，**不改任何导引参数**：格斗弹那六个
     * {@code dogfight*} 字段全在 {@code Balance.Missile} 里，一张卡买断之后没有等级可叠，
     * 于是"卡面数字"与"结算读数"两件事在它身上都不存在（{@code ShopRules.perLevelOf}
     * 对它返回 0 是同一条）。
     */
    public boolean dogfightOn() {
        return levelOf(Balance.ShopCard.DOGFIGHT) > 0;
    }

    /**
     * 连续杆（2026-10-02，他逐字裁「新增『连续杆』卡，未买靠撞击」）：**买断**，所以这里只有
     * "有没有"、没有"几级"。
     *
     * <p>⚠ 这个开关改的是**炸开算几只**，不是伤害数值——唯一读取点是
     * {@code Game.detonateMissile} 里那条分路（{@code WarheadRules.detonationRoute}）：
     * 真 ⇒ 展开杆与碎片，假 ⇒ 弹体只撞引信碰到的那一只。它天生带的时期写过一句
     * "导弹命中接近后展开连续杆战斗部"（他 L14750），那句话从今天起要读成"买了杆才展开"。
     */
    public boolean rodOn() {
        return levelOf(Balance.ShopCard.ROD) > 0;
    }

    // ---- 武器持有（2026-10-02，他裁「商店新增『基础导弹』和『基础激光』」＋「加个切换键」）------
    // 这一格之所以必须存在，是因为「格斗导弹」那张卡今天买下去场上什么都没发生：它开的判据是
    // {@code dogfightOn() && player.weapon().guided}，而**制导那一把自己从来没卖过**——六把枪里
    // 只有 MISSILE 的 guided 为真，通往它的唯一途径是物理键盘 1..6。买断卡有商品位、机制没有
    // 获取途径，两层各自都自洽，所以没有一条断言响过（他实测报的就是这条）。

    /**
     * 这一把我现在**有没有**。出厂只有不需要买的那几把；{@code LASER} 与 {@code MISSILE} 是商品，
     * 由两张解锁卡各买断一级。
     *
     * <p>判据写成 (武器 id → 卡 id) 的两行而不是 {@code Balance.Weapon} 上一个新字段：与
     * {@link #pelletBonus(int)} 那条「激光不吃」同一形状——武器与商品的对应关系只在**持有量**
     * 这一层有意义，烙进武器表就等于把商店的知识搬进配置表（那边一改价，这边不会跟着动）。
     *
     * @param weaponId {@link Balance.Weapon} 的武器 id
     */
    public boolean weaponUnlocked(int weaponId) {
        if (weaponId == Balance.Weapon.LASER) return levelOf(Balance.ShopCard.BASIC_LASER) > 0;
        if (weaponId == Balance.Weapon.MISSILE) return levelOf(Balance.ShopCard.BASIC_MISSILE) > 0;
        return true;
    }

    /**
     * 从 {@code currentId} 往后数**第一把我有的**，绕一圈。这一格只属于底部那枚切枪键；
     * 键盘 1..6 报的是**绝对 id**，它走的是 {@code Game.selectWeapon} 那道闸门——两条路的
     * 持有判据是同一个（{@link #weaponUnlocked}），所以"没买过的枪按下去会不会响"只有一个答案，
     * 而"下一把换成谁"只有触屏需要算。
     *
     * <p>循环而不是列表：六把枪按 id 排开，玩家要的是一次点击换一把，不是打开一个选择面板——
     * 后者在战斗里是两次点击加一次遮挡，而他给的是「加个切换键」（一个键）。
     *
     * <p>最后那句 {@code return currentId} 是**编译器要求的收尾**，不是一道防御：今天四把默认枪
     * 永远解锁，所以这个循环按构造必定找到一把别的（{@code ShopRulesTest} 里那条"每按一次必换"
     * 断的就是它）。真有一天有人把剩下三把也挂上闸门，那一格才第一次可达——届时它的语义是
     * "只有一把枪时这枚键按了没反应"，那仍然不是一种错误，所以既不抛也不返回 -1。
     */
    public int nextUnlockedWeapon(int currentId) {
        int n = Balance.weapons.length;
        for (int k = 1; k < n; k++) {
            int id = (currentId + k) % n;
            if (weaponUnlocked(id)) return id;
        }
        return currentId;
    }

    private float mul(int cardId, float perLevel) {
        return 1f + perLevel * levelOf(cardId);
    }
}
