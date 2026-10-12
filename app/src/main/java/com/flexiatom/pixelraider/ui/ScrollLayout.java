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

/**
 * 简报区那条轨的**全部可测数字**（常驻轨：视口高、内容高 → 箭头／行程／拇指／偏移）。
 *
 * <p>轨是**常驻**的（他 2026-10-13 逐字「所以是正常也超限？那就正常情况也用轨」）。所以这里没有
 * "要不要画轨"这一格——只有一条 `C ≤ V` 的钳位管"拖不拖得动"。内容不溢出时拇指占满行程，
 * 那条槽看着在、但拖不出新内容，这正是常驻的代价，不是 bug。
 *
 * <p>坐标一律是**视口内相对坐标**（顶边 y=0），换算成屏幕坐标由调用方加一次视口原点，
 * 与 {@link SaveLayout} 那套"页内相对 + 一处 pageTop"同形。
 *
 * <p>尺寸的比例约束（他 2026-10-12 逐字「轨宽与箭头的大小可以自定义，前提是保持比例」）只有两条：
 * 箭头高 = 1.5×轨宽（{@link #ARROW_H}），拇指宽 = 轨宽。第三条"轨长 : 轨宽 = 21 : 1"照不了——
 * 轨长就是视口高，是功能量不是比例量。
 */
public final class ScrollLayout {

    /**
     * 轨宽。MO 的原宽是 14，但常驻轨之后宽度是每行都付的**永久代价**，故取"拇指还能点"的最小档。
     * 这个 6 与下面那枚 6×9 的箭头是他 2026-10-13 逐字「你的自定可以」批下来的数，不是我自己钉的常量。
     */
    public static final int TRACK_W = 6;
    /** 箭头高 = ⌈1.5×轨宽⌉。上下两枚合计 18 = 3×轨宽，行程就是视口减掉这一截。 */
    public static final int ARROW_H = 9;

    /**
     * 拇指可点的下界。取 {@code Screen.minTouchLogic} 在真机读数里的**上界** 45
     * （{@code MIN_TOUCH_DP = 48f} 换算到逻辑像素是 34~45，按最坏的那一档钉，才不会在长画布上假装够用）。
     */
    public static final int MIN_THUMB = 45;

    /** 行程：视口高减掉上下两枚箭头。视口矮到装不下两枚箭头时给 0，由调用方按"版面不成立"处理。 */
    public static int travel(int viewportH) {
        return Math.max(0, viewportH - 2 * ARROW_H);
    }

    /**
     * 拇指高：MO 那条轨的真式 {@code 行程²/内容}，加两条钳位。
     *
     * <p>钳位一 {@code C ≤ V ⇒ 拇指 = 行程}：MO 的式子只在"内容确实超出"那一侧成立，
     * 轨常驻必然把不超出的状态召进来（战役选关页今天那一档就是 {@code C = V = 80}），那时式子会给出
     * **假行程**——拇指 48 而行程 62，内容一行都没溢却留着 14px 拖不出东西的空格。
     * 钳位二 {@code 拇指 ≤ 行程}：式子在 {@code C < 行程} 时算出比行程还长的拇指。
     *
     * <p>四舍五入而不是截断：截断会让"拇指 + 剩余行程"在 {@code V=80/C=90} 这一档少 1px
     * （43 与 42 之差），行程末尾留出一条缝。
     */
    public static int thumb(int viewportH, int contentH) {
        int s = travel(viewportH);
        if (s == 0 || contentH <= viewportH) return s;
        int t = (s * s + contentH / 2) / contentH;
        return Math.min(s, Math.max(1, t));
    }

    /** 内容方向的可滚量：溢出几 px 就能滚几 px（不溢出就是 0）。 */
    public static int maxOffset(int viewportH, int contentH) {
        return Math.max(0, contentH - viewportH);
    }

    /** 拇指自己的可走量：行程减拇指。为 0 时整条轨拖不动（{@link #offsetForThumbTop} 要接住这一格）。 */
    public static int thumbTravel(int viewportH, int contentH) {
        return travel(viewportH) - thumb(viewportH, contentH);
    }

