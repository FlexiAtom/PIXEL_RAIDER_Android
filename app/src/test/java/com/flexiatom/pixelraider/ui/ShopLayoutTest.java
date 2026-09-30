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
package com.flexiatom.pixelraider.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.flexiatom.pixelraider.core.RectI;
import com.flexiatom.pixelraider.game.Balance;
import com.flexiatom.pixelraider.plat.Screen;
import org.junit.Test;

/**
 * 商店面板几何。期望值**手推写死**（照 {@code PauseLayoutTest} 的规矩）：这块板子要在
 * 320~560 的每种逻辑高与任意安全区上不跟"进入下一波"叠字，而重叠在截图上往往只是
 * "按钮好像有点挤"，没人会为此回炉一次真机。
 *
 * <p>2026-09-30 双入口分区之后每一页几张由画布算，所以这里多了一类账：**页**账
 * （几页、每页几张、翻页时出口那行变成三格）。这类账一旦算错的表现是"第二页点下去命中的
 * 是第一页的卡"，它在截图上完全看不出来，只能靠矩形钉。
 */
public class ShopLayoutTest {

    private final ShopLayout l = new ShopLayout();

    /** 三张整卡的弹层高：74(边框+标题+两条池间隙+出口行+留白) + 3×60 + 2×4。 */
    private static final int SHEET_3 = 262;
    /** 六张整卡的弹层高：74 + 6×60 + 5×4。 */
    private static final int SHEET_6 = 454;

    private static void assertRect(String what, RectI r, int left, int top, int right, int bottom) {
        assertEquals(what + " left", left, r.left);
        assertEquals(what + " top", top, r.top);
        assertEquals(what + " right", right, r.right);
        assertEquals(what + " bottom", bottom, r.bottom);
    }

    /**
     * 版面数组的长度取自卡表，不是取自"一次上架几张"。
     *
     * <p>这两个数在分区之前恰好相等（都是 3），所以旧的那枚钉子同时钉着两件事；现在它必须是
     * 卡表那条——回合店摆 3 张、暂停店摆整条非通用侧，数组按 3 开就会在暂停店开架时越界。
     */
    @Test
    public void theArrayIsTheWholeCardTableNotOneOffer() {
        assertEquals("版面最多摆几张 = 卡表条数", Balance.Shop.CARDS, ShopLayout.MAX_CARDS);
        assertEquals("卡表条数 = 规则端的全集", Balance.Shop.CARDS, Balance.shopCards.length);
        assertEquals("数组真的按 MAX_CARDS 开（面板与 Game 都靠它不越界）",
                ShopLayout.MAX_CARDS, l.cards.length);
    }

    /** 矮屏（逻辑高 320）整块账：弹层 8,52..232,314（高 262 = 内容高），内容列 14..226。 */
    @Test
    public void bandsTileTheShortCanvas() {
        l.layout(320, 0, 0, 3, 0);
        assertRect("panel", l.panel, 8, 52, 232, 314);
        assertRect("titleBar", l.titleBar, 14, 56, 226, 84);
        assertRect("next", l.next, 39, 286, 200, 308);   // 212 的 76% = 161，左右各余 25/26
        assertRect("card0", l.cards[0], 14, 90, 226, 150);
        assertRect("card1", l.cards[1], 14, 154, 226, 214);
        assertRect("card2", l.cards[2], 14, 218, 226, 278);
        assertEquals(41, ShopLayout.textLeft(l.cards[0]));   // 14 + 4 + 18 + 5，见 textWidth 那条
        assertEquals("三张一页，没有第二页", 1, l.pages);
        assertRect("不翻页时没有上一页", l.pagePrev, 0, 0, 0, 0);
        assertRect("不翻页时没有下一页", l.pageNext, 0, 0, 0, 0);
    }

