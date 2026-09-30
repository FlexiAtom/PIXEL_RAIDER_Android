package com.flexiatom.pixelraider.game;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/**
 * 升级商店的规则端（规格 §升级商店：11 张卡、每波三选一、不是纯随机）。
 *
 * <p>期望值全部**手推**（照 {@code GrowthTreeTest} 的规矩）：这一层错了的表现是"低血时抽出三张
 * 养成卡"和"卡面写 +2% 实际 +3%"，两种都看不出所以然，只能靠断言判。
 */
public class ShopRulesTest {

    private static final int CARDS = Balance.Shop.CARDS;

    private static ShopRules.Snapshot snap(int wave, int hp, int coins) {
        ShopRules.Snapshot s = new ShopRules.Snapshot();
        s.wave = wave;
        s.hp = hp;
        s.maxHp = 100;
        s.shield = 0;
        s.maxShield = 0;
        s.coins = coins;
        return s;
    }

    /** {@link #offer} 的暂存：selectOffer 只往调用方给的数组里写，测试就不必每次 new。 */
    private static final int[] shelfInto = new int[Balance.Shop.OFFER];

    private static int[] offer(ShopRules.Snapshot s, long seed) {
        int n = ShopRules.selectOffer(s, new Rng(seed), new float[CARDS], new int[CARDS], shelfInto);
        int[] out = new int[n];
        System.arraycopy(shelfInto, 0, out, 0, n);
        return out;
    }

    private static boolean has(int[] ids, int cardId) {
        for (int id : ids) if (id == cardId) return true;
        return false;
    }

    // ---- 表本身 ---------------------------------------------------------------------------

    @Test
    public void cardTableIsTheSpecifiedElevenCardsInIdOrder() {
        assertEquals("规格钉死 11 张卡", 11, CARDS);
        assertEquals("卡表长度必须跟 CARDS 一致，否则快照数组会短一截", CARDS, Balance.shopCards.length);
        for (int id = 0; id < CARDS; id++) {
            assertEquals("第 " + id + " 项的 id 与下标不符（id 是货架槽位→卡 id 的唯一桥梁）",
                    id, Balance.shopCards[id].id);
        }
    }

    @Test
    public void everyCardHasAUniqueShortName() {
        Set<String> names = new HashSet<>();
        for (Balance.ShopCard c : Balance.shopCards) {
            assertTrue("卡名重复：" + c.name, names.add(c.name));
            assertTrue(c.name + " 名字过长（卡面文字宽放不下）", c.name.length() <= 5);
        }
    }

    // ---- 价格阶梯 -------------------------------------------------------------------------

    /**
     * 火力卡的价格**一直沿阶梯爬**，不再有"满级 = 负数哨兵"那一步。
     *
     * <p>2026-09-26 取消满级之后，这张卡唯一买不动的原因就是"下一级太贵"。这条断的是取消之后
     * 剩下的那道闸门**仍然在算**（{@code priceBase + priceStep·lv}），而且算到远超原上限的等级
     * 也不会突然变成点不动的哨兵——那会让"取消上限"只改了一半：等级能买，价格却装死。
     */
    @Test
    public void firepowerPriceKeepsClimbingInsteadOfGoingUnbuyable() {
        Balance.ShopCard fire = Balance.shopCards[Balance.ShopCard.FIREPOWER];
        ShopRules.Snapshot s = snap(1, 100, 999);
        // 30 + 12 * lv，手推前四级
        int[] ladder = {30, 42, 54, 66};
        for (int lv = 0; lv < ladder.length; lv++) {
            s.level[Balance.ShopCard.FIREPOWER] = lv;
            assertEquals(ladder[lv], ShopRules.nextPrice(fire, s));
        }
        for (int lv : new int[]{10, 11, 25, 80}) {
            s.level[Balance.ShopCard.FIREPOWER] = lv;
            assertNotEquals("第 " + lv + " 级又变成买不动的哨兵了（上限已取消）",
                    Balance.Shop.PRICE_MAXED, ShopRules.nextPrice(fire, s));
            assertEquals(30 + 12 * lv, ShopRules.nextPrice(fire, s));
        }
    }

