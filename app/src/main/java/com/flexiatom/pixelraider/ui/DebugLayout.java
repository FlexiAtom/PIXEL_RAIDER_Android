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
 * 调试页的全部**可测数字**（仅 debug 构建可达）。
 *
 * <p>版面沿 {@link ShopLayout} 的**底部弹层**口径：底边贴可用区下沿、顶边由内容决定，多出来的高度
 * 留给战场。它比商店更固定——不翻页、不随数据生长，就那么四行（标题 / 开关 / 两枚作弊键 / 返回），
 * 所以内容高是一个常量 {@link #CONTENT_H}，不必像商店那样按可用高倒推张数。
 *
 * <p>坐标一律是**逻辑画布坐标**（0..logicH），不做 {@code translate(battleTop)}。行从**下往上**锚：
 * 极矮画布叠上厚安全区时弹层被钳短，掉出去的是标题而不是"返回"那枚出口——出口是离开这块面板的唯一
 * 退路，不能第一个被裁。
 */
public final class DebugLayout {

    public static final int LEFT = 8;
    public static final int RIGHT = Screen.LOGIC_W - LEFT;
    public static final int PANEL_GAP = 6;
    public static final int BORDER = 2;
    public static final int PAD = 4;

    public static final int TITLE_H = 28;
    public static final int TITLE_TOP_PAD = 2;
    /** 各区块之间的固定间隙。 */
    public static final int SECTION_GAP = 6;
    /** 开关那一行的高（整行即按钮：左标签 + 右状态）。 */
    public static final int TOGGLE_H = 24;
    /** 两枚作弊键与返回键的行高。 */
    public static final int BTN_H = 22;
    /** 两枚作弊键之间的间隙（各占一半内容宽，见 {@link Widgets#equalHalves}）。 */
    public static final int BTN_GAP = 4;
    /** 返回键沿用出口那套 76% 居中宽度（与 {@link ShopLayout#PRIMARY_W_RATIO}、{@code PauseLayout.resume} 同口径）。 */
    public static final float BACK_W_RATIO = 0.76f;

    /** 弹层理想内容高：两条边框 + 标题 + 四行 + 三段间隙 + 底留白。 */
    public static final int CONTENT_H = BORDER + TITLE_TOP_PAD + TITLE_H + SECTION_GAP
            + TOGGLE_H + SECTION_GAP + BTN_H + SECTION_GAP + BTN_H + PAD + BORDER;

    public static final int PANEL_LIFT = 28;

    public final RectI panel = new RectI();
    public final RectI titleBar = new RectI();
    public final RectI toggle = new RectI();
    public final RectI victory = new RectI();
    public final RectI settle = new RectI();
    public final RectI back = new RectI();

    public int contentLeft, contentRight, contentWidth;

    /**
     * @param logicH     当前逻辑画布高
     * @param safeTop    顶部安全区内缩（逻辑 px）
     * @param safeBottom 底部安全区内缩
     */
    public void layout(int logicH, int safeTop, int safeBottom) {
        int h = logicH <= 0 ? Screen.BATTLE_H : logicH;
        int bottom = h - safeBottom - PANEL_GAP;
        int usable = Math.max(0, bottom - safeTop - PANEL_GAP);
        panel.set(LEFT, bottom - Math.min(CONTENT_H, usable), RIGHT, bottom);
        contentLeft = LEFT + BORDER + PAD;
        contentRight = RIGHT - BORDER - PAD;
        contentWidth = contentRight - contentLeft;

        // 从下往上排：出口（返回）最靠底，最后才被钳掉；标题最先出局。
        int y = panel.bottom - BORDER - PAD;
        int bw = backWidth();
        int bx = contentLeft + (contentWidth - bw) / 2;
        y -= BTN_H;
        back.set(bx, y, bx + bw, y + BTN_H);
        y -= SECTION_GAP;
        int pairBottom = y;
        int pairTop = y - BTN_H;
        Widgets.equalHalves(contentLeft, pairTop, contentWidth, BTN_H, BTN_GAP, victory, settle);
        y = pairTop;
        y -= SECTION_GAP;
        int toggleTop = y - TOGGLE_H;
        toggle.set(contentLeft, toggleTop, contentRight, toggleTop + TOGGLE_H);
        y = toggleTop;
        y -= SECTION_GAP;
        titleBar.set(contentLeft, y - TITLE_H, contentRight, y);
    }

    /** 返回键宽度（76% 内容宽，与其余面板的出口同口径，保证不偏心）。 */
    public int backWidth() {
        return Math.round(contentWidth * BACK_W_RATIO);
    }
}