    /**
     * 高屏上板子**不长高**：多出来的高度还给战场，卡高恒定 60、内容贴着面板底边排。
     *
     * <p>这条是"商店页面过大"的反证。板子铺满画布时，533 的屏上有 129px 是纯粹的遮罩盖住了
     * 玩家自己的战机和正在逼近的弹幕——玩家站在商店前面看不见战场，比按钮小一点严重得多。
     */
    @Test
    public void tallCanvasKeepsTheBattlefieldAboveTheSheet() {
        l.layout(533, 0, 0, 3, 0);
        assertEquals(ShopLayout.CARD_H, l.cards[0].height());
        assertEquals(ShopLayout.CARD_H, l.cards[2].height());
        assertRect("panel@533", l.panel, 8, 265, 232, 527);
        assertRect("next@533", l.next, 39, 499, 200, 521);
        assertRect("card0@533", l.cards[0], 14, 303, 226, 363);
        assertRect("card2@533", l.cards[2], 14, 431, 226, 491);
        assertEquals("内容高度就是面板高度，不长一分", SHEET_3, l.panel.height());
        assertEquals("卡区不再需要居中补偿", l.titleBar.bottom + ShopLayout.POOL_TOP_GAP, l.cards[0].top);
        assertEquals("卡区不再需要居中补偿", l.cards[2].bottom, l.next.top - ShopLayout.POOL_BOTTOM_GAP);
        assertTrue("战场被板子吃到只剩一半: panel.top=" + l.panel.top, l.panel.top >= 533 / 3);
    }

    /**
     * 装得下时弹层高度只由**这一页的内容**决定，与屏高无关；一页摆满六张才会变高。
     *
     * <p>六张在 533 上仍是一页（{@code 533 − 12 = 521 ≥ 454}），板子因此长到 454——这不是把
     * 旧那条"不长高"推翻，而是它的适用范围：暂停店拉开时下面本来就压着暂停页，战场已经不在
     * 玩家眼前了，让六张整卡各自摊开比省出 100px 遮罩有价值。
     */
    @Test
    public void sheetHeightIsContentHeightOnEveryCanvasThatFits() {
        for (int h = 320; h <= 560; h += 11) {
            l.layout(h, 0, 0, 3, 0);
            if (h - 2 * ShopLayout.PANEL_GAP < SHEET_3) continue;   // 装不下才该长满
            assertEquals("logicH=" + h + " 板子长高了", SHEET_3, l.panel.height());
            assertEquals("logicH=" + h + " 底边没贴可用区下沿", h - ShopLayout.PANEL_GAP, l.panel.bottom);
        }
        l.layout(533, 0, 0, 6, 0);
        assertEquals("六张那一页该长到 454", SHEET_6, l.panel.height());
        assertEquals("六张在高屏上还是一页", 1, l.pages);
        assertRect("六张时末张@533", l.cards[5], 14, 431, 226, 491);
    }

    /**
     * 安全区的账：顶部的洞不该推动弹层，底部的洞才推。
     *
     * <p>弹层贴的是"画布底边 − 底部安全区"。挖机屏 safeTop=18 时板子该原地不动——把板子往下
     * 推（等于让刘海去压内容）是铺满式面板的老算法留下的毛病。
     */
    @Test
    public void safeInsetsMoveTheSheetOnlyFromTheBottom() {
        l.layout(533, 0, 0, 3, 0);
        int h0 = l.cards[0].height();
        int top0 = l.panel.top;
        l.layout(533, 18, 0, 3, 0);    // 本机实测：挖孔 52px / pxPerLogic 3.0 → 18 逻辑像素
        assertRect("panel@safeTop18", l.panel, 8, top0, 232, 527);
        assertRect("titleBar@safeTop18", l.titleBar, 14, 269, 226, 297);
        assertEquals("卡高不受安全区影响", h0, l.cards[0].height());
        assertEquals("图标边长必须是 9 格网格的整数倍", 0, ShopLayout.ICON % 9);
        l.layout(533, 0, 24, 3, 0);    // 手势条 72px / 3.0 = 24
        assertRect("panel@safeBottom24", l.panel, 8, top0 - 24, 232, 503);
    }

