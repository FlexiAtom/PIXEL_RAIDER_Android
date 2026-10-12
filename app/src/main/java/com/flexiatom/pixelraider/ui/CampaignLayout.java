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

import com.flexiatom.pixelraider.core.RectI;
import com.flexiatom.pixelraider.game.CampaignBriefing;
import com.flexiatom.pixelraider.gfx.BitmapFont;
import com.flexiatom.pixelraider.plat.Screen;

/**
 * 战役选关页的全部**可测数字**（任务列表 + 简报视口 + 难度分段 + 键行）。池件
 * {@code campaign-data-tree} 批3-B 的版面半；内容在 {@link CampaignBriefing}，轨的算术在
 * {@link ScrollLayout}，这里只把它们**排在同一根竖轴上**。
 *
 * <p><b>固定页</b>（与存档页、结算页、成长页同类）：页高不随画布长高，居中原点由
 * {@code Screen.Metrics.pageTop(PAGE_H)} 给，这里一律是页内相对坐标，pageTop 只在
 * {@code CampaignScreen.layout} 加一次。
 *
 * <p><b>这根竖轴是一条等式，不是四个挑来的数</b>：
 * {@code 6 + 118 + 4 + 80 + 4 + 45 + 4 + 45 = 306 ≤ Screen.LOGIC_H_MIN(320)}，
 * 由测试把最后一格钉住。分配的理由只有两条，都在下面各自的字段上：
 * ① 两枚按钮带<b>按绘制尺寸就够触控下界</b>（{@link #CELL_H}），所以这一屏的命中区
 * 对它们<b>不外扩</b>——分段控件三格并排，外扩到 45 会互相吞掉相邻段；
 * ② 余量全给简报视口，因为它是这一屏唯一会长高的东西。
 *
 * <p><b>视口取 80 = 8×{@link BriefingLayout#LINE_STEP}</b>，这一格是<b>整行数的下界</b>与<b>校验有没有第二个答案</b>
 * 两者的交点：现文案八份都是 8 行（首行＋两枚空行＋背景 3＋目标 2，无折行）＝ 80 格，<b>正好装满、不溢出</b>；
 * 而他给的上限用满（背景 3＋目标 3）是 9 行 90 格，对 80 的视口<b>溢出 10px</b>。视口若取 90，连上限那一档
 * 也装得下，于是"会不会出条"这道校验永远只回答"否"——而他 2026-10-12 逐字说的正是
 * 「上限在代码里校验，但是为了是否出现滚动条而校验」：那道校验得有第二个答案可给，视口才准是 80。
 * 轨<b>照旧常驻</b>（他 10-13 逐字「所以是正常也超限？那就正常情况也用轨」），今天不溢出的那八份稿
 * 就画一条等于行程的拇指（{@code 80 − 2×9 = 62}，过 {@link ScrollLayout#MIN_THUMB}）。
 * 上限那一档拇指按 {@code 行程²/内容} 缩到 43、矮过 45——<b>不再是门槛</b>：拖的落点本来就是整个视口，
 * 拇指单抓抓不住也拖得动（{@link ScrollLayout#offsetForDrag}，他 10-13 裁的甲）。
 *
 * <p>这里不写"每行几格字"以外的字号真值：汉字档位归 {@link Md3}，简报档归
 * {@link BriefingLayout#GLYPH_H}，抄一份数字就等于给"改字阶忘了改行高"留缝。
 */
public final class CampaignLayout {

    public static final int LEFT = 8;
    public static final int RIGHT = Screen.LOGIC_W - LEFT;                   // 232
    public static final int PAD = 4;

    public static final int CONTENT_LEFT = LEFT + PAD;                       // 12
    /** 两枚按钮带的排布宽（简报那一侧不用它：文字出轨才算内缩，见 {@link BriefingLayout#TEXT_WIDTH}）。 */
    public static final int CONTENT_WIDTH = RIGHT - LEFT - 2 * PAD;          // 216

    /** 列表与页面顶边的缝：这一屏没有标题行（列表第一行就是「任务1」），缝留一格呼吸。 */
    public static final int TOP = 6;

    /** 任务行数 = 内容层的任务数 = 章数，不在版面抄一份 8。 */
    public static final int ROWS = CampaignBriefing.MISSIONS;                 // 8
    /**
     * 行高：一行 12px 汉字（烘焙贴图正好 12 格，{@code EmbeddedFontTest} 钉着这条）再加一格余量。
     * 再矮字身贴行底；再高则八行吃掉的竖轴要从视口出，而视口是这一屏唯一的读数区。
     */
    public static final int ROW_H = 13;
    public static final int ROW_GAP = 2;

    public static final int LIST_TOP = TOP;                                   // 6
    public static final int LIST_BOTTOM = LIST_TOP + ROWS * ROW_H + (ROWS - 1) * ROW_GAP;   // 124

