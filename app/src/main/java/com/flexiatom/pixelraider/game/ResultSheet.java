package com.flexiatom.pixelraider.game;

import com.flexiatom.pixelraider.plat.KeyValue;

/**
 * 结算页要显示的全部事实（死亡那一刻算一次，此后只读到下一次死亡）。
 *
 * 为什么要这么一份快照：纪录落盘会同时覆盖 best 与 last，而结算页要比的是**破纪录之前**的那份
 * best 和**本局之前**的那份 last。先抄后写，顺序反了就会显示"本局比本局高 0 分"。
 */
public final class ResultSheet {

    public final RunSummary current = new RunSummary();
    public final RunSummary bestBefore = new RunSummary();
    public final RunSummary lastBefore = new RunSummary();
    public final float[] dims = new float[Rating.COUNT];

    public float overall;
    public int letterIndex;
    public boolean newBest;

    /** 最弱的一维：规格说分段评级的目的是"指导改进"，那就得指出短板，不是夸长板。 */
    public int worstDim() {
        int w = 0;
        for (int i = 1; i < Rating.COUNT; i++) {
            if (dims[i] < dims[w]) w = i;
        }
        return w;
    }

    public void fill(RunStats s, long score, int waveReached, int wavesCleared,
                     RunRecords records, KeyValue kv) {
        Rating.dimensions(s, wavesCleared, Balance.grade, dims);
        overall = Rating.overall(dims, Balance.grade);
        letterIndex = Rating.letterIndex(overall, Balance.grade);
        bestBefore.copyFrom(records.best());
        lastBefore.copyFrom(records.last());
        current.capture(s, score, waveReached, letterIndex);
        newBest = records.commit(kv, current);
    }
}