    /** 扫遍支持的屏高与安全区：任何一档都不许让卡片骑到标题或按钮上。 */
    @Test
    public void stackNeverCrossesIntoTheTitleOrTheButton() {
        int[] counts = {Balance.Shop.OFFER, 6, ShopLayout.MAX_CARDS};
        for (int h = Screen.LOGIC_H_MIN; h <= Screen.LOGIC_H_MAX; h += 7) {
            for (int top = 0; top <= 32; top += 8) {
                for (int bottom = 0; bottom <= 48; bottom += 12) {
                    for (int count : counts) {
                        l.layout(h, top, bottom, count, 0);
                        for (int p = 0; p < l.pages; p++) {
                            l.layout(h, top, bottom, count, p);
                            checkBands(h, top, bottom, p);
                        }
                    }
                }
            }
        }
    }

    private void checkBands(int h, int topInset, int bottomInset, int page) {
        String where = "logicH=" + h + " safe=" + topInset + "/" + bottomInset
                + " page=" + page + " ";
        int poolTop = l.titleBar.bottom + ShopLayout.POOL_TOP_GAP;
        int poolBottom = l.next.top - ShopLayout.POOL_BOTTOM_GAP;
        RectI first = l.cards[0];
        RectI last = l.cards[l.shown - 1];
        assertTrue(where + "板子长进了顶部安全区: top=" + l.panel.top,
                l.panel.top >= topInset + ShopLayout.PANEL_GAP);
        assertTrue(where + "板子长过了可用区", l.panel.height() <= h - bottomInset - topInset - ShopLayout.PANEL_GAP);
        assertTrue(where + "首张卡骑到标题上", first.top >= poolTop);
        assertTrue(where + "末张卡压到按钮上: " + last.bottom + " > " + poolBottom,
                last.bottom <= poolBottom);
        assertTrue(where + "标题掉出面板", l.titleBar.bottom <= l.panel.bottom);
        assertTrue(where + "出口按钮掉出面板", l.next.bottom <= l.panel.bottom - 4);
        for (int i = 1; i < l.shown; i++) {
            int gap = l.cards[i].top - l.cards[i - 1].bottom;
            assertEquals(where + "卡间距不恒定", l.cards[1].top - l.cards[0].bottom, gap);
            assertTrue(where + "间距被改大: " + gap, gap <= ShopLayout.CARD_GAP);
        }
        if (first.height() == ShopLayout.CARD_H) {
            // 装得下才是完整卡：这时候三条带必须谁也不溢出谁的框
            assertTrue(where + "末行的说明带掉出卡框",
                    ShopLayout.row3Top(last) + ShopLayout.ROW3_H <= last.bottom);
        } else {
            assertTrue(where + "被压过的卡反而比标准卡高: " + first.height(),
                    first.height() < ShopLayout.CARD_H);
        }
        assertEquals(where + "同一页的卡同宽", first.width(), last.width());
    }

    /** 卡内三条带的账：加起来正好等于卡高减去上下留白，多一像素就会溢出到隔壁那张卡。 */
    @Test
    public void cardBandsFillTheCardWithoutSpilling() {
        l.layout(400, 0, 0, 3, 0);
        RectI c = l.cards[0];
        assertEquals(ShopLayout.CARD_H, c.height());
        assertEquals(c.top + ShopLayout.CARD_PAD, ShopLayout.row1Top(c));
        assertEquals(ShopLayout.row1Top(c) + ShopLayout.ROW1_H + ShopLayout.ROW_GAP,
                ShopLayout.row2Top(c));
        assertEquals(ShopLayout.row2Top(c) + ShopLayout.ROW2_H + ShopLayout.ROW_GAP,
                ShopLayout.row3Top(c));
        assertEquals(c.bottom - ShopLayout.CARD_PAD,
                ShopLayout.row3Top(c) + ShopLayout.ROW3_H);
    }

