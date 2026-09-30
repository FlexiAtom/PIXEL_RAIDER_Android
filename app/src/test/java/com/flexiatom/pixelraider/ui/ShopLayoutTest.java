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
 */
public class ShopLayoutTest {

    private final ShopLayout l = new ShopLayout();

    private static void assertRect(String what, RectI r, int left, int top, int right, int bottom) {
        assertEquals(what + " left", left, r.left);
        assertEquals(what + " top", top, r.top);
        assertEquals(what + " right", right, r.right);
        assertEquals(what + " bottom", bottom, r.bottom);
    }

    @Test
    public void shelfSizeIsTheSameNumberTheRulesUse() {
        assertEquals("一次上架几张：布局与规则各自写一份的话，一定会分裂",
                Balance.Shop.OFFER, ShopLayout.CARDS);
        assertEquals("卡表条数 = 规则端的全集", Balance.Shop.CARDS, Balance.shopCards.length);
    }

    /** 矮屏（逻辑高 320）整块账：弹层 8,52..232,314（高 262 = 内容高），内容列 14..226。 */
    @Test
    public void bandsTileTheShortCanvas() {
        l.layout(320, 0, 0);
        assertRect("panel", l.panel, 8, 52, 232, 314);
        assertRect("titleBar", l.titleBar, 14, 56, 226, 84);
        assertRect("next", l.next, 39, 286, 200, 308);   // 212 的 76% = 161，左右各余 25/26
        assertRect("card0", l.cards[0], 14, 90, 226, 150);
        assertRect("card1", l.cards[1], 14, 154, 226, 214);
        assertRect("card2", l.cards[2], 14, 218, 226, 278);
        assertEquals(41, ShopLayout.textLeft(l.cards[0]));   // 14 + 4 + 18 + 5，见 textWidth 那条
    }

    /**
     * 高屏上板子**不长高**：多出来的高度还给战场，卡高恒定 60、内容贴着面板底边排。
     *
     * <p>这条是"商店页面过大"的反证。板子铺满画布时，533 的屏上有 129px 是纯粹的遮罩盖住了
     * 玩家自己的战机和正在逼近的弹幕——玩家站在商店前面看不见战场，比按钮小一点严重得多。
     */
    @Test
    public void tallCanvasKeepsTheBattlefieldAboveTheSheet() {
        l.layout(533, 0, 0);
        assertEquals(ShopLayout.CARD_H, l.cards[0].height());
        assertEquals(ShopLayout.CARD_H, l.cards[2].height());
        assertRect("panel@533", l.panel, 8, 265, 232, 527);
        assertRect("next@533", l.next, 39, 499, 200, 521);
        assertRect("card0@533", l.cards[0], 14, 303, 226, 363);
        assertRect("card2@533", l.cards[2], 14, 431, 226, 491);
        assertEquals("内容高度就是面板高度，不长一分", ShopLayout.SHEET_H, l.panel.height());
        assertEquals("卡区不再需要居中补偿", l.titleBar.bottom + ShopLayout.POOL_TOP_GAP, l.cards[0].top);
        assertEquals("卡区不再需要居中补偿", l.cards[2].bottom, l.next.top - ShopLayout.POOL_BOTTOM_GAP);
        assertTrue("战场被板子吃到只剩一半: panel.top=" + l.panel.top, l.panel.top >= 533 / 3);
    }

    /** 弹层高度只由内容决定，与屏高无关（装得下的前提下）。 */
    @Test
    public void sheetHeightIsContentHeightOnEveryCanvasThatFits() {
        for (int h = 320; h <= 560; h += 11) {
            l.layout(h, 0, 0);
            if (h - 2 * ShopLayout.PANEL_GAP < ShopLayout.SHEET_H) continue;   // 装不下才该长满
            assertEquals("logicH=" + h + " 板子长高了", ShopLayout.SHEET_H, l.panel.height());
            assertEquals("logicH=" + h + " 底边没贴可用区下沿", h - ShopLayout.PANEL_GAP, l.panel.bottom);
        }
    }

