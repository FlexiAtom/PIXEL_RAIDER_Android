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
 * 成长页几何。期望值全部**手推写死**（规格 §九.20：不许拿生产代码的公式重算一遍来"证明"它）。
 *
 * <p>这一页最贵的两种错法都发生在"没在那台机器上跑过"的时候：某一行掉出整页底（按钮点不到，
 * 但按钮画得出来），以及价格列把等级点格挤出行框（每买一级都看见右边的东西挪位）。
 */
public class GrowthLayoutTest {

    private final RectI a = new RectI();
    private final RectI b = new RectI();
    private final RectI c = new RectI();

    private static void assertRect(String what, RectI r, int left, int top, int right, int bottom) {
        assertEquals(what + " left", left, r.left);
        assertEquals(what + " top", top, r.top);
        assertEquals(what + " right", right, r.right);
        assertEquals(what + " bottom", bottom, r.bottom);
    }

    @Test
    public void rowCountComesFromTheTableNotFromThePage() {
        assertEquals("行数就是成长树的项数（这一页不许自带一份计数）",
                Balance.Growth.ITEMS, GrowthLayout.ROWS);
        assertEquals("点格数就是等级上限", Balance.Growth.MAX_LEVEL, GrowthLayout.DOT_MAX);
        assertEquals(6, GrowthLayout.ROWS);
        assertEquals(5, GrowthLayout.DOT_MAX);
        assertEquals(28, GrowthLayout.DOTS_W);
    }

    @Test
    public void sixRowsTileTheListBandWithoutOverlap() {
        GrowthLayout.rowRect(0, a);
        assertRect("首行", a, 8, 62, 232, 88);
        GrowthLayout.rowRect(GrowthLayout.ROWS - 1, b);
        assertRect("末行", b, 8, 212, 232, 238);
        assertEquals("末行底边必须正好等于清单带底", GrowthLayout.LIST_BOTTOM, b.bottom);
        assertEquals(238, GrowthLayout.LIST_BOTTOM);
        for (int i = 1; i < GrowthLayout.ROWS; i++) {
            GrowthLayout.rowRect(i - 1, a);
            GrowthLayout.rowRect(i, b);
            assertTrue("第 " + i + " 行压上一行", a.bottom + GrowthLayout.ROW_GAP <= b.top);
        }
    }

    @Test
    public void wholePageFitsTheShortestCanvasAndItsExitStaysInside() {
        assertEquals(274, GrowthLayout.PAGE_H);
        assertEquals(GrowthLayout.BUTTON_BOTTOM, GrowthLayout.PAGE_H);
        assertTrue("整页比最矮画布还高，居中原点会变成负数",
                GrowthLayout.PAGE_H <= Screen.LOGIC_H_MIN);
        // 三条带区之间都要留气口
        assertTrue(GrowthLayout.TITLE_BOTTOM + 2 <= GrowthLayout.META_TOP);
        assertTrue(GrowthLayout.META_BOTTOM + 2 <= GrowthLayout.LIST_TOP);
        assertTrue(GrowthLayout.LIST_BOTTOM + 2 <= GrowthLayout.BUTTON_TOP);
        // 最矮画布上居中之后的绝对位置：外扩过的出口不许掉出画布
        int pageTop = (Screen.LOGIC_H_MIN - GrowthLayout.PAGE_H) / 2;     // 23
        GrowthLayout.backRect(a);
        Widgets.hitRect(a, 45, b);                                       // 长屏那台机的 48dp
        assertEquals(50, a.left);
        assertRect("出口外扩框", b, 50, 241, 190, 286);
        assertTrue("出口命中框掉出画布底", pageTop + b.bottom <= Screen.LOGIC_H_MIN);
        assertTrue("标题命中框掉出画布顶", pageTop + GrowthLayout.TITLE_TOP >= 0);
    }

    @Test
    public void exitHitBoxDoesNotStealTheLastRowOnTheReferenceFloor() {
        GrowthLayout.rowRect(GrowthLayout.ROWS - 1, a);
        GrowthLayout.backRect(b);
        Widgets.hitRect(b, 34, c);            // 参考机的 48dp 下限
        assertRect("出口的参考机外扩框", c, 50, 246, 190, 280);
        assertTrue("出口外扩框顶进最后一行 → 点返回可能花掉一枚芯片",
                c.top >= a.bottom);
        // 45 那一档是**已知会互压**的（外扩框 241 < 行底 238 的反面）：这正是 GrowthScreen.under
        // 第二遍 pass 要先认出口的理由，不是要修掉的 bug。这里把两个数写下来，改 order 的人看得见。
        Widgets.hitRect(b, 45, c);
        Widgets.hitRect(a, 45, a);
        assertEquals(241, c.top);
        assertEquals(248, a.bottom);
        assertTrue("45 档上行与出口的外扩框应当是重叠的（否则那条出口优先就没有理由）",
                c.top < a.bottom);
    }

    @Test
    public void columnsKeepTheEffectReadoutClearOfTheDotsAndCost() {
        GrowthLayout.rowRect(2, a);           // 中心 135
        assertEquals(12, GrowthLayout.iconLeft(a));
        assertEquals(36, GrowthLayout.nameLeft(a));
        assertEquals(228, GrowthLayout.costRight(a));
        // 价格列 [191,228]（定宽 37）、点格列 [157,185]、效果列右沿 149
        assertEquals(157, GrowthLayout.dotsLeft(a));
        assertEquals(149, GrowthLayout.effectRight(a));
        // 名字列不许长进效果列：最宽的效果读数也要留得下
        assertTrue("效果读数压到名字列",
                GrowthLayout.effectRight(a) - GrowthLayout.EFFECT_W_MAX
                        >= GrowthLayout.nameLeft(a) + GrowthLayout.NAME_W);
        // 点格整列不许长进价格列：价格从 costRight − COST_W 起画，压上去就是两行字叠在一起
        assertTrue("点格列压到价格列",
                GrowthLayout.dotsLeft(a) + GrowthLayout.DOTS_W
                        <= GrowthLayout.costRight(a) - GrowthLayout.COST_W);
        // 图标整只落在行内（中心 135 − 半边 18/2 = 126）
        assertEquals(126, GrowthLayout.iconTop(a));
        assertTrue(GrowthLayout.iconTop(a) + GrowthLayout.ICON <= a.bottom);
    }

    @Test
    public void dotCellsStayInsideTheirColumn() {
        GrowthLayout.rowRect(0, a);
        GrowthLayout.dotCellRect(a, 0, b);
        assertRect("首格", b, 157, 73, 161, 77);
        GrowthLayout.dotCellRect(a, GrowthLayout.DOT_MAX - 1, b);
        assertRect("末格", b, 181, 73, 185, 77);
        assertEquals("格高就是 DOT 边长", GrowthLayout.DOT, b.height());
        assertTrue("末格越出点格列", b.right <= GrowthLayout.dotsLeft(a) + GrowthLayout.DOTS_W);
        assertTrue("点格列压到价格列",
                b.right <= GrowthLayout.costRight(a) - GrowthLayout.COST_W);
    }
}
