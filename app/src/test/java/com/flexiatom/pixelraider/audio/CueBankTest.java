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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.flexiatom.pixelraider.game.AudioCues;

import org.junit.Test;

/**
 * 波形面（P2 的生成侧）在 JVM 里能钉住的那三件：**装得下、不溢出、闷只落在该闷的那条总线上**。
 *
 * <p>本类刻意不重算任何配方——它不问"这只声音该是什么形状"（那是听感，主观项不立案），
 * 只问三条可判定的边界：样本数有限且不过 1.0 满幅（溢出等于削成方波，是失真不是音量）、
 * 世界总线那只与未 {@code muffle} 的那只不逐样本相同（"闷"真的烘进去了）、
 * UI 那两只短过规格 §七 :293 写死的 30 ms。这三条 any 一条挂了都不需要耳朵。
 */
public final class CueBankTest {

    /** 满幅上限。{@code ChipWave.build} 收尾做 clamp，所以越过 1.0 只能是配方写错（增益相加）。 */
    private static final float FULL_SCALE = 1.0f;

    @Test
    public void everyCueIsNonEmptyFiniteAndWithinFullScale() {
        for (int cue = 0; cue < AudioCues.COUNT; cue++) {
            float[] w = CueBank.render(cue);
            assertTrue("空波形（cue=" + cue + "）：装载会得到一个只有头的 wav", w.length > 0);
            float peak = 0f;
            for (int i = 0; i < w.length; i++) {
                float v = w[i];
                assertFalse("NaN 出现在 cue=" + cue + " 的第 " + i + " 样本", Float.isNaN(v));
                assertTrue("无穷出现在 cue=" + cue, Float.isFinite(v));
                if (Math.abs(v) > peak) peak = Math.abs(v);
            }
            assertTrue("cue=" + cue + " 峰值只有 " + peak + "，等于静音", peak > 0f);
            assertTrue("cue=" + cue + " 峰值 " + peak + " 越过满幅 ⇒ 量化处会被削",
                    peak <= FULL_SCALE + 1e-6f);
        }
    }

    /**
     * 首样本必须是 0。起音从 0 抬起（{@code envelope} 的线性 attack）是"循环点听不出接缝"的前提：
     * 起点跳变＝一个阶跃＝一声咔哒，那是可判的失真，不是听感。
     */
    @Test
    public void everyCueStartsAtZero() {
        for (int cue = 0; cue < AudioCues.COUNT; cue++) {
            assertEquals("cue=" + cue + " 的首样本不是 0，装载后会有一声咔哒",
                    0f, CueBank.render(cue)[0], 1e-6f);
        }
    }

    /**
     * "闷"只挂在世界总线上。这条钉的是规格 §七 :291 在 P2 下的落点：它从制作期参数变成
     * 生成期参数，于是**只有** {@code busOf == BUS_WORLD} 那几只会被低通改写；玩家总线要是也被闷，
     * :290 那句"玩家射击清脆"就成了假话，而这件事耳朵一耳朵就能听出来。
     */
    @Test
    public void muffleAppliesToWorldBusOnly() {
        for (int cue = 0; cue < AudioCues.COUNT; cue++) {
            float[] raw = CueBank.waveOf(cue);
            float[] rendered = CueBank.render(cue);
            assertEquals("render 不该改长度（cue=" + cue + "）", raw.length, rendered.length);
            boolean world = AudioCues.busOf(cue) == AudioCues.BUS_WORLD;
            boolean differs = false;
            for (int i = 0; i < raw.length; i++) {
                if (raw[i] != rendered[i]) {
                    differs = true;
                    break;
                }
            }
            if (world) {
                assertTrue("cue=" + cue + " 在世界总线上却没被闷 ⇒ :291 的生成期参数丢了", differs);
            } else {
                assertFalse("cue=" + cue + " 在玩家总线上却被闷了 ⇒ :290 那句「清脆」没了", differs);
            }
        }
    }

    /** 世界总线那五只**逐样本相同于** waveOf 再过一遍 muffle：改一处配方不会悄悄漏掉另一处。 */
    @Test
    public void worldCueIsExactlyWaveOfMuffled() {
        for (int cue = 0; cue < AudioCues.COUNT; cue++) {
            if (AudioCues.busOf(cue) != AudioCues.BUS_WORLD) continue;
            float[] expect = CueBank.waveOf(cue);
            ChipWave.muffle(expect);
            float[] actual = CueBank.render(cue);
            assertEquals("cue=" + cue, expect.length, actual.length);
            for (int i = 0; i < expect.length; i++) {
                assertEquals("cue=" + cue + " 第 " + i + " 样本", expect[i], actual[i], 0f);
            }
        }
    }

