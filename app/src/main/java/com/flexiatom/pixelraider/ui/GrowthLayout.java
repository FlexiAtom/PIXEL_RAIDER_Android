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

import com.flexiatom.pixelraider.core.RectI;
import com.flexiatom.pixelraider.game.Balance;
import com.flexiatom.pixelraider.plat.Screen;

/**
 * 成长页（局外成长树）的全部**可测数字**（规格 §五：六项 ×5 级；消费入口按 {@code ShopRun} 类头
 * 那句「在主菜单的「成长」页上点」落在这一页）。
 *
 * <p>与结算页同一类：**固定页**。带区不随画布长高——多出来的高度还给背景，不还给内容，
 * 因为这一页的信息量等于六行，把它拉到 560 高只会让每行之间多出读不出意义的空地。
 * 居中原点由 {@code Screen.Metrics.pageTop(PAGE_H)} 给；这里的坐标全是这一页自己的相对坐标，
 * 命中框在 {@code layout} 里加一次 pageTop 换成屏幕坐标，两套坐标只在那一处换算。
 *
 * <p>{@link #ROWS} 与 {@link #DOT_MAX} 直接取自 {@code Balance.Growth}，不像
 * {@link ShopLayout#CARDS} 那样抄一个字面量再由测试钉相等：那一枚是**版面数**（一次上架几张卡
 * 归版面管），这两枚是**数据**（树有几项、每项几级由表说了算）。抄一份进版面就等于给
 * "改表忘改面板"留一条缝——而缝的两边一边能买、一边画不出来。
 */
public final class GrowthLayout {

    public static final int LEFT = 8;
    public static final int RIGHT = Screen.LOGIC_W - LEFT;              // 232

    public static final int ROWS = Balance.Growth.ITEMS;                // 6
    public static final int DOT_MAX = Balance.Growth.MAX_LEVEL;         // 5

    public static final int TITLE_TOP = 10;
    public static final int TITLE_BOTTOM = 38;
    /** 钱包与累计投入那一行（标题之下、六行清单之上）。 */
    public static final int META_TOP = 42;
    public static final int META_BOTTOM = 56;

    public static final int LIST_TOP = 62;
    public static final int ROW_H = 26;
    public static final int ROW_GAP = 4;
    public static final int LIST_BOTTOM = LIST_TOP + ROWS * ROW_H + (ROWS - 1) * ROW_GAP;   // 238

    public static final int BUTTON_TOP = LIST_BOTTOM + 14;                                    // 252
    public static final int BUTTON_H = 22;
    public static final int BUTTON_BOTTOM = BUTTON_TOP + BUTTON_H;                            // 274
    public static final int BUTTON_W = 140;

    /** 整页高：必须 ≤ {@code Screen.LOGIC_H_MIN}，否则最矮画布上这一页装不下自己的命中框。 */
    public static final int PAGE_H = BUTTON_BOTTOM;

    public static final int PAD = 4;
    /** 9×9 网格 ×2，与商店卡同档（规格 §三：整数倍是硬线）。 */
    public static final int ICON = 18;
    public static final int ICON_NAME_GAP = 6;
    /** 两项汉字的名字列（攻击 / 射速 / 护盾…，全部 2 字）。 */
    public static final int NAME_W = 24;

    public static final int DOT = 4;
    public static final int DOT_GAP = 2;
    public static final int DOTS_W = DOT_MAX * DOT + (DOT_MAX - 1) * DOT_GAP;                 // 28
    public static final int COST_GAP = 6;
    public static final int EFFECT_GAP = 8;
    /**
     * 价格列的定宽：两位点阵（2×6−1=11）+ 缝 2 + 两个 12px 汉字 = 37。
     * 定宽的理由不是"整齐"，是**等级点格不能横向跳动**——按列位算的话，价格从 3 涨到 11
     * 会把点格整体左移 6 格，玩家每买一级都看见右边的东西挪了位。
     */
    public static final int COST_W = 11 + 2 + 24;
    /** 最宽的一档是「+10点」：三位点阵 17 + 缝 2 + 一个 12px 单位 = 31。 */
    public static final int EFFECT_W_MAX = 17 + 2 + 12;

    private GrowthLayout() { }

    public static void titleRect(RectI out) {
        out.set(LEFT, TITLE_TOP, RIGHT, TITLE_BOTTOM);
    }

    public static void metaRect(RectI out) {
        out.set(LEFT, META_TOP, RIGHT, META_BOTTOM);
    }

    public static void rowRect(int i, RectI out) {
        int top = LIST_TOP + i * (ROW_H + ROW_GAP);
        out.set(LEFT, top, RIGHT, top + ROW_H);
    }

    public static int iconLeft(RectI row) {
        return row.left + PAD;
    }

    public static int iconTop(RectI row) {
        return row.centerY() - ICON / 2;
    }

    public static int nameLeft(RectI row) {
        return iconLeft(row) + ICON + ICON_NAME_GAP;
    }

    public static int costRight(RectI row) {
        return row.right - PAD;
    }

    /**
     * 点格列贴在**价格列的左边界**之外，而不是贴在画布右边距之外。
     *
     * <p>差别看着只有 {@link #COST_W}，但它正是"每买一级都看见右边的东西挪位"的那个开关：
     * 价格按右沿对齐、列宽定死，于是它的**起点**恒等于 {@code costRight − COST_W}；点格只有整列
     * 停在这条线以左，才不会被「芯片 11」压在下面。少了这一减，点格落在 191 到 222 之间，
     * 而价格从 191 起画——两列直接重叠。
     */
    public static int dotsLeft(RectI row) {
        return costRight(row) - COST_W - COST_GAP - DOTS_W;
    }

    /** 每级效果读数贴右（在主信息列与点格之间留一条固定的空带，视线不用找起点）。 */
    public static int effectRight(RectI row) {
        return dotsLeft(row) - EFFECT_GAP;
    }

    public static void dotCellRect(RectI row, int i, RectI out) {
        int x = dotsLeft(row) + i * (DOT + DOT_GAP);
        int top = row.centerY() - DOT / 2;
        out.set(x, top, x + DOT, top + DOT);
    }

    /** 唯一出口：返回主菜单。整页只有一枚按钮，所以它居中、不给等分。 */
    public static void backRect(RectI out) {
        int x = (Screen.LOGIC_W - BUTTON_W) / 2;
        out.set(x, BUTTON_TOP, x + BUTTON_W, BUTTON_BOTTOM);
    }
}