    /**
     * 文字可用宽是 tip 能否整句放下的分母。
     *
     * <p>181 = 226(内容右缘) − 4(卡内留白) − 41(图标左缘 18 + 5 + 卡的左边 14 起算)。
     * 这条与 {@code Balance} 里的卡面文案配着看：文案最长 15 个汉字 × 12px = 180，只剩 1px 余量，
     * 所以任何一次"把图标往右挪一格"都会让说明行折行——那是卡面被顶开的开始。
     *
     * <p>也是这条把"两列货架"否掉了：两列会把 181 砍到 79，最长那句当场放不下。分页是纵向切
     * 高度，动不到这个数。
     */
    @Test
    public void textColumnIsWideEnoughForTheLongestTip() {
        l.layout(400, 0, 0, 3, 0);
        RectI c = l.cards[0];
        assertEquals(41, ShopLayout.textLeft(c));
        assertEquals(181, ShopLayout.textWidth(c));
        int longest = 0;
        for (Balance.ShopCard card : Balance.shopCards) {
            longest = Math.max(longest, card.tip.length());
        }
        assertTrue("最长说明 " + longest + " 字 × 12px 放不下 181px 的文字列",
                longest * Md3.PX_BODY <= ShopLayout.textWidth(c));
    }

    /** 零或负的屏高不该产生翻转矩形（{@code RectI.contains} 一旦左大于右就永远命中不了）。 */
    @Test
    public void degenerateCanvasStillYieldsUsableRects() {
        l.layout(0, 0, 0, 0, 0);
        assertEquals("空架也得有一页，否则绘制端要防 pages=0", 1, l.pages);
        assertEquals(0, l.shown);
        assertTrue(l.next.bottom >= l.next.top);
        assertTrue("空架的池子塌成负高了：" + l.pool, l.pool.bottom >= l.pool.top);
        l.layout(0, 0, 0, 3, 0);
        for (int i = 0; i < l.shown; i++) {
            RectI c = l.cards[i];
            assertTrue(c.right > c.left);
            assertTrue("卡框翻转了：" + c, c.bottom >= c.top);
        }
    }

    // ---- 每页几张与翻页 ------------------------------------------------------------------------

    /**
     * 容量的三条边：矮屏的下限、高屏的上限、以及中间那一档到底几张。
     *
     * <p>手推：可用区 = {@code logicH − 2·PANEL_GAP}（安全区全 0 时）。
     * <ul>
     *   <li>320 → 308：{@code (308 − 74 + 4) / 64 = 3}。三张整卡 262 正好装下，第四张要 326。</li>
     *   <li>533 → 521：{@code (521 − 74 + 4) / 64 = 7}。六张 454 因此是一页。</li>
     *   <li>560 → 548：{@code (548 − 70) / 64 = 7} 还是 7——第八张要 {@code 518 > 474}，
     *       所以画布守护带上限那一档也摆不下整架 12 张，得翻两页。</li>
     * </ul>
     */
    @Test
    public void onePageHoldsAsManyCardsAsTheCanvasAllows() {
        l.layout(320, 0, 0, 1, 0);
        assertEquals("320 高只装得下三张整卡", 3, l.capacity);
        l.layout(533, 0, 0, 1, 0);
        assertEquals("533 高装得下七张", 7, l.capacity);
        l.layout(560, 0, 0, 1, 0);
        assertEquals(7, l.capacity);
        assertEquals("再大的画布也不许超过整张卡表",
                ShopLayout.MAX_CARDS, ShopLayout.capacityFor(10000));
    }

    /**
     * 回合店那一次三选一**永不翻页**，包括厚安全区叠上矮画布的那种机器。
     *
     * <p>这条是 {@link ShopLayout#capacityFor} 那个下限存在的理由：把三张候选拆成两页，玩家
     * 点第一页时看不见第三个候选，"三选一"就退化成两次"要不要"。那种局改走压卡分支
     * （见 {@link #thickInsetsCompressTheThreeInsteadOfPaginatingThem}）。
     */
    @Test
    public void theWaveShelfNeverPaginatesEvenUnderThickSafeAreas() {
        for (int h = Screen.LOGIC_H_MIN; h <= Screen.LOGIC_H_MAX; h += 13) {
            for (int top = 0; top <= 32; top += 8) {
                for (int bottom = 0; bottom <= 48; bottom += 12) {
                    l.layout(h, top, bottom, Balance.Shop.OFFER, 0);
                    String where = "logicH=" + h + " safe=" + top + "/" + bottom + " ";
                    assertEquals(where + "回合店被切成了 " + l.pages + " 页", 1, l.pages);
                    assertEquals(where + "有一张候选没摆出来", Balance.Shop.OFFER, l.shown);
                }
            }
        }
    }