    /**
     * 规格 §七 :293 写死"UI 音 30 ms 方波"。这两只是 HUD 状态提示，长过那一档就不再是提示音，
     * 而是和弹道事件抢注意力的第二只声音。
     */
    @Test
    public void uiCuesFitTheThirtyMillisecondSlot() {
        int cap = (int) (0.030f * ChipWave.RATE) + 1;
        for (int cue : new int[]{AudioCues.LOCK_ON, AudioCues.RADAR_CUE}) {
            int len = CueBank.render(cue).length;
            assertTrue("cue=" + cue + " 长 " + len + " 样本，超过 30 ms 的 " + cap, len <= cap);
            assertTrue("cue=" + cue + " 短到听不见（" + len + "）", len > ChipWave.RATE / 1000f);
        }
    }

    /**
     * 两只 UI 音必须**分得开**：它们在同一块 HUD 上表示两件事（锁定开眼／雷达指定）。
     *
     * <p>判据不能是时长——规格 :293 把 UI 那一族钉在同一格 30 ms 里，两只都是 1323 样本，
     * 这正是要它们同长的原因。能算的分别只剩音高，所以这里取**过零次数**做读数（方波每周期两次），
     * 不引入任何听感判断。
     */
    @Test
    public void theTwoUiTonesAreDistinguishable() {
        float[] a = CueBank.waveOf(AudioCues.LOCK_ON);
        float[] b = CueBank.waveOf(AudioCues.RADAR_CUE);
        assertEquals("两只都该占满 :293 那一格，长度不一样说明有人改了时长", a.length, b.length);
        int za = zeroCrossings(a);
        int zb = zeroCrossings(b);
        assertTrue("两只 UI 音的音高读数撞了（锁定 " + za + "／雷达 " + zb + " 个过零）⇒ 听不出是哪一个",
                za != zb);
        int same = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] == b[i]) same++;
        }
        assertTrue("两只 UI 音逐样本一致了（" + same + "/" + a.length + "）", same < a.length);
    }

    private static int zeroCrossings(float[] w) {
        int n = 0;
        for (int i = 1; i < w.length; i++) {
            if ((w[i - 1] < 0f) != (w[i] < 0f)) n++;
        }
        return n;
    }

    /** 哑火不给闷（用户 2026-10-05 逐字裁定它归"玩家投资没兑现"那一族，不是爆炸）。 */
    @Test
    public void misfireIsNotMuffledAndSharesNoBusWithExplosions() {
        assertEquals(AudioCues.BUS_PLAYER, AudioCues.busOf(AudioCues.MISFIRE));
        float[] raw = CueBank.waveOf(AudioCues.MISFIRE);
        float[] rendered = CueBank.render(AudioCues.MISFIRE);
        for (int i = 0; i < raw.length; i++) {
            assertEquals("哑火被闷了，第 " + i + " 样本", raw[i], rendered[i], 0f);
        }
    }

    /** 开火那七只（五把直飞枪 + 两档导弹）都归玩家总线，且各有独立配方。 */
    @Test
    public void fireCuesLiveOnThePlayerBus() {
        int[] fire = {
                AudioCues.FIRE_PULSE, AudioCues.FIRE_LASER, AudioCues.FIRE_SPREAD,
                AudioCues.FIRE_ARC, AudioCues.FIRE_SPIN,
                AudioCues.MISSILE_LAUNCH, AudioCues.MISSILE_DOGFIGHT,
        };
        for (int cue : fire) {
            assertEquals("cue=" + cue + " 不在玩家总线", AudioCues.BUS_PLAYER, AudioCues.busOf(cue));
            assertNotEquals("玩家射击档的优先级不该被抬到最高（那是死亡/Boss 那一族）",
                    3, AudioCues.priorityOf(cue));
        }
    }

    /**
     * 普通弹与格斗弹的出膛**必须分得开**（乙档逐字：导弹出膛"普通／格斗各一档"）。
     * 同一段低频 thump 只改增益的话，样本会成比例相同——那在这里判为没分开。
     */
    @Test
    public void guidedLaunchesAreNotScaledCopies() {
        float[] plain = CueBank.waveOf(AudioCues.MISSILE_LAUNCH);
        float[] dog = CueBank.waveOf(AudioCues.MISSILE_DOGFIGHT);
        assertNotEquals("两档导弹出膛一样长", plain.length, dog.length);
        int n = Math.min(plain.length, dog.length);
        int identical = 0;
        for (int i = 0; i < n; i++) {
            if (plain[i] == dog[i]) identical++;
        }
        assertTrue("两档导弹出膛逐样本相同（" + identical + "/" + n + "）", identical < n);
    }

    /** 长度落在配方写的那个量级上：差一个数量级说明 durS 参数被谁改动过。 */
    @Test
    public void durationsStayInTheirWrittenOrder() {
        int death = CueBank.render(AudioCues.PLAYER_DEATH).length;
        int fire = CueBank.render(AudioCues.FIRE_PULSE).length;
        int bossSpawn = CueBank.render(AudioCues.BOSS_SPAWN).length;
        assertTrue("开火音长过死亡音（" + fire + " vs " + death + "）⇒ 时长表串了", fire < death);
        assertTrue("Boss 出场短于死亡（" + bossSpawn + " vs " + death + "）", bossSpawn >= death);
        assertTrue("开火音超过半秒（" + fire + "），那是提示不是枪声",
                fire < 0.5f * ChipWave.RATE);
    }
}