    @Test
    public void oneShotCardsKeepTheirFlatPriceForever() {
        Balance.ShopCard repair = Balance.shopCards[Balance.ShopCard.REPAIR];
        ShopRules.Snapshot s = snap(1, 50, 999);
        for (int lv = 0; lv < 30; lv++) {
            s.level[Balance.ShopCard.REPAIR] = lv;
            assertEquals("一次性卡没有阶梯", 25, ShopRules.nextPrice(repair, s));
            assertEquals(ShopRules.UNLIMITED, ShopRules.maxLevelOf(Balance.ShopCard.REPAIR));
        }
    }

    // ---- 有效性：买了必须有事发生 ------------------------------------------------------------

    @Test
    public void fullHealthFullShieldAndReadyEnergyAreNotSold() {
        ShopRules.Snapshot s = snap(5, 100, 999);
        s.maxShield = 3;
        s.shield = 3;
        s.overloadReady = true;
        assertFalse(ShopRules.isValid(Balance.ShopCard.REPAIR, s));
        assertFalse(ShopRules.isValid(Balance.ShopCard.SHIELD, s));
        assertFalse(ShopRules.isValid(Balance.ShopCard.SURGE, s));
        s.hp = 99;
        s.shield = 2;
        s.overloadReady = false;
        assertTrue(ShopRules.isValid(Balance.ShopCard.REPAIR, s));
        assertTrue(ShopRules.isValid(Balance.ShopCard.SHIELD, s));
        assertTrue(ShopRules.isValid(Balance.ShopCard.SURGE, s));
    }

    /**
     * 上限仍然生效的那张卡，满级后必须掉出货架。
     *
     * <p>宿主从火力换成暴击：2026-09-26 取消满级之后火力永远有效，这条用例的过滤分支就不再被它
     * 走到了。挑宿主的标准是<b>prio 真能挤进前三</b>——第 20 波满血时暴击卡 0.78，仅次于火力 0.85
     * 与扳机 0.82，抖动 ±0.08 只是偶尔把它挤到第四（后面那五个对手全都要翻过 0.11 以上的差距）。
     * 于是先数"没满级时上架了几次"，再数"满级后上架了几次"：前者非零、后者为零，这条用例才真的
     * 在测过滤，而不是测一张本来就排不进前三的卡。
     */
    @Test
    public void maxedCardsLeaveTheShelfInsteadOfSittingThereUnbuyable() {
        ShopRules.Snapshot s = snap(20, 100, 999);
        int onShelf = 0;
        for (long seed = 0; seed < 60; seed++) {
            if (has(offer(s, seed), Balance.ShopCard.PRECISION)) onShelf++;
        }
        assertTrue("满级前暴击卡就基本不上架（只有 " + onShelf + "/60 次）：宿主选错了，"
                + "满级后为 0 说明不了任何事", onShelf >= 40);
        s.level[Balance.ShopCard.PRECISION] = ShopRules.maxLevelOf(Balance.ShopCard.PRECISION);
        assertFalse(ShopRules.isValid(Balance.ShopCard.PRECISION, s));
        for (long seed = 0; seed < 60; seed++) {
            assertFalse("满级卡又上架了（seed " + seed + "）",
                    has(offer(s, seed), Balance.ShopCard.PRECISION));
        }
    }

    @Test
    public void invalidCardsNeverReachTheShelfAtFullHealth() {
        ShopRules.Snapshot s = snap(7, 100, 999);
        s.maxShield = 4;
        s.shield = 4;
        for (long seed = 0; seed < 60; seed++) {
            int[] ids = offer(s, seed);
            assertFalse(has(ids, Balance.ShopCard.REPAIR));
            assertFalse(has(ids, Balance.ShopCard.SHIELD));
            for (int id : ids) {
                assertTrue("上架了无效卡", ShopRules.isValid(id, s));
            }
        }
    }

    // ---- prio：不是纯随机，也不该是固定菜单 ---------------------------------------------------

    @Test
    public void nearlyDeadPushesSurvivalAheadOfGrowthAtALateWave() {
        ShopRules.Snapshot s = snap(18, 20, 999);      // 两成血、第 18 波
        for (long seed = 0; seed < 60; seed++) {
            int[] ids = offer(s, seed);
            assertTrue("快死了却没推销血卡（seed " + seed + "）", has(ids, Balance.ShopCard.REPAIR));
            assertEquals("维修必须排在第一位", Balance.ShopCard.REPAIR, ids[0]);
        }
    }