    /**
     * 六张的货架：533 一页摆完，320 翻两页；请求越界的页由布局钳掉而不是画空架。
     *
     * <p>第二页的账（320、钳到末页）：{@code pageStart=3、shown=3}，几何与第一页逐像素相同——
     * 面板与按钮不该因为翻了一页就搬家，玩家的位置记忆是"中间那枚实心按钮走人"。
     */
    @Test
    public void aSixCardShelfPaginatesOnlyWhenTheCanvasRunsOut() {
        l.layout(533, 0, 0, 6, 0);
        assertEquals(1, l.pages);
        assertEquals(6, l.shown);
        assertEquals(0, l.pageStart);

        l.layout(320, 0, 0, 6, 0);
        assertEquals(2, l.pages);
        assertEquals("第一页摆前三个", 0, l.pageStart);
        assertEquals(3, l.shown);
        int panelTop0 = l.panel.top;
        int cardTop0 = l.cards[0].top;

        l.layout(320, 0, 0, 6, 1);
        assertEquals("第二页摆后三个", 3, l.pageStart);
        assertEquals(3, l.shown);
        assertEquals("翻页不动面板", panelTop0, l.panel.top);
        assertEquals("翻页不动卡位", cardTop0, l.cards[0].top);

        l.layout(320, 0, 0, 6, 9);
        assertEquals("越界的页请求该被钳到末页，不是摆一张空架", 1, l.page);
        assertEquals(3, l.shown);
    }

    /**
     * 翻页那三格的矩形（320 高、六张，两页各测一遍）：出口左右各一枚箭头，三格等宽。
     *
     * <p>212 = 3×68 + 2×4 整除，所以末列不吸收余数——中间那格仍是 68，比不翻页时的 161 窄，
     * 但「进入下一波」五个字 × 12px = 60 装得下，只差 8px 余量。这枚钉子挡的是"哪天文案变长
     * 或卡变宽，中间那格先挤爆"。
     */
    @Test
    public void thePagedRowIsThreeEqualCells() {
        for (int p = 0; p < 2; p++) {
            l.layout(320, 0, 0, 6, p);
            String w = "第 " + (p + 1) + " 页 ";
            assertRect(w + "上一页", l.pagePrev, 14, 286, 82, 308);
            assertRect(w + "出口", l.next, 86, 286, 154, 308);
            assertRect(w + "下一页", l.pageNext, 158, 286, 226, 308);
            assertTrue(w + "翻页格不许压到卡区",
                    l.cards[l.shown - 1].bottom <= l.pagePrev.top - ShopLayout.POOL_BOTTOM_GAP);
        }
    }

    /**
     * 刘海 32 + 手势条 48 叠在 320 上：可用区只剩 228，装不下 262 的三张整卡。
     *
     * <p>这一档走的是压卡分支而不是翻页（间距先收掉、卡高 60→51，153 = 3×51 ≤ 154）。
     * 手推：panel 8,38..232,266；titleBar 14,42..226,70；next 39,238..200,260；
     * pool 76..230；card0 76..127、card2 178..229。
     */
    @Test
    public void thickInsetsCompressTheThreeInsteadOfPaginatingThem() {
        l.layout(320, 32, 48, 3, 0);
        assertEquals("宁压不翻页", 1, l.pages);
        assertRect("panel", l.panel, 8, 38, 232, 266);
        assertRect("next", l.next, 39, 238, 200, 260);
        assertEquals(51, l.cards[0].height());
        assertRect("card0", l.cards[0], 14, 76, 226, 127);
        assertRect("card2", l.cards[2], 14, 178, 226, 229);
        assertEquals("间距先被收完才轮到压卡", 127, l.cards[1].top);
    }
}
