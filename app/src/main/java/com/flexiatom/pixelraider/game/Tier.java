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
 * 难度档：休闲／普通／终结（[规格 §8.2] 三档签字表；档位名与"三档"这个形状是他给的，五个乘子与
 * {@code eliteFloor} 是我定并署名的数）。
 *
 * <p><b>为什么是一张 3 行的乘子表，而不是三份 {@code Balance.Wave}</b>：七个分叉点里只有敌数量／
 * 精英权重／出怪间隔读那张波次形态表，小怪 hp、受伤、掉落三条<b>根本不读 cfg</b>（各在 {@link Game}
 * 里直取 {@link Balance}）。复制整枚 {@code Balance.Wave} 会把那些不相干的格一并复制，于是"改一档"
 * 要顺带核对六格有没有被漏染；这张表只装分叉的那七格，每格仍然只有唯一一个读取点。
 *
 * <p><b>普通档全 1.00 且 {@code eliteFloor = 2} 是刻意写的</b>：那是今天的基线读数，所以
 * {@code WaveDirectorTest} 钉死的那批数（敌数表、权重和 212/225、gap 0.55→0.22）在普通档红了
 * ＝ 实现漏染基线，而不是"该改测试"。掉落那一列取敌数量的近似倒数（终结 0.75 ≈ 1/1.34；休闲
 * 0.85 × 1.25 = 1.06）——否则"高难不多拿"在算术上不成立。
 *
 * <p><b>字段 final，与 {@link Balance} 的口径相反</b>：那张表要"运行期改表、下一帧生效"，这一枚
 * 跟着<b>一整局</b>走。中途换档会把已经刷出来的怪和接下来要刷的怪切成两套规则（HUD 那条"敌人"
 * 进度条的分母会当场与队列不符），所以换档只有 {@link Game} 的整局重置一处能做。
 *
 * <p>两条<b>不在表上</b>的口径，免得下一个人按字面去补：① <b>Boss 不吃 {@link #hpMul}</b>——Boss 血
 * 走 {@code WaveDirector.bossHpFor} 那条 1.45^n 的独立写点，终结档若再乘 1.75，第 8 个 Boss 会推到
 * ≈2830 血 ≈ 单场多打一分钟；② <b>商店价不分档</b>——那一枚乘点被卡面／扣币／{@code canAfford}
 * 三处共享，各档全 1.00 的死参数不值得那个风险（真要按档改价，唯一落点仍是 {@code discountFor}）。
 */
public final class Tier {

    /** [签字] 小怪 hp 乘子。Boss 与自爆比例伤害不在此轴（见类注释①）。 */
    public final float hpMul;
    /** [签字] 每波敌数量乘子。⚠ 必须落在 {@code enemyCountFor} 内部，落在建队列那侧会让进度条分母与队列不符。 */
    public final float countMul;
    /** [签字] 玩家受伤乘子，与状态层那枚在同一条唯一出口上相乘。 */
    public final float takenMul;
    /** [签字] 掉落乘子，挂两条入账口而不是概率侧（概率侧有 {@code clamp01} 上界，会与幸运卡相乘后被静默削掉）。 */
    public final float dropMul;
    /** [签字] 出怪间隔乘子。高波段会被 {@code Balance.Wave.spawnGapMin} 吃掉——那是"同屏压力不该无限增大"那道闸，不许穿透。 */
    public final float gapMul;
    /** [可调] 精英权重在 {@code WaveDirector.kindWeightsFor} 里的下限（普通档＝今天那个字面量 2）。 */
    public final int eliteFloor;

    Tier(float hpMul, float countMul, float takenMul, float dropMul, float gapMul, int eliteFloor) {
        this.hpMul = hpMul;
        this.countMul = countMul;
        this.takenMul = takenMul;
        this.dropMul = dropMul;
        this.gapMul = gapMul;
        this.eliteFloor = eliteFloor;
    }

    /** [规格 §8.2] 休闲：厚补贴薄威胁——hp 0.75／受伤 0.70，但掉落给到 1.25。 */
    public static final Tier CASUAL = new Tier(0.75f, 0.85f, 0.70f, 1.25f, 1.15f, 2);
    /** [规格 §8.2] 普通＝基线，一字节不动。 */
    public static final Tier NORMAL = new Tier(1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 2);
    /** [规格 §8.2] 终结：hp 1.75 × count 1.34 ＝ 波总血量 2.35 倍封顶（审查打掉的 2.20 × 1.55 那一档会到 3.4 倍）。 */
    public static final Tier TERMINAL = new Tier(1.75f, 1.34f, 1.30f, 0.75f, 0.86f, 6);

    /**
     * 档号 → 档，序＝上面三格的声明序（休闲 0／普通 1／终结 2）。
     *
     * <p>它是 {@code SaveSlots.tierOrdinal} 的<b>唯一逆映射</b>：那枚 2 位段的打包与解包各只有一处，
     * 读档侧不必再手写一串 {@code if}（那种副本是"槽位列表写着终结档、局内跑普通档"的来源）。
     *
     * <p>越界（两位段装得下 3）夹到 {@link #NORMAL} 而不是抛：这一格的输入是<b>盘上的旧数</b>，为一个
     * 显示用的位段把整次读档炸掉不划算。夹成基线的后果是"这局按普通档跑"——玩家看得见，也解释得清。
     */
    public static Tier at(int ordinal) {
        if (ordinal == 0) return CASUAL;
        if (ordinal == 2) return TERMINAL;
        return NORMAL;
    }

    /** {@link #at} 的另一向。两格同住本类是为了让"声明序"只有一个地方在数（{@code SaveSlots} 的位段读它）。 */
    public static int ordinalOf(Tier tier) {
        if (tier == CASUAL) return 0;
        return tier == TERMINAL ? 2 : 1;
    }
}