    @Test
    public void healthyLateWaveSellsBuildsNotBandAids() {
        ShopRules.Snapshot s = snap(18, 100, 999);
        for (long seed = 0; seed < 60; seed++) {
            int[] ids = offer(s, seed);
            assertTrue("第 18 波满血时最该推的伤害卡缺席", has(ids, Balance.ShopCard.FIREPOWER));
            assertFalse(has(ids, Balance.ShopCard.SALVO));
            assertFalse(has(ids, Balance.ShopCard.SURGE));
        }
    }

    /**
     * 被打掉六成血时，速度卡必须**每一波都摆得上架**。
     *
     * <p>归属与日期在 2026-09-27 逐子句重核过（本仓注释日期 = 本地日；transcript 戳是 UTC，
     * 比日期要先 +8 再取日）：改成限速跟随这个**选择**是用户做的，但那几个字是**我写的选项标签**——
     * 他的逐字作答是 **L12104**（answers-**OPT**，逐字「指针改成限速跟随（推荐）」，
     * UTC 09-24T15:20:49 ＝ **本地 09-24** 23:20）。先前这里写"2026-09-25 用户决策"两样都偏了：
     * 日子按本地口径偏一天，"决策"二字把选项标签当成了原话。
     *
     * <p>下面那条因果是我的推导，不来自任何一条裁定：触屏走位改成限速跟随之后，机体速度同时是
     * "指针欠账每秒能放出去多少"的上限（{@code DragDebt.consume} 的 speed），所以这张卡不只是躲子弹
     * 的手段，也是"手感跟不上眼睛"的唯一出口——出口得真的开着，而不是排在十一张末尾、前几波抽不到。
     */
    @Test
    public void bruisedRunAlwaysSeesTheThrustCard() {
        ShopRules.Snapshot s = snap(6, 40, 999);
        s.maxShield = 3;
        s.shield = 1;
        for (long seed = 0; seed < 60; seed++) {
            assertTrue("掉了六成血却没有速度卡（seed " + seed + "）",
                    has(offer(s, seed), Balance.ShopCard.THRUSTS));
        }
        assertEquals("速度卡降到 22 币起卖，第 1 波的钱包就够摸到一级",
                22, ShopRules.nextPrice(Balance.shopCards[Balance.ShopCard.THRUSTS], snap(1, 100, 0)));
    }

    @Test
    public void greedIsWorthMoreTheEarlierYouTakeIt() {
        float early = ShopRules.prio(Balance.ShopCard.GREED, snap(2, 100, 999));
        float late = ShopRules.prio(Balance.ShopCard.GREED, snap(18, 100, 999));
        assertTrue("贪婪卡不随波次衰减，卡面那句『越早买越划算』就是假的", early > late + 0.1f);
        float damageEarly = ShopRules.prio(Balance.ShopCard.FIREPOWER, snap(2, 100, 999));
        float damageLate = ShopRules.prio(Balance.ShopCard.FIREPOWER, snap(18, 100, 999));
        assertTrue(damageLate > damageEarly);
    }

    @Test
    public void waveTermStopsClimbingPastItsKnee() {
        assertEquals(0f, ShopRules.waveTerm(0, 20f), 0f);
        assertEquals(0.1f, ShopRules.waveTerm(2, 20f), 1e-6f);
        assertEquals(1f, ShopRules.waveTerm(20, 20f), 0f);
        assertEquals("第 40 波不该把养成卡顶到永久霸榜", 1f, ShopRules.waveTerm(40, 20f), 0f);
    }

    @Test
    public void sameSeedReplaysTheSameShelf() {
        ShopRules.Snapshot s = snap(6, 55, 999);
        s.maxShield = 3;
        s.shield = 1;
        int[] a = offer(s, 12345L);
        int[] b = offer(s, 12345L);
        assertTrue(java.util.Arrays.equals(a, b));
    }

    @Test
    public void jitterActuallyReordersTheShelf() {
        // 满血、缺盾：这样 prio 的中段（弹药 0.55 / 火力 0.50 / 速度 0.495 / 扳机 0.484 …）
        // 全都挤在抖动 ±0.08 能翻盘的带里。半血那档反而测不出这件事——速度卡的 prio 抬到 0.72
        // 之后，"快死了"的货架第三格被它稳定占住，换种子也只换前两张的先后顺序。
        ShopRules.Snapshot s = snap(6, 100, 999);
        s.maxShield = 3;
        s.shield = 1;
        Set<String> distinct = new HashSet<>();
        for (long seed = 0; seed < 40; seed++) {
            distinct.add(java.util.Arrays.toString(offer(s, seed)));
        }
        assertTrue("三选一退化成了固定菜单（只有 " + distinct.size() + " 种货架）", distinct.size() >= 5);
    }

