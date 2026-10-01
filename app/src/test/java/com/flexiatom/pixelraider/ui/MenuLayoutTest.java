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
 * <p>这一页存在的理由是给成长页一个宿主，所以它最贵的两种错法都在"看得见的东西点不到"这一类：
 * 整页比最矮画布还高（按钮画得出来，命中框掉出画布），以及标题辉光盖住读数带（辉光是精灵，
 * 压上去没有描边可分辨，「芯片 12」会被读成糊在一起的一块）。
 */
public class MenuLayoutTest {

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
    public void buttonsSplitTheRowInEqualHalvesWithAnEightPixelGap() {
        MenuLayout.buttons(a, b);
        // 半宽 = (232 - 8 - 8) / 2 = 108
        assertRect("开始新一局", a, 8, 176, 116, 202);
        assertRect("成长", b, 124, 176, 232, 202);
        assertEquals(a.width(), b.width());
        assertEquals(a.height(), b.height());
        assertEquals(MenuLayout.BUTTON_GAP, b.left - a.right);
    }

    @Test
    public void grownHitBoxesGrowOnlyVerticallySoTheTwoButtonsCannotCollide() {
        // 108 宽的按钮在 34~45 这两档里根本不需要横向外扩，所以"点左边买到右边"在这页是几何不可能
        MenuLayout.buttons(a, b);
        Widgets.hitRect(a, 34, c);
        assertRect("参考机命中框", c, 8, 172, 116, 206);
        assertTrue(c.right <= b.left);
        Widgets.hitRect(a, 45, c);
        assertRect("长屏命中框", c, 8, 167, 116, 212);   // 19 的余数给下沿
        assertTrue("两枚按钮的命中框叠了 → 点开始可能进成长页", c.right <= b.left);
        assertEquals("横向不该被撑开", MenuLayout.LEFT, c.left);
    }

    @Test
    public void wholePageFitsTheShortestCanvasAndBothBandsStayInside() {
        assertEquals(202, MenuLayout.PAGE_H);
        assertEquals(MenuLayout.BUTTON_BOTTOM, MenuLayout.PAGE_H);
        assertTrue("整页比最矮画布还高，居中原点会变成负数",
                MenuLayout.PAGE_H <= Screen.LOGIC_H_MIN);
        int pageTop = (Screen.LOGIC_H_MIN - MenuLayout.PAGE_H) / 2;      // 59
        assertEquals(59, pageTop);
        MenuLayout.buttons(a, b);
        Widgets.hitRect(a, 45, c);                                       // 最宽的那档外扩
        assertTrue("命中框掉出画布底", pageTop + c.bottom <= Screen.LOGIC_H_MIN);
        assertTrue("命中框掉出画布顶", pageTop + c.top >= 0);
        // 页底不许留一条读不出意义的空地：按钮就是这页的最后一件事
        assertTrue(MenuLayout.BUTTON_BOTTOM >= MenuLayout.PAGE_H);
    }
}
