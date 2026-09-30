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

/**
 * 波次状态机（纯算术、无 android 依赖）。
 *
 * <pre>PREP（横幅）→ SPAWN（按间隔出怪）→ CLEAR（等场上清空）→ INTERMISSION → 下一波</pre>
 *
 * 每帧只有一个入口 {@link #pollSpawn(float, int)}：它同时负责推计时器、翻相位，并返回
 * "这一帧该刷出来的单位"。分成 step/spawn 两个入口会出现谁先谁后的顺序耦合——先刷后清
 * 和先清后刷差一只怪，而这种差在肉眼看是"最后一波偶尔卡住"。
 *
 * 两条规格决定，不是手感：
 * - [规格 §同屏上限] 超出 {@link Balance.Wave#maxAlive} 时**排队等待**，不是不刷。
 *   所以这里不丢队列元素，只是不消费它。
 * - [规格 §芯片保底] 第 2 波起若整波一枚芯片都没掉，波末补 1 枚。补的判定在
 *   {@link Drops#waveGuarantee}，本类只在关波时问一次、把结果交给渲染层去落点。
 */
public final class WaveFlow {

    public static final int PREP = 0, SPAWN = 1, CLEAR = 2, INTERMISSION = 3, VICTORY = 4;

    /** 出怪队列里的 Boss 占位。不能用 0——0 是 {@code Balance.Enemy.STRAIGHT}。 */
    public static final int BOSS = -9;
    /** 本帧不该刷任何单位。 */
    public static final int NONE = -1;

    private final WaveDirector director;
    private final Rng rng;

    private int wave;
    private int phase = PREP;
    private float phaseLeft;
    private float gapLeft;

    private int[] kinds = new int[0];
    private int kindCount;
    private int cursor;

    private boolean chipDroppedThisWave;
    private int guaranteeChips;
    /** 上一帧 {@link #pollSpawn} 是否真的吐出了一只。回收只认这一帧，否则会重复退同一个单位。 */
    private boolean armed;

    private final int[] weights = new int[Balance.Enemy.BURSTER + 1];

    public WaveFlow(WaveDirector director, Rng rng) {
        this.director = director;
        this.rng = rng;
    }

    /** 开局与 {@code resetRun} 都走这里：波号回到 1，队列重新抽。 */
    public void begin() {
        wave = 1;
        guaranteeChips = 0;
        enterPrep();
    }

    public void reset() {
        begin();
    }

    /**
     * 每帧一次。
     *
     * @param dt         世界步长（hit-stop 期间由调用方缩减，本类不关心）
     * @param aliveCount 场上存活敌人数
     * @return {@link #BOSS}、敌人 kind id，或 {@link #NONE}
     */
    public int pollSpawn(float dt, int aliveCount) {
        armed = false;
        switch (phase) {
            case PREP:
                phaseLeft -= dt;
                if (phaseLeft <= 0f) buildQueue();
                return NONE;
            case SPAWN:
                return spawnStep(dt, aliveCount);
            case CLEAR:
                if (aliveCount <= 0) closeWave();
                return NONE;
            case INTERMISSION:
                phaseLeft -= dt;
                if (phaseLeft <= 0f) {
                    wave++;
                    enterPrep();
                }
                return NONE;
            default:
                return NONE;
        }
    }

    /**
     * 静场结束：跳过剩下的 INTERMISSION，直接进下一波的 PREP。
     *
     * <p>给升级商店用。规格的流程是"清场 → 商店 → 下一波"，商店开着的时候世界时钟是冻住的，
     * 那 2 秒静场不会偷偷走完；不主动收掉它就会在关店之后再多等一截（表现为"买完卡还要罚站"）。
     *
     * <p>只在 INTERMISSION 生效：别的相位调它会把还没刷完的队列跳掉。
     */
    public void finishIntermission() {
        if (phase != INTERMISSION) return;
        phaseLeft = 0f;
        wave++;
        enterPrep();
    }

    /** 池子刚好满了没刷出来：把这一次退回队列，下一帧立即重试。退回的不是丢怪。 */
    public void returnSpawn() {
        if (!armed) return;                 // 只退"上一帧真的吐出过"的那一只
        armed = false;
        cursor--;
        gapLeft = 0f;                       // 一只都没落地，就不该付这段出怪间隔
        if (phase == CLEAR) phase = SPAWN;  // 退回来的是最后一只，相位也得退回出怪
    }

