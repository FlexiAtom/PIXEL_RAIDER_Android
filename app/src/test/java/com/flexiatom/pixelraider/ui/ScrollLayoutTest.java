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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.flexiatom.pixelraider.core.RectI;
import org.junit.Test;

/**
 * 常驻轨的算术。期望值**手推写死**（照 {@link SaveLayoutTest} 的规矩）。
 *
 * <p>钉死的是四张脸：满装那一格到底溢几个 px、钳位（内容不超出时不许有"假行程"）、
 * 拇指够不够点（45 是 {@code Screen.minTouchLogic} 实测上界）、以及偏移↔拇指位置的双向换算
 * 在同一档里能来回。这四格错一格的画面都长一样——差的是"拖不动""拖出空地""按下没反应"。
 */
public class ScrollLayoutTest {

    /** 保守口径的简报视口（键行按命中 45 那一档从 320 画布里剩下的数）。 */
    private static final int V = 84;
    /** 满装内容高：首行 1 + 背景 ≤3 + 目标 ≤3，按 8px 字 + 2px 行距 = 每行步进 10。 */
    private static final int C_FULL = 90;

    @Test
    public void theRatioHeHeld() {
        assertEquals("两枚箭头的总高就是 3×轨宽（箭头高:宽 = 1.5:1）",
                3 * ScrollLayout.TRACK_W, 2 * ScrollLayout.ARROW_H);
        assertEquals("拇指宽恒等于轨宽", ScrollLayout.TRACK_W,
                new RectI(0, 0, ScrollLayout.TRACK_W, 1).width());
    }

    @Test
    public void fullBriefingOverflowsSixPixelsAndStillHasATouchableThumb() {
        assertEquals("行程 = 视口 − 上下两枚箭头", 66, ScrollLayout.travel(V));
        assertEquals("MO 式 行程²/内容 = 66×66/90 ≈ 48.4 → 48", 48, ScrollLayout.thumb(V, C_FULL));
        assertEquals("满装真正要滚的量只有 6px", 6, ScrollLayout.maxOffset(V, C_FULL));
        assertEquals("拇指自己在行程里还能走 18px", 18, ScrollLayout.thumbTravel(V, C_FULL));
        assertTrue("拇指 48 ≥ 可点下界 45（这一档是轨宽只能到 6 的原因）",
                ScrollLayout.thumbTouchable(V, C_FULL));
    }

    @Test
    public void thumbBottomKissesTheDownArrowAtFullScroll() {
        RectI thumb = new RectI();
        ScrollLayout.thumbRect(218, V, C_FULL, 0, thumb);
        assertEquals("偏移 0 时拇指贴上箭头的下沿", 9, thumb.top);
        ScrollLayout.thumbRect(218, V, C_FULL, ScrollLayout.maxOffset(V, C_FULL), thumb);
        assertEquals("偏到底时拇指底边正好吻上下箭头顶（V−ARROW_H）", V - ScrollLayout.ARROW_H, thumb.bottom);
        assertEquals("轨宽", 6, thumb.width());
        assertEquals("拇指高", 48, thumb.height());
    }

    @Test
    public void offsetAndThumbPositionRoundTrip() {
        assertEquals(0, ScrollLayout.offsetForThumbTop(V, C_FULL, 9));
        assertEquals(6, ScrollLayout.offsetForThumbTop(V, C_FULL, 27));
        assertEquals(27, ScrollLayout.thumbTop(V, C_FULL, 6));
        assertEquals("越界的读数钳回来，不让拇指画出轨", 6, ScrollLayout.clampOffset(999, V, C_FULL));
        assertEquals(0, ScrollLayout.clampOffset(-5, V, C_FULL));
    }

    @Test
    public void nonOverflowingContentGetsNoPhantomTravel() {
        // 钳位这一格是常驻轨新召进来的状态：8 行的稿（C=80）根本不溢出。
        // MO 的式子在 C ≤ V 域会算出 54 的拇指、11.6 的假行程——拖得动，但拖不出任何新内容。
        assertEquals(0, ScrollLayout.maxOffset(V, 80));
        assertEquals("内容不超出时拇指占满行程", ScrollLayout.travel(V), ScrollLayout.thumb(V, 80));
        assertEquals("占满就是拖不动", 0, ScrollLayout.thumbTravel(V, 80));
        assertEquals("拖不动时任何落点都换回 0 偏移", 0, ScrollLayout.offsetForThumbTop(V, 80, 40));
    }

    @Test
    public void looseViewportShowsTheSameFullBriefingWithoutScrolling() {
        // 宽松口径（键行按 MO 绘制高 24 ⇒ 视口 105）下满装 90 根本不溢出：轨在、拖不动。
        assertEquals(87, ScrollLayout.travel(105));
        assertEquals(87, ScrollLayout.thumb(105, C_FULL));
        assertEquals(0, ScrollLayout.maxOffset(105, C_FULL));
    }

    @Test
    public void theOldThirteenLineCapWouldHavePutAnUntouchableThumbOnScreen() {
        // 旧的 13 行上限（C=130）作废，留这一条当证据：那一档下拇指只有 34，点不着。
        assertEquals(34, ScrollLayout.thumb(V, 130));
        assertFalse("轨宽 6 也救不了 130 的内容高——上限收到 9 行才是可点的前提",
                ScrollLayout.thumbTouchable(V, 130));
        assertEquals(46, ScrollLayout.maxOffset(V, 130));
    }

    @Test
    public void narrowerViewportShrinksTheThumbMonotonically() {
        assertEquals(48, ScrollLayout.thumb(V, C_FULL));
        assertEquals(36, ScrollLayout.thumb(V, 120));
        assertEquals(30, ScrollLayout.thumb(V, 145));
    }

    @Test
    public void aViewportTooShortForTwoArrowsCollapsesToNoTravel() {
        assertEquals(0, ScrollLayout.travel(2 * ScrollLayout.ARROW_H));
        assertEquals(0, ScrollLayout.thumb(18, C_FULL));
    }

    @Test
    public void theTrackEatsWidthPermanentlyAndLeavesTwentySixGlyphsPerLine() {
        assertEquals("整列 224 减掉轨宽 = 出轨后的可视宽", 218, ScrollLayout.visibleWidth(224));
        assertEquals("再减左右各 4 的内缩 = 折行量的那一档", 210, ScrollLayout.wrapWidth(224, 4));
        assertEquals("8px 档每行装得下 26 个汉字", 26, ScrollLayout.wrapWidth(224, 4) / 8);
        assertEquals("12px 档每行 17 个", 210 / 12, 17);
    }
}
