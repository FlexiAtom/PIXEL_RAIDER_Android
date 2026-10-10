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
package com.flexiatom.pixelraider.game;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 三档签字表本身（[规格 §8.2]）。
 *
 * <p>这里钉的是**表上的数**，不是"用它算一遍"——算出来的行为在 {@code WaveDirectorTest} 那批
 * {@code tier*} 用例里。这张表要能防的那件事是：有人改了某个乘子而没打算重签，于是行为跟着漂，
 * 而漂移在别处看起来像"平衡调过了"。
 */
public class TierTest {

    /** 休闲：厚补贴薄威胁。 */
    @Test
    public void casualRow() {
        assertEquals(0.75f, Tier.CASUAL.hpMul, 0f);
        assertEquals(0.85f, Tier.CASUAL.countMul, 0f);
        assertEquals(0.70f, Tier.CASUAL.takenMul, 0f);
        assertEquals(1.25f, Tier.CASUAL.dropMul, 0f);
        assertEquals(1.15f, Tier.CASUAL.gapMul, 0f);
        assertEquals(2, Tier.CASUAL.eliteFloor);
    }

    /** 普通：全 1.00 ＋ 精英下限＝今天那个字面量。这一行**任何一格都不是 1.00 就说明基线被改了**。 */
    @Test
    public void normalRowIsTheBaseline() {
        assertEquals(1.00f, Tier.NORMAL.hpMul, 0f);
        assertEquals(1.00f, Tier.NORMAL.countMul, 0f);
        assertEquals(1.00f, Tier.NORMAL.takenMul, 0f);
        assertEquals(1.00f, Tier.NORMAL.dropMul, 0f);
        assertEquals(1.00f, Tier.NORMAL.gapMul, 0f);
        assertEquals(2, Tier.NORMAL.eliteFloor);
    }

    /** 终结：那三个 1.75／1.34／1.30 是被对抗审查从 2.20／1.55／1.60 削下来的（§8.3），不许顺手抬回去。 */
    @Test
    public void terminalRow() {
        assertEquals(1.75f, Tier.TERMINAL.hpMul, 0f);
        assertEquals(1.34f, Tier.TERMINAL.countMul, 0f);
        assertEquals(1.30f, Tier.TERMINAL.takenMul, 0f);
        assertEquals(0.75f, Tier.TERMINAL.dropMul, 0f);
        assertEquals(0.86f, Tier.TERMINAL.gapMul, 0f);
        assertEquals(6, Tier.TERMINAL.eliteFloor);
    }

    /**
     * 两条**取值规矩**（§8.2 规矩②＋§8.3 ①），他们当初就是照这两条定的数：
     * ① 掉落取敌数量的近似倒数 ⇒ 净收入不该"高难多拿"；② hp × count 是波总血量，封顶 2.35 倍。
     */
    @Test
    public void theTwoRulesThatMadeTheseNumbers() {
        float terminalNet = Tier.TERMINAL.countMul * Tier.TERMINAL.dropMul;
        assertTrue("终结档净收入该≈1.00（高难不多拿）：" + terminalNet,
                Math.abs(terminalNet - 1f) < 0.02f);
        float casualNet = Tier.CASUAL.countMul * Tier.CASUAL.dropMul;
        assertTrue("休闲档该略微补贴（否则『简单』读成『更弱』）：" + casualNet, casualNet > 1f);
        float terminalWaveHp = Tier.TERMINAL.hpMul * Tier.TERMINAL.countMul;
        assertTrue("波总血量倍数该收在 2.35，不是审查打掉的那 3.4：" + terminalWaveHp,
                terminalWaveHp > 2.2f && terminalWaveHp < 2.5f);
    }
}
