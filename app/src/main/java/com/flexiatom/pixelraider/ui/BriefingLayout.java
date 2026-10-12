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

import com.flexiatom.pixelraider.plat.Screen;

import java.util.ArrayList;
import java.util.List;

/**
 * 简报块的**可测数字**（源行 → 上屏行 → 行位 → 可见区间；轨的算术在 {@link ScrollLayout}）。
 *
 * <p>这一层只认"行"，不认"哪一句是背景、哪一句是目标"——句子由 {@code game.CampaignBriefing}
 * 给，宽度由 {@link TextWrap} 量。三件事分开住，是因为它们各有自己的改动的理由：换文案不该碰版面，
 * 换字阶不该碰文案，而"最多 3 行"那条上限（他 2026-10-12 逐字「『最多3行』的"行"是<b>上屏折行后</b>
 * 不超过三行」）要求行数是<b>量</b>出来的，不是数源串数出来的。
 *
 * <p>坐标是**视口内相对坐标**（视口顶边 y=0），换屏幕坐标由绘制端加一次视口原点——
 * 与 {@link SaveLayout} 那套"页内相对 + 一处 pageTop"同形。
 */
public final class BriefingLayout {

    public static final int LEFT = 8;
    public static final int RIGHT = Screen.LOGIC_W - LEFT;                   // 232
    /** 文字与列边之间的内缩（轨那一侧的内缩算在 {@link #TEXT_WIDTH} 里，见 ScrollLayout.wrapWidth）。 */
    public static final int PAD = 4;

    /** 8px 档字身——甲＝双档原生面，简报走小的那一档（他 2026-10-12 逐字「简报区字要等比缩小」）。 */
    public static final int GLYPH_H = 8;
    /** 行距：字身 + 2px 缝。空行也按这一档付高（{@link TextWrap} 把空串数成一格）。 */
    public static final int LINE_STEP = 10;

    /** 文字左界。 */
    public static final int TEXT_LEFT = LEFT + PAD;                          // 12
    /** 折行宽：整列宽 − 那条常驻轨 − 左右内缩。 */
    public static final int TEXT_WIDTH = ScrollLayout.wrapWidth(RIGHT - LEFT, PAD);   // 210
    /** 轨的左边界（它贴在列的右缘，出轨之后文字才轮到内缩）。 */
    public static final int TRACK_LEFT = RIGHT - ScrollLayout.TRACK_W;       // 226

    private BriefingLayout() { }

    /**
     * 源行展平成上屏行：一枚源行折成几行就占几格，空串仍占一格（简报里那两枚空行靠这一格把行距付掉）。
     * {@code width} 由调用方给——真机是 {@code gfx.TextCache} 的度量，单测是等宽 stub。
     */
    public static String[] lines(String[] rows, TextWrap.Width width) {
        List<String> out = new ArrayList<>();
        for (String row : rows) {
            for (String line : TextWrap.wrap(row, TEXT_WIDTH, width)) out.add(line);
        }
        return out.toArray(new String[0]);
    }

    /** 内容高（px）：上屏行数 × 行距。滚动量、拇指高、可见区间全从这一个数推。 */
    public static int contentHeight(String[] rows, TextWrap.Width width) {
        int lines = 0;
        for (String row : rows) lines += TextWrap.lineCount(row, TEXT_WIDTH, width);
        return lines * LINE_STEP;
    }

    /** 第 {@code line} 行在视口内的顶边 y。负值是已滚出上界的那一行，由绘制端的裁剪处理。 */
    public static int lineTop(int line, int offset) {
        return line * LINE_STEP - offset;
    }

    /**
     * 第一行要画的行号：整行滚出上界以后不再画。偏移正好落在行距整数倍上时，上一行的底边与
     * 视口上沿重合（零面积），那一行不画——所以直接用整除。
     */
    public static int visibleFrom(int offset) {
        return Math.max(0, offset / LINE_STEP);
    }

    /**
     * 最后一行之后的行号（开区间上界），已钳到总行数。视口高不是行距整数倍时，最下面那一行
     * 只露半截字身——这一层<b>不替绘制端舍弃半行</b>：露多少画多少，裁由那一侧的 clip 决定。
     *
     * <p>偏移越出内容时返回值可能小于 {@link #visibleFrom}，绘制端那个循环自然走空。
     */
    public static int visibleTo(int offset, int viewportH, int totalLines) {
        return Math.min(totalLines, (offset + viewportH + LINE_STEP - 1) / LINE_STEP);
    }
}
