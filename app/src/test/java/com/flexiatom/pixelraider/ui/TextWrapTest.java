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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 折行尺——用等宽 stub 量，断的是规则不是字形。
 *
 * <p>{@link #PER_CHAR} 每码位 8px，正好是简报 8px 档的格宽：行宽 24px ⇒ 一行 3 字，
 * 这样每次断行都能用手算的格数复核，不靠字体度量。
 */
public class TextWrapTest {

    private static final TextWrap.Width PER_CHAR =
            text -> 8 * text.codePointCount(0, text.length());

    private static final int THREE_CHARS = 24;
    private static final int FOUR_CHARS = 32;

    /** 汉字之间随时可断：满格就断，不多挤一个字。 */
    @Test
    public void wrapsBetweenEveryHanChar() {
        assertArrayEquals(
                new String[]{"任务目", "标一完", "成新手", "引导杀", "敌"},
                TextWrap.wrap("任务目标一完成新手引导杀敌", THREE_CHARS, PER_CHAR));
    }

    /** 连续字母是一个整体：宁可行首空着，也不把 "ABC" 拆成 "AB"／"C"。 */
    @Test
    public void keepsAsciiRunTogether() {
        assertArrayEquals(new String[]{"枪", "ABC"}, TextWrap.wrap("枪ABC", THREE_CHARS, PER_CHAR));
    }

    /** 小数点的两侧都不许断：这是「0.55」被读坏的那一格。 */
    @Test
    public void keepsDecimalNumberTogether() {
        assertArrayEquals(
                new String[]{"每", "0.55", "秒"},
                TextWrap.wrap("每0.55秒", FOUR_CHARS, PER_CHAR));
    }

    /**
     * 整串里没有可断处时硬切一格——窄栏配大字不许卡死，也不许吐出空行。
     * 这一格断点本就不存在（一行只容 3 码位，"0.55" 自己就占 4），切在数字中间是设计内的代价。
     */
    @Test
    public void hardCutsWhenNoBreakPointExists() {
        String[] lines = TextWrap.wrap("每0.55秒", THREE_CHARS, PER_CHAR);
        assertArrayEquals(new String[]{"每", "0.5", "5秒"}, lines);
        for (String line : lines) assertFalse(line.isEmpty());
    }

    /** 单个码位比行宽还宽，也必须逐格前进。 */
    @Test
    public void hardCutsOversizedCodePoint() {
        assertArrayEquals(new String[]{"激", "光"}, TextWrap.wrap("激光", 4, PER_CHAR));
    }

    /** 行首的半角空格吃掉；源串里那个「: 」正是踩这一格。 */
    @Test
    public void swallowsLeadingSpaceOfNextLine() {
        assertArrayEquals(new String[]{"AB", "CD"}, TextWrap.wrap("AB CD", THREE_CHARS, PER_CHAR));
    }

    /** 无空格的串，拼回去必须等于原文——断行不吞字。 */
    @Test
    public void everyCodePointSurvivesTheSplit() {
        String text = "实弹在这儿跟不上开枪慢近两成";
        StringBuilder joined = new StringBuilder();
        for (String line : TextWrap.wrap(text, 18, PER_CHAR)) joined.append(line);
        assertEquals(text, joined.toString());
    }

    /** 空串是一行——简报那两枚空行就靠这一格把行距付掉。 */
    @Test
    public void emptyTextIsStillOneLine() {
        assertEquals(1, TextWrap.lineCount("", THREE_CHARS, PER_CHAR));
        assertArrayEquals(new String[]{""}, TextWrap.wrap("", THREE_CHARS, PER_CHAR));
    }

    /** 放得下与放不下，字幕那侧只问这一个布尔；等宽时恰好占满算放得下。 */
    @Test
    public void fitsOneLineIsTheSubtitlesOnlyQuestion() {
        assertTrue(TextWrap.fitsOneLine("击杀一", THREE_CHARS, PER_CHAR));
        assertFalse(TextWrap.fitsOneLine("击杀一二", THREE_CHARS, PER_CHAR));
    }

    /** 简报源串里那枚半角「: 」——冒号跟上一字走，空格被下一行的行首吞掉。 */
    @Test
    public void splitsTheBriefingGoalRowAtItsColonSpace() {
        assertArrayEquals(
                new String[]{"任务目", "标:", "击杀一", "个敌人"},
                TextWrap.wrap("任务目标: 击杀一个敌人", THREE_CHARS, PER_CHAR));
    }

    /** 行宽不是正数属于调用方算错列宽，当场报，不静默退化成一行一个字的窄栏。 */
    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveWidth() {
        TextWrap.wrap("任务", 0, PER_CHAR);
    }
}
