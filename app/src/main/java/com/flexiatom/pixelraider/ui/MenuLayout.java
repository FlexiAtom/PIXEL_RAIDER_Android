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
import com.flexiatom.pixelraider.plat.Screen;

/**
 * 主菜单页几何（纯算术，JVM 可测）。
 *
 * <p>这一页存在的理由只有一条：**给消费侧做宿主**——成长页（芯片的出口）与存档页（续玩的出口）都住在这里。
 * 规格把成长入口写在「主菜单的『成长』页」，而结算页那颗「返回菜单」此前按下等于重开，2026-09-28 已被真接线
 * 替换（{@code Game.openMenu} → {@code push(MENU)}）；存档页那枚入口是 2026-10-11 他补的（「**补进主菜单，
 * 本件内做掉**」）。所以这一页刻意保持薄——只放标题、两个读数和三枚真出口，未落地的档（设置、图鉴）连禁用格
 * 都不摆：一颗灰着的按钮比没有按钮更糟，那是规格点名的"点了没反应"那一类。
 *
 * <p>与 {@link GrowthLayout} 同一类：固定页，不随画布长高。
 */
public final class MenuLayout {

    public static final int LEFT = 8;
    public static final int RIGHT = Screen.LOGIC_W - LEFT;          // 232

    public static final int TITLE_TOP = 64;
    public static final int TITLE_BOTTOM = 98;
    /** 标题底光的余量：辉光比字身大一圈，超出带区但不要出页。 */
    public static final int TITLE_GLOW_PAD = 8;

    public static final int META_TOP = 112;
    public static final int META_BOTTOM = 128;

    public static final int BUTTON_TOP = 176;
    public static final int BUTTON_H = 26;
    public static final int BUTTON_BOTTOM = BUTTON_TOP + BUTTON_H;   // 202
    /** 三格一行的间隙取 4，与暂停页第三行／存档页动作行同一个数（同一道算术的三个实例）。 */
    public static final int BUTTON_COLS = 3;
    public static final int BUTTON_GAP = 4;

    public static final int PAGE_H = BUTTON_BOTTOM;

    private MenuLayout() { }

    public static void titleRect(RectI out) {
        out.set(LEFT, TITLE_TOP, RIGHT, TITLE_BOTTOM);
    }

    public static void titleGlowRect(RectI out) {
        out.set(LEFT, TITLE_TOP - TITLE_GLOW_PAD, RIGHT, TITLE_BOTTOM + TITLE_GLOW_PAD);
    }

    public static void metaRect(RectI out) {
        out.set(LEFT, META_TOP, RIGHT, META_BOTTOM);
    }

    /**
     * 一行三格：0「开始新一局」（filled，主操作）、1「成长」、2「存档」（text，二级）。
     * 主次靠有没有底区分，不靠大小；三格等宽是因为这一行没有"第二排"可以藏，档位只够由底色给。
     */
    public static void buttons(RectI runOut, RectI growthOut, RectI saveOut) {
        Widgets.gridCell(LEFT, BUTTON_TOP, RIGHT - LEFT, BUTTON_H, BUTTON_COLS, 1, BUTTON_GAP, 0, runOut);
        Widgets.gridCell(LEFT, BUTTON_TOP, RIGHT - LEFT, BUTTON_H, BUTTON_COLS, 1, BUTTON_GAP, 1, growthOut);
        Widgets.gridCell(LEFT, BUTTON_TOP, RIGHT - LEFT, BUTTON_H, BUTTON_COLS, 1, BUTTON_GAP, 2, saveOut);
    }
}