    @Test
    public void nullRngIsDeterministicInsteadOfThrowing() {
        ShopRules.Snapshot s = snap(3, 100, 999);
        int n = ShopRules.selectOffer(s, null, new float[CARDS], new int[CARDS], shelfInto);
        assertEquals(Balance.Shop.OFFER, n);
        assertEquals("无抖动时严格按 prio 排", Balance.ShopCard.SALVO, shelfInto[0]);
    }

    // ---- 买得起的保底 -----------------------------------------------------------------------

    /**
     * 把抖动临时调成 0 再验保底：抖动非零时"最靠前那张买得起的卡"会随种子漂，
     * 断言就退化成"偶尔成立"。抖动本身该不该翻盘由 {@link #jitterActuallyReordersTheShelf} 管。
     */
    @Test
    public void theLastSlotIsSpentOnAnAffordableCardWhenTheTopThreeAreNot() {
        float jitter = Balance.shop.prioJitter;
        try {
            Balance.shop.prioJitter = 0f;
            // 25 币：火力 30 / 扳机 34 / 贪婪 45 都买不起，三者恰好是 prio 前三。
            ShopRules.Snapshot s = snap(4, 99, 25);
            s.bombs = 1;                                // 压住弹药卡的 0.55，别让它挤进前三
            s.level[Balance.ShopCard.THRUSTS] = ShopRules.maxLevelOf(Balance.ShopCard.THRUSTS);
            // 速度卡降到 22 币之后，买得起的"前三"就不止一个了——把它顶成满级掉出货架，
            // 这个"前三全买不起、只能靠保底"的局才还成立。
            int[] ids = offer(s, 1);
            assertEquals(Balance.Shop.OFFER, ids.length);
            assertTrue("保底没把末位换成维修：" + java.util.Arrays.toString(ids),
                    has(ids, Balance.ShopCard.REPAIR));
            int affordable = 0;
            for (int id : ids) if (ShopRules.canAfford(Balance.shopCards[id], s)) affordable++;
            assertEquals("只该靠保底换来一张点得动的", 1, affordable);
        } finally {
            Balance.shop.prioJitter = jitter;
        }
    }

    @Test
    public void brokePlayersStillGetThreeInformativeCards() {
        ShopRules.Snapshot s = snap(4, 100, 0);
        assertEquals("一枚币都没有时也照样开三张（价格是信息，下一波还会掉币）",
                Balance.Shop.OFFER, offer(s, 7).length);
        for (int id : offer(s, 7)) {
            assertFalse(ShopRules.canAfford(Balance.shopCards[id], s));
        }
    }

    @Test
    public void purchasableIsTheOneJudgeBothEndsRead() {
        ShopRules.Snapshot s = snap(4, 100, 30);
        assertTrue(ShopRules.purchasable(Balance.shopCards[Balance.ShopCard.FIREPOWER], s));
        s.coins = 29;
        assertFalse(ShopRules.purchasable(Balance.shopCards[Balance.ShopCard.FIREPOWER], s));
        s.coins = 999;
        s.hp = 100;
        assertFalse("满血维修：点它什么都没发生，所以它不该点得动",
                ShopRules.purchasable(Balance.shopCards[Balance.ShopCard.REPAIR], s));
    }

    // ---- 卡面数字 == 结算数字 ---------------------------------------------------------------

    /**
     * 热调一档数值时，卡面与结算必须一起动。这条是"显示与实际不一致"那一类 bug 的哨兵：
     * 只要有人在面板里另写一份字面量，改了 {@code Balance.shop} 它就会红。
     */
    @Test
    public void displayedNumbersFollowTheTableNotALiteral() {
        int original = Balance.shop.damageFlatPerLevel;
        try {
            Balance.shop.damageFlatPerLevel = 4;
            assertEquals("卡面读的是同一格：调成 4 点就显示 4", 4f,
                    ShopRules.coreValue(Balance.ShopCard.FIREPOWER), 1e-4f);
            ShopRun run = new ShopRun();
            run.buyUpgrade(Balance.ShopCard.FIREPOWER);
            assertEquals("结算按新档走", 4, run.damageBonus());
        } finally {
            Balance.shop.damageFlatPerLevel = original;
        }
        assertEquals("改回去之后显示值也要跟着回去", 1f,
                ShopRules.coreValue(Balance.ShopCard.FIREPOWER), 1e-4f);
    }

