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
 * 结算页几何（纯算术，JVM 可测）。
 *
 * 整块内容住在 240×320 的战斗区里（规格 §二），十段揭示各占一条带，纵向不重叠——
 * 揭示是**时间上**逐段出现，不是**空间上**叠在一起。这条不变量只有写成纯函数才测得了，
 * 靠截图肉眼比对发现"数据卡第 5 行压住了按钮"已经太晚。
 *
 * 五维用横条而不是雷达图：规格点名的雷达图是**图鉴页武器六维**（"形状比逐行读文字快"），
 * 结算页要的是"哪一维拖了后腿"，和 HUD 状态条同一种读法，视线不用重新学。
 */
public final class ResultLayout {

    public static final int LEFT = 8;
    public static final int RIGHT = Screen.LOGIC_W - 8;         // 232

    // 带区的行高一律跟着内嵌像素中文抬到 12（大标题 24）。整页由 PAGE_H 汇总，
    // 在画布里居中而不是钉在 320 那条旧带上——见 Screen.Metrics.pageTop(int)。
    //
    // 全套带区比"刚好装下"再上移 2：抬到 12px 之后按钮底到 313，命中框按 34 外扩（上下各 6）
    // 恰好落在 320 以内。少让这 2 格，最矮画布上"返回菜单"的下半截命中区就掉出画布了。
    public static final int TITLE_TOP = 12;
    public static final int TITLE_BOTTOM = 38;                  // 26 格装 24px 大标题
    /** 标题底光的带：规格说"发光从 16 降到 5"，这里是那 5 的落点（比标题带上下各扩 8）。 */
    public static final int TITLE_GLOW_PAD = 8;
    public static final int DIVIDER_Y = 42;
    public static final int SCORE_TOP = 48;
    public static final int SCORE_BOTTOM = 66;
    public static final int RECORD_TOP = 70;
    public static final int RECORD_BOTTOM = 84;
    public static final int RANK_TOP = 88;
    public static final int RANK_BOTTOM = 106;
    public static final int RADAR_TOP = 112;
    public static final int RADAR_ROW = 14;                     // 12px 标签 + 上下各 1
    public static final int RADAR_ROWS = 5;
    public static final int RADAR_BOTTOM = RADAR_TOP + RADAR_ROW * RADAR_ROWS;   // 182
    public static final int CARDS_TOP = 186;
    public static final int CARD_ROW = 13;
    public static final int CARDS_BOTTOM = CARDS_TOP + CARD_ROW * RevealScript.CARD_ROWS;  // 251
    /** 成就进度（2.5s 段）一行：整条带先占住，否则 S5 接成就时要重排全部带区。 */
    public static final int ACH_TOP = 255;
    public static final int ACH_BOTTOM = 271;
    public static final int COMPARE_TOP = 275;
    public static final int COMPARE_BOTTOM = 287;
    public static final int BUTTON_H = 22;
    public static final int BUTTON_TOP = 291;
    public static final int BUTTON_BOTTOM = BUTTON_TOP + BUTTON_H;
    public static final int BUTTON_GAP = 8;

    /**
     * 这一页自己有多高。整页模态按它居中（{@code Screen.Metrics.pageTop(int)}），
     * 短画布上它顶到 313，仍在 {@code Screen.BATTLE_H} 以内。
     */
    public static final int PAGE_H = BUTTON_BOTTOM;

    /** 条体左端：标签占两位 12px 汉字 + 间距，五维与数据卡共用同一列，视线直线上下。 */
    public static final int LABEL_W = 24;
    public static final int ROW_TEXT_X = LEFT;
    public static final int BAR_X = LEFT + LABEL_W + 6;         // 38
    public static final int BAR_RIGHT = RIGHT - 34;             // 198，右侧留给百分比读数
    public static final int BAR_H = 5;
    /** 标签/读数的半高：12px 汉字烘焙出来正好 12 高，±6 就是它的盒子。 */
    private static final int ROW_HALF = 6;

    private ResultLayout() { }

    public static void titleRect(RectI out) {
        out.set(LEFT, TITLE_TOP, RIGHT, TITLE_BOTTOM);
    }

    public static void titleGlowRect(RectI out) {
        out.set(LEFT, TITLE_TOP - TITLE_GLOW_PAD, RIGHT, TITLE_BOTTOM + TITLE_GLOW_PAD);
    }

    public static void scoreRect(RectI out) {
        out.set(LEFT, SCORE_TOP, RIGHT, SCORE_BOTTOM);
    }

    public static void recordRect(RectI out) {
        out.set(LEFT, RECORD_TOP, RIGHT, RECORD_BOTTOM);
    }

    public static void rankRect(RectI out) {
        out.set(LEFT, RANK_TOP, RIGHT, RANK_BOTTOM);
    }

    /** 五维第 i 条：标签在左、条在中、读数在右。 */
    public static void radarRowRect(int i, RectI label, RectI bar, RectI value) {
        rowCells(RADAR_TOP + i * RADAR_ROW + RADAR_ROW / 2, label, bar, value);
    }

    /** 成就进度行：与五维共用同一套列位，视线不必重新找列。 */
    public static void achRowRect(RectI label, RectI bar, RectI value) {
        rowCells((ACH_TOP + ACH_BOTTOM) / 2, label, bar, value);
    }

    private static void rowCells(int mid, RectI label, RectI bar, RectI value) {
        label.set(ROW_TEXT_X, mid - ROW_HALF, ROW_TEXT_X + LABEL_W, mid + ROW_HALF);
        bar.set(BAR_X, mid - BAR_H / 2, BAR_RIGHT, mid - BAR_H / 2 + BAR_H);
        value.set(BAR_RIGHT + 4, mid - ROW_HALF, RIGHT, mid + ROW_HALF);
    }

    /** 数据卡第 row 行（整行一条，标签左、数值右）。 */
    public static void cardRowRect(int row, RectI out) {
        int top = CARDS_TOP + row * CARD_ROW;
        out.set(ROW_TEXT_X, top, RIGHT, top + CARD_ROW);
    }

    public static void compareRect(RectI out) {
        out.set(LEFT, COMPARE_TOP, RIGHT, COMPARE_BOTTOM);
    }

    /** 重试（左，primary）+ 返回菜单（右，ghost），并排各半宽同高。 */
    public static void buttons(RectI retryOut, RectI menuOut) {
        Widgets.equalHalves(LEFT, BUTTON_TOP, RIGHT - LEFT, BUTTON_H, BUTTON_GAP, retryOut, menuOut);
    }

    /** 条体填充宽：0..1 归一值 → 像素。 */
    public static int barFillWidth(float norm01) {
        float v = Float.isNaN(norm01) ? 0f : (norm01 < 0f ? 0f : (norm01 > 1f ? 1f : norm01));
        return Math.round((BAR_RIGHT - BAR_X) * v);
    }
}
