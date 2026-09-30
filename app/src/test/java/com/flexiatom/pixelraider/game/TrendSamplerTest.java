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
package com.flexiatom.pixelraider.game;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 趋势采样的口径（规格 §四 暂停面板：每 1 秒一帧、只存累计量、速率靠相邻帧相减）。
 *
 * 折线图最骗人的地方是"看起来有数据"：一帧就画线、卡帧补出一串假点、窗口覆盖错序，
 * 玩家都会当成战况解读。所以这里钉的是**采样语义**，不是像素。
 */
public class TrendSamplerTest {

    @Test
    public void nothingIsSampledBeforeTheFirstSecond() {
        TrendSampler t = new TrendSampler();
        assertFalse(t.tick(0.5f, 3, 500L, 0.9f));
        assertEquals(0, t.frames());
        assertFalse("不足两帧不许画线（该显示\"采样中…\"）", t.hasSeries());
        assertTrue(t.tick(0.6f, 4, 700L, 0.8f));
        assertEquals(1, t.frames());
        assertFalse(t.hasSeries());
    }

    @Test
    public void twoFramesAreEnoughForOneRatePoint() {
        TrendSampler t = new TrendSampler();
        t.tick(1f, 2, 300L, 1f);
        t.tick(1f, 7, 900L, 0.5f);
        assertEquals(2, t.frames());
        assertTrue(t.hasSeries());
        assertEquals("两帧只相减得出一个速率点（n 帧 ⇒ n-1 段）",
                1, t.pointCount(TrendSampler.TAB_KILLS));
        assertEquals(5f, t.valueAt(TrendSampler.TAB_KILLS, 0), 1e-5f);
        assertEquals(600f, t.valueAt(TrendSampler.TAB_SCORE, 0), 1e-5f);
        // 生命是"量"不是"率"：点数等于帧数，读数就是那一帧的比例
        assertEquals(2, t.pointCount(TrendSampler.TAB_HP));
        assertEquals(1f, t.valueAt(TrendSampler.TAB_HP, 0), 1e-5f);
        assertEquals(0.5f, t.valueAt(TrendSampler.TAB_HP, 1), 1e-5f);
    }

    @Test
    public void outOfRangeReadsAreZeroNotCrash() {
        TrendSampler t = new TrendSampler();
        t.tick(1f, 1, 10L, 1f);
        t.tick(1f, 2, 20L, 1f);
        assertEquals(0f, t.valueAt(TrendSampler.TAB_KILLS, -1), 0f);
        assertEquals(0f, t.valueAt(TrendSampler.TAB_KILLS, 99), 0f);
    }

    @Test
    public void aLongStallAddsOneFrameNotThree() {
        TrendSampler t = new TrendSampler();
        assertTrue(t.tick(5f, 10, 1000L, 0.7f));
        assertEquals("卡帧的欠账要清零，否则横轴上会挤出一串同刻的假点", 1, t.frames());
        assertFalse(t.tick(0.5f, 11, 1100L, 0.6f));
        assertEquals(1, t.frames());
    }

    @Test
    public void pausedWorldTimeDoesNotSample() {
        TrendSampler t = new TrendSampler();
        assertFalse(t.tick(0f, 5, 500L, 0.5f));
        assertFalse(t.tick(-1f, 5, 500L, 0.5f));
        assertEquals(0, t.frames());
    }

    @Test
    public void windowWrapsAndDropsTheOldestFrame() {
        TrendSampler t = new TrendSampler();
        for (int i = 1; i <= 30; i++) {
            t.tick(1f, 10 * i, 100L * i, i);
        }
        assertEquals(TrendSampler.CAP, t.frames());
        // 速率处处相同，光看折线分不出窗口滑到哪了；生命那一列是"累计序号"，它才钉得住覆盖顺序：
        // 30 次采样留下最后 24 帧 ⇒ 最旧点是 7，最新点是 30
        assertEquals(TrendSampler.CAP - 1, t.pointCount(TrendSampler.TAB_KILLS));
        assertEquals(10f, t.valueAt(TrendSampler.TAB_KILLS, 0), 1e-5f);
        assertEquals(10f, t.valueAt(TrendSampler.TAB_KILLS, t.pointCount(TrendSampler.TAB_KILLS) - 1), 1e-5f);
        assertEquals(7f, t.valueAt(TrendSampler.TAB_HP, 0), 1e-5f);
        assertEquals(30f, t.valueAt(TrendSampler.TAB_HP, TrendSampler.CAP - 1), 1e-5f);
    }

    @Test
    public void resetForgetsWithTheNewRun() {
        TrendSampler t = new TrendSampler();
        t.tick(1f, 4, 40L, 0.9f);
        t.tick(1f, 6, 60L, 0.8f);
        t.reset();
        assertEquals(0, t.frames());
        assertFalse(t.hasSeries());
    }
}