    /**
     * 护盾卡的三段同源：卡面数字 → {@code Balance.shield.fromUpgrade} → 玩家身上真的多出这么多点。
     *
     * <p>中间那一跳以前是断的：结算走按剩余空间截断的通用入口，80/100 买 "+50" 只到 100——卡面写了
     * 收益、场上打折，正是规格点名的那一类 bug。这一条把三段重新钉在一起；顺带把"越限后自动下架"
     * 钉成**故意**（它是溢出额度唯一的闸门）。
     *
     * <p>归属分两半（2026-09-27 现取，与 {@code PlayerState.addShieldBeyondCap} 同一条账）：
     * 他的裁定逐字只到「护盾充能卡允许溢出上限」——**L13322**（纯文本自敲，UTC 09-25T05:33:42
     * ＝本地 09-25 13:33）；<b>「全额入账」这条实现形状与"卡面写了收益、场上少给"这个理由都是我的</b>，
     * 先前这句写成"用户裁定商店卡全额入账"是把我的方案挂到了他名下。
     */
    @Test
    public void shieldCardPaysOutExactlyTheNumberOnItsFace() {
        int original = Balance.shield.fromUpgrade;
        try {
            Balance.shield.fromUpgrade = 73;
            assertEquals("卡面读的是同一格：调成 73 就显示 73", 73f,
                    ShopRules.coreValue(Balance.ShopCard.SHIELD), 1e-4f);
            PlayerState p = new PlayerState();
            p.shield = 80;
            assertEquals("结算按同一格全额给", 73, p.addShieldBeyondCap(Balance.shield.fromUpgrade));
            assertEquals(153, p.shield);
            assertEquals("[推导] 越限后比值出口不钳，HUD 才有得可表意", 1.53f, p.shieldRatio(), 1e-6f);
        } finally {
            Balance.shield.fromUpgrade = original;
        }

        ShopRules.Snapshot s = snap(5, 100, 999);
        s.maxShield = Balance.player.maxShield;
        s.shield = s.maxShield;
        // 这条断言的消息以前写着"用户裁定"，2026-09-27 按第一手改口（同一条账详见
        // ShopRules.isValid 的 SHIELD 分支）：「护盾充能卡允许溢出上限」= 用户纯文本自敲
        // **L13322**（UTC 09-25T05:33:42 ＝本地 09-25 13:33）⇒ 他的字、他的决定；
        // 而「满盾不上架」这个**做法**是我给出的选项标签、他挑了它：**L13890**
        // （answers-**OPT**，UTC 09-25T06:26:20 ＝本地 09-25 14:26，逐字「满盾不上架（最小改动）」）
        // ⇒ 决策是他的、措辞是我的。
        assertFalse("满盾不上架（他挑的这一档）：这张卡只在缺盾时出现",
                ShopRules.isValid(Balance.ShopCard.SHIELD, s));
        s.shield = s.maxShield + Balance.shield.fromUpgrade - 1;
        assertFalse("越限之后自动下架，溢出不会连买叠起来",
                ShopRules.isValid(Balance.ShopCard.SHIELD, s));
    }

    @Test
    public void coreValuesAreReadTheWayTheCardShowsThem() {
        assertEquals("火力卡每级 +1 点（定值，不再是百分比）", 1f,
                ShopRules.coreValue(Balance.ShopCard.FIREPOWER), 1e-4f);
        assertEquals(ShopRules.CORE_POINTS, ShopRules.coreKind(Balance.ShopCard.FIREPOWER));
        assertEquals(0, ShopRules.coreDecimals(Balance.ShopCard.FIREPOWER));
        assertEquals("暴击按百分点显示", 3f,
                ShopRules.coreValue(Balance.ShopCard.PRECISION), 1e-4f);
        assertEquals(ShopRules.CORE_POINTS, ShopRules.coreKind(Balance.ShopCard.HULL));
        assertEquals(20f, ShopRules.coreValue(Balance.ShopCard.HULL), 0f);
        assertEquals(ShopRules.CORE_COUNT, ShopRules.coreKind(Balance.ShopCard.SALVO));
        assertEquals(3f, ShopRules.coreValue(Balance.ShopCard.SALVO), 0f);
        assertEquals(ShopRules.CORE_INSTANT, ShopRules.coreKind(Balance.ShopCard.SURGE));
        assertEquals("磁吸 +30% 有小数档时显示一位小数", 30f,
                ShopRules.coreValue(Balance.ShopCard.MAGNET), 1e-4f);
        assertEquals(0, ShopRules.coreDecimals(Balance.ShopCard.MAGNET));
    }

