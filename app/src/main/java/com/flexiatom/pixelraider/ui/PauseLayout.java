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
import com.flexiatom.pixelraider.plat.Screen;

/**
 * 暂停战术面板的全部**可测数字**：几何（入场节奏在 {@link PanelMotion}，规格 §四 暂停菜单）。
 *
 * 与结算页的分工：{@link ResultLayout} 铺在战斗区上（它是"战场的结束"），这块面板走整张逻辑画布
 * （它是"从座舱里拉下来的数据板"，矮屏 320 到高屏 560 都要能把图表区与卡片区账分得开）。
 * 所以这里的矩形一律是**逻辑画布坐标**，不做 {@code translate(battleTop)}。
 *
 * 纵向分账按规格来：图表区与卡片区共用"tab 以下、按钮以上"那块剩余高度，图表拿 38%
 * 但钳在 42~62——高屏不给图表无限长（一张 110px 的折线只是空白），矮屏也不让它塌成一条缝。
 */
public final class PauseLayout {

    public static final int LEFT = 8;
    public static final int RIGHT = Screen.LOGIC_W - LEFT;
    /** 面板与画布上下边的间隙。 */
    public static final int PANEL_GAP = 6;
    /** 装置边框厚度。 */
    public static final int BORDER = 2;
    /** 内容内缩。 */
    public static final int PAD = 4;

    /** 标题一行 24 的汉字（{@code Md3.PX_TITLE}）加上下各 2 的呼吸位。 */
    public static final int TITLE_H = 28;
    /** tab 标签 12 的汉字加 4 的点按余量。 */
    public static final int TAB_H = 16;
    public static final int CARD_ROWS = 4;
    public static final int CARD_COLS = 2;
    public static final int CARDS = CARD_ROWS * CARD_COLS;
    public static final int CELL_GAP = 4;

    /** 三级按钮：继续作战（独占一行）→ 成就/设置 → 重开/返回主菜单。 */
    public static final int ROW_PRIMARY_H = 22;
    public static final int ROW_H = 18;
    public static final int ROW_GAP = 4;
    public static final int BUTTON_GAP = 8;
    /** 主操作占内容宽的 76%（规格 §四）。 */
    public static final float PRIMARY_W_RATIO = 0.76f;

    /** 图表占分账池的比例与上下限（规格：38%，钳 42~62）。 */
    public static final float CHART_RATIO = 0.38f;
    public static final int CHART_MIN = 42;
    public static final int CHART_MAX = 62;

    /**
     * 面板抬升距离。入场节奏（时长、级联、遮罩浓度）在 {@link PanelMotion}——四块面板共用一套，
     * 只有这个位移量属于本面板自己的版面。
     */
    public static final int PANEL_LIFT = 28;

    /**
     * 单张数据卡的高度上限。
     *
     * <p>这条是 2026-09-24 自适应决策加的：画布高度改成按屏幕比例生长之后（430 → 533），
     * 原来"卡片吃掉图表剩下的全部"这个分账方式把多出来的 100px 全灌进 4 行卡片，
     * 每张卡中间裂出一大块空白。卡片只需要装下标签 + 数值，长高不带来任何信息，
     * 所以封顶在这里，富余量改为上下留白（内容块在池子里垂直居中）。
     */
    public static final int CARD_MAX_H = 34;

    public final RectI panel = new RectI();
    public final RectI titleBar = new RectI();
    public final RectI chart = new RectI();
    public final RectI[] tabs = {new RectI(), new RectI(), new RectI()};
    public final RectI[] cards = {
            new RectI(), new RectI(), new RectI(), new RectI(),
            new RectI(), new RectI(), new RectI(), new RectI()
    };
    public final RectI resume = new RectI();
    public final RectI achievements = new RectI();
    public final RectI settings = new RectI();
    public final RectI restart = new RectI();
    public final RectI menu = new RectI();

    public int contentLeft, contentRight, contentWidth;
    public int chartHeight;

    /**
     * @param logicH     当前逻辑画布高（320~{@link Screen#LOGIC_H_MAX}，来自 {@link Screen.Metrics#logicH}）
     * @param safeTop    顶部安全区内缩，逻辑 px（{@link Screen.Metrics#safeTop}）——面板不再压进挖孔带
     * @param safeBottom 底部安全区内缩，逻辑 px
     */
    public void layout(int logicH, int safeTop, int safeBottom) {
        int h = logicH <= 0 ? Screen.BATTLE_H : logicH;
        panel.set(LEFT, safeTop + PANEL_GAP, RIGHT, h - safeBottom - PANEL_GAP);
        contentLeft = LEFT + BORDER + PAD;
        contentRight = RIGHT - BORDER - PAD;
        contentWidth = contentRight - contentLeft;

        int titleTop = panel.top + BORDER + 2;
        titleBar.set(contentLeft, titleTop, contentRight, titleTop + TITLE_H);
        int tabTop = titleBar.bottom + 4;
        for (int i = 0; i < tabs.length; i++) {
            Widgets.gridCell(contentLeft, tabTop, contentWidth, TAB_H, tabs.length, 1, CELL_GAP, i, tabs[i]);
        }

        int row3Bottom = panel.bottom - BORDER - PAD;
        int row3Top = row3Bottom - ROW_H;
        int row2Bottom = row3Top - ROW_GAP;
        int row2Top = row2Bottom - ROW_H;
        int row1Bottom = row2Top - ROW_GAP;
        int row1Top = row1Bottom - ROW_PRIMARY_H;
        Widgets.equalHalves(contentLeft, row3Top, contentWidth, ROW_H, BUTTON_GAP, restart, menu);
        Widgets.equalHalves(contentLeft, row2Top, contentWidth, ROW_H, BUTTON_GAP, achievements, settings);
        int pw = primaryWidth();
        int px = contentLeft + (contentWidth - pw) / 2;
        resume.set(px, row1Top, px + pw, row1Bottom);

        int poolTop = tabTop + TAB_H + 4;
        int poolBottom = row1Top - 6;
        chartHeight = chartHeightFor(poolBottom - poolTop);
        int cardsIdeal = CARD_ROWS * CARD_MAX_H + (CARD_ROWS - 1) * CELL_GAP;
        // 图表按上限拿、卡片按封顶拿，剩下的富余量上下均分——高屏上那是留白，不是被撑空的卡片。
        int slack = Math.max(0, poolBottom - poolTop - chartHeight - CELL_GAP - cardsIdeal);
        int startY = poolTop + slack / 2;
        chart.set(contentLeft, startY, contentRight, startY + chartHeight);
        int cardsTop = chart.bottom + CELL_GAP;
        int cardsH = Math.max(0, Math.min(poolBottom - cardsTop, cardsIdeal));   // 反向矩形会被 drawRect 静默吞掉
        for (int i = 0; i < cards.length; i++) {
            Widgets.gridCell(contentLeft, cardsTop, contentWidth, cardsH, CARD_COLS, CARD_ROWS, CELL_GAP, i, cards[i]);
        }
    }

    /** 主操作行宽度（76% 内容宽）。两处用到，算一次保证按钮不偏心。 */
    public int primaryWidth() {
        return Math.round(contentWidth * PRIMARY_W_RATIO);
    }

    /** 分账：图表高 = 池高 × 38% 钳进 42~62；池子比下限还矮时只能给到池高。 */
    public static int chartHeightFor(int poolHeight) {
        if (poolHeight <= 0) return 0;
        int want = Math.round(poolHeight * CHART_RATIO);
        return Math.min(Screen.clamp(want, CHART_MIN, CHART_MAX), poolHeight);
    }
}
