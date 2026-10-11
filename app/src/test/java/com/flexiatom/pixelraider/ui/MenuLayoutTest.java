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
package com.flexiatom.pixelraider.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.flexiatom.pixelraider.core.RectI;
import com.flexiatom.pixelraider.plat.Screen;
import org.junit.Test;

/**
 * 主菜单页几何。期望值全部**手推写死**（规格 §九.20：不许拿生产代码的公式重算一遍来"证明"它）。
 *
 * <p>这一页存在的理由是给成长页与存档页一个宿主，所以它最贵的两种错法都在"看得见的东西点不到"这一类：
 * 整页比最矮画布还高（按钮画得出来，命中框掉出画布），以及标题辉光盖住读数带（辉光是精灵，
 * 压上去没有描边可分辨，「芯片 12」会被读成糊在一起的一块）。
 */
public class MenuLayoutTest {

    private final RectI a = new RectI();
    private final RectI b = new RectI();
    private final RectI c = new RectI();
    private final RectI d = new RectI();
    private final RectI e = new RectI();

    private static void assertRect(String what, RectI r, int left, int top, int right, int bottom) {
        assertEquals(what + " left", left, r.left);
        assertEquals(what + " top", top, r.top);
        assertEquals(what + " right", right, r.right);
        assertEquals(what + " bottom", bottom, r.bottom);
    }

    @Test
    public void threeBandsStackWithAirBetweenThem() {
        assertEquals(64, MenuLayout.TITLE_TOP);
        assertEquals(98, MenuLayout.TITLE_BOTTOM);
        assertEquals(112, MenuLayout.META_TOP);
        assertEquals(128, MenuLayout.META_BOTTOM);
        assertEquals(176, MenuLayout.BUTTON_TOP);
        assertEquals(202, MenuLayout.BUTTON_BOTTOM);
        assertTrue(MenuLayout.TITLE_TOP >= 0);
        // 每对之间留 ≥2px，否则 1px 描边糊成一块
        assertTrue(MenuLayout.TITLE_BOTTOM + 2 <= MenuLayout.META_TOP);
        assertTrue(MenuLayout.META_BOTTOM + 2 <= MenuLayout.BUTTON_TOP);
        MenuLayout.titleRect(a);
        assertRect("标题带", a, 8, 64, 232, 98);
        MenuLayout.metaRect(b);
        assertRect("读数带", b, 8, 112, 232, 128);
    }

    @Test
    public void titleGlowClearsTheReadoutBand() {
        MenuLayout.titleGlowRect(a);
        assertRect("标题辉光", a, 8, 56, 232, 106);
        assertTrue("辉光顶出画布", a.top >= 0);
        assertTrue("辉光压到读数带：芯片余额会在余光里糊成一块",
                a.bottom + 2 <= MenuLayout.META_TOP);
    }

    @Test
    public void buttonsSplitTheRowIntoThreeEqualCellsWithAFourPixelGap() {
        MenuLayout.buttons(a, b, c);
        // 一格 = (232 - 8 - 2×4) / 3 = 72；216 能被 3 整除，所以没有余数要交给哪一格
        assertRect("开始新一局", a, 8, 176, 80, 202);
        assertRect("成长", b, 84, 176, 156, 202);
        assertRect("存档", c, 160, 176, 232, 202);
        assertEquals(a.width(), b.width());
        assertEquals(b.width(), c.width());
        assertEquals(a.height(), b.height());
        assertEquals(MenuLayout.BUTTON_GAP, b.left - a.right);
        assertEquals(MenuLayout.BUTTON_GAP, c.left - b.right);
        assertEquals(4, MenuLayout.BUTTON_GAP);
        assertEquals(3, MenuLayout.BUTTON_COLS);
    }

    @Test
    public void grownHitBoxesGrowOnlyVerticallySoTheThreeButtonsCannotCollide() {
        // 72 宽的按钮在 34~45 这两档里根本不需要横向外扩，所以"点开始进成长"在这页是几何不可能
        MenuLayout.buttons(a, b, c);
        Widgets.hitRect(a, 34, d);
        assertRect("参考机命中框", d, 8, 172, 80, 206);
        assertTrue(d.right <= b.left);
        Widgets.hitRect(a, 45, d);
        assertRect("长屏命中框", d, 8, 167, 80, 212);   // 19 的余数给下沿
        assertTrue("两枚按钮的命中框叠了 → 点开始可能进成长页", d.right <= b.left);
        assertEquals("横向不该被撑开", MenuLayout.LEFT, d.left);
        // 中间那一格被左右两格的外扩夹在中间：三格的命中框互压一条就不成立，而这一页有三个出口
        Widgets.hitRect(b, 45, d);
        Widgets.hitRect(b, 45, e);
        assertRect("本格命中框", d, 84, 167, 156, 212);
        assertTrue("左邻的命中框压进了本格", a.right < e.left);
        assertTrue("本格的命中框压到了右邻", e.right < c.left);
        // 最右那一格同样不许越过页边：外扩只发生在纵向
        Widgets.hitRect(c, 45, d);
        assertRect("末格命中框", d, 160, 167, 232, 212);
        assertTrue(d.right <= MenuLayout.RIGHT);
    }

    @Test
    public void wholePageFitsTheShortestCanvasAndBothBandsStayInside() {
        assertEquals(202, MenuLayout.PAGE_H);
        assertEquals(MenuLayout.BUTTON_BOTTOM, MenuLayout.PAGE_H);
        assertTrue("整页比最矮画布还高，居中原点会变成负数",
                MenuLayout.PAGE_H <= Screen.LOGIC_H_MIN);
        int pageTop = (Screen.LOGIC_H_MIN - MenuLayout.PAGE_H) / 2;      // 59
        assertEquals(59, pageTop);
        MenuLayout.buttons(a, b, c);
        Widgets.hitRect(a, 45, d);                                       // 最宽的那档外扩
        assertTrue("命中框掉出画布底", pageTop + d.bottom <= Screen.LOGIC_H_MIN);
        assertTrue("命中框掉出画布顶", pageTop + d.top >= 0);
        // 页底不许留一条读不出意义的空地：按钮就是这页的最后一件事
        assertTrue(MenuLayout.BUTTON_BOTTOM >= MenuLayout.PAGE_H);
    }
}
