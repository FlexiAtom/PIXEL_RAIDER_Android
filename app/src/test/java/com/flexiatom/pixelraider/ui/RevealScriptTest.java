/*
 * PIXEL RAIDER — 原生 Android 纵版弹幕射击
 * Copyright (C) 2026 Flexiatom
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 结算页揭示表：十个时间点是规格明文，逐条钉住，改表要连带动这条测试。 */
public class RevealScriptTest {

    @Test
    public void stageStartsMatchSpecExactly() {
        assertEquals(0.55f, RevealScript.startAt(RevealScript.TITLE), 0f);
        assertEquals(0.90f, RevealScript.startAt(RevealScript.DIVIDER), 0f);
        assertEquals(1.05f, RevealScript.startAt(RevealScript.SCORE), 0f);
        assertEquals(1.45f, RevealScript.startAt(RevealScript.RECORD), 0f);
        assertEquals(1.55f, RevealScript.startAt(RevealScript.OVERALL), 0f);
        assertEquals(1.75f, RevealScript.startAt(RevealScript.RADAR), 0f);
        assertEquals(1.90f, RevealScript.startAt(RevealScript.CARDS), 0f);
        assertEquals(2.50f, RevealScript.startAt(RevealScript.ACHIEVEMENTS), 0f);
        assertEquals(2.70f, RevealScript.startAt(RevealScript.COMPARE), 0f);
        assertEquals(3.20f, RevealScript.startAt(RevealScript.BUTTONS), 0f);
    }

    @Test
    public void stagesRevealInOrder() {
        for (int i = 1; i < RevealScript.COUNT; i++) {
            assertTrue("第 " + i + " 段不比前一段晚",
                    RevealScript.startAt(i) > RevealScript.startAt(i - 1));
        }
    }

    @Test
    public void progressRisesOnlyAfterItsOwnStart() {
        assertEquals(0f, RevealScript.progressOf(RevealScript.TITLE, 0.5f), 0f);
        assertEquals(0.5f, RevealScript.progressOf(RevealScript.TITLE, 0.64f), 1e-6f);
        assertEquals(1f, RevealScript.progressOf(RevealScript.TITLE, 5f), 0f);
        // 越界的段号永远不露出（漏编号会画到屏外，而不是崩）
        assertEquals(0f, RevealScript.progressOf(RevealScript.COUNT, 99f), 0f);
    }

    @Test
    public void cardRowsAreStaggeredNotSimultaneous() {
        // t=2.05：第 0 行起于 1.90（走了 0.83），第 1 行起于 2.00（走了 0.28），第 4 行还没轮到
        float t = 2.05f;
        assertEquals(0.8333f, RevealScript.cardProgressOf(0, t), 1e-3f);
        assertEquals(0.2777f, RevealScript.cardProgressOf(1, t), 1e-3f);
        assertEquals(0f, RevealScript.cardProgressOf(4, t), 0f);
        assertTrue(RevealScript.cardProgressOf(0, t) > RevealScript.cardProgressOf(1, t));
        // 1.90 + 4*0.10 + 0.18 = 2.48，第 5 行到此全部露完（取 2.6 留出浮点余量）
        assertEquals(1f, RevealScript.cardProgressOf(4, 2.6f), 0f);
    }

    @Test
    public void fiveCardRowsFinishBeforeAchievements() {
        // 规格把成就钉在 2.50s。行数一旦加到 6，逐行弹入会撞进成就段——那时必须改的是行步长，
        // 不是把这条断言删掉。
        assertEquals(5, RevealScript.CARD_ROWS);
        assertTrue(RevealScript.CARDS_DONE_AT <= RevealScript.startAt(RevealScript.ACHIEVEMENTS));
    }

    @Test
    public void buttonsIgnoreTapsBeforeTheAntiMisTapWindow() {
        assertFalse(RevealScript.buttonsLive(3.19f));
        assertTrue(RevealScript.buttonsLive(3.2f));
        assertTrue(RevealScript.buttonsLive(30f));
    }

    @Test
    public void easeOutBackEndPointsAndOvershoot() {
        assertEquals(0f, Easing.easeOutBack(0f), 1e-6f);
        assertEquals(1f, Easing.easeOutBack(1f), 1e-6f);
        assertTrue("没有过冲就不叫 easeOutBack", Easing.easeOutBack(0.7f) > 1f);
        // 不许过冲的量用 cubic
        assertEquals(0f, Easing.easeOutCubic(0f), 1e-6f);
        assertEquals(1f, Easing.easeOutCubic(1f), 1e-6f);
        assertTrue(Easing.easeOutCubic(0.5f) < 1f);
    }
}
