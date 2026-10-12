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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.flexiatom.pixelraider.core.RectI;
import com.flexiatom.pixelraider.game.CampaignBriefing;
import com.flexiatom.pixelraider.game.Tier;
import com.flexiatom.pixelraider.gfx.BitmapFont;
import com.flexiatom.pixelraider.plat.Screen;
import java.util.Arrays;
import org.junit.Test;

/**
 * 战役选关页的几何与静态面。期望值**手推写死**（照 {@link SaveLayoutTest} 的规矩）。
 *
 * <p>这一屏要钉的不是"挤不挤"，是<b>那根竖轴是一条等式而不是四个挑来的数</b>：页高 316 由
 * {@code 6 + 118 + 4 + 90 + 4 + 45 + 4 + 45} 加出来，最后一格必须 ≤ {@code Screen.LOGIC_H_MIN}。
 * 算错一格的画面是"最矮的那台机器上最后一行键的命中框掉到页外"——截图上页是完整的，只有按下去
 * 才发现那枚键点不动。
 *
 * <p>与 {@link SaveScreenTest} 同一条边界：不 {@code new CampaignScreen(...)}。构造会拉起
 * {@link DrawKit} 的 Paint，而单测跑在未 mock 的 android 桩上（{@code returnDefaultValues = false}），
 * 类一加载就炸；绘制与命中那两半只能走真机。这里因此只读 {@link CampaignLayout}（纯算术）、
 * {@link CampaignScreen} 的 {@code public static} 动作码，折行用 stub 的等宽度量——口径与
 * {@link BriefingLayoutTest} 同一条。
 */
public class CampaignLayoutTest {

    /** stub 每码位 8px＝8px 档汉字面宽（真机 ASCII 更窄，同一句只会折得更少，这里是最坏的一档）。 */
    private static final TextWrap.Width PER_CHAR =
            text -> 8 * text.codePointCount(0, text.length());

    /** 手推的列位：内容列 216 = 3×69 + 2×4 + 1（末列吸收余数），与 {@link SaveLayoutTest} 同一次推导。 */
    private static final int COL0_L = 12, COL0_R = 81;
    private static final int COL1_L = 85, COL1_R = 154;
    private static final int COL2_L = 158, COL2_R = 228;

    private final RectI r = new RectI();
    private final RectI s = new RectI();

    private static void assertRect(String what, RectI box, int left, int top, int right, int bottom) {
        assertEquals(what + " left", left, box.left);
        assertEquals(what + " top", top, box.top);
        assertEquals(what + " right", right, box.right);
        assertEquals(what + " bottom", bottom, box.bottom);
    }

    /**
     * 整条竖轴：四带三相间距加出来正好是页高，而页高装得进最矮的那块画布。
     *
     * <p>这条是这一屏的<b>总闸</b>。它红掉的形态是"页高与末带底边不一致"——{@code Game.render} 拿
     * {@code pageTop(PAGE_H)} 平移整页，而 {@code CampaignScreen.layout} 把同一批矩形平移到屏幕坐标，
     * 两者不同源时最矮画布上键行会伸出页外。
     */
    @Test
    public void verticalAxisIsOneEquation() {
        int listBand = CampaignLayout.LIST_BOTTOM - CampaignLayout.LIST_TOP;
        int sum = CampaignLayout.TOP + listBand + CampaignLayout.GROUP_GAP
                + CampaignLayout.VIEWPORT_H + CampaignLayout.GROUP_GAP + CampaignLayout.CELL_H
                + CampaignLayout.GROUP_GAP + CampaignLayout.CELL_H;
        assertEquals(6 + 118 + 4 + 90 + 4 + 45 + 4 + 45, sum);
        assertEquals(sum, CampaignLayout.PAGE_H);
        assertTrue("最矮画布装不下整页：" + CampaignLayout.PAGE_H + " > " + Screen.LOGIC_H_MIN,
                CampaignLayout.PAGE_H <= Screen.LOGIC_H_MIN);
    }

    /** 八行 × 行高 13 ＋ 七道缝 × 2 = 118：列表那一带的高是数出来的，不是挑来的。 */
    @Test
    public void listBandAbsorbsEightRowsAndSevenGutters() {
        assertEquals(CampaignBriefing.MISSIONS, CampaignLayout.ROWS);
        assertEquals(8, CampaignLayout.ROWS);
        assertEquals(118, CampaignLayout.LIST_BOTTOM - CampaignLayout.LIST_TOP);
        assertEquals(CampaignLayout.LIST_BOTTOM, CampaignLayout.BRIEF_TOP - CampaignLayout.GROUP_GAP);
    }

