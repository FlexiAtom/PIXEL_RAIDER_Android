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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/**
 * 升级商店的规则端（规格 §升级商店：每波三选一、不是纯随机；卡表今天 12 张，规格钉的是 11 张，
 * 第 12 张「随机强化」是 2026-10-01 按他的加成卡裁定加的——别把这张卡记成规格原文）。
 *
 * <p>2026-10-01 双入口分区（commit B1）之后，这块板子测的是<b>两家店</b>：{@code ENTRY_WAVE}
 * 那一家仍按"三选一 + prio"抽样，{@code ENTRY_PAUSE} 那一家把非通用侧整条摆出来、不抽样也不打折。
 * 规格那句"每波三选一"没有作废，它现在只描述回合那一家；因此下面每个用例都要**自己说清在哪
 * 个入口测**——不写 entry 的用例走的是 {@code Snapshot.entry} 的默认值（回合店）。
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
    private static final int[] shelfInto = new int[CARDS];

    private static int[] offer(ShopRules.Snapshot s, long seed) {
        int n = ShopRules.selectOffer(s, new Rng(seed), new float[CARDS], new int[CARDS], shelfInto);
        int[] out = new int[n];
        System.arraycopy(shelfInto, 0, out, 0, n);
        return out;
    }

    /** 暂停店那条货架：就地把这个快照换成暂停入口再开架（不抽样，所以没有种子）。 */
    private static int[] shelf(ShopRules.Snapshot s) {
        s.entry = ShopRules.ENTRY_PAUSE;
        return offer(s, 0L);
    }

    private static boolean has(int[] ids, int cardId) {
        for (int id : ids) if (id == cardId) return true;
        return false;
    }

    // ---- 表本身 ---------------------------------------------------------------------------

    @Test
    public void cardTableIsTheSpecifiedTwelveCardsInIdOrder() {
        assertEquals("卡表钉死 12 张（11 张 ＋「随机强化」）", 12, CARDS);
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
     *
     * <p>入口改成暂停店（分区之后这张卡只在那儿卖），所以四个手推数是裸阶梯 30/42/54/66。
     * 回合店那一路的取整方向不在这里测了——它由 {@link #discountIsAppliedOnceAndNeverTouchesTheMaxedSentinel}
     * 里护盾卡那枚 28.5 钉住。
     */
    @Test
    public void firepowerPriceKeepsClimbingInsteadOfGoingUnbuyable() {
        Balance.ShopCard fire = Balance.shopCards[Balance.ShopCard.FIREPOWER];
        ShopRules.Snapshot s = snap(1, 100, 999);
        s.entry = ShopRules.ENTRY_PAUSE;
        int[] ladder = {30, 42, 54, 66};
        for (int lv = 0; lv < ladder.length; lv++) {
            s.level[Balance.ShopCard.FIREPOWER] = lv;
            assertEquals(ladder[lv], ShopRules.nextPrice(fire, s));
        }
        int prev = -1;
        for (int lv : new int[]{10, 11, 25, 80}) {
            s.level[Balance.ShopCard.FIREPOWER] = lv;
            int price = ShopRules.nextPrice(fire, s);
            assertNotEquals("第 " + lv + " 级又变成买不动的哨兵了（上限已取消）",
                    Balance.Shop.PRICE_MAXED, price);
            // 只断"还在爬"：把 30+12·lv 再乘一遍写进断言就是立了第二个价格真源，改了公式它跟着改，
            // 什么也拦不住。阶梯的形状由上面那四个手推数钉，深度由这条单调性钉。
            assertTrue("第 " + lv + " 级 " + price + " 不比上一级 " + prev + " 贵", price > prev);
            prev = price;
        }
    }

    @Test
    public void oneShotCardsKeepTheirFlatPriceForever() {
        Balance.ShopCard repair = Balance.shopCards[Balance.ShopCard.REPAIR];
        ShopRules.Snapshot s = snap(1, 50, 999);
        for (int lv = 0; lv < 30; lv++) {
            s.level[Balance.ShopCard.REPAIR] = lv;
            assertEquals("一次性卡没有阶梯（25 过九五折 = 23.75 → 24）", 24,
                    ShopRules.nextPrice(repair, s));
            assertEquals(ShopRules.UNLIMITED, ShopRules.maxLevelOf(Balance.ShopCard.REPAIR));
        }
    }

    /**
     * 九五折（I-3）只有 {@code nextPrice} 这一个乘点，并且**乘在满级哨兵之后**。
     *
     * <p>四个数全手推：{@code 55 → 52.25 → 52}、{@code 45 → 42.75 → 43}、{@code 38 → 36.1 → 36}，
     * 加上维修那张的 {@code 25 → 23.75 → 24}。取整方向（向上/向下）在金币上是看得见的差，
     * 所以宁可写四个手推数，也不在断言里重述公式。
     * 装甲强化满级后必须还是哨兵：负数被乘成 −0.95 再取整"碰巧"仍是 −1，那是巧合不是保证——
     * 这条把哨兵钉在乘子之前，将来谁把早退挪到乘法后面，它会红。
     *
     * <p>上面三个乘过的数是 −.25 / +.75 / +.1，它们只钉得住"四舍五入"这一半；
     * 护盾卡那条 {@code 30 → 28.5 → 29} 才是**恰好半分**的唯一证据（向下取整会给出 28）。
     * 它以前挂在火力卡那格，分区之后火力卡归暂停店、走乘子 1，这枚钉子必须搬到一张真的还在
     * 回合货架上的卡——<b>用哪张卡测，取决于哪家店真的卖它</b>，否则测的是一条没有读取点的路径。
     */
    @Test
    public void discountIsAppliedOnceAndNeverTouchesTheMaxedSentinel() {
        ShopRules.Snapshot s = snap(1, 100, 999);   // entry 默认 ENTRY_WAVE ⇒ 这里读到的是九五折那一路
        assertEquals(52, ShopRules.nextPrice(Balance.shopCards[Balance.ShopCard.SALVO], s));
        assertEquals(43, ShopRules.nextPrice(Balance.shopCards[Balance.ShopCard.GREED], s));
        assertEquals(36, ShopRules.nextPrice(Balance.shopCards[Balance.ShopCard.PRECISION], s));
        assertEquals("28.5 这枚半分向上取整 = 29（向下会给 28）", 29,
                ShopRules.nextPrice(Balance.shopCards[Balance.ShopCard.SHIELD], s));
        s.level[Balance.ShopCard.HULL] = ShopRules.maxLevelOf(Balance.ShopCard.HULL);
        assertEquals("满级哨兵不许被折扣乘一遍", Balance.Shop.PRICE_MAXED,
                ShopRules.nextPrice(Balance.shopCards[Balance.ShopCard.HULL], s));
    }

    // ---- 双入口分区 -------------------------------------------------------------------------

    /**
     * 分区的**不重不漏**。他 2026-09-30 的逐字提案是「我提议，波次结束的商店仅提供弹药补给、
     * 护盾、血量、临时加成之类的，而其他非补给的卡在暂停页做个商店放里面」，这条把那句话
     * 翻译成两个数：通用侧 6 张、非通用侧 6 张，并且**每张卡点名落在哪一家**。
     *
     * <p>为什么点名而不是只数张数：两侧由同一个谓词互补切出来，所以"把装甲卡换成火力卡"这种
     * 一整一出的错登记**两侧张数照旧各是 6**，只有点名能看见它搬了家。簇 II 还要往卡表里加四张
     * 构筑卡，那正是最容易只加卡不加 case、或顺手把新卡也归进通用侧的时候。
     *
     * <p>非通用侧的 6 是从 {@link ShopRules#listShelf} 数出来的（生产函数），不是照着 switch 再点一遍。
     */
    @Test
    public void partitionCoversEveryCardExactlyOnce() {
        int wave = 0;
        for (int id = 0; id < CARDS; id++) if (ShopRules.isWaveCard(id)) wave++;
        assertEquals("通用侧（回合店）今天六张", 6, wave);
        assertEquals("非通用侧（暂停店）六张——这个 6 是从 listShelf 数出来的",
                6, shelf(snap(1, 100, 999)).length);
        assertEquals("两侧合起来正好覆盖整张卡表。今天'不漏'是由 default 自动保证的（一个谓词、"
                + "两侧互补）；这条钉的是将来两侧拆成两个独立判据时那张两头都不认的卡",
                CARDS, wave + 6);
        for (int id : new int[]{Balance.ShopCard.REPAIR, Balance.ShopCard.SHIELD,
                Balance.ShopCard.SALVO, Balance.ShopCard.SURGE, Balance.ShopCard.HULL,
                Balance.ShopCard.RANDOM}) {
            assertTrue("这张补给/临时加成卡不在回合店里：" + Balance.shopCards[id].name,
                    ShopRules.isWaveCard(id));
        }
        for (int id : new int[]{Balance.ShopCard.FIREPOWER, Balance.ShopCard.TRIGGER,
                Balance.ShopCard.PRECISION, Balance.ShopCard.THRUSTS, Balance.ShopCard.MAGNET,
                Balance.ShopCard.GREED}) {
            assertFalse("这张非补给卡跑到回合店来了：" + Balance.shopCards[id].name,
                    ShopRules.isWaveCard(id));
        }
    }

    /**
     * 回合货架上**永远不该出现构筑卡**——三种血量档 × 60 个种子都不许出现。
     *
     * <p>这条与 {@link #healthyLateWaveSellsGeneralsNotBuildups} 是同一件事的两面：那条说
     * "最该推的卡换了人"，这条说"旧人一次都没回来过"。跑三档血量是因为通用侧哪几张有效随现状变
     * （满血时维修被 {@code isValid} 滤掉、残血时六张全在争），三档各换一种争抢格局——
     * 万一过滤哪天只写在某一条路径上，只测一档会漏。
     */
    @Test
    public void waveShelfNeverCarriesBuildCards() {
        int[] hp = {100, 55, 20};
        for (int h : hp) {
            ShopRules.Snapshot s = snap(8, h, 999);
            s.maxShield = 3;
            s.shield = 1;
            s.bombs = 1;
            for (long seed = 0; seed < 60; seed++) {
                for (int id : offer(s, seed)) {
                    assertTrue("回合店摆出了构筑卡（hp " + h + "、seed " + seed + "）："
                            + Balance.shopCards[id].name, ShopRules.isWaveCard(id));
                }
            }
        }
    }

    /**
     * 暂停店那条货架：<b>整条非通用侧、按 id 稳定、不打折、不抽样</b>。
     *
     * <p>四条各挡一种错法：
     * <ul>
     *   <li>整条 id 序列 {@code [0, 1, 2, 3, 9, 10]}：张数与构成一次钉死，挡住"暂停店也走三选一"
     *       （那等于把抽样搬进面板，他这句话要的是常驻），也挡住 default 那一路的静默漏登记。</li>
     *   <li>换一个大种子开出同一条货架：证明这一侧真的不读随机数。</li>
     *   <li>按 id 而不是按余额排（同一条断言）：货位一旦随金币漂移，玩家下次拉开面板得重新找
     *       那张卡。</li>
     *   <li>六张全价（30/34/38/22/32/45，手推自 {@code Balance.shopCards} 的 priceBase）：九五折挂在
     *       "回合结束那个商店"这句话上，覆盖到这家就变成"同一个等级在两家店卖两个价"，
     *       而这两家店在同一局里同时可达。</li>
     * </ul>
     */
    @Test
    public void pauseShelfIsTheWholeBuildSideAtFullPrice() {
        ShopRules.Snapshot s = snap(8, 100, 999);
        int[] ids = shelf(s);
        assertEquals("构筑卡按 id 稳定排列（4..8 与 11 都是通用侧，所以被跳开）",
                "[0, 1, 2, 3, 9, 10]", java.util.Arrays.toString(ids));
        ShopRules.Snapshot again = snap(8, 100, 999);
        again.entry = ShopRules.ENTRY_PAUSE;
        assertTrue("暂停货架随种子漂（它不该读随机数）",
                java.util.Arrays.equals(ids, offer(again, 987654L)));
        int[] full = {30, 34, 38, 22, 32, 45};
        for (int i = 0; i < ids.length; i++) {
            ShopRules.Snapshot one = snap(8, 100, 999);
            one.entry = ShopRules.ENTRY_PAUSE;
            assertEquals(Balance.shopCards[ids[i]].name + " 在暂停店被打了折",
                    full[i], ShopRules.nextPrice(Balance.shopCards[ids[i]], one));
        }
    }

    /** 满级的构筑卡从暂停货架上掉下去，而不是摆在那儿点不动（与通用侧同一个 {@code isValid} 闸门）。 */
    @Test
    public void pauseShelfDropsMaxedCards() {
        ShopRules.Snapshot s = snap(8, 100, 999);
        s.level[Balance.ShopCard.PRECISION] = ShopRules.maxLevelOf(Balance.ShopCard.PRECISION);
        int[] ids = shelf(s);
        assertEquals(5, ids.length);
        assertFalse("暴击卡满级了还挂在暂停店", has(ids, Balance.ShopCard.PRECISION));
        assertTrue("只该摘掉满级那一张，其余五张都得在", has(ids, Balance.ShopCard.GREED));
    }

    /**
     * 同一张卡、同一份快照，只换 {@code entry} ⇒ 价格差一个乘子。
     *
     * <p>这条是 {@link ShopRules#discountFor} 的正面断言，也是"乘子由入口派生、不由调用方派生"
     * 那个形状的证据：如果 {@code nextPrice} 是靠三个调用点各传一个参数实现的，这里就得写两次
     * 不同的调用；现在两次调用只差<b>快照里的一个字段</b>。手推：{@code 30 → 28.5 → 29} 与
     * {@code 30 → 30}。
     */
    @Test
    public void entryDecidesThePriceMultiplier() {
        ShopRules.Snapshot s = snap(1, 100, 999);
        Balance.ShopCard fire = Balance.shopCards[Balance.ShopCard.FIREPOWER];
        s.entry = ShopRules.ENTRY_WAVE;
        assertEquals(29, ShopRules.nextPrice(fire, s));
        s.entry = ShopRules.ENTRY_PAUSE;
        assertEquals(30, ShopRules.nextPrice(fire, s));
        assertEquals(1f, ShopRules.discountFor(ShopRules.ENTRY_PAUSE), 0f);
        assertEquals(Balance.shop.waveDiscount, ShopRules.discountFor(ShopRules.ENTRY_WAVE), 0f);
    }

    /**
     * 分区是「随机强化」的**生效前提**（commit A 收口时登记的验收项）。
     *
     * <p>全表时代它在 12 张里排 0.40，而第 1 波的第三名门槛已经有 0.340、第 20 波爬到 0.700
     * ⇒ 探针实测露脸率 0.0%，等于这张卡买了个不存在的名额。分区之后同一条平值落在通用池里，
     * 本轮**现测**（满血、无盾上限、手里没弹，60 个连续种子）：波 2 = 58、波 5 = 50、波 10 = 30、
     * 波 14 = 23、波 20 = 22。露脸率随波次**下降**是平值的必然后果——装甲卡带波次项
     * （0.25 + 0.35·waveTerm，第 10 波已经 0.425）会一路压过这张不动的 0.40，所以它从"早期几乎必上"
     * 变成"后期约三分之一"。这不是 bug，但它意味着 commit A 那句"平值就够"只在早中波成立。
     *
     * <p>⚠ 别把上面这串数与 {@code ShopRules.prio} 里那条"通用池满状态档露脸率 94.8%"当成同一档：
     * 归档（状态根 {@code reference/evidence/launchunit/out16.txt} 第 2 行）写着那一档的现状是
     * **盾 3/3、弹 1**，而这里这份快照是 **无盾上限、手里没弹**。差别在弹药卡——有弹时它只排 0.30，
     * 无弹时它是 0.55，那 0.55 在这里占掉一格 ⇒ 门槛抬高、这张卡的 0.40 从"94.8%"掉到"五成"。
     * 两条读数各自只在自己那一档成立，不构成矛盾。下限取 10 而不是"非零"：分区没落地时这里实测是 0，
     * 池子重新长回去时会掉到 10 以下。
     */
    @Test
    public void randomCardReachesTheWaveShelfOnceThePoolIsGeneralOnly() {
        ShopRules.Snapshot s = snap(10, 100, 999);
        int onShelf = 0;
        for (long seed = 0; seed < 60; seed++) {
            if (has(offer(s, seed), Balance.ShopCard.RANDOM)) onShelf++;
        }
        assertTrue("满血第 10 波时「随机强化」60 次里只上架 " + onShelf + " 次：分区之后它仍然买不到",
                onShelf >= 10);
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
     * <p>宿主的历史：先是火力，2026-09-26 取消满级后换成暴击（它当时靠 prio 挤得进前三），
     * 2026-10-01 分区后再换成**装甲强化**——暴击卡已经归暂停店，那条"过滤掉就不上架"的分支
     * 在抽样这一路再也没有它了。挑宿主的标准没变：<b>prio 真能稳定挤进前三</b>。第 20 波满血时
     * 通用侧的形状是 装甲 0.60 / 弹药 0.55 / 超载 0.42 / 随机 0.40（维修满血无效、护盾无上限无效），
     * 装甲卡的 worst 是 0.60−0.08 = 0.52，高于超载的 best 0.42+0.08 = 0.50 与随机的
     * 0.40+0.08 = 0.48 ⇒ 只有弹药翻得过它，它这一档稳在前两格。于是先数"没满级时上架了几次"，
     * 再数"满级后上架了几次"：前者非零、后者为零，这条用例才真的在测过滤，
     * 而不是测一张本来就排不进前三的卡。
     */
    @Test
    public void maxedCardsLeaveTheShelfInsteadOfSittingThereUnbuyable() {
        ShopRules.Snapshot s = snap(20, 100, 999);
        int onShelf = 0;
        for (long seed = 0; seed < 60; seed++) {
            if (has(offer(s, seed), Balance.ShopCard.HULL)) onShelf++;
        }
        assertTrue("满级前装甲卡就基本不上架（只有 " + onShelf + "/60 次）：宿主选错了，"
                + "满级后为 0 说明不了任何事", onShelf >= 40);
        s.level[Balance.ShopCard.HULL] = ShopRules.maxLevelOf(Balance.ShopCard.HULL);
        assertFalse(ShopRules.isValid(Balance.ShopCard.HULL, s));
        for (long seed = 0; seed < 60; seed++) {
            assertFalse("满级卡又上架了（seed " + seed + "）",
                    has(offer(s, seed), Balance.ShopCard.HULL));
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

    /**
     * 满血的高波次，回合店卖的是"上限与手牌"，不是保命补给，也不是构筑卡。
     *
     * <p>用例名字里的"builds"在分区前后换了含义，所以这里把三条断言各自挂在哪道闸门上说清：
     * <ul>
     *   <li><b>维修不在</b>——{@code isValid}（满血买它什么都没发生），分区前就有的一道闸。</li>
     *   <li><b>火力不在</b>——{@code isWaveCard}，分区新加的那道闸，也是这条用例今天真正要测的东西。
     *       分区前它靠 prio 排在最前（第 18 波 0.85，全场第一），所以旧断言写的是"必在架上"；
     *       现在同一个 id 必须<b>永远</b>不在回合货架上。这两条断言方向相反，是分区本体的正反面。</li>
     *   <li><b>装甲必在</b>——prio 的形状（0.25 + 波次项 0.35，第 18 波 = 0.565）加上"满血时
     *       维修/护盾都无效"，让它成为这一档的常客。装甲 0.565−0.08=0.485 高于随机 0.40+0.08=0.48
     *       ⇒ 60 个种子没有一个能把它挤出前三。</li>
     * </ul>
     */
    @Test
    public void healthyLateWaveSellsGeneralsNotBuildups() {
        ShopRules.Snapshot s = snap(18, 100, 999);
        for (long seed = 0; seed < 60; seed++) {
            int[] ids = offer(s, seed);
            assertTrue("第 18 波满血时最该推的上限卡缺席", has(ids, Balance.ShopCard.HULL));
            assertFalse("满血还卖维修", has(ids, Balance.ShopCard.REPAIR));
            assertFalse("构筑卡跑到回合店来了（它归暂停店）",
                    has(ids, Balance.ShopCard.FIREPOWER));
        }
    }

    /**
     * 「随机强化」的 prio 形状：<b>平值、残血整条让位</b>（探针⑯ 定案，2026-10-01）。
     *
     * <p>三条读数决定这三条断言（波 1..20 × 4000 种子 × 四档现状，通用侧六张卡的池子；
     * 归档见状态根 {@code reference/evidence/launchunit/out16.txt}）：
     * <ul>
     *   <li>通用池满状态那档的<b>第三名 prio 从波 3 起恒为 0.30</b>、扣掉抖动后门槛 0.22
     *       ⇒ 平值 0.40 的露脸率 94.8%。低于 0.30 就等于这张卡买不到，所以 0.30 是地板而非口味。</li>
     *   <li>给它<b>波次项</b>能换到"半血也 100% 上架"（候选 4：99.7%），但那等于把"十二秒的
     *       消耗品越到后期越该买"这句没根据的话写进分数里——这张卡的边际价值不随波次变，
     *       与贪婪卡那条"越早买越划算"正好相反的方向。所以断言取"平"。</li>
     *   <li>残血<b>有弹</b>那一档（hp 25 / bombs 1）平值 0.40 仍有 16.3% 的货架混进三格
     *       （波 1..6 各 30~38%），而那三格该留给维修/护盾/弹药 ⇒ panic 项钳到 0 后实测 0.0%。</li>
     * </ul>
     *
     * <p>⚠ 这些读数只在**通用侧六张卡**的池子里成立。分区落地前回合店从十二张全表里抽，全表门槛
     * 从波 1 的 0.340 爬到波 20 的 0.700，任何平值都进不了架（0.26 与 0.40 实测都是 0.0%）——
     * 那条错账曾让我以为这张卡必须随波次爬。2026-10-01 commit B1 之后回合店真的只剩这一池，
     * 上面那些读数才从"探针里的假想池"变成生产形状；"满血到底进不进得了架"这条正着断言由
     * {@link #randomCardReachesTheWaveShelfOnceThePoolIsGeneralOnly} 钉（它测的是池子构成，
     * 不是这张卡的分数，所以不混进下面这几条）。
     */
    @Test
    public void randomCardIsAFlatFillerThatYieldsWhenDying() {
        assertEquals("消耗品的分数不随波次变：波 2 与波 18 同一个数",
                ShopRules.prio(Balance.ShopCard.RANDOM, snap(2, 100, 999)),
                ShopRules.prio(Balance.ShopCard.RANDOM, snap(18, 100, 999)), 0f);
        assertTrue("低于通用池的第三名地板 0.30，这张卡就上不了架（探针⑯ cut line）",
                ShopRules.prio(Balance.ShopCard.RANDOM, snap(10, 100, 999)) > 0.30f);
        ShopRules.Snapshot dying = snap(10, 25, 999);
        dying.bombs = 1;                       // 有翻盘手牌的残血局——平值正是从这一档漏进三格的
        assertEquals("残血整条让位：那三格是维修/护盾/弹药的，不该是十二秒的运气",
                0f, ShopRules.prio(Balance.ShopCard.RANDOM, dying), 0f);
        for (long seed = 0; seed < 60; seed++) {
            assertFalse("残血时这张卡不许出现在货架上（seed " + seed + "）",
                    has(offer(dying, seed), Balance.ShopCard.RANDOM));
        }
    }

    /**
     * 被打掉六成血时，速度卡必须**一直摆在那儿**。
     *
     * <p>这条用例的结论没变，承载物换了：分区前"上架"是 prio 挤进前三的结果（所以要跑 60 个种子
     * 数露脸率），分区后这张卡归暂停店、{@code listShelf} 无条件铺出来 ⇒ 可见性不再依赖概率，
     * 一次开架就是一次性的事实。他逐字裁的就是换承载物这件事：「提高速度卡出现概率废弃，
     * 因为失去承载物，仅低价即可」——所以** prio 里那条 0.42 的抬升不再是闸门**，这条断言改钉货架本身。
     *
     * <p>归属与日期在 2026-09-27 逐子句重核过（本仓注释日期 = 本地日；transcript 戳是 UTC，
     * 比日期要先 +8 再取日）：改成限速跟随这个**选择**是用户做的，但那几个字是**我写的选项标签**——
     * 他的逐字作答是 **L12104**（answers-**OPT**，逐字「指针改成限速跟随（推荐）」，
     * UTC 09-24T15:20:49 ＝ **本地 09-24** 23:20）。先前这里写"2026-09-25 用户决策"两样都偏了：
     * 日子按本地口径偏一天，"决策"二字把选项标签当成了原话。
     *
     * <p>下面那条因果是我的推导，不来自任何一条裁定：触屏走位改成限速跟随之后，机体速度同时是
     * "指针欠账每秒能放出去多少"的上限（{@code DragDebt.consume} 的 speed），所以这张卡不只是躲子弹
     * 的手段，也是"手感跟不上眼睛"的唯一出口——出口得真的开着。
     *
     * <p>那个"低价"也换了口径：暂停店全价 ⇒ 卡面与扣币端读到的都是 22，不再是折后的 21。
     */
    @Test
    public void bruisedRunAlwaysSeesTheThrustCard() {
        ShopRules.Snapshot s = snap(6, 40, 999);
        s.maxShield = 3;
        s.shield = 1;
        assertTrue("暂停货架上没有速度卡", has(shelf(s), Balance.ShopCard.THRUSTS));
        ShopRules.Snapshot priced = snap(1, 100, 0);
        priced.entry = ShopRules.ENTRY_PAUSE;
        assertEquals("速度卡 22 币起卖、暂停店不打折，第 1 波的钱包就够摸到一级",
                22, ShopRules.nextPrice(Balance.shopCards[Balance.ShopCard.THRUSTS], priced));
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

    /**
     * 抖动必须真的在换货架，否则"三选一"退化成"确认键"。
     *
     * <p>满血、缺盾这一档通用侧的 prio 形状（现读，非手推公式）：护盾 0.88 / 弹药 0.55 /
     * 超载 0.42 / 随机 0.40 / 装甲 0.355，维修满血无效。护盾稳在第一；弹药偶尔被
     * 超载 0.42+0.08 = 0.50 顶到第三；末格在 随机 / 装甲 / 超载 之间换。
     * 注释里以前那串"弹药 0.55 / 火力 0.50 / 速度 0.495 / 扳机 0.484"是**十二张全表**的读数，
     * 分区之后那六条构筑卡根本不进这一路，留着会把人引回一张已经不存在的货架。
     *
     * <p>⚠ 种子数从 40 提到 2000 是因为**可达排列数**跟着池子一起缩了：12 张挑 3 张的时候 40 个
     * 种子能看见五六个排列，5 张挑 3 张的时候全部可达排列只有 6 个，而其中两个各只占约 1/2000
     * ——实测 40、120、400 个连续种子都只数到 4 种货架。留在那一档，这条会在"采样不够"时红，
     * 而不是在"抖动真的失效"时红，那是假警报。下限 5 贴着观测值 6：把 jitter 调到 0 会只剩 1 种。
     */
    @Test
    public void jitterActuallyReordersTheShelf() {
        ShopRules.Snapshot s = snap(6, 100, 999);
        s.maxShield = 3;
        s.shield = 1;
        Set<String> distinct = new HashSet<>();
        Set<Integer> lastSlot = new HashSet<>();
        for (long seed = 0; seed < 2000; seed++) {
            int[] ids = offer(s, seed);
            distinct.add(java.util.Arrays.toString(ids));
            lastSlot.add(ids[ids.length - 1]);
        }
        assertTrue("三选一退化成了固定菜单（2000 个种子只见到 " + distinct.size() + " 种货架）",
                distinct.size() >= 5);
        assertTrue("末格只有一张卡在轮值（" + lastSlot.size() + " 个候选）：抖动没能换到人",
                lastSlot.size() >= 3);
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
     *
     * <p>25 币这一档在分区前后是**两个不同的局**：以前挡在前三的是构筑卡（火力 29 / 扳机 32 /
     * 贪婪 43），现在它们整条搬到暂停店，回合货架上买不起的前三换成了
     * 弹药 0.55(52) / 超载 0.42(38) / 随机 0.40(29)，第四名的维修 0.312(24) 才是保底换进来的那张。
     * 三个 prio 与四档折后价全手推；上一版为了腾出这个局还得把速度卡顶成满级——那张卡现在
     * 不在这条路上，这一行也跟着没了。
     */
    @Test
    public void theLastSlotIsSpentOnAnAffordableCardWhenTheTopThreeAreNot() {
        float jitter = Balance.shop.prioJitter;
        try {
            Balance.shop.prioJitter = 0f;
            ShopRules.Snapshot s = snap(4, 99, 25);
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

    /**
     * 边界跟着折扣走：护盾卡原价 30，过九五折 = 28.5 → 29 ⇒ 29 币点得动、28 币点不动。
     * 这对数字同时钉住两件事：扣币端与面板端读的是同一个 nextPrice（否则边界会分裂成两个），
     * 以及取整方向是"半分向上"（向下取整的话 28 就该点得动了）。
     *
     * <p>宿主以前是火力卡：那张卡在分区之后归暂停店、拿的是乘子 1，"折后 29 / 28 币点不动"
     * 这个局在生产里已经不会出现，留在它身上等于测一条走不到的路。护盾卡在回合货架上，
     * 而且它的 30 恰好乘出半分，换过去之后这枚钉子钉的仍是生产形状。
     */
    @Test
    public void purchasableIsTheOneJudgeBothEndsRead() {
        ShopRules.Snapshot s = snap(4, 100, 29);
        s.maxShield = 3;
        s.shield = 1;                     // 缺盾才有效：判据是"有效 ∧ 买得起"，两道都得给
        assertTrue(ShopRules.purchasable(Balance.shopCards[Balance.ShopCard.SHIELD], s));
        s.coins = 28;
        assertFalse(ShopRules.purchasable(Balance.shopCards[Balance.ShopCard.SHIELD], s));
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
        // 「随机强化」**特意不落在 default 那一路**：default 画「立即就绪」，那是给冷却制卡（超载）
        // 说的话，画在一张"立即给你一种增益"的卡上就是上屏的假话。这条是 JVM 侧唯一还能拦住
        // 它的地方——drawCore 那个 switch 走 Canvas，测试碰不到（见 ShopRules 里 CORE_CHOICE 的注释）。
        assertEquals(ShopRules.CORE_CHOICE, ShopRules.coreKind(Balance.ShopCard.RANDOM));
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
                Balance.ShopCard.SALVO, Balance.ShopCard.SURGE, Balance.ShopCard.RANDOM}) {
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
