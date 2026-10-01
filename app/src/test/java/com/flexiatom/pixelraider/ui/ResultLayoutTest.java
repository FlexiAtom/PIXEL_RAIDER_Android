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
 * 结算页几何。期望值全部**手推写死**（规格 §九.20：不许用生产代码的公式重算一遍来"证明"它）。
 *
 * 竖排带区一共十条，靠肉眼在真机上发现"第 5 行数据卡压住按钮"已经太晚——那一刻按钮已经点不到了。
 */
public class ResultLayoutTest {

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
    public void tenBandsStackWithoutOverlap() {
        assertEquals(38, ResultLayout.TITLE_BOTTOM);
        assertEquals(42, ResultLayout.DIVIDER_Y);
        assertEquals(66, ResultLayout.SCORE_BOTTOM);
        assertEquals(84, ResultLayout.RECORD_BOTTOM);
        assertEquals(106, ResultLayout.RANK_BOTTOM);
        assertEquals(182, ResultLayout.RADAR_BOTTOM);
        assertEquals(251, ResultLayout.CARDS_BOTTOM);
        assertEquals(271, ResultLayout.ACH_BOTTOM);
        assertEquals(287, ResultLayout.COMPARE_BOTTOM);
        assertEquals(313, ResultLayout.BUTTON_BOTTOM);
        // 逐对之间都要留气口（≥2px），否则 1px 描边就糊成一块
        assertTrue(ResultLayout.TITLE_BOTTOM + 2 <= ResultLayout.DIVIDER_Y);
        assertTrue(ResultLayout.DIVIDER_Y + 2 <= ResultLayout.SCORE_TOP);
        assertTrue(ResultLayout.SCORE_BOTTOM + 2 <= ResultLayout.RECORD_TOP);
        assertTrue(ResultLayout.RECORD_BOTTOM + 2 <= ResultLayout.RANK_TOP);
        assertTrue(ResultLayout.RANK_BOTTOM + 2 <= ResultLayout.RADAR_TOP);
        assertTrue(ResultLayout.RADAR_BOTTOM + 2 <= ResultLayout.CARDS_TOP);
        assertTrue(ResultLayout.CARDS_BOTTOM + 2 <= ResultLayout.ACH_TOP);
        assertTrue(ResultLayout.ACH_BOTTOM + 2 <= ResultLayout.COMPARE_TOP);
        assertTrue(ResultLayout.COMPARE_BOTTOM + 2 <= ResultLayout.BUTTON_TOP);
        assertTrue(ResultLayout.TITLE_TOP >= 0);
        assertTrue("整块溢出战斗区，矮屏上按钮会被裁掉",
                ResultLayout.BUTTON_BOTTOM <= Screen.BATTLE_H);
    }

    @Test
    public void revealOrderMatchesTopToBottomOrder() {
        // 规格的揭示次序就是阅读次序：先露出的必须在上面，否则视线要上下跳
        float[] at = {
                RevealScript.startAt(RevealScript.TITLE),
                RevealScript.startAt(RevealScript.DIVIDER),
                RevealScript.startAt(RevealScript.SCORE),
                RevealScript.startAt(RevealScript.RECORD),
                RevealScript.startAt(RevealScript.OVERALL),
                RevealScript.startAt(RevealScript.RADAR),
                RevealScript.startAt(RevealScript.CARDS),
                RevealScript.startAt(RevealScript.ACHIEVEMENTS),
                RevealScript.startAt(RevealScript.COMPARE),
                RevealScript.startAt(RevealScript.BUTTONS),
        };
        int[] top = {
                ResultLayout.TITLE_TOP, ResultLayout.DIVIDER_Y, ResultLayout.SCORE_TOP,
                ResultLayout.RECORD_TOP, ResultLayout.RANK_TOP, ResultLayout.RADAR_TOP,
                ResultLayout.CARDS_TOP, ResultLayout.ACH_TOP, ResultLayout.COMPARE_TOP,
                ResultLayout.BUTTON_TOP,
        };
        for (int i = 1; i < at.length; i++) {
            assertTrue("第 " + i + " 段揭示更晚却画得更靠上", at[i] > at[i - 1]);
            assertTrue("第 " + i + " 段带区没往下走", top[i] >= top[i - 1]);
        }
    }