    /** 视口是 9 行整：上限那一稿（首行＋两空行＋背景 3＋目标 3）填满它，也正好滚不动。 */
    @Test
    public void viewportIsExactlyNineWholeRows() {
        assertEquals(9 * BriefingLayout.LINE_STEP, CampaignLayout.VIEWPORT_H);
        assertEquals(90, CampaignLayout.VIEWPORT_H);
        CampaignLayout.briefRect(r);
        assertEquals(CampaignLayout.VIEWPORT_H, r.height());
        assertEquals(CampaignLayout.BRIEF_BOTTOM, r.bottom);

        String[] cap = new String[9];
        Arrays.fill(cap, "字");
        int contentH = BriefingLayout.contentHeight(cap, PER_CHAR);
        assertEquals(CampaignLayout.VIEWPORT_H, contentH);
        assertEquals(0, ScrollLayout.maxOffset(CampaignLayout.VIEWPORT_H, contentH));
        // 不溢出那一档拇指等于行程（钳位一）；行程自己过可点下界
        assertEquals(ScrollLayout.travel(CampaignLayout.VIEWPORT_H),
                ScrollLayout.thumb(CampaignLayout.VIEWPORT_H, contentH));
        assertTrue(ScrollLayout.travel(CampaignLayout.VIEWPORT_H) >= ScrollLayout.MIN_THUMB);
    }

    /**
     * 现文案八份简报，每一份在<b>最坏度量</b>（每码位 8px）下都不出轨 ⇒ 这一屏今天拖不动。
     *
     * <p>这条钉的是"轨常驻而内容为 8 行"这一<b>现状</b>，不是永久规则：他 10-12 的上限（背景 3 行／
     * 目标 3 条）用满是 9 行 = 90，正好等于视口。有人把上限改宽或把句子写长时这条会红，红的读法是
     * "文案开始溢出了，回来看视口要不要长"，不是"折行坏了"。
     */
    @Test
    public void everyCurrentBriefingFitsTheViewport() {
        for (int m = 1; m <= CampaignBriefing.MISSIONS; m++) {
            int contentH = BriefingLayout.contentHeight(CampaignBriefing.lines(m), PER_CHAR);
            assertTrue("任务 " + m + " 的简报溢出：" + contentH + " > " + CampaignLayout.VIEWPORT_H,
                    contentH <= CampaignLayout.VIEWPORT_H);
        }
    }

    /** 行矩形：首行贴 TOP，末行底边吻 LIST_BOTTOM；越界钳到表内（画面与命中读同一份）。 */
    @Test
    public void rowsTileTheListAndClampAtBothEnds() {
        CampaignLayout.rowRect(0, r);
        assertRect("第 0 行", r, 8, 6, 232, 19);
        CampaignLayout.rowRect(7, s);
        assertRect("第 7 行", s, 8, 111, 232, 124);
        CampaignLayout.rowRect(-1, r);
        assertEquals(6, r.top);
        CampaignLayout.rowRect(99, r);
        assertEquals(124, r.bottom);
        // 行与行之间留的就是 ROW_GAP 那一格，不许重叠（重叠的外扩框会让两行共用一次命中）
        CampaignLayout.rowRect(0, r);
        CampaignLayout.rowRect(1, s);
        assertEquals(CampaignLayout.ROW_GAP, s.top - r.bottom);
    }

    /**
     * 难度带的格数、档名表长、{@link Tier} 声明序三者必须同源。
     *
     * <p>错一格的画面是"档位列写着终结、这一局实际按休闲跑"（{@code tierCell(i)} 的第 i 格与
     * {@code Tier.at(i)} 是两条独立声明的序）。默认选中那一格必须是<b>普通</b>：它的乘子全 1.00，
     * 于是一进来直接按「开始任务」与今天那条{@code resetRun(Mode.CAMPAIGN)}逐字节相同。
     */
    @Test
    public void tierCellsMatchTheTierTableAndEnum() {
        Tier[] all = {Tier.CASUAL, Tier.NORMAL, Tier.TERMINAL};
        assertEquals(all.length, CampaignLayout.TIER_CELLS);
        assertEquals(SaveScreen.L_TIER.length, CampaignLayout.TIER_CELLS);
        for (int i = 0; i < all.length; i++) {
            assertSame("第 " + i + " 格与 Tier.at 不同源", all[i], Tier.at(i));
            assertEquals(i, Tier.ordinalOf(all[i]));
        }
        assertEquals(1, Tier.ordinalOf(Tier.NORMAL));
        assertEquals("普通", SaveScreen.L_TIER[Tier.ordinalOf(Tier.NORMAL)]);
    }

    /** 难度带三格：手推的列位（69/69/70，末列吸收余数），上下界就是 TIER_TOP/BOTTOM。 */
    @Test
    public void tierBandIsThreeCellsOfTheContentColumn() {
        CampaignLayout.tierCell(0, r);
        assertRect("难度第一格", r, COL0_L, CampaignLayout.TIER_TOP, COL0_R, CampaignLayout.TIER_BOTTOM);
        CampaignLayout.tierCell(1, r);
        assertRect("难度第二格", r, COL1_L, CampaignLayout.TIER_TOP, COL1_R, CampaignLayout.TIER_BOTTOM);
        CampaignLayout.tierCell(2, r);
        assertRect("难度第三格", r, COL2_L, CampaignLayout.TIER_TOP, COL2_R, CampaignLayout.TIER_BOTTOM);
    }

