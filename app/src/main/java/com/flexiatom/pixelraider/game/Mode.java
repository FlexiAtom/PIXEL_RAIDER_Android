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
 * 模式：一局是「战役」还是「无尽」（[规格 §5.5] 那张分叉清单的唯一真源）。
 *
 * <p><b>为什么要立这一枚</b>：在他给这套流程之前，逻辑核里<b>没有"模式"这个概念</b>——全仓只有一条
 * {@code WaveFlow.closeWave} 里的 {@code wave >= Balance.wave.maxWave ⇒ VICTORY}。"无尽"当时只是主菜单
 * 上那枚标签。所以分叉不能靠"读一个全局标签"，得有枚举，否则每个分叉点各自 {@code if} 一遍，
 * 就会长成 [规格 §8.5] 判过的那种"散落判断"。
 *
 * <p><b>它只带两枚字段</b>（{@code campaign} 与 {@link Tier}），<b>不带 chapter</b>：章由波号推得出
 * （{@link #chapterForWave}，八章＝40 波 ＝ {@code wavesPerZone} 那条既有星区界），今天没有任何读者
 * 需要"这一局从第几章起"——选关页与存档（批2/批3）才有那枚读取点。提前加字段就是提前设计。
 *
 * <p><b>战役侧＝今天的基线，一字节不动</b>：对数敌数、逐只出怪、恒 1 的 hp 增长、40 波终局。
 * 主菜单那枚开局键因此仍走 {@link #CAMPAIGN}——这不是妥协而是刻意的：<i>把默认设成无尽会让已交付的
 * 通关终态（[规格 §54] 那条 VICTORY 出口与结算页通关标记）当场不可达</i>。无尽的分叉在逻辑核里已完整
 * 落地并有测试，它的<b>入口</b>随选关页一起落（批3），在那之前那枚键不该改变任何人的读数。
 *
 * <p><b>无尽侧分叉的四格</b>：敌数走线性（{@link Balance.Wave#endlessStep}）、小怪 hp 随波增长
 * （{@link #hpGrowth}）、出怪走批次（{@link #batchesSpawns}）、<b>Boss 轮次封顶</b>
 * （{@link #chapterCount()} 那条夹，见 {@code WaveDirector.bossHpFor}——[规格 §5.5] 那张清单没有这一格，
 * 它是"撤了终态"新暴露的连带：{@code 1.45^n} 是给八轮设计的，无界跑下去第 100 波的堡垒约 15 万血）。
 * 四条都<b>只改形状不改难度档</b>——档那一维由 {@link Tier} 负责，两枚正交，所以
 * {@code 无尽 × 终结} 是"又长又厚"而不是互相覆盖。
 *
 * <p><b>战役侧 hp 恒 1、无尽侧恒跑 {@link Tier#NORMAL}</b> 是本类的一条不变式：两条 hp 来源因此在
 * 任一模式下都<b>只有一轴非 1</b>，乘起来不会双计。这条不是巧合，是设计——选档只发生在战役的选关页，
 * 无尽没有档可挑（[规格 §5] 待裁第 2 格答"不加"）。它由 {@code ModeTest} 钉住。
 *
 * <p><b>字段 final、构造 package-private</b>：与 {@link Tier} 同一条纪律。中途换模式＝把已经刷出来的怪
 * 和接下来要刷的怪切成两套规则，所以换模式只有 {@link Game} 的整局重置一处能做。
 */
public final class Mode {

    /** 战役（普通档）——<b>今天的默认</b>，主菜单那枚开局键走这里。 */
    public static final Mode CAMPAIGN = new Mode(true, Tier.NORMAL);
    /** 无尽（普通档）。今天还没有入口（见类注释），批3 的选关页/主菜单改造把它接上。 */
    public static final Mode ENDLESS = new Mode(false, Tier.NORMAL);

    /** 战役＝true。这一枚是本类唯一的"模式判据"字段，其余规则都从它派生。 */
    public final boolean campaign;
    /** 本局的难度档。无尽侧今天恒 {@link Tier#NORMAL}（无选档入口）。 */
    public final Tier tier;

    Mode(boolean campaign, Tier tier) {
        this.campaign = campaign;
        this.tier = tier;
    }

    /** 战役 + 指定档（选关页那条链的用入口）。 */
    public static Mode campaign(Tier tier) {
        return new Mode(true, tier);
    }

    /** 无尽 + 指定档。⚠ 无尽没有档可挑，这一枚是给"将来若要给"留的形状；今天的调用点全走 {@link #ENDLESS}。 */
    public static Mode endless(Tier tier) {
        return new Mode(false, tier);
    }

    /**
     * 这一局到底不封顶。<b>全仓唯一读它的地方是 {@code WaveFlow.closeWave}</b>——局末出口只有一处，
     * 这是这条链最容易走歪的地方（[规格 §5.5] 第 2 行）。
     */
    public boolean hasVictory() {
        return campaign;
    }

    /** 出怪走不走批次（[规格 §5.5] 那条"单加数量已被实测证伪"的解法）。 */
    public boolean batchesSpawns() {
        return !campaign;
    }

    /**
     * 无尽侧小怪 hp 的波次增长：{@code m(n) = 1 + 0.05(n-1)}，再被两道闸封顶；战役侧恒 1。
     *
     * <p><b>为什么撤掉了那条二次项</b>（本批落地时我自己否掉的）：原式 {@code +0.008·max(0,n-15)²}
     * 配"不封顶"在第 100 波是 <b>63 倍</b>厚——精英 12 血变 765 血，单只十几秒，而那时
     * {@code maxAlive} 闸外压着 26 只。那不是难度，是墙。线性＋封顶之后曲线在<b>第 41 波</b>走完，
     * 恰好＝战役本该结束的位置（[规格 §5.5] 那条"撤终态"的边界），此后压力只从数量那一侧来。
     *
     * <p>{@link Balance.Enemy#BURSTER} 与 {@link Balance.Enemy#RUSHER} 走更低的
     * {@link Balance.Wave#endlessHpCap}（2 倍，第 21 波起）：<b>这两种的伤害按玩家 maxHp 的比例结算</b>，
     * 血厚一倍读起来不是"更难杀"而是"更多时间贴着你不死"，到第 100 波那格会把自爆变成即死。
     * 其余四种走 {@link Balance.Wave#endlessHpCeil}（3 倍）。两道闸的分工是"打不死"与"躲不开"两件事。
     *
     * <p>⚠ Boss 不走这一格：{@code Game.initBoss} 写的是另一条独立数值（{@code bossHpFor}），
     * [规格 §8.5] 那条"Boss 豁免"因此对<b>两个轴</b>同时成立。
     */
    public float hpGrowth(int wave, int kind) {
        if (campaign) return 1f;
        int n = wave < 1 ? 1 : wave;
        Balance.Wave cfg = Balance.wave;
        float g = 1f + cfg.endlessHpPerWave * (n - 1);
        boolean ratioDamage = kind == Balance.Enemy.BURSTER || kind == Balance.Enemy.RUSHER;
        return Math.min(g, ratioDamage ? cfg.endlessHpCap : cfg.endlessHpCeil);
    }

    /**
     * 章号由全局波号推出（[规格 §5.3] 那条"章界＝波界，天然成立"的争议在此结账：<b>取全局波号</b>）。
     *
     * <p>为什么不做"每章从第 1 波起"：那会让八章共用同一条难度曲线，第 8 章打起来与第 1 章一样——
     * 而曲线（敌数、权重过渡、星区、{@code scoreWaveFactor}）本来就是波号的函数，章号只该<b>切叙事与
     * Boss 节拍</b>，不该切数值。
     *
     * <p>⚠ 结果夹在 {@link #chapterCount()}：无尽打到第 41 波之后<b>没有第 9 章</b>（剧情表只有 8 句，
     * {@code 章号 → 文本} 那张贴过去就越界）。这一格是全仓唯一的"章"真源，所以夹在这里而不是让每个
     * 读取点自己 {@code min} 一遍——漏掉一处就是一个不存在的章号上屏。
     */
    public int chapterForWave(int wave) {
        if (wave < 1) return 0;
        int c = (wave - 1) / Balance.wave.wavesPerZone + 1;
        return c < chapterCount() ? c : chapterCount();
    }

    /** 章数＝{@code maxWave / wavesPerZone}，与那条 [规格] "40 波 8 星区"同源（1.45^n 那条 8 轮 Boss 同它）。 */
    public int chapterCount() {
        return Balance.wave.maxWave / Balance.wave.wavesPerZone;
    }
}
