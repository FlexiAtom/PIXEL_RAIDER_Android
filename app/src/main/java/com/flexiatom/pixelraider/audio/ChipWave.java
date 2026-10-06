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
 * 芯片波形的公共底座（三种振荡器 + 包络 + 限幅 + 软削波 + 生成器）。
 *
 * <p>形状照上游终端版的合成器：方波带占空比、三角、16 位 LFSR 噪声，包络是"线性起音 + 指数衰减"。
 * **这是重写不是搬运**（用户 2026-10-05 逐字「我们这个是需要用Java实现的，所以没有协议问题（属于重写）」），
 * 语义与参数逐字对照，代码一行不搬。
 *
 * <p>本类不含任何 android 依赖 ⇒ 全部可在 JVM 单测里直接验（规格 §八：能与平台解耦的算术解耦出来）。
 */
public final class ChipWave {

    /** 采样率，与上游一致；换它等于换所有配方的时长读数。 */
    public static final int RATE = 44100;

    /** worldBus 的"闷"：一阶低放的截止频率。**这个数是我定的**，规格只给了"-6dB 高频衰减"那半句（§七 :291）。 */
    static final float MUFFLE_CUTOFF_HZ = 1800f;

    /** -6dB ＝ 0.5 倍幅度，规格 §七 :291 逐字给的那个数的兑现。 */
    static final float MUFFLE_GAIN = 0.5f;

    private ChipWave() {
    }

    /** 一个样本的求值点：{@code t} 是秒、{@code i} 是样本下标。 */
    public interface Osc {
        float at(float t, int i);
    }

    /** 占空比可调的方波。相位累加而不是 {@code sin(t·f)}，是为了让占空比边界精确落在样本上。 */
    public static final class Square {
        private final float duty;
        private float phase;

        public Square(float duty) {
            this.duty = duty;
        }

        public float sample(float freq) {
            phase += freq / RATE;
            if (phase >= 1f) phase -= 1f;
            return phase < duty ? 1f : -1f;
        }
    }

    public static final class Triangle {
        private float phase;

        public float sample(float freq) {
            phase += freq / RATE;
            if (phase >= 1f) phase -= 1f;
            return phase < 0.5f ? 4f * phase - 1f : 3f - 4f * phase;
        }
    }

    /** 16 位 LFSR（x^16 + x^14 + x^13 + x^11 + 1 那一族抽头），噪声按频率而不是按样本刷新。 */
    public static final class Noise {
        private final int seed;
        private int reg;
        private float acc;
        private float last;

        public Noise(int seed) {
            this.seed = seed;
            reg = (seed | 1) & 0xffff;
        }

        public float sample(float freq) {
            acc += freq / RATE;
            while (acc >= 1f) {
                acc -= 1f;
                int bit = (reg ^ (reg >> 2)) & 1;
                reg = (reg >> 1) | (bit << 15);
                last = (reg & 1) == 1 ? 1f : -1f;
            }
            return last;
        }
    }

    public static float envelope(float t, float attack, float decay) {
        if (attack > 0f && t < attack) return t / attack;
        return (float) Math.exp(-decay * (t - attack));
    }

    public static float clamp(float v) {
        return v < -1f ? -1f : (v > 1f ? 1f : v);
    }

    /** 软削波：多声部叠加后先过它再量化，硬顶会把方波削成"滋滋"的奇次谐波。 */
    public static float softClip(float x) {
        if (x >= -1.5f && x <= 1.5f) return clamp(x * (1f - x * x / 9f));
        return x > 0 ? 1f : -1f;
    }

    /** 生成一段单声道波形，每个样本先限幅到 ±1（上游那条断言的形状）。 */
    public static float[] build(float durationSeconds, Osc osc) {
        int n = (int) (durationSeconds * RATE);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = clamp(osc.at((float) i / RATE, i));
        return out;
    }

    /**
     * 就地烘"闷"（规格 §七 :291：worldBus 的高频衰减在**生成期**做完，运行时只调音量与并发）。
     *
     * <p>P2 路线里"生成期"＝启动期那一次，所以这一句仍然是"离线于每帧 DSP"——运行时那侧没有任何滤波旋钮。
     */
    public static void muffle(float[] w) {
        float k = (float) (1.0 - Math.exp(-2.0 * Math.PI * MUFFLE_CUTOFF_HZ / RATE));
        float acc = 0f;
        for (int i = 0; i < w.length; i++) {
            acc += k * (w[i] - acc);
            w[i] = clamp(acc * MUFFLE_GAIN);
        }
    }
}