    /** 键行两格：106/110（末列吸收 4 格余数），右界同样吻在内容列右缘 228。 */
    @Test
    public void keyBandIsTwoCells() {
        CampaignLayout.keyCell(0, r);
        assertRect("开始任务", r, 12, CampaignLayout.KEY_TOP, 118, CampaignLayout.KEY_BOTTOM);
        CampaignLayout.keyCell(1, r);
        assertRect("返回", r, 122, CampaignLayout.KEY_TOP, 228, CampaignLayout.KEY_BOTTOM);
    }

    /**
     * 两枚按钮带<b>按绘制尺寸</b>就够触控下界，所以这一屏的命中框对它们不外扩。
     *
     * <p>45 是 {@code Screen.minTouchLogic} 的真机读数上界（口径同 {@link ScrollLayout#MIN_THUMB}）。
     * 这一条是"不外扩"那个决定的算术前提：它一旦低于 45，不外扩就变成"点不动"，得回头把带加高。
     */
    @Test
    public void buttonBandsClearTheTouchFloorWithoutGrowing() {
        assertEquals(45, CampaignLayout.CELL_H);
        assertTrue(CampaignLayout.CELL_H >= ScrollLayout.MIN_THUMB);
        CampaignLayout.tierCell(0, r);
        assertEquals(CampaignLayout.CELL_H, r.height());
        CampaignLayout.keyCell(0, r);
        assertEquals(CampaignLayout.CELL_H, r.height());
    }

    /**
     * 星区名那一列不随任务号位数挪：左界 = 行内文字位 + 「任务」两字 + 一格缝 + <b>定宽两位</b> + 格间距。
     *
     * <p>数字按两位算而不是按实际位数算，是为了让第 1 行与第 8 行的星区名对齐在同一列上；八任务今天
     * 全是一位数，把上限（{@link CampaignBriefing#MISSIONS}）加到两位时这一列不该整列跳一格。
     */
    @Test
    public void zoneColumnIsFixedForEveryRow() {
        CampaignLayout.rowRect(0, r);
        assertEquals(53, CampaignLayout.zoneLeft(r));
        assertEquals(CampaignLayout.labelLeft(r) + 2 * Md3.PX_LABEL + DrawKit.GAP
                + CampaignLayout.digitWidth(2) + CampaignLayout.CELL_GAP, CampaignLayout.zoneLeft(r));
        for (int i = 0; i < CampaignLayout.ROWS; i++) {
            CampaignLayout.rowRect(i, r);
            assertEquals("第 " + i + " 行的星区名列与首行不同源", 53, CampaignLayout.zoneLeft(r));
            // 行右界容得下「任务」＋定宽数字＋最长星区名（八字 @12px），不越到行外
            assertTrue(CampaignLayout.zoneLeft(r) + 8 * Md3.PX_LABEL <= r.right - CampaignLayout.PAD);
        }
        assertEquals(5, CampaignLayout.digitWidth(1));
        assertEquals(BitmapFont.CELL_W * 2 - 1, CampaignLayout.digitWidth(2));
        assertEquals(11, CampaignLayout.digitWidth(2));
        assertEquals(0, CampaignLayout.digitWidth(0));
        assertEquals(0, CampaignLayout.digitWidth(-3));
    }

    /** 简报容器与文字带、轨的相对位置：出轨之后才是轨，轨之后不再写字。 */
    @Test
    public void briefTextBandStopsBeforeTheTrack() {
        CampaignLayout.briefRect(r);
        assertEquals(CampaignLayout.LEFT, r.left);
        assertEquals(CampaignLayout.RIGHT, r.right);
        assertEquals(BriefingLayout.TEXT_LEFT, CampaignLayout.CONTENT_LEFT);
        assertTrue(BriefingLayout.TEXT_LEFT + BriefingLayout.TEXT_WIDTH <= BriefingLayout.TRACK_LEFT);
        assertTrue(BriefingLayout.TRACK_LEFT < CampaignLayout.RIGHT);
        assertEquals(CampaignLayout.RIGHT - ScrollLayout.TRACK_W, BriefingLayout.TRACK_LEFT);
    }

    /**
     * 三枚动作码互不相同、{@code NONE} 是 0：{@code pressUp} 用一串 if 比它们，两枚撞码就等于
     * 其中一条出口永远走不到——而这里撞的是「开始任务」与「返回」。
     */
    @Test
    public void actionCodesDoNotCollide() {
        int[] codes = {CampaignScreen.ACTION_NONE, CampaignScreen.ACTION_BACK,
                CampaignScreen.ACTION_START};
        assertEquals(0, CampaignScreen.ACTION_NONE);
        for (int i = 0; i < codes.length; i++) {
            for (int j = i + 1; j < codes.length; j++) {
                assertNotEquals("第 " + i + " 与第 " + j + " 枚动作码相同", codes[i], codes[j]);
            }
        }
    }
}