    /**
     * 安全区的账：顶部的洞不该推动弹层，底部的洞才推。
     *
     * <p>弹层贴的是"画布底边 − 底部安全区"。挖机屏 safeTop=18 时板子该原地不动——把板子往下
     * 推（等于让刘海去压内容）是铺满式面板的老算法留下的毛病。
     */
    @Test
    public void safeInsetsMoveTheSheetOnlyFromTheBottom() {
        l.layout(533, 0, 0);
        int h0 = l.cards[0].height();
        int top0 = l.panel.top;
        l.layout(533, 18, 0);        // 本机实测：挖孔 52px / pxPerLogic 3.0 → 18 逻辑像素
        assertRect("panel@safeTop18", l.panel, 8, top0, 232, 527);
        assertRect("titleBar@safeTop18", l.titleBar, 14, 269, 226, 297);
        assertEquals("卡高不受安全区影响", h0, l.cards[0].height());
        assertEquals("图标边长必须是 9 格网格的整数倍", 0, ShopLayout.ICON % 9);
        l.layout(533, 0, 24);        // 手势条 72px / 3.0 = 24
        assertRect("panel@safeBottom24", l.panel, 8, top0 - 24, 232, 503);
    }

    /** 扫遍支持的屏高与安全区：任何一档都不许让卡片骑到标题或按钮上。 */
    @Test
    public void stackNeverCrossesIntoTheTitleOrTheButton() {
        for (int h = Screen.LOGIC_H_MIN; h <= Screen.LOGIC_H_MAX; h += 7) {
            for (int top = 0; top <= 32; top += 8) {
                for (int bottom = 0; bottom <= 48; bottom += 12) {
                    l.layout(h, top, bottom);
                    checkBands(h, top, bottom);
                }
            }
        }
    }

    private void checkBands(int h, int topInset, int bottomInset) {
        String where = "logicH=" + h + " safe=" + topInset + "/" + bottomInset + " ";
        int poolTop = l.titleBar.bottom + ShopLayout.POOL_TOP_GAP;
        int poolBottom = l.next.top - ShopLayout.POOL_BOTTOM_GAP;
        RectI first = l.cards[0];
        RectI last = l.cards[ShopLayout.CARDS - 1];
        assertTrue(where + "板子长进了顶部安全区: top=" + l.panel.top,
                l.panel.top >= topInset + ShopLayout.PANEL_GAP);
        assertTrue(where + "板子长过了可用区", l.panel.height() <= h - bottomInset - topInset - ShopLayout.PANEL_GAP);
        assertTrue(where + "第一张卡骑到标题上", first.top >= poolTop);
        assertTrue(where + "末张卡压到按钮上: " + last.bottom + " > " + poolBottom,
                last.bottom <= poolBottom);
        assertTrue(where + "标题掉出面板", l.titleBar.bottom <= l.panel.bottom);
        assertTrue(where + "出口按钮掉出面板", l.next.bottom <= l.panel.bottom - 4);
        int gap = l.cards[1].top - l.cards[0].bottom;
        assertEquals(where + "卡间距不恒定", gap, l.cards[2].top - l.cards[1].bottom);
        assertTrue(where + "间距被改大: " + gap, gap <= ShopLayout.CARD_GAP);
        if (first.height() == ShopLayout.CARD_H) {
            // 装得下才是完整卡：这时候三条带必须谁也不溢出谁的框
            assertTrue(where + "末行的说明带掉出卡框",
                    ShopLayout.row3Top(last) + ShopLayout.ROW3_H <= last.bottom);
        } else {
            assertTrue(where + "被压过的卡反而比标准卡高: " + first.height(),
                    first.height() < ShopLayout.CARD_H);
        }
        assertEquals(where + "三张卡同宽", first.width(), last.width());
    }

    /** 卡内三条带的账：加起来正好等于卡高减去上下留白，多一像素就会溢出到隔壁那张卡。 */
    @Test
    public void cardBandsFillTheCardWithoutSpilling() {
        l.layout(400, 0, 0);
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
     */
    @Test
    public void textColumnIsWideEnoughForTheLongestTip() {
        l.layout(400, 0, 0);
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
        l.layout(0, 0, 0);
        for (RectI c : l.cards) {
            assertTrue(c.right > c.left);
            assertTrue("卡框翻转了：" + c, c.bottom >= c.top);
        }
        assertTrue(l.next.bottom >= l.next.top);
    }
}
