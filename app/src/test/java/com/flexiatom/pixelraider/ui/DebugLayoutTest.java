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
import org.junit.Test;

/**
 * 调试页几何。期望值一律**手推写死**（照 {@link PauseLayoutTest} 的口径）：弹层要在各种逻辑高上
 * 把四行排得不压不裂，靠"公式跑出来的结果"验公式等于没验。
 */
public class DebugLayoutTest {

    private final DebugLayout l = new DebugLayout();

    private static void assertRect(String what, RectI r, int left, int top, int right, int bottom) {
        assertEquals(what + " left", left, r.left);
        assertEquals(what + " top", top, r.top);
        assertEquals(what + " right", right, r.right);
        assertEquals(what + " bottom", r.bottom, bottom);
    }

    /** 高屏（逻辑高 430、无安全区）整块账：弹层高 = CONTENT_H，底边贴可用区下沿，行从下往上锚。 */
    @Test
    public void tilesTheBottomSheetFromTheBottomUp() {
        l.layout(430, 0, 0);
        // panel = (8, 430-6-124, 232, 430-6) = (8, 300, 232, 424)；内容列 14..226
        assertRect("panel", l.panel, 8, 300, 232, 424);
        assertRect("titleBar", l.titleBar, 14, 304, 226, 332);
        assertRect("toggle", l.toggle, 14, 338, 226, 362);
        assertRect("victory", l.victory, 14, 368, 118, 390);
        assertRect("settle", l.settle, 122, 368, 226, 390);
        // back 宽 = round(212*0.76) = 161，奇数宽余项落在左：39..200
        assertRect("back", l.back, 39, 396, 200, 418);
    }

    /** 开关行占满内容宽；两枚作弊键等宽对分；返回键是那唯一偏窄的居中主操作。 */
    @Test
    public void rowsHaveTheirIntendedWidths() {
        l.layout(430, 0, 0);
        assertEquals(l.contentWidth, l.toggle.width());
        assertEquals(l.victory.width(), l.settle.width());
        // (212-4)/2 = 104 每半格，装得下四字标签（48px）
        assertEquals(104, l.victory.width());
        assertEquals(161, l.back.width());
        assertTrue("返回键比作弊键窄，视觉位阶靠宽度也靠配色", l.back.width() < l.contentWidth);
    }

    /**
     * 可用区装不下整块弹层时（厚安全区叠矮画布）：面板钳到可用高，但**返回键仍贴底**——
     * 行从下往上锚，最先被裁掉的是标题而不是出口。出口是离开这块面板的唯一退路。
     */
    @Test
    public void clampsToUsableHeightButKeepsTheExitAtTheBottom() {
        // bottom = 320-100-6 = 214；usable = 214-100-6 = 108 < CONTENT_H(124)
        l.layout(320, 100, 100);
        int bottom = 320 - 100 - DebugLayout.PANEL_GAP;
        int usable = bottom - 100 - DebugLayout.PANEL_GAP;
        assertEquals("面板顶边由可用高钳出", bottom - usable, l.panel.top);
        assertEquals("面板底边仍贴可用区下沿", bottom, l.panel.bottom);
        assertEquals("返回键底边贴面板内沿（-边框-留白）",
                l.panel.bottom - DebugLayout.BORDER - DebugLayout.PAD, l.back.bottom);
    }

    /** 四行两两不压：这是"先认绘制框、再认外扩框"两遍命中解析的前提。 */
    @Test
    public void drawnRowsNeverOverlap() {
        for (int h = 320; h <= 560; h++) {
            for (int st = 0; st <= 24; st += 8) {
                l.layout(h, st, st);
                RectI[] rows = {l.titleBar, l.toggle, l.victory, l.settle, l.back};
                for (int i = 0; i < rows.length; i++) {
                    for (int j = i + 1; j < rows.length; j++) {
                        assertTrue("h=" + h + " st=" + st + " 第 " + i + " 与第 " + j + " 行压上了",
                                !overlaps(rows[i], rows[j]));
                    }
                }
            }
        }
    }

    private static boolean overlaps(RectI a, RectI b) {
        return Math.min(a.right, b.right) > Math.max(a.left, b.left)
                && Math.min(a.bottom, b.bottom) > Math.max(a.top, b.top);
    }
}
