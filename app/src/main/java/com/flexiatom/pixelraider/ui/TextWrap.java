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

import java.util.ArrayList;
import java.util.List;

/**
 * 折行——全仓唯一一把"这段字在这条宽里占几行"的尺。
 *
 * <p>两处读它，两种问法，不许各自再写一份：
 * <ul>
 *   <li>简报（{@code BriefingLayout}）问 {@link #lineCount}——他 2026-10-12 逐字
 *       「『最多3行』的"行"是上屏折行后不超过三行」，行数只能在这把尺上数，按源串数会少数。</li>
 *   <li>局内字幕问 {@link #fitsOneLine}——他逐字「单行指一句话为一行，并且需要注意不能超过屏幕」：
 *       字幕<b>不折行</b>，放不下就不上屏，那一侧只要一个布尔。</li>
 * </ul>
 *
 * <p>测量口径由调用方给（{@link Width}）：真机走 {@code gfx.TextCache} 的度量，单测塞等宽 stub。
 * 这层不 import Android，才能按规格 §九.20 用纯 JVM 单测直接验。
 */
public final class TextWrap {

    /** 一段文字的像素宽。真源是字体度量，不是"字数 × 字号"——等宽只保证汉字之间相等，标点不欠。 */
    public interface Width {
        int of(String text);
    }

    /**
     * 贪心折行。逐码位试探当前行能不能多收一个字，收不下就断。两条例外：
     * <ul>
     *   <li>断点回退到最后一个**词边界**——连续 ASCII 字母数字不拆（「0.55」断成「0.5／5」是把数读坏）；
     *       汉字之间没有这条限制，随时可断。</li>
     *   <li>单个码位就超过行宽时**硬切一格**，保证循环一定前进（窄栏配大字时不许卡死）。</li>
     * </ul>
     * 下一行的行首空格直接吃掉——源串里那个半角「: 」正是踩这一格的。
     */
    public static String[] wrap(String text, int maxWidth, Width width) {
        if (maxWidth <= 0) throw new IllegalArgumentException("折行宽必须为正，收到 " + maxWidth);
        if (text == null || text.isEmpty()) return new String[]{""};
        List<String> lines = new ArrayList<>();
        int n = text.length();
        int start = 0;
        while (start < n) {
            int end = start;
            int lastBreak = -1;
            while (end < n) {
                int next = end + Character.charCount(text.codePointAt(end));
                if (width.of(text.substring(start, next)) > maxWidth) break;
                end = next;
                if (end < n && canBreak(text, end)) lastBreak = end;
            }
            if (end == start) {
                end = start + Character.charCount(text.codePointAt(start));
            } else if (end < n && lastBreak > start) {
                end = lastBreak;
            }
            lines.add(text.substring(start, end));
            start = end;
            while (start < n && text.charAt(start) == ' ') start++;
        }
        return lines.toArray(new String[0]);
    }

    /** 上屏占几行。空串是一行——简报里那两枚空行就是靠这一格把行距付掉的。 */
    public static int lineCount(String text, int maxWidth, Width width) {
        return wrap(text, maxWidth, width).length;
    }

    /** 放不放得下一行（字幕那侧的唯一问法，不看折行）。 */
    public static boolean fitsOneLine(String text, int maxWidth, Width width) {
        return text != null && width.of(text) <= maxWidth;
    }

    /**
     * 断点：空格断在它<b>之前</b>（好让下一行的行首把它吞掉，不挂在行尾撑宽）；连续 ASCII
     * 字母数字之间不断；数字与小数点之间不断——「0.55」断成「0／.55」正是把数读坏。
     * 汉字与标点两头只要有一头不是字母数字，就随时可断。
     */
    private static boolean canBreak(String s, int at) {
        int prev = s.codePointBefore(at);
        int cur = s.codePointAt(at);
        if (prev == ' ') return false;
        if (isAsciiWord(prev) && isAsciiWord(cur)) return false;
        return !((isAsciiDigit(prev) && cur == '.') || (prev == '.' && isAsciiDigit(cur)));
    }

    private static boolean isAsciiWord(int cp) {
        return cp < 128 && Character.isLetterOrDigit((char) cp);
    }

    private static boolean isAsciiDigit(int cp) {
        return cp >= '0' && cp <= '9';
    }

    private TextWrap() { }
}
