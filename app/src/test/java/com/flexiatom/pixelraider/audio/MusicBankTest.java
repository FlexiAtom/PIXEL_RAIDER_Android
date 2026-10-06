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
package com.flexiatom.pixelraider.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.flexiatom.pixelraider.game.AudioCues;

import org.junit.Test;

/**
 * 三条 BGM 的循环面。P2 走的是"一条轨烘成一整段 wav，播放器整段循环"，于是
 * **循环接缝**是唯一一处能被算术判定的听感问题：接缝两头的差就是一次硬阶跃。
 *
 * <p>这里不判"这条轨好不好听"（主观项不立案，2026-10-05 逐字「手感我没提就是没有问题」），
 * 只钉四件事：渲染长度等于 {@link MusicBank#loopFrames}、样本有限、不越满幅、接缝阶跃不超过读数上限。
 * 前三条挂了是数学错了，第四条挂了是包络改动把循环点顶开了——那正是"烘成文件"
 * 相对实时合成唯一多出来的风险，所以它必须有网。
 */
public final class MusicBankTest {

    /** 采样数下限：短到这个数以下的"轨"根本不可能有旋律，是配置串行的信号。 */
    private static final int MIN_FRAMES = 44100;

    @Test
    public void renderLengthEqualsLoopFramesAndIsFinite() {
        for (int track = 0; track < MusicBank.TRACK_COUNT; track++) {
            float[] w = MusicBank.render(track);
            assertEquals("轨 " + track + " 渲出来的长度不等于 loopFrames",
                    MusicBank.loopFrames(track), w.length);
            assertTrue("轨 " + track + " 只有 " + w.length + " 帧", w.length >= MIN_FRAMES);
            float peak = 0f;
            for (float v : w) {
                assertFalse("轨 " + track + " 出现 NaN", Float.isNaN(v));
                assertTrue("轨 " + track + " 出现无穷", Float.isFinite(v));
                if (Math.abs(v) > peak) peak = Math.abs(v);
            }
            assertTrue("轨 " + track + " 峰值 " + peak + "，等于静音", peak > 0f);
            assertTrue("轨 " + track + " 峰值 " + peak + " 越过满幅", peak <= 1.0f + 1e-6f);
        }
    }

    /**
     * 循环接缝是一次**量出来的**阶跃，不是设计成 0 的那个数。
     *
     * <p>P2 把整段烘成文件、播放器到结尾把指针重置回 0（规格 §七 :294 作废 ⇒ 无交叉淡入），
     * 于是接缝两头的差就是一步硬跳。首样本必为 0（包络从 0 起），尾样本却不是：
     * {@link MusicBank#loopFrames} 由「音符格数 × step × 采样率」取整得出，而每个声部各自把自己的
     * 每条音符取整，循环末尾因此多出几帧——那是**下一轮起音**的头几格，高度就是包络在那几毫秒里的值。
     * 2026-10-05 实算：MENU 0.0060／PLAY 0.0245／BOSS 0.0038（同一段里最大的一格 ≈ 满幅 2.5%）。
     *
     * <p>所以这条钉的是"接缝跳不过满幅的 3%"。它拦的是包络或增益被改大到阶跃真能听见的那一档，
     * 不拦那几帧的存在——那几帧要消掉得动 loopFrames 的取整口径，连带让所有已装设备重生成一遍。
     */
    @Test
    public void loopSeamIsAMeasuredStepNotADesignedZero() {
        for (int track = 0; track < MusicBank.TRACK_COUNT; track++) {
            float[] w = MusicBank.render(track);
            assertEquals("轨 " + track + " 的首样本不为 0", 0f, w[0], 1e-6f);
            float step = Math.abs(w[w.length - 1] - w[0]);
            assertTrue("轨 " + track + " 的循环阶跃 " + step + " 顶过了 0.03 满幅", step <= 0.03f);
        }
    }

    /** 三条轨的轨号与 {@link AudioCues} 那三个常量同源，且**互不相同**（换轨才有意义）。 */
    @Test
    public void trackIdsMatchTheCueTableAndDiffer() {
        assertEquals(0, AudioCues.BGM_MENU);
        assertEquals(1, AudioCues.BGM_PLAY);
        assertEquals(2, AudioCues.BGM_BOSS);
        assertEquals("轨数与 AudioCues 的三个常量脱钩了", 3, MusicBank.TRACK_COUNT);
        assertDifferentNames(AudioCues.bgmFileNameOf(AudioCues.BGM_MENU),
                AudioCues.bgmFileNameOf(AudioCues.BGM_PLAY));
        assertDifferentNames(AudioCues.bgmFileNameOf(AudioCues.BGM_PLAY),
                AudioCues.bgmFileNameOf(AudioCues.BGM_BOSS));
    }

    /**
     * 三条轨的循环长度按 {@code MENU > PLAY > BOSS} 排。这个次序由 tempo（0.75/1.0/1.12）
     * 与音符格数共同派生，改任何一条轨的表都可能把它翻过来——翻过来不一定是错，但它必须
     * 是**有人看过这条断言之后**决定的，不是顺带发生的。
     */
    @Test
    public void loopLengthOrdersMenuLongestAndBossShortest() {
        int menu = MusicBank.loopFrames(AudioCues.BGM_MENU);
        int play = MusicBank.loopFrames(AudioCues.BGM_PLAY);
        int boss = MusicBank.loopFrames(AudioCues.BGM_BOSS);
        assertTrue("菜单轨(" + menu + ")应当长过战斗轨(" + play + ")", menu > play);
        assertTrue("战斗轨(" + play + ")应当长过 Boss 轨(" + boss + ")", play > boss);
    }

    private static void assertDifferentNames(String a, String b) {
        assertFalse("两条轨共用文件名 " + a + " ⇒ 缓存键会互相覆盖", a.equals(b));
    }
}
