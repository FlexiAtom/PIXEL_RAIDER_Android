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

/**
 * 波次规划（纯函数 + 无 android 依赖，JVM 单测直接验证）。
 *
 * 只回答"这一波该有多少怪、什么组合、什么时候刷、是不是 Boss、在哪个星区"，
 * 不负责刷怪本身——那在 {@link Game}。
 */
public final class WaveDirector {

    private final Balance.Wave cfg;

    public WaveDirector() {
        this(Balance.wave);
    }

    public WaveDirector(Balance.Wave cfg) {
        this.cfg = cfg;
    }

    /**
     * [规格] 敌人数用**对数增长** {@code 5 + floor(n^0.72 × 3)}，不是线性的 5 + n×3。
     * 线性公式在 15 波后超出清场能力——那是数值问题，不是技术问题。
     */
    public int enemyCountFor(int wave) {
        if (wave < 1) return 0;
        int n = cfg.base + (int) Math.floor(Math.pow(wave, cfg.exponent) * cfg.multiplier);
        return Math.min(n, 400);   // 防御：数值表被改坏时不至于刷出天文数字
    }

    /** 本波总敌人数（HUD"敌人"条的分母 = 排队未刷出 + 已在场）。 */
    public int waveTotalFor(int wave) {
        return isBossWave(wave) ? 1 : enemyCountFor(wave);
    }

    public boolean isBossWave(int wave) {
        return wave > 0 && wave % cfg.bossEvery == 0;
    }

    /**
     * [可调] 击杀得分的波次加成：第 n 波 = 1 + 0.04×(n-1)。
     * 加成挂在波次上而不是敌人 hp 上，才能让"后期更值钱"与"后期更难杀"是两件事。
     */
    public float scoreWaveFactor(int wave) {
        int n = wave < 1 ? 1 : wave;
        return 1f + (n - 1) * Balance.score.waveBonusPerWave;
    }

    /** [规格] 3 种 Boss 每 5 波轮换。 */
    public int bossIndexFor(int wave) {
        int nth = wave / cfg.bossEvery;
        int idx = (nth - 1) % cfg.bossCount;
        return idx < 0 ? 0 : idx;
    }

    /** Boss 血量随波次长：[可调] 每轮 Boss 比上一轮厚 45%。 */
    public int bossHpFor(int wave) {
        Balance.Boss b = Balance.bosses[bossIndexFor(wave)];
        int nth = Math.max(1, wave / cfg.bossEvery);
        return Math.round(b.hp * (float) Math.pow(1.45, nth - 1));
    }

    /**
     * [规格] 基础小怪权重随波次衰减，高级怪逐步接管。
     * 返回 6 种敌人的权重（和不必为 1，由调用方按总权重抽），写入 {@code outWeights}。
     */
    public int kindWeightsFor(int wave, int[] outWeights) {
        float t = Math.min(1f, Math.max(0f, (wave - 1) / 20f));   // 20 波内完成接管过渡
        outWeights[Balance.Enemy.STRAIGHT] = Math.max(1, Math.round(100 * (1f - t * 0.85f)));
        outWeights[Balance.Enemy.WEAVE] = Math.max(1, Math.round(80 * (1f - t * 0.70f)));
        outWeights[Balance.Enemy.SHOOTER] = Math.round(18 + 46 * t);
        outWeights[Balance.Enemy.RUSHER] = Math.round(8 + 52 * t);
        outWeights[Balance.Enemy.ELITE] = Math.round(2 + 30 * t);
        outWeights[Balance.Enemy.BURSTER] = Math.round(4 + 26 * t);
        int total = 0;
        for (int i = 0; i < 6; i++) total += outWeights[i];
        return total;
    }

    /** 按权重抽一种（{@code roll} 是 0..1 的随机数，交给调用方保证可复现）。 */
    public int pickKind(int[] weights, int total, float roll) {
        float target = roll * total;
        float acc = 0f;
        for (int i = 0; i < weights.length; i++) {
            acc += weights[i];
            if (target < acc) return i;
        }
        return weights.length - 1;
    }

    /** 相邻出怪间隔：随波次略微收紧，钳下限。 */
    public float spawnGapFor(int wave) {
        float g = cfg.spawnGap - (wave - 1) * cfg.spawnGapTightenPerWave;
        return g < cfg.spawnGapMin ? cfg.spawnGapMin : g;
    }

    /** [规格] 8 星区，前 4 无惩罚覆盖 1~20 波，后 4 带环境规则覆盖 21~40 波。 */
    public int zoneIndexForWave(int wave) {
        int z = (wave - 1) / cfg.wavesPerZone;
        if (z < 0) z = 0;
        return Math.min(z, 7);
    }

    /** 星区 4 个带环境规则（4..7）：寒冷 / 过热 / 磁暴 / 虚空。 */
    public static boolean zoneHasEnvironment(int zoneIndex) {
        return zoneIndex >= 4 && zoneIndex <= 7;
    }

    /** [规格 §三] 8 星区名（简体）。索引固定，越界夹回首区而不是抛——HUD 每帧都要读它。 */
    public static String zoneName(int zoneIndex) {
        int i = zoneIndex < 0 ? 0 : (zoneIndex >= ZONE_NAMES.length ? ZONE_NAMES.length - 1 : zoneIndex);
        return ZONE_NAMES[i];
    }

    private static final String[] ZONE_NAMES = {
            "猎户边境", "玫瑰星云", "麒麟暗域", "赤色深空",
            "冰封环带", "恒星熔炉", "磁暴涡旋", "虚空洞穴",
    };
}