    @Test
    public void buttonsSplitTheRowInEqualHalvesWithAnEightPixelGap() {
        ResultLayout.buttons(a, b);
        // 半宽 = (232 - 8 - 8) / 2 = 108
        assertRect("重试", a, 8, 291, 116, 313);
        assertRect("返回", b, 124, 291, 232, 313);
        assertEquals(a.width(), b.width());
        assertEquals(a.height(), b.height());
        assertEquals(ResultLayout.BUTTON_GAP, b.left - a.right);
    }

    @Test
    public void grownButtonHitBoxesStillDoNotCollide() {
        ResultLayout.buttons(a, b);
        Widgets.hitRect(a, 34, c);            // 参考机的 48dp 下限
        assertRect("重试命中框", c, 8, 285, 116, 319);
        assertTrue("两枚按钮的命中框叠了 → 点重试可能返回菜单", c.right <= b.left);
        assertTrue("命中框掉出战斗区", c.bottom <= Screen.BATTLE_H);
    }

    @Test
    public void radarRowsShareOneColumnGrid() {
        ResultLayout.radarRowRect(0, a, b, c);
        assertRect("标签", a, 8, 113, 32, 125);
        assertRect("条体", b, 38, 117, 198, 122);
        assertRect("读数", c, 202, 113, 232, 125);
        ResultLayout.radarRowRect(4, a, b, c);
        assertRect("末行标签", a, 8, 169, 32, 181);
        assertEquals(5, b.height());
        assertTrue("末行掉出五维带", c.bottom <= ResultLayout.RADAR_BOTTOM);
        ResultLayout.radarRowRect(0, a, new RectI(), new RectI());
        int firstBottom = a.bottom;
        ResultLayout.radarRowRect(1, a, new RectI(), new RectI());
        assertTrue("相邻两行重叠", firstBottom <= a.top);
    }

    @Test
    public void achievementRowReusesTheRadarColumns() {
        ResultLayout.radarRowRect(0, a, b, c);
        RectI label = new RectI(), bar = new RectI(), value = new RectI();
        ResultLayout.achRowRect(label, bar, value);
        assertRect("成就标签", label, 8, 257, 32, 269);
        assertRect("成就条", bar, 38, 261, 198, 266);
        assertRect("成就读数", value, 202, 257, 232, 269);
        assertEquals(a.left, label.left);
        assertEquals(b.left, bar.left);
        assertEquals(c.right, value.right);
        assertTrue(label.top >= ResultLayout.ACH_TOP);
        assertTrue("成就行溢出自己的带", value.bottom <= ResultLayout.ACH_BOTTOM);
    }

    @Test
    public void cardRowsTileTheCardBandExactly() {
        ResultLayout.cardRowRect(0, a);
        assertRect("首行卡", a, 8, 186, 232, 199);
        ResultLayout.cardRowRect(RevealScript.CARD_ROWS - 1, b);
        assertRect("末行卡", b, 8, 238, 232, 251);
        assertEquals(ResultLayout.CARDS_BOTTOM, b.bottom);
        assertEquals("卡与条同数：一行对一条", ResultLayout.RADAR_ROWS, RevealScript.CARD_ROWS);
    }

    @Test
    public void barFillWidthClampsToTheTrackAndNeverReturnsNaN() {
        assertEquals(0, ResultLayout.barFillWidth(0f));
        assertEquals(160, ResultLayout.barFillWidth(1f));
        assertEquals(80, ResultLayout.barFillWidth(0.5f));
        assertEquals(0, ResultLayout.barFillWidth(-3f));
        assertEquals(160, ResultLayout.barFillWidth(9f));
        assertEquals(0, ResultLayout.barFillWidth(Float.NaN));
        ResultLayout.radarRowRect(0, new RectI(), b, new RectI());
        assertEquals("填充宽必须正好等于轨道宽", b.width(), ResultLayout.barFillWidth(1f));
        assertTrue("填充越过读数列", ResultLayout.BAR_X + ResultLayout.barFillWidth(1f) <= b.right);
    }

    @Test
    public void titleGlowStaysAboveTheScoreBand() {
        ResultLayout.titleGlowRect(a);
        assertRect("标题辉光", a, 8, 4, 232, 46);
        assertTrue("辉光顶出画布", a.top >= 0);
        assertTrue("辉光压到分数带", a.bottom <= ResultLayout.SCORE_TOP);
    }
}