    /**
     * 各卡的可购档数（用户裁定 2026-09-25：火力与扳机<b>取消满级</b>）。
     *
     * <p>逐字 = **L14223**（纯文本自敲，UTC 09-25T06:52:47 ＝**本地 09-25** 14:52）
     * 「需要把火力强化卡的10满级上限取消，射速卡也是满级上限取消，每次+1」。
     * 先前这里写的 09-26 是**落地日**、不是裁定日（本仓注释记本地日，UTC 戳要先 +8 再取日）。
     * ⚠ 他口中的「射速卡」指当时名为「快速扳机」那张卡，2026-09-26 由他另一条裁定改了卡名
     * （见 {@code Balance.Shop.CARDS} 那一条的注释）；「每次+1」是他的字，
     * "每级 +1 发弹丸"那套措辞与标识符是我的（详见 {@code pelletPerLevel} 的 javadoc）。
     *
     * <p>其余五张的上限逐条保留：它们是被"档数 × 每级量"这个乘积定价的，砍掉上限等于改变
     * 它们的强度天花板，而这次改动只买两张卡。
     */
    @Test
    public void maxLevelsMatchTheSpecifiedRepeatLimits() {
        assertEquals("火力卡不限级：伤害每级 +1 点，代价全在价格阶梯上", ShopRules.UNLIMITED,
                ShopRules.maxLevelOf(Balance.ShopCard.FIREPOWER));
        assertEquals("扳机卡不限级，且射速固定不动——它现在卖的是发数", ShopRules.UNLIMITED,
                ShopRules.maxLevelOf(Balance.ShopCard.TRIGGER));
        assertEquals(5, ShopRules.maxLevelOf(Balance.ShopCard.PRECISION));
        assertEquals(5, ShopRules.maxLevelOf(Balance.ShopCard.THRUSTS));
        assertEquals(5, ShopRules.maxLevelOf(Balance.ShopCard.HULL));
        assertEquals(3, ShopRules.maxLevelOf(Balance.ShopCard.MAGNET));
        assertEquals(4, ShopRules.maxLevelOf(Balance.ShopCard.GREED));
        assertEquals(ShopRules.UNLIMITED, ShopRules.maxLevelOf(Balance.ShopCard.SURGE));
    }

    /**
     * 角标判据与上限哨兵<b>必须能分家</b>——两者曾经靠同一个 {@code UNLIMITED} 表达。
     *
     * <p>误伤方向是两头都能错：照旧从 {@code maxLevelOf} 推角标，取消满级的两张卡会连「Lv n」
     * 一起没了（玩家看不出这局压了几级）；反过来判反一次，四张一次性卡会印出「Lv 3」，而第三支
     * 维修针并不比第一支更值钱——那是一句上屏的假话。
     */
    @Test
    public void levelBadgesAreJudgedIndependentlyOfTheCapSentinel() {
        for (int id : new int[] {Balance.ShopCard.FIREPOWER, Balance.ShopCard.TRIGGER}) {
            assertEquals("这两张是「无上限的养成卡」，不是「一次性补给」："
                            + "哨兵相同，角标仍要画", ShopRules.UNLIMITED,
                    ShopRules.maxLevelOf(id));
            assertTrue(ShopRules.showsLevel(id));
        }
        for (int id : new int[] {Balance.ShopCard.REPAIR, Balance.ShopCard.SHIELD,
                Balance.ShopCard.SALVO, Balance.ShopCard.SURGE}) {
            assertEquals("一次性卡同样是 UNLIMITED 哨兵", ShopRules.UNLIMITED,
                    ShopRules.maxLevelOf(id));
            assertFalse("但它没有累计收益，角标不许画", ShopRules.showsLevel(id));
        }
        assertTrue(ShopRules.showsLevel(Balance.ShopCard.PRECISION));
    }