    private int spawnStep(float dt, int aliveCount) {
        if (cursor >= kindCount) {
            phase = CLEAR;
            return NONE;
        }
        gapLeft -= dt;
        if (gapLeft > 0f) return NONE;
        if (aliveCount >= Balance.wave.maxAlive) return NONE;   // 排队，不消费
        int kind = kinds[cursor++];
        gapLeft = director.spawnGapFor(wave);
        armed = true;
        // 最后一只是在刷出**之后**才判清的：先翻相位会让它自己没被计入存活数。
        if (cursor >= kindCount) phase = CLEAR;
        return kind;
    }

    private void enterPrep() {
        phase = PREP;
        chipDroppedThisWave = false;
        kindCount = 0;
        cursor = 0;
        gapLeft = 0f;
        // 首波多给一段准备时间：规格要求开局不立刻被贴脸。
        phaseLeft = Balance.wave.bannerSec
                + (wave == 1 ? Balance.wave.firstWaveDelay : 0f);
    }

    private void buildQueue() {
        if (director.isBossWave(wave)) {
            ensure(1);
            kinds[kindCount++] = BOSS;
        } else {
            int n = director.enemyCountFor(wave);
            ensure(n);
            int total = director.kindWeightsFor(wave, weights);
            for (int i = 0; i < n; i++) {
                kinds[kindCount++] = director.pickKind(weights, total, rng.next01());
            }
        }
        cursor = 0;
        gapLeft = 0f;
        phase = SPAWN;
    }

    private void closeWave() {
        guaranteeChips += Drops.waveGuarantee(wave, chipDroppedThisWave ? 1 : 0);
        if (wave >= Balance.wave.maxWave) {
            phase = VICTORY;
            return;
        }
        phase = INTERMISSION;
        phaseLeft = Balance.wave.intermissionSec;
    }

    private void ensure(int n) {
        if (kinds.length >= n) return;
        int cap = kinds.length == 0 ? 16 : kinds.length;
        while (cap < n) cap <<= 1;
        kinds = new int[cap];
    }

    // ---- 只读视图（HUD 与渲染层每帧读，不分配）--------------------------------------

    public int wave() {
        return wave;
    }

    public int phase() {
        return phase;
    }

    /**
     * 已清场波数（生存评级用它，不是 {@link #wave()}）。
     *
     * 死在第 5 波中场不算清过 5 波：{@code wave} 是"正在打哪一波"，波次是在 {@link #closeWave()}
     * 才结的，所以只有进了间歇/通关相位才够加这一格。
     */
    public int wavesCleared() {
        if (wave <= 0) return 0;
        return (phase == INTERMISSION || phase == VICTORY) ? wave : wave - 1;
    }

    public boolean victory() {
        return phase == VICTORY;
    }

    public boolean currentWaveIsBoss() {
        return director.isBossWave(wave);
    }

    public int currentBossIndex() {
        return director.bossIndexFor(wave);
    }

    public int currentBossHp() {
        return director.bossHpFor(wave);
    }

    /** 本波敌人总数（HUD 进度条分母）。 */
    public int waveTotal() {
        return director.waveTotalFor(wave);
    }

    /** 尚未刷出的数量；与调用方传入的存活数相加才等于总数。 */
    public int pendingSpawns() {
        return kindCount - cursor;
    }

    /** 队列里第 i 个单位的 kind（越界返回 {@link #NONE}）。 */
    public int spawnKindAt(int i) {
        return i < 0 || i >= kindCount ? NONE : kinds[i];
    }

    public int zoneIndex() {
        return director.zoneIndexForWave(wave);
    }

    public float scoreWaveFactor() {
        return director.scoreWaveFactor(wave);
    }

    /** 当前相位剩余秒数，横幅淡出与"下一波"倒计时读它。 */
    public float phaseLeft() {
        return phaseLeft < 0f ? 0f : phaseLeft;
    }

    /** [规格 §芯片掉落] 掉出来就算掉过（保底补的是"这一波什么都没掉"的挫败，不是"你没捡到"）。 */
    public void notifyChipDropped() {
        chipDroppedThisWave = true;
    }

    /** 取走待补的保底芯片数（调用方负责把它掉到玩家附近）。 */
    public int takeGuaranteeChips() {
        int n = guaranteeChips;
        guaranteeChips = 0;
        return n;
    }
}
