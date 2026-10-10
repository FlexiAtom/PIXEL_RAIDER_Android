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

    /**
     * 本局的模式（[规格 §5.5]）。这里要它只为两格：<b>局末出口</b>与<b>出怪批次</b>——两格都在本类，
     * 且各自只有这一个读取点。敌数、hp、权重那几格经 {@link WaveDirector} 转授，不在这里重复读。
     *
     * <p>volatile 与 {@code WaveDirector.mode} 同一条理由（整局重置有一条链来自 UI 线程）。
     */
    private volatile Mode mode = Mode.CAMPAIGN;

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

    /**
     * 唯一的换模式入口。它与 {@link WaveDirector#setMode} 必须由同一个整局重置<b>一起</b>调用——
     * 分两次调会给出一帧的窗口，那一帧里"终局判据"读新模式而"敌数"读旧模式。
     */
    public void setMode(Mode mode) {
        this.mode = mode;
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
     * 真机取证用（{@code EV_DEBUG_VICTORY}，只在 debug 包被 {@code MainActivity} 放行）：
     * 把波号推到终波并**清空待刷队列**，剩下的交给真的那条路——空队列使下一帧
     * {@link #spawnStep} 翻 {@code CLEAR}，场上又没敌人，于是 {@link #closeWave} 自己走到
     * {@code VICTORY}。相位不是这里直接写的。
     *
     * <p>为什么不干脆让调用方写 {@code phase = VICTORY}，或干脆去调 {@code Game.onVictory()}：
     * #54 抱怨的是**通关出口没接**，取证必须让"发现通关"的那一条（{@code Game.step} 读
     * {@link #victory()}）真的跑一遍。绕过发现路径去画那张脸，取到的证不成立——
     * 与 {@code Game.debugEndRun} 复用真伤害链是同一条道理。
     *
     * <p>波号也推到 {@code maxWave} 是因为页面上"到达波次"那一行要得的是终波的数，
     * 半途的读数会让取证照片看起来像另一局。
     */
    public void debugReachFinalWave() {
        wave = Balance.wave.maxWave;
        kindCount = 0;
        cursor = 0;
        gapLeft = 0f;
        phase = SPAWN;
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
        gapLeft = nextGap();
        armed = true;
        // 最后一只是在刷出**之后**才判清的：先翻相位会让它自己没被计入存活数。
        if (cursor >= kindCount) phase = CLEAR;
        return kind;
    }

    /**
     * 刷出上一只之后该隔多久再刷下一只。
     *
     * <p><b>战役侧一字节不动</b>（逐只付 {@code spawnGapFor}）。无尽侧走<b>批次</b>：每
     * {@link Balance.Wave#endlessBatchSize} 只算一批，批内间隙归零、批间付一次 {@code spawnGapFor}。
     *
     * <p>为什么批次不是可选项而是这条链的一部分：那条"无尽＝敌数线性加下去"的裁定被实测证伪过——
     * 逐只节拍下多出来的怪<b>排在 {@code maxAlive} 那道闸外面等位</b>，同屏峰值实测仍是 1。
     * 也就是"数量加了但玩家什么都感觉不到"。批次让一坨怪<b>同时</b>进场，闸才真的被压住。
     *
     * <p>批内是"一帧一只"而不是真同帧：{@code Game.spawnQueued} 每帧只问一次 {@code pollSpawn}，
     * 所以 12 只在 12 帧（60fps ≈ 0.2 s）内落地。视觉上是一坨，池侧却仍守住"每帧至多一只"的分配上限
     * －这一条是刻意的取舍，比给 {@code pollSpawn} 加"吐 N 只"的新契约便宜。
     */
    private float nextGap() {
        float cadence = director.spawnGapFor(wave);
        if (!mode.batchesSpawns()) return cadence;
        int size = Balance.wave.endlessBatchSize;
        return cursor % size == 0 ? cadence : 0f;
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
        // [规格 §5.5] 局末出口仍<b>只有一处</b>，只是它从此读模式：战役＝走完 maxWave，无尽＝永不。
        // 这条链最容易走歪的地方是有人在此之外再补一个"无尽第 N 波也算通关"的出口——那就成了两个真源。
        //
        // ⚠ 阈值今天恒等于全局 maxWave（40），因为战役的<b>波号是全局的</b>（八章＝40 波，见
        // {@link Mode#chapterForWave} 那条理由）。批3 的选关页一旦能"从第 k 章起一局"，这一格要换成
        // 章末的<b>相对</b>阈值——那是本方法剩下的唯一一分叉，别再往别处加判据。
        if (mode.hasVictory() && wave >= Balance.wave.maxWave) {
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

    // ---- 快照 -----------------------------------------------------------------------------

    /**
     * 拍下<b>波号、相位、两段计时、待刷队列与那一格没消费的退回标记</b>。
     *
     * <p>队列必须连<b>游标</b>一起存，不能只存"还剩几只"：{@link #nextGap} 的批次判据读的是
     * {@code cursor % size}，那是一格**绝对位置**，把它换成"剩余数"会让无尽模式读档后的第一批
     * 落在错误的节拍上（批次只在整除那一拍付间隔）。
     *
     * <p>{@code armed} 也存，理由写在 {@link #returnSpawn} 那条上：它是"上一帧真的吐出了一只"的
     * 一次性标记。存档恰好拍在池满退回的前一帧时，丢了它下一帧就退不回那一只；不丢则最多退一次。
     */
    public void writeSnapshot(Buf w) {
        w.writeInt(wave);
        w.writeInt(phase);
        w.writeFloat(phaseLeft);
        w.writeFloat(gapLeft);
        w.writeInt(kindCount);
        w.writeInts(kinds, 0, kindCount);
        w.writeInt(cursor);
        w.writeBoolean(chipDroppedThisWave);
        w.writeInt(guaranteeChips);
        w.writeBoolean(armed);
    }

    /**
     * 队列的每个元素都要验：它是这份快照里<b>唯一会决定"接下来刷出什么"</b>的一段，而
     * {@code Game.spawnQueued} 拿到非法 kind 不会抛——它会去读 {@code Balance.enemies[kind]}
     * 那一族数值，越界就是数组外，不越界但坏了就是一整波打不了的怪。
     */
    public void restoreSnapshot(Buf.Reader r) {
        int n = r.readInt();
        int ph = r.readInt();
        float pl = r.readFloat();
        float gl = r.readFloat();
        int count = r.readInt();
        if (n < 1) throw new Buf.Invalid("wave " + n + " below 1");
        if (ph < PREP || ph > VICTORY) throw new Buf.Invalid("phase " + ph + " outside 0.." + VICTORY);
        if (Float.isNaN(pl) || Float.isInfinite(pl) || pl < -1f || pl > 600f) {
            throw new Buf.Invalid("phaseLeft " + pl);
        }
        if (Float.isNaN(gl) || Float.isInfinite(gl) || gl < -1f || gl > 600f) {
            throw new Buf.Invalid("gapLeft " + gl);
        }
        // 上限不是随手写的：enemyCountFor 自己把整波数夹在 400，Boss 波只有 1，所以合法队列最长 400。
        // 越过这条只有两种可能——布局错位，或那一段被改坏；两种都不该进一局。
        if (count < 0 || count > 400) throw new Buf.Invalid("queue length " + count + " over the 400 cap");
        int[] q = new int[count];
        r.readInts(q, 0, count);
        int cur = r.readInt();
        boolean chip = r.readBoolean();
        int chips = r.readInt();
        boolean wasArmed = r.readBoolean();
        if (cur < 0 || cur > count) throw new Buf.Invalid("cursor " + cur + " outside 0.." + count);
        if (chips < 0) throw new Buf.Invalid("guaranteeChips " + chips);
        for (int k : q) {
            if (k != BOSS && (k < Balance.Enemy.STRAIGHT || k > Balance.Enemy.BURSTER)) {
                throw new Buf.Invalid("queued kind " + k + " is neither a enemy nor BOSS");
            }
        }
        ensure(count);
        System.arraycopy(q, 0, kinds, 0, count);
        wave = n;
        phase = ph;
        phaseLeft = pl;
        gapLeft = gl;
        kindCount = count;
        cursor = cur;
        chipDroppedThisWave = chip;
        guaranteeChips = chips;
        armed = wasArmed;
    }
}
