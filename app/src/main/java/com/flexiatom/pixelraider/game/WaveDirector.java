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

    /**
     * 本局的模式（[规格 §5.5] 那张分叉清单的真源）。档在它里面（{@link Mode#tier}），所以这里不再
     * 单存一份——两枚字段会给出"读到两份档"的窗口，而 {@link Game#applyMode} 是唯一写点。
     *
     * <p>volatile 的理由与 {@code InputRouter.metrics} 同一条：整局重置有**一条**调用链来自 UI 线程
     * （{@code MainActivity → GameSurfaceView.applyMetrics → onSurfaceReady}），而这些格在渲染线程上读。
     * 引用写本身原子，所以不加它会有的后果不是撕裂，是"新一局头几帧还按上一局的模式出怪"。
     */
    private volatile Mode mode = Mode.CAMPAIGN;

    public WaveDirector() {
        this(Balance.wave, Mode.CAMPAIGN);
    }

    public WaveDirector(Balance.Wave cfg) {
        this(cfg, Mode.CAMPAIGN);
    }

    public WaveDirector(Balance.Wave cfg, Mode mode) {
        this.cfg = cfg;
        this.mode = mode;
    }

    /** 唯一的换模式入口（由 `Game` 的整局重置调用）。模式与档都是不可变的，所以这里只换引用。 */
    public void setMode(Mode mode) {
        this.mode = mode;
    }

    /**
     * [规格] 战役侧敌人数用**对数增长** {@code 5 + floor(n^0.72 × 3)}，不是线性的 5 + n×3。
     * 线性公式在 15 波后超出清场能力——那是数值问题，不是技术问题。
     *
     * <p>⚠ 上面那句理由<b>只在战役成立</b>（[规格 §5.5]）：它的隐含前提是"40 波有终局、每波之间有商店"。
     * 无尽撤了终态就要换一条能一直长的形状，取线性 {@code base + endlessStep×(n-1)}，同屏压力改由
     * {@link Balance.Wave#maxAlive} 的排队与 {@link Balance.Wave#endlessBatchSize} 的批次管。
     * 两条曲线<b>实算</b>是第 7 波相交（都是 17 只）：之前无尽更少（第 1 波 5 vs 8），之后更陡
     * （第 40 波 83 vs 47 ≈1.8 倍）。无尽的压力因此落在"同屏更多"那一侧，不是"个体更厚"
     * （hp 那两道闸见 {@link Mode#hpGrowth}）。
     *
     * <p>[规格 §8.2] 档位的 {@link Tier#countMul} 乘在**整波总数**上（不是乘在 base 上、也不是乘在
     * 那个 3 上）：休闲档第 39 波 46 → 39 只，少的那 7 只是这条链给的。乘子必须落在这个方法内部，
     * 落到建队列那侧会让 HUD"敌人"条的分母（{@link #waveTotalFor}）与队列实际排的数量不符。
     */
    public int enemyCountFor(int wave) {
        if (wave < 1) return 0;
        int n = mode.campaign
                ? cfg.base + (int) Math.floor(Math.pow(wave, cfg.exponent) * cfg.multiplier)
                : cfg.base + cfg.endlessStep * (wave - 1);
        n = Math.round(n * mode.tier.countMul);
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

    /**
     * Boss 血量随波次长：[可调] 每轮 Boss 比上一轮厚 45%。
     *
     * <p>⚠ 这条指数是给<b>八轮</b>设计的（战役第 40 波 nth=8，堡垒 120 → 1617，已是单场多打一分钟的量）。
     * 无尽没有第 9 轮之后该多厚的裁定，所以把轮次夹在 {@link Mode#chapterCount()} 那一轮＝<b>无尽里
     * Boss 是节拍而不是成长</b>。战役侧这一夹是惰性的（nth 最大正好等于 8）⇒ 基线读数一字节不动。
     * {@code 1.45} 那枚字面量仍在本方法里（与 {@code Balance.bosses} 那批数值不同源），改动它以前
     * 先想清楚战役第 8 章那场堡垒战要不要一起变。
     *
     * <p>⚠ 这一夹读 {@link Mode#chapterCount()}（那格走全局 {@code Balance.wave}），而 {@code bossEvery}
     * 走 {@link #cfg}。今天每个构造点传的都是同一枚全局表 ⇒ 同源；将来若真注入自定义 cfg，这一格会分叉。
     */
    public int bossHpFor(int wave) {
        Balance.Boss b = Balance.bosses[bossIndexFor(wave)];
        int nth = Math.max(1, wave / cfg.bossEvery);
        if (!mode.campaign) nth = Math.min(nth, mode.chapterCount());
        return Math.round(b.hp * (float) Math.pow(1.45, nth - 1));
    }

    /**
     * [规格] 基础小怪权重随波次衰减，高级怪逐步接管。
     * 返回 6 种敌人的权重（和不必为 1，由调用方按总权重抽），写入 {@code outWeights}。
     *
     * <p>[规格 §8.5] 档位只动**精英那一格的下限**（{@link Tier#eliteFloor}）：普通档那个字面量是 2，
     * 终结档给 6——其余五格是"过渡曲线"，与档无关。这是全仓第一格由难度档改**形状量**而不是改乘子的
     * 分叉点，所以它进表而不是进某枚乘子。
     */
    public int kindWeightsFor(int wave, int[] outWeights) {
        float t = Math.min(1f, Math.max(0f, (wave - 1) / 20f));   // 20 波内完成接管过渡
        outWeights[Balance.Enemy.STRAIGHT] = Math.max(1, Math.round(100 * (1f - t * 0.85f)));
        outWeights[Balance.Enemy.WEAVE] = Math.max(1, Math.round(80 * (1f - t * 0.70f)));
        outWeights[Balance.Enemy.SHOOTER] = Math.round(18 + 46 * t);
        outWeights[Balance.Enemy.RUSHER] = Math.round(8 + 52 * t);
        outWeights[Balance.Enemy.ELITE] = Math.round(mode.tier.eliteFloor + 30 * t);
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

    /**
     * 相邻出怪间隔：随波次略微收紧，钳下限。
     *
     * <p>[规格 §8.2] 档位的 {@link Tier#gapMul} 乘在**收紧之后的整段间隔**上，而 {@code spawnGapMin}
     * 按**未缩放的原始值**钳——即终结档那 0.86 只在低波段真的更紧，越接近下限越被这道闸吃掉，永远
     * 到不了 0.22 以下。这不是漏乘，是"同屏压力不该无限增大"那道闸本来的职责。
     */
    public float spawnGapFor(int wave) {
        float g = cfg.spawnGap - (wave - 1) * cfg.spawnGapTightenPerWave;
        if (g < cfg.spawnGapMin) return cfg.spawnGapMin;
        return Math.max(cfg.spawnGapMin, g * mode.tier.gapMul);
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