    // ---- 持有量与乘子 -----------------------------------------------------------------------

    @Test
    public void multipliersDoNotDependOnPurchaseOrder() {
        ShopRun a = new ShopRun();
        for (int i = 0; i < 3; i++) a.buyUpgrade(Balance.ShopCard.FIREPOWER);
        ShopRun b = new ShopRun();
        b.buyUpgrade(Balance.ShopCard.GREED);
        b.buyUpgrade(Balance.ShopCard.FIREPOWER);
        b.buyUpgrade(Balance.ShopCard.MAGNET);
        b.buyUpgrade(Balance.ShopCard.FIREPOWER);
        b.buyUpgrade(Balance.ShopCard.GREED);
        b.buyUpgrade(Balance.ShopCard.FIREPOWER);
        assertEquals(3, a.damageBonus(), 0f);
        assertEquals(a.damageBonus(), b.damageBonus(), 0f);
        assertEquals("三次伤害 + 两次贪婪 + 一次磁吸，总数对得上", 6, b.totalLevels());
    }

    @Test
    public void buyingPastTheCapIsRefusedSoTheCallerDoesNotCharge() {
        ShopRun run = new ShopRun();
        for (int i = 0; i < Balance.shop.magnetMaxLevel; i++) {
            assertTrue(run.buyUpgrade(Balance.ShopCard.MAGNET));
        }
        assertFalse(run.buyUpgrade(Balance.ShopCard.MAGNET));
        assertTrue(run.maxed(Balance.ShopCard.MAGNET));
        assertEquals(1.9f, run.magnetMul(), 1e-6f);
        assertNotEquals(ShopRules.UNLIMITED, ShopRules.maxLevelOf(Balance.ShopCard.MAGNET));
    }

    @Test
    public void critAndHullBonusesAreWholePoints() {
        ShopRun run = new ShopRun();
        run.buyUpgrade(Balance.ShopCard.PRECISION);
        assertEquals("暴击是百分点，不是百分数", 3, run.critBonus());
        run.buyUpgrade(Balance.ShopCard.HULL);
        run.buyUpgrade(Balance.ShopCard.HULL);
        assertEquals(40, run.hullBonusTotal());
        assertEquals("这两张卡一格发数也不加：发数只从扳机卡来", 0,
                run.pelletBonus(Balance.Weapon.PULSE));
        run.reset();
        assertEquals(0, run.totalLevels());
        assertEquals(0, run.critBonus());
    }

    /**
     * 扳机卡的语义（用户裁定 2026-09-25，两条逐字：L14292「射速本身固定，但是子弹每次+1」、
     * L14614「每张卡+1个子弹/伤害，只需要在购买后计算一次即可」）：射速<b>固定不动</b>，每级 +1 发弹丸。
     * <p>"固定不动""发弹丸"这套措辞是本用例作者的，不是用户原话；实质归属见那两条行号。
     *
     * <p>发数这条替换了原来的"买卡提高射速乘子"：固定步长把冷却量化成 {@code ceil(cd/STEP)·STEP}，
     * 激光在乘子上界附近连吃十几级都是同一个 20 发/秒——那是把同一件商品卖了十次。
     *
     * <p>取消满级之后没有东西再拦着它，所以这条也顺便钉"连买 30 级不被拒"：那张卡的价格阶梯
     * （{@link #firepowerPriceKeepsClimbingInsteadOfGoingUnbuyable} 同一条口径）是唯一的闸门。
     */
    @Test
    public void triggerCardSellsPelletsNotFireRate() {
        ShopRun run = new ShopRun();
        for (int lv = 1; lv <= 30; lv++) {
            assertTrue("扳机卡第 " + lv + " 级买不动了（上限已取消）",
                    run.buyUpgrade(Balance.ShopCard.TRIGGER));
            assertEquals("每级 " + Balance.shop.pelletPerLevel + " 发，逐累计",
                    Balance.shop.pelletPerLevel * lv, run.pelletBonus(Balance.Weapon.PULSE));
        }
        assertFalse(run.maxed(Balance.ShopCard.TRIGGER));
        assertEquals("散射自己带 5 发底数，加成叠在底数之上而不是替换它",
                Balance.shop.pelletPerLevel * 30, run.pelletBonus(Balance.Weapon.SHOT));
    }

