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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 面板入场节奏（四块面板共用，所以它自己一份测试，而不是塞在某一块的布局测试里）。
 *
 * <p>这块的正确性肉眼看不出来——0.28 秒和 0.34 秒在手感之外不改变任何一帧的画面，
 * 而"末级按钮在入场结束时只淡到一半"这种错只会表现成"面板打开得有点慢"。只能断言。
 */
public class PanelMotionTest {

    @Test
    public void cascadeCompletesInsideTheEntranceWindow() {
        assertEquals(1f, PanelMotion.enterProgress(PanelMotion.IN_SEC), 1e-5f);
        assertEquals(0.5f, PanelMotion.enterProgress(PanelMotion.IN_SEC / 2f), 1e-5f);
        assertEquals(0f, PanelMotion.enterProgress(-1f), 1e-5f);
        assertEquals(0f, PanelMotion.buttonProgress(0, 0f), 1e-5f);
        assertEquals(1f, PanelMotion.buttonProgress(0, 0.12f), 1e-5f);
        assertEquals(0f, PanelMotion.buttonProgress(2, PanelMotion.BUTTON_STEP * 2f), 1e-5f);
        assertEquals("末级必须在入场结束时刚好落定",
                1f, PanelMotion.buttonProgress(2, PanelMotion.IN_SEC), 1e-5f);
        assertTrue(PanelMotion.buttonProgress(0, 0.2f) >= PanelMotion.buttonProgress(1, 0.2f));
        assertTrue(PanelMotion.buttonProgress(1, 0.2f) >= PanelMotion.buttonProgress(2, 0.2f));
    }

    @Test
    public void maskSettlesAtTheSpecifiedDensityAndNeverBelowTheFloor() {
        assertEquals(0f, PanelMotion.maskAlphaAt(0f), 1e-5f);
        assertEquals(PanelMotion.MASK_ALPHA, PanelMotion.maskAlphaAt(1f), 1e-5f);
        assertTrue("规格：浓度不低于 0.7，否则浅色字压在弹幕上读不出",
                PanelMotion.MASK_ALPHA >= PanelMotion.MASK_FLOOR);
        assertTrue("渐暗是单调的，不许中途闪回",
                PanelMotion.maskAlphaAt(0.3f) < PanelMotion.maskAlphaAt(0.6f));
    }

    /**
     * 位移曲线：起点在最终位之上、终点归零、中途**越过**一次零。
     *
     * <p>那条过冲是 {@code easeOutBack} 的定义，不是 bug——它是"落下来"这个手感的全部来源。
     * 断言写在这里，是因为下一个改曲线的人会把它当成"面板掉过头了"而"修"掉。
     */
    @Test
    public void liftStartsAboveSettlesToZeroAndOvershootsOnce() {
        assertEquals(-28, PanelMotion.liftFor(0f, 28));
        assertEquals(0, PanelMotion.liftFor(1f, 28));
        assertTrue("前段从上方落下来", PanelMotion.liftFor(0.2f, 28) < 0);
        assertTrue("末段略低于最终位（过冲）", PanelMotion.liftFor(0.5f, 28) > 0);
        for (float p = 0f; p <= 1.0001f; p += 0.05f) {
            int lift = PanelMotion.liftFor(p, 28);
            assertTrue("位移越界 p=" + p + " lift=" + lift, lift >= -28 && lift <= 28);
        }
        assertEquals("抬升量为 0 时不该有任何位移", 0, PanelMotion.liftFor(0.3f, 0));
    }
}
