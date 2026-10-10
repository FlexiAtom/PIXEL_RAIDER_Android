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
import com.flexiatom.pixelraider.game.SaveSlots;
import com.flexiatom.pixelraider.gfx.BitmapFont;
import com.flexiatom.pixelraider.plat.Screen;

/**
 * 存档页的全部**可测数字**（快照列表：50 个槽、分页、三个动作、翻页）。
 *
 * <p>与结算页、成长页同一类：**固定页**。页高不随画布长高，居中原点由
 * {@code Screen.Metrics.pageTop(PAGE_H)} 给，这里一律是页内相对坐标，命中框在
 * {@code SaveScreen.layout} 里加一次 pageTop 换成屏幕坐标——两套坐标只在那一处换算。
 * 理由与那两页相同：这一屏的信息量等于"一页几行"，把多出来的高度灌进行距只会让每行中间
 * 裂出一块读不出意义的空地。
 *
 * <p><b>每页几行是反解出来的，不是挑的</b>：{@link #ROWS_PER_PAGE} 是
 * {@link #pageH(int)} 压在 {@code Screen.LOGIC_H_MIN} 之下能取到的最大行数（8），
 * 于是页高、页数、末页行数全是同一个不等式的推论。池件 {@code pause-save-entry}
 * 待裁第 7 格裁的是"分页不换滚动"，每页几行归版面按最矮画布倒推。
 *
 * <p>这里引用 {@link BitmapFont#CELL_W}（{@code gfx}）不算越层：宽度是编译期常量，而
 * "点阵每字符 6 格"这条真源住在字体类里。抄一个 6 进版面，就等于给"换字号忘了改列宽"
 * 留缝——而这一屏的列是定宽的，缝一开整行横向错位。
 */
public final class SaveLayout {

    public static final int LEFT = 8;
    public static final int RIGHT = Screen.LOGIC_W - LEFT;                  // 232
    /** 行内左右内缩（行底是容器，文字不贴容器边）。 */
    public static final int PAD = 4;

    public static final int TITLE_TOP = 10;
    public static final int TITLE_BOTTOM = 38;
    /** 占用读数与提示语那一条（标题之下、列表之上）。 */
    public static final int META_TOP = 42;
    public static final int META_BOTTOM = 56;

    public static final int LIST_TOP = 60;
    /**
     * 行高：两条文字带（12px 汉字 + 7px 点阵）各占 12 格中心高，带间距 2，上下各留 2。
     * 再矮一档汉字与点阵就叠笔了；再高则一页装不下 8 行（见 {@link #ROWS_PER_PAGE}）。
     */
    public static final int ROW_H = 24;
    public static final int ROW_GAP = 2;

    public static final int ACTION_GAP = 4;
    public static final int ACTION_H = 20;
    public static final int NAV_GAP = 4;
    public static final int NAV_H = 18;

    /** 动作行与翻页行都是三格，间隙与暂停页第三行同一个数（同一道算术的两个实例）。 */
    public static final int CELLS = 3;
    public static final int CELL_GAP = 4;

    /** 列表长度 = 槽位账本的上限，不在版面抄一份 50（改上限的人只改一处）。 */
    public static final int SLOTS = SaveSlots.CAPACITY;

    /** 内容列：行底从 LEFT 铺到 RIGHT，文字在 {@code LEFT+PAD .. RIGHT-PAD} 里排。 */
    public static final int CONTENT_LEFT = LEFT + PAD;                      // 12
    public static final int CONTENT_RIGHT = RIGHT - PAD;                    // 228
    public static final int CONTENT_WIDTH = CONTENT_RIGHT - CONTENT_LEFT;   // 216

    private SaveLayout() { }

    // ---- 纵向账（rows → 页高）---------------------------------------------------------------

    /** rows 行的列表底边；0 行时列表塌成一条线（不返回负高）。 */
    public static int listBottom(int rows) {
        return rows <= 0 ? LIST_TOP : LIST_TOP + rows * ROW_H + (rows - 1) * ROW_GAP;
    }

    /** rows 行时整页高：列表 + 动作行 + 翻页行。 */
    public static int pageH(int rows) {
        return listBottom(rows) + ACTION_GAP + ACTION_H + NAV_GAP + NAV_H;
    }

    /** 画布高 {@code limit} 装得下几行——取满足 {@link #pageH(int)} 的最大值，至少 1。 */
    public static int rowsFitting(int limit) {
        int rows = 0;
        while (pageH(rows + 1) <= limit) rows++;
        return Math.max(1, rows);
    }

    /** 反解结果：8 行（9 行要 338 格，越过 320 的最矮画布）。 */
    public static final int ROWS_PER_PAGE = rowsFitting(Screen.LOGIC_H_MIN);

    /** 整页高：必须 ≤ {@code Screen.LOGIC_H_MIN}，否则最矮画布上这一页装不下自己的命中框。 */
    public static final int PAGE_H = pageH(ROWS_PER_PAGE);                  // 312

