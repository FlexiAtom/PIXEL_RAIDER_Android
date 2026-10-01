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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 零分配格式化器的输出正确性。期望值全部手推，尤其 fixed 的进位与 pad 的空格数——
 * 这些是 HUD 上玩家直接读到的字符，错一位就是读数列对不齐。
 */
public class HudTextTest {

    @Test
    public void integersPrintWithoutLeadingZerosAndKeepSign() {
        assertEquals("0", new HudText(32).reset().num(0).snapshot());
        assertEquals("7", new HudText(32).num(7).snapshot());
        assertEquals("-42", new HudText(32).num(-42).snapshot());
        assertEquals("1000000", new HudText(32).num(1000000).snapshot());
        // 取反在最小值上会溢出回自身，所以逐位必须按负数取模
        assertEquals("-9223372036854775808", new HudText(32).num(Long.MIN_VALUE).snapshot());
        assertEquals("9223372036854775807", new HudText(32).num(Long.MAX_VALUE).snapshot());
    }

    @Test
    public void fixedRoundsAndCarries() {
        assertEquals("16.7", new HudText(32).fixed(16.73f, 1).snapshot());
        assertEquals("2.0", new HudText(32).fixed(2f, 1).snapshot());
        assertEquals("0.1", new HudText(32).fixed(0.05f, 1).snapshot());
        assertEquals("1.23", new HudText(32).fixed(1.234f, 2).snapshot());
        assertEquals("-3.5", new HudText(32).fixed(-3.5f, 1).snapshot());
        // 99.96 → 100.0：进位必须并进整数部分，小数位补零
        assertEquals("100.0", new HudText(32).fixed(99.96f, 1).snapshot());
        // 1.09 → 整数部分 1、小数 09：小数位要补前导零，否则 1.09 会显示成 1.9
        assertEquals("1.09", new HudText(32).fixed(1.09f, 2).snapshot());
    }

    @Test
    public void padAddsExactlyTheRightNumberOfSpaces() {
        assertEquals("   7", new HudText(32).pad(7, 4).snapshot());
        assertEquals("  0", new HudText(32).pad(0, 3).snapshot());
        assertEquals(" 42", new HudText(32).pad(42, 3).snapshot());
        // pad 只作用于刚写下的那一段，不动前面的内容
        assertEquals("[ 42]", new HudText(32).text("[").pad(42, 3).text("]").snapshot());
        // 已经超宽的不动它（宁可撑破列，也不把数字截断成另一个数）
        assertEquals("12345", new HudText(32).pad(12345, 4).snapshot());
    }

    @Test
    public void percentClampsBothEnds() {
        assertEquals("50%", new HudText(32).percent(0.5f).snapshot());
        assertEquals("100%", new HudText(32).percent(1.5f).snapshot());
        assertEquals("0%", new HudText(32).percent(-1f).snapshot());
        assertEquals("33%", new HudText(32).percent(0.333f).snapshot());
    }

    @Test
    public void overflowDropsInsteadOfGrowing() {
        // 容量是布局算出来的上界；运行期扩容就是每帧分配，所以只能丢
        HudText t = new HudText(4);
        t.text("SCORE").num(12);
        assertEquals(4, t.length());
        assertEquals("SCOR", t.snapshot());
        assertEquals(4, t.capacity());
    }

    @Test
    public void resetClearsButKeepsTheBuffer() {
        HudText t = new HudText(8);
        t.num(1234);
        assertEquals(4, t.length());
        t.reset().num(7);
        assertEquals("7", t.snapshot());
        assertEquals(8, t.capacity());
    }
}
