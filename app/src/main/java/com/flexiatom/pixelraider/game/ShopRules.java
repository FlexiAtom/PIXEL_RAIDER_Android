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
 * 商店的纯算术端：有效性过滤、优先级、三选一抽签、价格阶梯、卡面核心数字。
 *
 * <p>两个入口、两种货架策略（{@link #ENTRY_WAVE} 抽样三选一 ／ {@link #ENTRY_PAUSE} 常驻整条货架），
 * 判据全部住在本类，界面只问"这一格是什么"。
 *
 * 这里**不 import android**，因为"低血时不该抽出三张长线养成卡"这类判据只能靠断言判，
 * 肉眼从截图上看不出来（看不出来 ≠ 没问题，项目级坑里已经记过三次这个形状）。
 *
 * 数值一律现读 {@link Balance.Shop}，本类不存任何一份：卡面显示的数与实际结算的数
 * 必须是同一个字节的同一个字段，否则热调一档就会分裂成"显示与实际不一致"。
 */
public final class ShopRules {

    private ShopRules() { }

    /** 一次性补给卡没有等级上限的哨兵（= {@link Balance.Shop#UNLIMITED}，就近转发给调用方）。 */
    public static final int UNLIMITED = Balance.Shop.UNLIMITED;

    /**
     * 卡面核心数字怎么读。面板据此决定后缀，规则端只管数值。
     *
     * <p>{@link #CORE_SHOTS} 是扳机卡每级改卖一个离散量之后新增的一档。⚠「改卖发数」这个说法是我的：
     * 用户逐字只到 L14292（answers-**FREE**，2026-09-25T07:00:45.782Z）「射速本身固定，但是子弹每次+1」
     * 与 L23955（纯文本自敲，UTC 2026-09-26T09:35:09.178Z ＝本地 17:35）「射速卡卖的是一个值在购买后加一…对于导弹…是多一发导弹」
     * ——他没有用过"发数/弹丸/pellet"这套词，而且后一条把语义交给各武器档自己解释。
     * ⚠ 新增一档就必须同时改
     * {@code ShopScreen.drawCore} 那个 switch——它的 default 画"立即就绪"并 return，
     * 漏 case 的话那张卡**安静地印成一句与数值无关的话**。这条没有测试兜着：
     * {@code ShopScreenTest} 只钉得住静态动作码（{@code ACTION_*} 区间），
     * {@code drawCore} 走的是 Canvas 绘制路径，JVM 侧**零覆盖** ⇒ 判据要下沉到本类
     * （见 {@link #coreKind}），面板侧只剩"真机看一眼"。
     *
     * <p>{@link #CORE_CHOICE} 是「随机强化」（2026-10-01）加的第六档：它没有数值可显示，
     * 卡面要说的是"四样里抽一样"这件事本身。**特意不走 default**——default 那一路画
     * 「立即就绪」，那是给冷却制卡片用的话，画在一张"立即给你一种增益"的卡上是一句上屏的假话。
     *
     * <p>后三档同族，都是簇 II 三张导弹卡（2026-10-01 实现授权）带来的新单位：
     * {@link #CORE_UNLOCK} 和 {@link #CORE_CHOICE} 一样**没有数**（中段引导是买断机制），
     * {@link #CORE_PX}／{@link #CORE_DEG} 则是两个**只在导引头上有意义的离散量**——
     * 它们不能并入 {@code CORE_POINTS}，"点"在 HUD 里已经被生命/伤害占走，
     * 把「+25 点」印在一张加长雷达扇形的卡上会让玩家以为那是伤害。
     */
    public static final int CORE_PERCENT = 0, CORE_POINTS = 1, CORE_COUNT = 2, CORE_INSTANT = 3,
            CORE_SHOTS = 4, CORE_CHOICE = 5, CORE_UNLOCK = 6, CORE_PX = 7, CORE_DEG = 8;

    /**
     * 商店的两个入口。他的逐字（2026-09-30 直发）：「我提议，波次结束的商店仅提供弹药补给、护盾、
     * 血量、临时加成之类的，而其他非补给的卡在暂停页做个商店放里面」。
     *
     * <p>住在 {@link Snapshot#entry} 上、**开架那一刻冻结一次**，因为"这是哪家店"要同时决定三件事：
     * 货架构成（{@link #isWaveCard}）、价格乘子（{@link #discountFor}）、以及关店动作——
     * ⚠ 第三件在 B1 这一步**还没接**：{@code Game.closeShop} 今天无条件清场并推进波次，那是回合店的
     * 语义，暂停店关掉面板时既不该清空战场也不该结束间奏（接线在下一段，见池件 §12.4）。
     * ⚠ 做成方法参数而不是快照字段是有代价的：{@link #nextPrice} 有**三个**读取点（卡面、扣币端、
     * {@link #canAfford}），参数化就等于把"这一处该传哪个入口"变成三道各自可能答错的判断题。
     * 挂在同一个快照上，三处读的是同一个字节。
     */
    public static final int ENTRY_WAVE = 0, ENTRY_PAUSE = 1;

    /**
     * 这张卡在**哪一侧**的货架上。回合店（通用侧）＝ 补给与临时加成，暂停店（非通用侧）＝ 养成卡。
     *
     * <p>必须显式写出来，不许从 {@code priceStep == 0} 猜——两侧各打穿一次那种猜法：
     * 装甲强化是**通用但不是一次性补给**（阶梯 40+18·lv、有满级，他自己逐字裁「装甲强化归通用」），
     * 随机强化是**一次性但通用**。他给的轴本来是"补给 / 非补给"，落点收窄成"通用 / 非通用"
     * 正是被这两张卡逼出来的（池件 §7.4）。
     *
     * <p>新卡**默认落进暂停侧**（default 那一路），这个方向是挑过的：常驻那一侧不看 {@link #prio}，
     * 漏登记一次的表现只是"这张卡摆在了暂停店里"，画面与扣钱都不会错。反过来若默认进通用侧，
     * 没写过分支的卡会撞上 {@code prio} 的 {@code default: return 0f}，永远排不进前三——表现是
     * "这张卡根本买不到"，读起来像数值问题，不像漏登记。真正兜底的是 {@code ShopRulesTest}
     * 钉住的**两侧张数**：加一张而不在这里登记，它就红。
     */
    public static boolean isWaveCard(int cardId) {
        switch (cardId) {
            case Balance.ShopCard.REPAIR:
            case Balance.ShopCard.SHIELD:
            case Balance.ShopCard.SALVO:
            case Balance.ShopCard.SURGE:
            case Balance.ShopCard.HULL:
            case Balance.ShopCard.RANDOM:
                return true;
            default:
                return false;
        }
    }

    /**
     * 开架那一刻的现状。由 Game 填（一次一填，不是每帧），本类只读。
     *
     * <p>{@link #level} 指向 {@link ShopRun#levels()} 那同一个数组，不复制——复制一份就得在两处
     * 维护"买完之后"的等级，而商店开着时玩家会连续买好几张。
     */
    public static final class Snapshot {
        public int wave;
        public int hp, maxHp;
        public int shield, maxShield;
        /** 超载是冷却制，"转好了"就是没有可卖的能量卡（规格：能量满不出能量卡）。 */
        public boolean overloadReady;
        public int bombs;
        public int coins;
        public int[] level = new int[Balance.Shop.CARDS];
        /**
         * 开架时冻结的入口（{@link ShopRules#ENTRY_WAVE} ／ {@link ShopRules#ENTRY_PAUSE}）。
         * 默认回合店 —— 字段初值就是那个"折扣与抽样都照旧"的旧行为，加了这个字段而没接线
         * 的调用点不会悄悄变成暂停店。
         */
        public int entry = ENTRY_WAVE;

        public float hpRatio() {
            return maxHp <= 0 ? 1f : (float) hp / (float) maxHp;
        }

        public int levelOf(int cardId) {
            return cardId < 0 || cardId >= level.length ? 0 : level[cardId];
        }

        /** 缺盾比例（满盾 0，一点没有且上限存在 1）；用于"该不该卖护盾卡"。 */
        public float shieldDeficit() {
            return maxShield <= 0 ? 0f : (float) (maxShield - shield) / (float) maxShield;
        }
    }

    // ---- 阶梯 -----------------------------------------------------------------------------

    /** 每级的量。一次性卡返回它那一档的固定量（维修 +40 点、弹药 +3 枚），所以"每级"在这里就是"每次"。 */
    public static float perLevelOf(int cardId) {
        Balance.Shop k = Balance.shop;
        switch (cardId) {
            case Balance.ShopCard.FIREPOWER: return k.damageFlatPerLevel;
            case Balance.ShopCard.TRIGGER: return k.pelletPerLevel;
            case Balance.ShopCard.PRECISION: return k.critPerLevel;
            case Balance.ShopCard.THRUSTS: return k.speedPerLevel;
            case Balance.ShopCard.HULL: return k.hullPerLevel;
            case Balance.ShopCard.REPAIR: return k.repairAmount;
            case Balance.ShopCard.SHIELD: return Balance.shield.fromUpgrade;
            case Balance.ShopCard.SALVO: return k.salvoBombs;
            case Balance.ShopCard.SURGE: return 0f;      // 没有量，只有"立即"
            case Balance.ShopCard.RANDOM: return 0f;     // 同上：卖的是"抽一种"，不是某个数
            case Balance.ShopCard.MAGNET: return k.magnetPerLevel;
            case Balance.ShopCard.GREED: return k.coinPerLevel;
            // 簇 II 四张：这里的"每级的量"是**卡面显示值**，真正生效的加法在 ShopRun 那几个读数里，
            // 两边都读同一格 Balance.shop 字段，所以不存在"写着 +25、实发 +30"那种分裂。
            case Balance.ShopCard.MID_COURSE: return 0f;   // 买断机制，没有数
            case Balance.ShopCard.IGNITION: return k.seekerRangePerLevel;
            case Balance.ShopCard.HANDLING: return k.seekerArcDegPerLevel;
            case Balance.ShopCard.DOGFIGHT: return 0f;     // 同中段引导：买断一条流，没有数
            // 两张武器解锁卡（2026-10-02）与中段引导同形：卖的是"这一把在不在飞机上"，没有量。
            case Balance.ShopCard.BASIC_MISSILE: return 0f;
            case Balance.ShopCard.BASIC_LASER: return 0f;
            // 连续杆（2026-10-02）同形：它改的是"这一发炸开算几只"，表上没有一个可加的数。
            // ⚠ 这里的 0 与 default 那一路**撞不出来**：default 也返回 0f。它必须显式写，是因为
            // coreKind 那一路要给这张卡挑「解锁」而不是「立即就绪」，而那张卡有没有数就看在不在这一格里。
            case Balance.ShopCard.ROD: return 0f;
            // 雷达锁定同形：它买的是"一块屏在不在 + 那一锁听不听指定"，表上没有一个可加的数。
            case Balance.ShopCard.RADAR: return 0f;
            default: return 0f;
        }
    }

    /**
     * 等级上限；一次性卡与 2026-09-26 起取消满级的两张养成卡返回 {@link #UNLIMITED}
     * （买多少次都按固定量 / 都按阶梯加价）。
     *
     * <p>⚠ 这两张养成卡**刻意写成显式 case** 而不是让它们掉进 default：default 那一路的语义是
     * "一次性补给卡，没有等级可言"，把养成卡混进去就等于把 {@link #showsLevel} 的判据
     * 变成"读 maxLevelOf"，而那个哨兵正因为承载了两种语义才需要被拆开。
     */
    public static int maxLevelOf(int cardId) {
        Balance.Shop k = Balance.shop;
        switch (cardId) {
            case Balance.ShopCard.FIREPOWER:
            case Balance.ShopCard.TRIGGER: return UNLIMITED;
            case Balance.ShopCard.PRECISION: return k.critMaxLevel;
            case Balance.ShopCard.THRUSTS: return k.speedMaxLevel;
            case Balance.ShopCard.HULL: return k.hullMaxLevel;
            case Balance.ShopCard.MAGNET: return k.magnetMaxLevel;
            case Balance.ShopCard.GREED: return k.coinMaxLevel;
            // 簇 II 四张都有上限：中段引导与格斗导弹是**买断**（1 级），另两张是"级数换半径/角度"。
            // 满级后由 isValid 顶部那道 maxLevelOf 检查自动下架，不靠货架端特判。
            case Balance.ShopCard.MID_COURSE: return k.midCourseMaxLevel;
            case Balance.ShopCard.IGNITION: return k.seekerRangeMaxLevel;
            case Balance.ShopCard.HANDLING: return k.handlingMaxLevel;
            case Balance.ShopCard.DOGFIGHT: return k.dogfightMaxLevel;
            // 两张武器解锁卡：买断一级，之后由上面那道 isValid 的满级检查自动下架（同中段引导）。
            // 读同一个 weaponUnlockMaxLevel 是刻意的——这两张的"级数"不是一个可分别调的量（见 Balance 那条）。
            case Balance.ShopCard.BASIC_MISSILE:
            case Balance.ShopCard.BASIC_LASER: return k.weaponUnlockMaxLevel;
            // 连续杆：买断一级（同中段引导）。这一格**不写就会安静地坏**——default 是 UNLIMITED，
            // 表现是买过之后它永远留在货架上，而一张没有第二级可买的卡反复上架就是假商品。
            case Balance.ShopCard.ROD: return k.rodMaxLevel;
            // 雷达锁定同族：default 是 UNLIMITED，漏这一格就是"买过一次之后永远上架"的假商品。
            case Balance.ShopCard.RADAR: return k.radarMaxLevel;
            default: return UNLIMITED;
        }
    }

    /**
     * 卡角该不该画「Lv n」。判据是**收益会不会随累计等级变多**，不是"它有没有等级上限"。
     *
     * <p>这条判据原本是从 {@link #maxLevelOf} 那个哨兵推出来的，取消满级之后推不出来了：
     * 火力与扳机现在也返回 {@link #UNLIMITED}，照旧判法它们会连角标一起没了（玩家看不出自己
     * 这局压了几级），而反过来把哨兵判反一次，那几张一次性卡就会印出「Lv 3」——第三支维修针
     * 并不比第一支更值钱，那是一句上屏的假话。
     */
    public static boolean showsLevel(int cardId) {
        switch (cardId) {
            case Balance.ShopCard.REPAIR:
            case Balance.ShopCard.SHIELD:
            case Balance.ShopCard.SALVO:
            case Balance.ShopCard.SURGE:
            case Balance.ShopCard.RANDOM:
            case Balance.ShopCard.MID_COURSE:
            case Balance.ShopCard.DOGFIGHT:
            // 两张武器解锁卡同族：买断一个机制（这一把在不在飞机上），没有累计等级可画。
            case Balance.ShopCard.BASIC_MISSILE:
            case Balance.ShopCard.BASIC_LASER:
            // 连续杆同族：买断的是"这一发炸不炸成一片"，没有累计等级可画（default 是 true，
            // 漏这一格就会在卡角印出「Lv 0」——那张卡买过一次就下架，那行字永远是废话）。
            case Balance.ShopCard.ROD:
            // 雷达锁定同族：买断的是"那块屏在不在"，只有买过/没买过两态。
            case Balance.ShopCard.RADAR:
                // 中段引导与格斗导弹都是买断：只有"买过/没买过"两态，而买过之后这张卡就下架了，
                // 所以「Lv 0」是它唯一会上屏的一行废话——判据仍是那条"收益随不随累计等级变多"。
                return false;
            default:
                return true;
        }
    }

    /** 下一级的价格（已购 lv 级之后）。满级返回 {@link Balance.Shop#PRICE_MAXED}。 */
    public static int nextPrice(Balance.ShopCard card, Snapshot s) {
        if (card == null) return Balance.Shop.PRICE_MAXED;
        int lv = s.levelOf(card.id);
        int max = maxLevelOf(card.id);
        if (max != UNLIMITED && lv >= max) return Balance.Shop.PRICE_MAXED;
        int raw = card.priceBase + card.priceStep * Math.max(0, lv);
        // 折扣（I-3）只有这一个乘点：卡面显示的价与扣币端算的价必须出自同一个函数，各乘一份
        // 就会长成"写着 28、扣 30"——本仓规格点名的"显示与结算不一致"那一类。
        // 上面那两条 PRICE_MAXED 早退在乘子之前 ⇒ 哨兵原样透出，不靠"-1×0.95 取整还是 -1"这种巧合。
        // 乘子由入口派生（他裁的是"**回合结束那个**商店打九五折"，暂停页那家全价），
        // 见 {@link #discountFor}——两个入口共用这一行，所以"哪家店"必须由快照说，不能由调用方说。
        return Math.round(raw * discountFor(s.entry));
    }

    /**
     * 这个入口的价格乘子：回合店九五折（{@link Balance.Shop#waveDiscount}），暂停店**全价**。
     *
     * <p>暂停侧没有折扣字段，是**故意**的：他给的 0.95 挂在"回合结束那个商店"这句话上，
     * 把它做成一个全局乘子会悄悄覆盖两个入口。真要给暂停店配折扣，就再加一个字段，
     * 而不是把这里改成 {@code 1f} 之外的任何东西。
     */
    public static float discountFor(int entry) {
        return entry == ENTRY_WAVE ? Balance.shop.waveDiscount : 1f;
    }

    // ---- 过滤与优先级 ---------------------------------------------------------------------

    /**
     * 这张卡对当前这架飞机还有没有意义。
     *
     * <p>判据是"买了之后数值一定变"：满血买维修、满盾买充能、能量转好买重置，三种都是花了钱什么都没发生。
     * 满级也算无效（它不该再上架，而不是上架成一张永远点不动的卡）。
     *
     * <p>护盾卡那条还兼着**溢出额度唯一的闸门**（下面 case 上的注释），所以它现在不只是"防白买"。
     */
    public static boolean isValid(int cardId, Snapshot s) {
        int max = maxLevelOf(cardId);
        if (max != UNLIMITED && s.levelOf(cardId) >= max) return false;
        switch (cardId) {
            case Balance.ShopCard.REPAIR: return s.hp < s.maxHp;
            // 归属分两半（2026-09-27 逐子句核第一手；本仓注释日期 = 本地日，transcript 戳是 UTC）：
            // ① 「护盾充能卡允许溢出上限」= 用户纯文本自敲 **L13322**（05:33:42Z）⇒ 他的字、他的决定。
            // ② 「满盾不上架」这个**做法**是我给出的选项标签，他挑了它：**L13890**（06:26:20Z，
            //    answers-**OPT**，逐字「满盾不上架（最小改动）」）⇒ 决策是他的、措辞是我的，
            //    先前这句写成"用户裁定'满盾不上架'"是把选项标签当成了原话。
            // 结算端已经允许这张卡越过 maxShield 全额入账，
            // 规格 §五 的无效卡过滤原本只列了"满血不出治疗、能量满不出能量"两种，满盾这条是实现自己加的
            // 推导——现在它升格成溢出额度的**唯一**真源：买一张最多顶到 maxShield+49，越限后自动下架，
            // 掉回上限之内才重新出现。所以这里**不能**改成恒真，也不能在 PlayerState 里再钳一次。
            case Balance.ShopCard.SHIELD: return s.shield < s.maxShield;
            case Balance.ShopCard.SURGE: return !s.overloadReady;
            // 簇 II 那几张改的是**导弹的导引参数、那条流的发射判据与那发的战斗部**。手上没有导弹
            // 这一把时买它们，数值一层没变、场上一点不动——这正是本方法的判据（"买了之后数值一定变"）
            // 要拦的那一种，所以闸门挂在解锁卡上，而不是让下游每张各自变成"先存着，等哪天有了导弹再说"。
            // ⚠ 这条推论是我的，他不是这么说的：他裁的是「商店新增『基础导弹』和『基础激光』」＋
            // 「加个切换键」。但他报的病因（「格斗导弹购买后无效果」）里，"上游没开闸的下游照卖"
            // 是第二半，只修切枪修不完——所以他一旦按这条裁定把导弹买了，货架上那几张才第一次真的有用。
            case Balance.ShopCard.MID_COURSE:
            case Balance.ShopCard.IGNITION:
            case Balance.ShopCard.HANDLING:
            case Balance.ShopCard.DOGFIGHT:
            // 连续杆（2026-10-02）也在闸门之后：它改的是**弹体炸开那一下**，没有导弹这一把时
            // 场上没有任何一发会走到那条路，买下去数值层没变、画面层也没变。
            case Balance.ShopCard.ROD:
            // 雷达锁定走同一道闸门（2026-10-03）：它指定的是**普通弹那一锁**的目标，
            // 手上没有导弹这条流时，那块屏画出来也点不出任何后果——卖一件场上不存在的机制就是假商品。
            case Balance.ShopCard.RADAR:
                return s.levelOf(Balance.ShopCard.BASIC_MISSILE) > 0;
            default: return true;
        }
    }

    /**
     * 「当前最缺什么」的分数。**没有量纲**，只在同一张货架内比较（规格 §升级商店：不是纯随机）。
     *
     * <p>⚠ 分区之后（2026-10-01）**只有通用侧那六张还有生产读取点**——{@link #selectOffer} 先按
     * {@link #isWaveCard} 过滤，非通用侧走 {@link #listShelf} 根本不比较分数。下面六条养成卡的分支
     * 今天只剩测试与探针在读（{@code ShelfMix} 靠它复算门槛）。删它们要单独一轮，依据写的是
     * "没有读取点"而不是"看着多余"（池件 §7.5）。
     *
     * <p>两条线：
     * <ul>
     *   <li><b>保命项</b>随血量缺口上升，并且额外叠一项"低于 {@link Balance.Shop#panicHpRatio}
     *       就陡增"的台阶。台阶是必需的：0.35 血和 0.45 血只差 0.1，长线卡的波次项一旦爬到 0.8，
     *       纯线性会让"快死了推养成卡"在高波次重新出现。</li>
     *   <li><b>长线养成项</b>随波次上升（越到后面越值），贪婪卡反向随波次**衰减**——它的收益是
     *       "之后每一波掉的金币都乘一下"，第 2 波买和第 18 波买不是同一个商品，卡面那句
     *       "越早买越划算"要有人替它排序才成立。分区之后这六条都不再决定上架（它们改由
     *       {@link #listShelf} 无条件可见），形状保留只为让"越早越划算"这件事还能被断言。</li>
     * </ul>
     */
    public static float prio(int cardId, Snapshot s) {
        Balance.Shop k = Balance.shop;
        float hpRatio = s.hpRatio();
        float deficit = 1f - hpRatio;
        float panic = hpRatio < k.panicHpRatio ? 1f : 0f;
        float waveTerm = waveTerm(s.wave, k.growthWaveScale);
        switch (cardId) {
            case Balance.ShopCard.REPAIR:
                return 0.30f + deficit * 1.20f + panic * 0.60f;
            case Balance.ShopCard.SHIELD:
                return 0.28f + s.shieldDeficit() * 0.90f + panic * 0.30f;
            case Balance.ShopCard.THRUSTS:
                // 基线从 0.20 抬到 0.42。归属分三层（与 {@code Balance.ShopCard} 的 THRUSTS 那格同源）：
                // ① "提高这张卡的出现概率 + 下调价格"= 用户纯文本自敲 **L12803**（UTC 09-24T16:43
                //    ＝本地 09-25T00:43，queued_command/origin=human）⇒ 动作是他的；
                // ② 让它值这个价的前提"触屏走位改成限速跟随"= **L12104**（09-24T15:20:49Z，
                //    answers-**OPT**「指针改成限速跟随（推荐）」）⇒ 选择是他做的、那六个字是我写的标签；
                // ③ **0.42 这个数是我的**——他没给过任何优先级数值，"抬到多少"没有第一手出处。
                // 触屏走位改成限速跟随之后，
                // 机体速度同时是"指针欠账每秒能放出去多少"的上限（见 DragDebt.consume 的 speed），
                // 这张卡因此不只是躲子弹的手段，也是"手感跟不上眼睛"的唯一出口——
                // 原来它满血时排在全部卡的末尾，前几波基本抽不到，等于那条出口没开。
                // ⚠ 分区（2026-10-01）之后这张卡归**暂停店**，常驻可见、压根不走抽样 ⇒ 这条抬升
                // 已经没有承载物，他逐字裁的就是这件事：「提高速度卡出现概率废弃，因为失去承载物，
                // 仅低价即可」。低价（22/10）留着；这条分支只作为 prio 全表函数的一环留着，不再是闸门。
                return 0.42f + deficit * 0.50f + waveTerm * 0.25f;
            case Balance.ShopCard.HULL:
                // 抬上限是"接下来这一整波都受益"的投资：血已经见底时才让位给立刻能用的维修。
                return 0.25f + deficit * 0.30f + waveTerm * 0.35f - panic * 0.20f;
            case Balance.ShopCard.SALVO:
                // 手里没炸弹时它是唯一的一张"翻盘手牌"；有了就只是补充。
                return s.bombs <= 0 ? 0.55f : 0.30f;
            case Balance.ShopCard.SURGE:
                return 0.42f;
            case Balance.ShopCard.RANDOM:
                // 平值一张，残血整条让位。0.40 这个数是我的——他没给过任何 prio 数值
                // （归属三层见上面 THRUSTS 那条 case）。
                //
                // 为什么是平值：这张卡卖的是**十二秒的消耗品**，不是投资。长线养成卡的波次项
                // 编码的是"越早买越划算"，临时 buff 没有这件事——给它波次项只是为了跟住货架门槛，
                // 把"后期更该买"这句假话写进分数里。
                //
                // 为什么 0.40 就够（探针⑯，波 1..20 × 4000 种子 × 四档现状）：分区之后回合店只从
                // 六张通用卡里抽，满状态那档的**第三名 prio 恒在 0.30**（波 3 起）、扣掉抖动后门槛
                // 0.22 ⇒ 0.40 的露脸率 94.8%，剩下的 5% 是抖动在换 SURGE/HULL/SALVO 的位。
                // ⚠ 这条读数**推翻了本 case 先前那版注释的理由**（"刻意压在 REPAIR 地板 0.30 之下、
                // 靠 prioJitter 0.08 才翻得上架"）：那是拿**十二张全表**想的，全表门槛从波 1 的 0.340
                // 一路爬到波 20 的 0.700，任何平值都进不了架（0.26 与 0.40 实测都是 0.0%）。
                // 这张卡的家在通用池，不在养成卡霸榜的全表里——在错的池子里量出来的"上不了架"
                // 当时差点把这张卡改成随波次爬的假形状。
                //
                // panic 项买的是另一条实测：残血**有弹**那一档（hp 25、bombs 1），平值 0.40 仍有
                // 16.3% 的货架挤进三格（波 1..6 各 30~38%）——那三格该是维修/护盾/弹药的。
                // 钳到 0 之后 0.0%。残血无弹本来就被 SALVO 的 0.55 挡在外面（平值也只 0.1%），
                // 所以这条钳只在"有翻盘手牌"的残血局里真正起作用。
                return 0.40f - panic * 0.40f;
            case Balance.ShopCard.FIREPOWER:
                return 0.35f + waveTerm * 0.50f;
            case Balance.ShopCard.TRIGGER:
                return 0.34f + waveTerm * 0.48f;
            case Balance.ShopCard.PRECISION:
                return 0.33f + waveTerm * 0.45f;
            case Balance.ShopCard.MAGNET:
                return 0.30f + waveTerm * 0.30f;
            case Balance.ShopCard.GREED:
                return 0.52f - waveTerm * 0.24f;
            default:
                return 0f;
        }
    }

    /** 波次项：第 N 波 = N / 分母，钳在 0~1（第 20 波之后不再无限爬，免得养成卡永久霸榜）。 */
    public static float waveTerm(int wave, float scale) {
        if (scale <= 0f || wave <= 0) return 0f;
        float v = wave / scale;
        return v > 1f ? 1f : v;
    }

    /**
     * 开架：把这一家店的货写进 {@code out}。**入口决定货架策略**，判据只在这一处分支：
     *
     * <ul>
     *   <li>{@link #ENTRY_WAVE} 回合店 —— 只在通用侧抽：过滤无效 → 按 prio + 抖动降序 →
     *       取前 {@link Balance.Shop#OFFER} 张。</li>
     *   <li>{@link #ENTRY_PAUSE} 暂停店 —— 非通用侧**整条摆出来**（{@link #listShelf}），不抽样。</li>
     * </ul>
     *
     * <p>抖动是必需的：纯 prio 的话同一局每次开架都是同三张，"三选一"退化成"确认键"。
     * 幅度钉在 {@link Balance.Shop#prioJitter}（默认 0.08）——它要小到翻不了保命/养成的盘，
     * 又要大到让同级卡换位。
     *
     * <p>一条硬保底（只对抽样那一路）：货架上**只要存在**买得起的卡，三张里就至少有一张买得起。
     * 规格那句"玩家会觉得系统在为难他"同样适用于"三张都点不动"。买得起的卡照样上架（它是信息，
     * 而且下一波还会掉币），不做全过滤。
     *
     * @param score 工作区，长度 ≥ {@link Balance.Shop#CARDS}
     * @param order 工作区，返回时前 n 项是按分数降序的卡 id
     * @param out   货架，返回时前 n 项是卡 id。⚠ 长度上限由入口决定：暂停侧会写到**整条非通用侧**
     *              （今天 6 张，簇 II 之后 10 张），按 {@link Balance.Shop#OFFER} 开的数组会越界。
     *              调用方按 {@link Balance.Shop#CARDS} 开。
     * @return 实际上架张数（通用侧被过滤空时会少于三张）
     */
    public static int selectOffer(Snapshot s, Rng rng, float[] score, int[] order, int[] out) {
        if (s.entry != ENTRY_WAVE) return listShelf(s, out);
        float jitter = Balance.shop.prioJitter;
        int n = 0;
        for (int id = 0; id < Balance.shopCards.length; id++) {
            if (!isWaveCard(id) || !isValid(id, s)) continue;
            order[n] = id;
            score[n] = prio(id, s) + (rng == null ? 0f : rng.range(-jitter, jitter));
            n++;
        }
        // 插排：全表才十几张卡，且大部分时候顺序已经接近对（同一局内 prio 变化是渐进的）。
        for (int i = 1; i < n; i++) {
            int id = order[i];
            float sc = score[i];
            int j = i - 1;
            while (j >= 0 && score[j] < sc) {
                order[j + 1] = order[j];
                score[j + 1] = score[j];
                j--;
            }
            order[j + 1] = id;
            score[j + 1] = sc;
        }
        int taken = Math.min(Balance.Shop.OFFER, n);
        System.arraycopy(order, 0, out, 0, taken);
        ensureAffordable(s, order, n, out, taken);
        return taken;
    }

    /**
     * 暂停店的货架：非通用侧**整条铺出来**，按 id 稳定排序，不过滤余额。
     *
     * <p>为什么不走 {@link #selectOffer} 那一路抽样：三选一是"这一波该卖什么"的裁决，常驻货架
     * 没有这个问题——他逐字要的是"其他非补给的卡在暂停页做个商店放里面"，**全部可见**才是这块板子
     * 的意义（也因此这一侧不看 {@link #prio}：构筑卡那几条分支在**生产路径**上没有读取点了，
     * 只剩测试与探针还在读，见 {@link #prio} 上那段注释与删它们的正确依据）。
     *
     * <p>为什么按 id 而不是按"买得起/缺什么"排：货位一旦随金币漂移，玩家下次拉开面板就得重新找
     * 那张卡。买不买得起由 {@link #purchasable} 在面板上画成禁用态，那是显示，不是库存。
     *
     * <p>满级的卡由 {@link #isValid} 那道闸门剔除（与通用侧同一个判据），不是画一张点不动的卡。
     *
     * @param out 长度 ≥ 非通用侧张数，见 {@link #selectOffer} 的同一句警告
     * @return 上架张数
     */
    public static int listShelf(Snapshot s, int[] out) {
        int n = 0;
        for (int id = 0; id < Balance.shopCards.length; id++) {
            if (isWaveCard(id) || !isValid(id, s)) continue;
            out[n++] = id;
        }
        return n;
    }

    /** 把"三张全买不起"这一种局换掉：末位换成排序里最靠前的一张买得起的卡。 */
    private static void ensureAffordable(Snapshot s, int[] order, int n, int[] out, int taken) {
        if (taken == 0) return;
        boolean any = false;
        for (int i = 0; i < taken; i++) {
            if (canAfford(Balance.shopCards[out[i]], s)) {
                any = true;
                break;
            }
        }
        if (any) return;
        for (int i = taken; i < n; i++) {
            if (canAfford(Balance.shopCards[order[i]], s)) {
                out[taken - 1] = order[i];
                return;
            }
        }
    }

    public static boolean canAfford(Balance.ShopCard card, Snapshot s) {
        int price = nextPrice(card, s);
        return price != Balance.Shop.PRICE_MAXED && s.coins >= price;
    }

    /**
     * 这一张现在**点它会不会有事发生** = 有效 ∧ 买得起。
     *
     * <p>面板的命中端与 {@code Game} 的扣币端读同一个判据：两边各写一份的话，一定会分裂成
     * "看着能点、点了没反应"或者"点不动却被扣了钱"。（"已满级"藏在 {@link #isValid} 里。）
     */
    public static boolean purchasable(Balance.ShopCard card, Snapshot s) {
        return isValid(card.id, s) && canAfford(card, s);
    }

    // ---- 卡面核心数字 ---------------------------------------------------------------------

    /**
     * 卡面上的那个数（决策主信息）。**返回的是显示值**，不是乘子：+5% 返回 5、+1 点返回 1。
     *
     * <p>从 {@link #perLevelOf} 派生而不是各写一份字面量，就是为了"卡面与结算同源"这一条。
     *
     * <p>⚠ 扳机卡**不在** ×100 那一组里：它每级的量是个**离散量**（{@code pelletPerLevel} = 1），
     * 跟着 THRUSTS/MAGNET/GREED 一起乘 100 会把「+1」印成「+100」。这两组的分界不是
     * 显示口味，是**每级那个量的单位**——乘子型才要换算成百分数，离散量本来就是裸数。
     * 卡面把它读成「+1 发」，那是**子弹档的解释**（用户 L23955 的裁定是"一个值每买一级 +1，
     * 各武器档各自解释"，"发数"这个词是我的）。
     */
    public static float coreValue(int cardId) {
        float per = perLevelOf(cardId);
        switch (cardId) {
            case Balance.ShopCard.THRUSTS:
            case Balance.ShopCard.MAGNET:
            case Balance.ShopCard.GREED:
                return per * 100f;
            case Balance.ShopCard.PRECISION:
                return per;                        // 已经是百分点
            default:
                return per;                        // 点数/枚数/发数（火力与扳机 2026-09-25/26 起都是离散量）
        }
    }

    /** 核心数字怎么读：百分号、点数、枚数、发数，还是"立即"这种没有数的。 */
    public static int coreKind(int cardId) {
        switch (cardId) {
            case Balance.ShopCard.TRIGGER:
                return CORE_SHOTS;                 // 离散量按"发"读（子弹档口径），不是百分比
            case Balance.ShopCard.PRECISION:
            case Balance.ShopCard.THRUSTS:
            case Balance.ShopCard.MAGNET:
            case Balance.ShopCard.GREED:
                return CORE_PERCENT;
            case Balance.ShopCard.FIREPOWER:       // 定值加伤，写成点数才与结算一致
            case Balance.ShopCard.HULL:
            case Balance.ShopCard.REPAIR:
            case Balance.ShopCard.SHIELD:
                return CORE_POINTS;
            case Balance.ShopCard.SALVO:
                return CORE_COUNT;
            case Balance.ShopCard.RANDOM:
                return CORE_CHOICE;                // 没有数，只有"四选一"这件事
            // 簇 II 那几张**必须显式写死**：default 那一路画「立即就绪」，而它们没有一样是立即的。
            case Balance.ShopCard.MID_COURSE:
            case Balance.ShopCard.DOGFIGHT:
            // 两张武器解锁卡与上面同族：这一把在不在飞机上不是一个数，卡面只剩「解锁」可印。
            case Balance.ShopCard.BASIC_MISSILE:
            case Balance.ShopCard.BASIC_LASER:
            // 连续杆也走这一格：default 会印「立即就绪」，而这卡买的是一条**常驻**的战斗部规则，
            // 没有"立即"也没有"数"。
            case Balance.ShopCard.ROD:
            // 雷达锁定同族（2026-10-03）：买断的是"那块屏在不在 + 那一锁听不听指定"，
            // 既不是数也不是立即，卡面只剩「解锁」可印。
            case Balance.ShopCard.RADAR:
                return CORE_UNLOCK;                // 买断一个机制，卡面只说"解锁"
            case Balance.ShopCard.IGNITION:
                return CORE_PX;                    // 每级加长的是导引头半径（px）
            case Balance.ShopCard.HANDLING:
                return CORE_DEG;                   // 每级放宽的是视场总夹角（度）
            default:
                return CORE_INSTANT;
        }
    }

    /** 核心数字要不要带小数位（+2.5% 这种半档才要）。 */
    public static int coreDecimals(int cardId) {
        float v = coreValue(cardId);
        return Math.abs(v - Math.round(v)) < 0.001f ? 0 : 1;
    }
}
