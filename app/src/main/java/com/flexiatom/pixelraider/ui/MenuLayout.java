package com.flexiatom.pixelraider.ui;

import com.flexiatom.pixelraider.core.RectI;
import com.flexiatom.pixelraider.plat.Screen;

/**
 * 主菜单页几何（纯算术，JVM 可测）。
 *
 * <p>这一页存在的理由只有一条：**给成长页一个宿主**。规格把消费入口写在「主菜单的『成长』页」，
 * 而结算页那颗「返回菜单」此前按下等于重开，2026-09-28 已被真接线替换（{@code Game.openMenu} → {@code push(MENU)}）。所以这一页
 * 刻意保持薄——只放标题、两个读数和两枚真出口，未落地的档（设置、图鉴）连禁用格都不摆：
 * 一颗灰着的按钮比没有按钮更糟，那是规格点名的"点了没反应"那一类。
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
    public static final int BUTTON_BOTTOM = BUTTON_TOP + BUTTON_H;   // 196
    public static final int BUTTON_GAP = 8;

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

    /** 左「开始新一局」（filled，主操作）、右「成长」（text，二级）。主次靠有没有底区分，不靠大小。 */
    public static void buttons(RectI runOut, RectI growthOut) {
        Widgets.equalHalves(LEFT, BUTTON_TOP, RIGHT - LEFT, BUTTON_H, BUTTON_GAP, runOut, growthOut);
    }
}
