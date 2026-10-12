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

import java.util.Arrays;
import org.junit.Test;

/**
 * 简报块行数与可见窗口的算术。
 *
 * <p>stub 每码位 8px＝8px 档汉字面宽，于是 {@link BriefingLayout#TEXT_WIDTH}（210）
 * 正好容 26 字，行数能用手算复核。真机那侧走 {@code gfx.TextCache} 的度量，ASCII 更窄，
 * 同一句只会折得更少——这里钉的是规则，不是某一句文案的长度。
 */
public class BriefingLayoutTest {

    private static final TextWrap.Width PER_CHAR =
            text -> 8 * text.codePointCount(0, text.length());

    /** 保守预算解出来的视口高（列表 120 + 难度 59 + 键行 45 + 三条形间距 12 之后剩的那一截）。 */
    private static final int VIEWPORT_H = 84;
    /** 上限用满的那一稿：首行 1 + 空 1 + 背景 3 + 空 1 + 目标 3。当前文案只写到目标 2 行。 */
    private static final int FULL_ROWS = 9;

    private static String han(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append('字');
        return sb.toString();
    }

    /** FULL_ROWS 行、每行都不折行的稿——行数只由行高决定，用来量"满装"那一档。 */
    private static String[] fullRows() {
        String[] rows = new String[FULL_ROWS];
        Arrays.fill(rows, han(10));
        return rows;
    }

    /** 折行宽是"整列 − 常驻轨 − 左右内缩"，三个数各住一处，这里对一次。 */
    @Test
    public void textBandSitsInsideTheTrack() {
        assertEquals(12, BriefingLayout.TEXT_LEFT);
        assertEquals(210, BriefingLayout.TEXT_WIDTH);
        assertEquals(226, BriefingLayout.TRACK_LEFT);
        assertEquals(26, BriefingLayout.TEXT_WIDTH / PER_CHAR.of("字"));
    }

    /** 26 字恰好一行、27 字折成 26＋1；空串仍占一格——简报那两枚空行就靠它付行距。 */
    @Test
    public void rowsFlattenIntoTheLinesTheyActuallyDraw() {
        String[] lines = BriefingLayout.lines(new String[]{han(26), han(27), ""}, PER_CHAR);
        assertEquals(4, lines.length);
        assertEquals(26, lines[0].length());
        assertEquals(26, lines[1].length());
        assertEquals(1, lines[2].length());
        assertEquals("", lines[3]);
    }

    /** 内容高按上屏行数算，不按源串数：三枚源行折出 4 行 ⇒ 40px，数源串会报 30px。 */
    @Test
    public void contentHeightCountsDrawnLinesNotSourceRows() {
        assertEquals(40, BriefingLayout.contentHeight(new String[]{han(26), han(27), ""}, PER_CHAR));
        assertEquals(30, BriefingLayout.contentHeight(new String[]{han(10), han(10), han(10)}, PER_CHAR));
    }

    /** 满装九行＝90px，84 的视口溢出 6px——轨存在的唯一理由就是这 6px。 */
    @Test
    public void fullBriefingOverflowsBySixPixelsOnly() {
        int contentH = BriefingLayout.contentHeight(fullRows(), PER_CHAR);
        assertEquals(90, contentH);
        assertEquals(6, ScrollLayout.maxOffset(VIEWPORT_H, contentH));
    }

    /** 行位就是"行距 × 行号 − 偏移"，与轨的偏移共用同一个数。 */
    @Test
    public void lineTopSubtractsTheOffset() {
        assertEquals(30, BriefingLayout.lineTop(3, 0));
        assertEquals(24, BriefingLayout.lineTop(3, 6));
        assertEquals(-10, BriefingLayout.lineTop(0, 10));
    }

    /** 84 不是行距的整数倍：静止与滚满都摊到 9 行，最后一行只露 4px 字身，裁不裁归绘制端。 */
    @Test
    public void visibleWindowKeepsNineRowsAtBothEnds() {
        assertEquals(0, BriefingLayout.visibleFrom(0));
        assertEquals(FULL_ROWS, BriefingLayout.visibleTo(0, VIEWPORT_H, FULL_ROWS));
        assertEquals(0, BriefingLayout.visibleFrom(6));
        assertEquals(FULL_ROWS, BriefingLayout.visibleTo(6, VIEWPORT_H, FULL_ROWS));
    }

    /** 偏移滚到一整格行距，第一行才真的消失（底边与视口上沿重合＝零面积，不画）。 */
    @Test
    public void aRowOnlyLeavesWhenItsWholePitchIsGone() {
        assertEquals(0, BriefingLayout.visibleFrom(9));
        assertEquals(1, BriefingLayout.visibleFrom(10));
    }

    /**
     * 视口取行距整数倍（80）时半行不再出现；它原本的代价是"拇指缩到 43、低于可点下界 45"，
     * 而这一格已被他 2026-10-13 逐字「我的建议是允许按着视口区域拖动」作废——拖的落点是整个视口，
     * 拇指单抓抓不住不再决定拖不拖得动。⇒ <b>80 那一形没有代价了</b>，84 那形仍留着半行。
     * 取 80 是<b>我</b>读他这句读出来的（他没写数），3-B 落地前可推翻。
     */
    @Test
    public void rowPitchViewportCostsThumbTouchability() {
        int contentH = BriefingLayout.contentHeight(fullRows(), PER_CHAR);
        assertEquals(8, BriefingLayout.visibleTo(0, 80, FULL_ROWS));
        assertEquals(8, BriefingLayout.visibleTo(10, 80, FULL_ROWS) - BriefingLayout.visibleFrom(10));
        assertEquals(43, ScrollLayout.thumb(80, contentH));
        assertEquals(48, ScrollLayout.thumb(VIEWPORT_H, contentH));
        assertFalse(ScrollLayout.thumbTouchable(80, contentH));
        assertTrue(ScrollLayout.thumbTouchable(VIEWPORT_H, contentH));
        assertEquals("拇指抓不住那一档，按着视口拖照样拖满", 10,
                ScrollLayout.offsetForDrag(80, contentH, 0, -10));
    }
}
