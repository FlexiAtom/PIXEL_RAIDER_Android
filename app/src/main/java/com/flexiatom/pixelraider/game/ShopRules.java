package com.flexiatom.pixelraider.game;

/**
 * 商店的纯算术端：有效性过滤、优先级、三选一抽签、价格阶梯、卡面核心数字。
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
     */
    public static final int CORE_PERCENT = 0, CORE_POINTS = 1, CORE_COUNT = 2, CORE_INSTANT = 3,
            CORE_SHOTS = 4;

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
            case Balance.ShopCard.MAGNET: return k.magnetPerLevel;
            case Balance.ShopCard.GREED: return k.coinPerLevel;
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
            default: return UNLIMITED;
        }
    }

    /**
     * 卡角该不该画「Lv n」。判据是**收益会不会随累计等级变多**，不是"它有没有等级上限"。
     *
     * <p>这条判据原本是从 {@link #maxLevelOf} 那个哨兵推出来的，取消满级之后推不出来了：
     * 火力与扳机现在也返回 {@link #UNLIMITED}，照旧判法它们会连角标一起没了（玩家看不出自己
     * 这局压了几级），而反过来把哨兵判反一次，四张一次性卡就会印出「Lv 3」——第三支维修针
     * 并不比第一支更值钱，那是一句上屏的假话。
     */
    public static boolean showsLevel(int cardId) {
        switch (cardId) {
            case Balance.ShopCard.REPAIR:
            case Balance.ShopCard.SHIELD:
            case Balance.ShopCard.SALVO:
            case Balance.ShopCard.SURGE:
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
        return card.priceBase + card.priceStep * Math.max(0, lv);
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
            default: return true;
        }
    }

    /**
     * 「当前最缺什么」的分数。**没有量纲**，只在同一张货架内比较（规格 §升级商店：不是纯随机）。
     *
     * <p>两条线：
     * <ul>
     *   <li><b>保命项</b>随血量缺口上升，并且额外叠一项"低于 {@link Balance.Shop#panicHpRatio}
     *       就陡增"的台阶。台阶是必需的：0.35 血和 0.45 血只差 0.1，长线卡的波次项一旦爬到 0.8，
     *       纯线性会让"快死了推养成卡"在高波次重新出现。</li>
     *   <li><b>长线养成项</b>随波次上升（越到后面越值），贪婪卡反向随波次**衰减</b>——
     *       它的收益是"之后每一波掉的金币都乘一下"，第 2 波买和第 18 波买不是同一个商品，
     *       卡面 tip 说了"越早买越划算"，prio 就得真的这么排。</li>
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
                // 原来它满血时排在全部 11 张的末尾，前几波基本抽不到，等于那条出口没开。
                return 0.42f + deficit * 0.50f + waveTerm * 0.25f;
            case Balance.ShopCard.HULL:
                // 抬上限是"接下来这一整波都受益"的投资：血已经见底时才让位给立刻能用的维修。
                return 0.25f + deficit * 0.30f + waveTerm * 0.35f - panic * 0.20f;
            case Balance.ShopCard.SALVO:
                // 手里没炸弹时它是唯一的一张"翻盘手牌"；有了就只是补充。
                return s.bombs <= 0 ? 0.55f : 0.30f;
            case Balance.ShopCard.SURGE:
                return 0.42f;
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
     * 抽这一波的三张卡：过滤无效 → 按 prio + 抖动降序 → 取前 {@link Balance.Shop#OFFER} 张。
     *
     * <p>抖动是必需的：纯 prio 的话同一局每次开架都是同三张，"三选一"退化成"确认键"。
     * 幅度钉在 {@link Balance.Shop#prioJitter}（默认 0.08）——它要小到翻不了保命/养成的盘，
     * 又要大到让同级卡换位。
     *
     * <p>一条硬保底：货架上**只要存在**买得起的卡，三张里就至少有一张买得起。规格那句
     * "玩家会觉得系统在为难他"同样适用于"三张都点不动"。买得起的卡照样上架（它是信息，
     * 而且下一波还会掉币），不做全过滤。
     *
     * @param score 工作区，长度 ≥ {@link Balance.Shop#CARDS}
     * @param order 工作区，返回时前 n 项是按分数降序的卡 id
     * @param out   货架，返回时前 n 项是卡 id
     * @return 实际上架张数（卡池被过滤空时会少于三张）
     */
    public static int selectOffer(Snapshot s, Rng rng, float[] score, int[] order, int[] out) {
        float jitter = Balance.shop.prioJitter;
        int n = 0;
        for (int id = 0; id < Balance.shopCards.length; id++) {
            if (!isValid(id, s)) continue;
            order[n] = id;
            score[n] = prio(id, s) + (rng == null ? 0f : rng.range(-jitter, jitter));
            n++;
        }
        // 插排：11 个元素，且大部分时候顺序已经接近对（同一局内 prio 变化是渐进的）。
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