    /** 偏移钳到 {@code 0..maxOffset}，越界不报错——拖动读数与惯性都从这里过。 */
    public static int clampOffset(int offset, int viewportH, int contentH) {
        return Math.min(maxOffset(viewportH, contentH), Math.max(0, offset));
    }

    /**
     * **按着视口区域拖**（他 2026-10-13 逐字「我的建议是允许按着视口区域拖动」）：按下以后手指相对
     * 按下点的位移 {@code dyFromDown}（屏幕坐标，向下为正）→ 内容偏移。手指上移露出下面的内容，
     * 所以取负；整段钳在 {@code 0..maxOffset}，拖过界不报错也不回弹。
     *
     * <p>落点是**整个视口**，不只是拇指——这一格的存在让 {@link #thumbTouchable} 从"能不能拖"的
     * 门槛降成"拇指画多长"的读数：拇指矮到抓不住时，按在文字上照样拖得动。
     */
    public static int offsetForDrag(int viewportH, int contentH, int startOffset, int dyFromDown) {
        return clampOffset(startOffset - dyFromDown, viewportH, contentH);
    }

    /**
     * 拇指顶边在视口内的 y（含上箭头那一截）。偏移 0 贴顶，偏移满时拇指底边正好吻上下箭头顶。
     */
    public static int thumbTop(int viewportH, int contentH, int offset) {
        int span = thumbTravel(viewportH, contentH);
        int moved = span <= 0 ? 0
                : (int) Math.round((float) clampOffset(offset, viewportH, contentH) * span / maxOffset(viewportH, contentH));
        return ARROW_H + moved;
    }

    /** 反向：拇指顶边 → 内容偏移（拖动落点用这一条换算）。 */
    public static int offsetForThumbTop(int viewportH, int contentH, int thumbTopInViewport) {
        int span = thumbTravel(viewportH, contentH);
        if (span <= 0) return 0;
        int moved = Math.min(span, Math.max(0, thumbTopInViewport - ARROW_H));
        return clampOffset((int) Math.round((float) moved * maxOffset(viewportH, contentH) / span),
                viewportH, contentH);
    }

    /** 上箭头（顶行翻一步的落点，步进由调用方按行高给）。trackLeft 是轨的左边界。 */
    public static void arrowUp(int trackLeft, int viewportH, RectI out) {
        out.set(trackLeft, 0, trackLeft + TRACK_W, ARROW_H);
    }

    /** 下箭头。 */
    public static void arrowDown(int trackLeft, int viewportH, RectI out) {
        out.set(trackLeft, viewportH - ARROW_H, trackLeft + TRACK_W, viewportH);
    }

    /** 拇指（宽恒等于轨宽，是他那句"保持比例"里唯一没有自由度的那条）。 */
    public static void thumbRect(int trackLeft, int viewportH, int contentH, int offset, RectI out) {
        int top = thumbTop(viewportH, contentH, offset);
        out.set(trackLeft, top, trackLeft + TRACK_W, top + thumb(viewportH, contentH));
    }

    /**
     * 拇指这一档<b>单抓</b>抓不抓得住——它只是"拇指画多长"的读数，<b>不是</b>能不能拖的前提：
     * 他 2026-10-13 逐字「我的建议是允许按着视口区域拖动」之后，拖的落点是整个视口（{@link #offsetForDrag}），
     * 拇指矮过下界那一档仍然拖得动，只是不能拿指头去掐那条 6px 宽的皮。
     */
    public static boolean thumbTouchable(int viewportH, int contentH) {
        return thumb(viewportH, contentH) >= MIN_THUMB;
    }

    /** 出轨之后的可视宽：整列宽减掉那条永久占位的轨。 */
    public static int visibleWidth(int columnWidth) {
        return columnWidth - TRACK_W;
    }

    /** 可视宽再减左右内缩——折行就按这一档量，别按整列宽量（轨常驻以后没有"出轨前"那一档了）。 */
    public static int wrapWidth(int columnWidth, int pad) {
        return visibleWidth(columnWidth) - 2 * pad;
    }

    private ScrollLayout() { }
}