    /**
     * 激光是唯一不吃扳机的武器，且<b>这条例外只允许定义在 {@code pelletBonus} 一处</b>。
     *
     * <p>它是"卡面写明例外"这句话在代码里唯一能落地的形式（⚠ 那五个字是**我给出的选项标签**，
     * 他在 **L14413**（answers-**OPT**，utc 2026-09-25T07:51:41.353Z ⇒ 本地 09-25 15:51）里挑了它
     * ⇒ 决策是他的、措辞是我的；本仓注释日期一律记本地日）：{@code ShopRules.Snapshot} 不带武器
     * 字段，卡面不可能按武器显示两个数字，所以例外只能靠 tip 文案表达。既然显示端已经
     * 放弃了按武器分支，结算端就必须把分支收成一点，否则"卡面 +1 发"与实际"激光 0 发"
     * 会在两个文件里各写一份。
     */
    @Test
    public void laserIgnoresTheTriggerCardEverywhere() {
        ShopRun run = new ShopRun();
        for (int i = 0; i < 8; i++) run.buyUpgrade(Balance.ShopCard.TRIGGER);
        assertEquals(0, run.pelletBonus(Balance.Weapon.LASER));
        assertTrue(run.pelletBonus(Balance.Weapon.PULSE) > 0);
    }

    /**
     * 扳机卡的<b>卡面数字</b>：按"枚"显示 +1，不是按百分点显示 +100。
     *
     * <p>{@code ShopScreen} 是 Canvas 类、JVM 一条测试都盖不到，所以显示端判据全部下沉成
     * {@code ShopRules} 的纯函数断言（与 {@link #coreValuesAreReadTheWayTheCardShowsThem} 同族）：
     * <ul>
     *   <li>{@code coreKind != CORE_PERCENT} —— {@code ShopScreen.drawCore} 对 PERCENT 会补一个
     *       {@code %}，配上数值 100 就是玩家读到「+100%」。</li>
     *   <li>{@code coreValue == pelletPerLevel} —— 把扳机卡摘出 {@code per * 100f} 那一组，
     *       否则「+1 发」印成「+100」。</li>
     * </ul>
     * 再加一条热调：改表之后显示与结算一起动（{@link #displayedNumbersFollowTheTableNotALiteral}
     * 的形状），防止有人在 UI 里另抄一份字面量。
     */
    @Test
    public void triggerCardShowsWholePelletsNotAPercent() {
        assertNotEquals(ShopRules.CORE_PERCENT, ShopRules.coreKind(Balance.ShopCard.TRIGGER));
        assertEquals(ShopRules.CORE_SHOTS, ShopRules.coreKind(Balance.ShopCard.TRIGGER));
        assertEquals(1f, ShopRules.coreValue(Balance.ShopCard.TRIGGER), 0f);
        assertEquals(0, ShopRules.coreDecimals(Balance.ShopCard.TRIGGER));

        int original = Balance.shop.pelletPerLevel;
        try {
            Balance.shop.pelletPerLevel = 2;
            assertEquals("卡面读的是同一格：调成 2 发就显示 2", 2f,
                    ShopRules.coreValue(Balance.ShopCard.TRIGGER), 1e-4f);
            ShopRun run = new ShopRun();
            run.buyUpgrade(Balance.ShopCard.TRIGGER);
            assertEquals("结算按新档走", 2, run.pelletBonus(Balance.Weapon.PULSE));
        } finally {
            Balance.shop.pelletPerLevel = original;
        }
        assertEquals("改回去之后显示值也要跟着回去", 1f,
                ShopRules.coreValue(Balance.ShopCard.TRIGGER), 0f);
    }

    @Test
    public void resetReusesTheArrayTheSnapshotAlreadyPointsAt() {
        ShopRun run = new ShopRun();
        ShopRules.Snapshot s = snap(1, 100, 999);
        s.level = run.levels();
        run.buyUpgrade(Balance.ShopCard.TRIGGER);
        assertEquals(1, s.levelOf(Balance.ShopCard.TRIGGER));
        run.reset();
        assertEquals("快照读的是同一个数组，reset 之后不必重新接", 0, s.levelOf(Balance.ShopCard.TRIGGER));
    }
}
