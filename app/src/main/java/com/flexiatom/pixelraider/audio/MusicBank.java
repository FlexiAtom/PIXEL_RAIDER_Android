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

/**
 * 三条 BGM（菜单／交战／Boss）的配方与整循环渲染。
 *
 * <p>音符表、tempo 倍率（0.75／1.0／1.12）、lead 占空、bass 音色与两路增益照上游**参数表**重写。
 * 输出是**单声道**整循环样本：P2 里 BGM 与音效走同一条"启动期生成 wav"的路，
 * 只是它交给 {@link android.media.MediaPlayer} 流式播（20 秒的循环常驻内存不值）。
 *
 * <p><b>换轨语义＝重置指针从头</b>，无交叉淡入（用户 2026-10-05 逐字「只废 BGM 那半句」，
 * 作废的是规格 §七 :294 那条双播放器提前 200ms 的调度）。循环点的接缝我量过：首样本恒 0.0000，
 * 尾样本 MENU 0.0060／PLAY 0.0245／BOSS 0.0038——阶跃因此**就等于尾样本那一格**（最大 2.5% 满幅）。
 * 那几帧是 {@link #loopFrames} 取整后多出来的下一轮起音，读数由 {@code MusicBankTest} 钉住。
 */
public final class MusicBank {

    /** 三条轨的上游基准 tempo。 */
    private static final float BPM = 132f;

    public static final int TRACK_COUNT = 3;

    private MusicBank() {
    }

    private static final class Track {
        final float tempo;
        final int[][] mel;
        final int[][] bass;
        final int leadDuty;
        final int bassTim;          // 1＝三角，2＝噪声
        final float leadGain;
        final float bassGain;

        Track(float tempo, int[][] mel, int[][] bass, int leadDuty, int bassTim,
              float leadGain, float bassGain) {
            this.tempo = tempo;
            this.mel = mel;
            this.bass = bass;
            this.leadDuty = leadDuty;
            this.bassTim = bassTim;
            this.leadGain = leadGain;
            this.bassGain = bassGain;
        }
    }

    private static final Track[] TRACKS = {
            new Track(BPM * 0.75f,
                    new int[][]{{60, 4}, {63, 4}, {67, 4}, {70, 4}, {69, 4}, {67, 4}, {63, 4}, {60, 4},
                            {58, 4}, {62, 4}, {65, 4}, {70, 4}, {69, 4}, {65, 4}, {62, 4}, {58, 4}},
                    new int[][]{{36, 8}, {43, 8}, {36, 8}, {41, 8}, {34, 8}, {41, 8}, {36, 8}, {43, 8}},
                    50, 1, 0.30f, 0.24f),
            new Track(BPM,
                    new int[][]{{60, 2}, {60, 2}, {63, 2}, {65, 2}, {67, 4}, {65, 2}, {63, 2},
                            {60, 2}, {60, 2}, {58, 2}, {60, 2}, {63, 4}, {60, 4},
                            {65, 2}, {65, 2}, {67, 2}, {70, 2}, {72, 4}, {70, 2}, {67, 2},
                            {65, 2}, {63, 2}, {60, 2}, {60, 2}, {63, 2}, {65, 2}, {60, 4}},
                    new int[][]{{36, 4}, {36, 4}, {39, 4}, {39, 4}, {41, 4}, {41, 4}, {43, 4}, {43, 4},
                            {36, 4}, {36, 4}, {39, 4}, {39, 4}, {41, 4}, {43, 4}, {44, 4}, {43, 4}},
                    25, 1, 0.26f, 0.22f),
            new Track(BPM * 1.12f,
                    new int[][]{{60, 2}, {61, 2}, {60, 2}, {63, 2}, {60, 2}, {65, 2}, {63, 2}, {61, 2},
                            {60, 2}, {68, 2}, {67, 2}, {65, 2}, {63, 2}, {61, 2}, {60, 4}},
                    new int[][]{{36, 2}, {36, 2}, {36, 2}, {37, 2}, {38, 2}, {38, 2}, {39, 2}, {39, 2},
                            {36, 2}, {36, 2}, {36, 2}, {37, 2}, {38, 2}, {39, 2}, {40, 2}, {39, 2}},
                    50, 2, 0.28f, 0.20f),
    };

    public static float[] render(int track) {
        int frames = loopFrames(track);
        float[] out = new float[frames];
        Sequencer sq = new Sequencer(track);
        sq.mixInto(out);
        return out;
    }

    /** 一条轨混完整循环所需的帧数（mel／bass 各自音符格数之和取大）。 */
    public static int loopFrames(int track) {
        Track t = TRACKS[track];
        float step = 60f / t.tempo / 4f;
        long mel = 0;
        long bass = 0;
        for (int[] s : t.mel) mel += s[1];
        for (int[] s : t.bass) bass += s[1];
        return (int) (Math.max(mel, bass) * step * ChipWave.RATE);
    }

    private static float freq(int midi) {
        return (float) (440.0 * Math.pow(2.0, (midi - 69) / 12.0));
    }

    /** 一个声部的播放指针。{@code framesLeft == 0} 才推进到下一格，所以 tempo 变了也不会把音符截掉。 */
    private static final class Part {
        private final int[][] seq;
        private final float gain;
        private final int duty;
        private final boolean tri;
        private final boolean noise;
        private final ChipWave.Noise nz = new ChipWave.Noise(0x2001);
        private final ChipWave.Triangle tr = new ChipWave.Triangle();
        private ChipWave.Square sq = new ChipWave.Square(0.5f);
        private int idx;
        private int framesLeft;
        private int noteFrames;
        private int frameInNote;
        private float curFreq;
        private boolean rest;

        Part(int[][] seq, int duty, boolean tri, boolean noise, float gain) {
            this.seq = seq;
            this.duty = duty;
            this.tri = tri;
            this.noise = noise;
            this.gain = gain;
        }

        float sample(float stepSecs) {
            if (framesLeft == 0) {
                int[] note = seq[idx];
                int total = Math.max(1, (int) (note[1] * stepSecs * ChipWave.RATE));
                noteFrames = total;
                framesLeft = total;
                frameInNote = 0;
                rest = note[0] == 0;
                curFreq = freq(note[0]);
                if (!tri && !noise) sq = new ChipWave.Square(duty / 100f);
                idx = (idx + 1) % seq.length;
            }
            framesLeft--;
            float t = (float) frameInNote / ChipWave.RATE;
            float frac = (float) frameInNote / noteFrames;
            frameInNote++;
            if (rest) return 0f;
            float decay = 2f + 60f / ((float) noteFrames / ChipWave.RATE * 30f + 1f);
            float env = Math.min(ChipWave.envelope(t, 0.004f, decay), 1f - frac * 0.15f);
            float wave = noise ? nz.sample(Math.max(2000f, curFreq))
                    : (tri ? tr.sample(curFreq) : sq.sample(curFreq));
            return gain * wave * Math.max(0f, env);
        }
    }

    private static final class Sequencer {
        private final Part lead;
        private final Part bass;
        private final float stepSecs;

        Sequencer(int track) {
            Track t = TRACKS[track];
            stepSecs = 60f / t.tempo / 4f;
            lead = new Part(t.mel, t.leadDuty, false, false, t.leadGain);
            bass = new Part(t.bass, 0, t.bassTim == 1, t.bassTim == 2, t.bassGain);
        }

        void mixInto(float[] out) {
            for (int i = 0; i < out.length; i++) out[i] += lead.sample(stepSecs) + bass.sample(stepSecs);
        }
    }
}