    /** 三个带之间的缝（同一道算术的三个实例，所以只有一个数）。 */
    public static final int GROUP_GAP = 4;

    public static final int BRIEF_TOP = LIST_BOTTOM + GROUP_GAP;              // 128
    /** 视口高＝8 行整。两档读数（现文案装满／上限溢出 10）都在类注释里，测试钉它与 LINE_STEP 成套。 */
    public static final int VIEWPORT_H = 8 * BriefingLayout.LINE_STEP;        // 80
    public static final int BRIEF_BOTTOM = BRIEF_TOP + VIEWPORT_H;            // 208

    public static final int TIER_TOP = BRIEF_BOTTOM + GROUP_GAP;              // 212
    /**
     * 难度分段与键行共用的高度：{@code Screen.minTouchLogic} 的真机读数上界是 45
     * （口径同 {@link ScrollLayout#MIN_THUMB}，那里逐字写过为什么按最坏的一档钉）。
     * 这两带按<b>绘制尺寸</b>就够界，于是它们不需要外扩命中区——分段控件三格并排，外扩必互吞。
     */
    public static final int CELL_H = 45;
    public static final int TIER_BOTTOM = TIER_TOP + CELL_H;                  // 257

    public static final int KEY_TOP = TIER_BOTTOM + GROUP_GAP;                // 261
    public static final int KEY_BOTTOM = KEY_TOP + CELL_H;                    // 306

    /** 整页高：必须 ≤ {@code Screen.LOGIC_H_MIN}，否则最矮画布上这一页装不下自己的命中框。 */
    public static final int PAGE_H = KEY_BOTTOM;                              // 306

    /** 难度三格／键行两格共用的格间距。 */
    public static final int CELL_GAP = 4;
    public static final int TIER_CELLS = 3;
    public static final int KEY_CELLS = 2;

    private CampaignLayout() { }

    // ---- 矩形 -----------------------------------------------------------------------------

    /** 第 {@code i} 个任务行；越界钳到表内（画面与命中读同一份，见 {@link SaveLayout#rowRect} 那句理由）。 */
    public static void rowRect(int i, RectI out) {
        int k = i < 0 ? 0 : (i >= ROWS ? ROWS - 1 : i);
        int top = LIST_TOP + k * (ROW_H + ROW_GAP);
        out.set(LEFT, top, RIGHT, top + ROW_H);
    }

    /**
     * 简报容器（轨道那一侧贴 {@link BriefingLayout#TRACK_LEFT}，文字折行宽取
     * {@link BriefingLayout#TEXT_WIDTH}——两者都不在这里重算，轨的算术只有一个真源）。
     */
    public static void briefRect(RectI out) {
        out.set(LEFT, BRIEF_TOP, RIGHT, BRIEF_BOTTOM);
    }

    /** 难度分段第 {@code i} 格（0 休闲 / 1 普通 / 2 终结，序＝{@code Tier} 声明序）。 */
    public static void tierCell(int i, RectI out) {
        Widgets.gridCell(CONTENT_LEFT, TIER_TOP, CONTENT_WIDTH, CELL_H,
                TIER_CELLS, 1, CELL_GAP, i, out);
    }

    /** 键行第 {@code i} 格：0 开始任务 / 1 返回。 */
    public static void keyCell(int i, RectI out) {
        Widgets.gridCell(CONTENT_LEFT, KEY_TOP, CONTENT_WIDTH, CELL_H,
                KEY_CELLS, 1, CELL_GAP, i, out);
    }

    // ---- 行内列位 --------------------------------------------------------------------------

    /** 行内文字左界（行底从 LEFT 铺到 RIGHT，字不贴行底边）。 */
    public static int labelLeft(RectI row) {
        return row.left + PAD;                                                 // 12
    }

    /** n 位点阵的宽度：定宽 × n，整串不留尾隙（口径同 {@code BitmapFont.textWidth} 与 {@link SaveLayout}）。 */
    public static int digitWidth(int n) {
        return n <= 0 ? 0 : n * BitmapFont.CELL_W - 1;                         // 1 位 = 5
    }

    /**
     * 星区名左界：「任务」两字 + 一格缝 + <b>定宽两位</b>数字 + 格间距。
     *
     * <p>数字定宽是为了这一列不随任务号位数挪——八任务今天是一位，但行数上限来自
     * {@link CampaignBriefing#MISSIONS}，加到两位数时这一列不该整列跳一格（存档页那条定宽同一口径）。
     */
    public static int zoneLeft(RectI row) {
        return labelLeft(row) + 2 * Md3.PX_LABEL + DrawKit.GAP + digitWidth(2) + CELL_GAP;   // 53
    }

    /** 行内 12px 汉字的中心线（贴图 12 格，行 13 格，上下各余半格）。 */
    public static int lineCenterY(RectI row) {
        return row.top + ROW_H / 2;
    }
}