    /** 槽一共要几页列完（末页可能不满）。 */
    public static final int PAGES = (SLOTS + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE;   // 7

    public static final int ACTION_TOP = listBottom(ROWS_PER_PAGE) + ACTION_GAP;   // 270
    public static final int ACTION_BOTTOM = ACTION_TOP + ACTION_H;                 // 290
    public static final int NAV_TOP = ACTION_BOTTOM + NAV_GAP;                     // 294
    public static final int NAV_BOTTOM = NAV_TOP + NAV_H;                          // 312

    // ---- 分页算术（照 ShopLayout 那条"页号越界就地钳"的先例）--------------------------------

    public static int clampPage(int page) {
        return page < 0 ? 0 : (page >= PAGES ? PAGES - 1 : page);
    }

    /** 这一页第一张槽在全架里的下标。 */
    public static int pageStart(int page) {
        return clampPage(page) * ROWS_PER_PAGE;
    }

    /** 这一页实际摆几行（末页 2 行，其余 8 行）。 */
    public static int shownOn(int page) {
        return Math.max(0, Math.min(ROWS_PER_PAGE, SLOTS - pageStart(page)));
    }

    /**
     * 槽 {@code slot} 摆不摆在第 {@code page} 页上。存档页那三枚改数据的键读它：看不见目标的那一格
     * 不许是活的（越界的那一侧是末页——那里只有 2 行，{@code < start + shown} 而非 {@code <=} 才是对的）。
     */
    public static boolean onPage(int page, int slot) {
        int start = pageStart(page);
        return slot >= start && slot < start + shownOn(page);
    }

    // ---- 矩形 -----------------------------------------------------------------------------

    public static void titleRect(RectI out) {
        out.set(LEFT, TITLE_TOP, RIGHT, TITLE_BOTTOM);
    }

    public static void metaRect(RectI out) {
        out.set(CONTENT_LEFT, META_TOP, CONTENT_RIGHT, META_BOTTOM);
    }

    /** 这一页第 {@code i} 行；越界钳到页内（画面与命中读同一份，不让一处自己数一遍）。 */
    public static void rowRect(int i, RectI out) {
        int k = i < 0 ? 0 : (i >= ROWS_PER_PAGE ? ROWS_PER_PAGE - 1 : i);
        int top = LIST_TOP + k * (ROW_H + ROW_GAP);
        out.set(LEFT, top, RIGHT, top + ROW_H);
    }

    /** 动作行三格：0 载入 / 1 存档 / 2 删除。 */
    public static void actionCell(int i, RectI out) {
        Widgets.gridCell(CONTENT_LEFT, ACTION_TOP, CONTENT_WIDTH, ACTION_H,
                CELLS, 1, CELL_GAP, i, out);
    }

    /** 翻页行三格：0 上一页 / 1 返回 / 2 下一页。 */
    public static void navCell(int i, RectI out) {
        Widgets.gridCell(CONTENT_LEFT, NAV_TOP, CONTENT_WIDTH, NAV_H,
                CELLS, 1, CELL_GAP, i, out);
    }

    // ---- 行内列位（定宽，见 digitWidth 那条注释）--------------------------------------------

    /** n 位点阵的宽度：定宽 × n，整串不留尾隙（口径同 {@code BitmapFont.textWidth}）。 */
    public static int digitWidth(int n) {
        return n <= 0 ? 0 : n * BitmapFont.CELL_W - 1;
    }

    /**
     * meta 那条左端读数「已存 50/50」的右界（五个字符：两位占用、斜杠、两位上限）。
     *
     * <p>它存在的唯一理由是**提示语贴右**：那一句最长时不许压到这个读数上，而"最长"是一句
     * 文案的长度，住在 {@code SaveScreen} 里——排版边界留在这里，由测试把两边对起来，
     * 免得改文案的人要去猜这一格画到哪个像素。
     */
    public static final int META_READOUT_RIGHT =
            CONTENT_LEFT + 2 * Md3.PX_LABEL + DrawKit.GAP + digitWidth(5);            // 67

    /** 「槽」+ 两位槽号。 */
    public static final int COL_SLOT_W = Md3.PX_LABEL + DrawKit.GAP + digitWidth(2);   // 25
    /** 「战役 / 无尽」：两字模式名。 */
    public static final int COL_MODE_W = 2 * Md3.PX_LABEL;                              // 24
    /** 「休闲 / 普通 / 终结」：三字表里每档都是两字。 */
    public static final int COL_TIER_W = 2 * Md3.PX_LABEL;                              // 24
    /** 「波」+ 三位（无尽模式打到 100 波以上时列宽不涨，见 {@link #COL_SLOT_W} 那条定宽理由）。 */
    public static final int COL_WAVE_W = Md3.PX_LABEL + DrawKit.GAP + digitWidth(3);    // 31

    public static int slotLeft(RectI row) {
        return row.left + PAD;                                                          // 12
    }

    /** 模式列，同时是空格那行的「空」落点（空槽没有模式可画，位置留给状态词）。 */
    public static int modeLeft(RectI row) {
        return slotLeft(row) + COL_SLOT_W + CELL_GAP;                                   // 41
    }

    public static int tierLeft(RectI row) {
        return modeLeft(row) + COL_MODE_W + 2;                                          // 67
    }

    public static int waveLeft(RectI row) {
        return tierLeft(row) + COL_TIER_W + CELL_GAP;                                   // 95
    }

    /** 第一带与第二带的右界共用：分数贴右、时间也贴右，两带的右缘才在同一条竖线上。 */
    public static int textRight(RectI row) {
        return row.right - PAD;                                                         // 228
    }

    /** 第一带中心线（12px 汉字：占行内 2..14）。 */
    public static int line1CenterY(RectI row) {
        return row.top + 8;
    }

    /** 第二带中心线（7px 点阵：占行内 15..22）。 */
    public static int line2CenterY(RectI row) {
        return row.top + 18;
    }

    /** 「分」标签的左界上界：分数最宽（标签 + 十一位）时它该停在波列之后，不许压过去。 */
    public static int scoreLabelLeftFloor(RectI row) {
        return waveLeft(row) + COL_WAVE_W + CELL_GAP;
    }
}
