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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.flexiatom.pixelraider.plat.MemStore;
import org.junit.Test;

/**
 * 纪录持久化往返（用内存 fake，不碰 android）。落盘格式错了在真机上只会表现为"纪录莫名其妙没了"，
 * 那是最难查的一类 bug，所以键名、钳位、判定顺序全部在 JVM 里钉死。
 */
public class RunRecordsTest {

    private static RunStats stats(long kills, long shotsFired, long shotsHit, int peakCombo,
                                  int deaths, long overloadUses, float seconds) {
        RunStats s = new RunStats();
        s.kills = kills;
        s.shotsFired = shotsFired;
        s.shotsHit = shotsHit;
        s.peakCombo = peakCombo;
        s.deaths = deaths;
        s.overloadUses = overloadUses;
        s.worldSeconds = seconds;
        return s;
    }

    @Test
    public void summaryRoundTripsThroughTheStore() {
        MemStore kv = new MemStore();
        RunStats out = stats(320, 400, 200, 27, 1, 3, 131.7f);
        RunSummary a = new RunSummary();
        a.capture(out, 98765L, 12, 3);
        a.writeTo(kv, "best_");
        kv.flush();
        assertEquals(1, kv.flushes);

        RunSummary b = new RunSummary();
        assertFalse("没读之前不该算有纪录", b.present());
        b.readFrom(kv, "best_");
        assertTrue(b.present());
        assertEquals(98765L, b.score);
        assertEquals(12, b.waveReached);
        assertEquals(320, b.kills);
        assertEquals(131, b.seconds);
        assertEquals(27, b.peakCombo);
        assertEquals(3, b.letterIndex);
        assertEquals(500, b.accPermille);        // 200/400
    }

    @Test
    public void prefixIsolatesBestFromLast() {
        MemStore kv = new MemStore();
        RunSummary s = new RunSummary();
        s.capture(stats(1, 2, 1, 0, 0, 0, 1f), 100L, 1, 0);
        s.writeTo(kv, "best_");
        s.score = 200L;
        s.writeTo(kv, "last_");
        kv.flush();
        RunSummary best = new RunSummary();
        best.readFrom(kv, "best_");
        assertEquals(100L, best.score);
    }

    @Test
    public void corruptStoreClampsPermilleAndMissingKeysReadEmpty() {
        MemStore kv = new MemStore();
        kv.putLong("best_seen", 1);
        kv.putLong("best_acc", 5_000);          // 越界：500%
        RunSummary s = new RunSummary();
        s.readFrom(kv, "best_");
        assertTrue(s.present());
        assertEquals(1000, s.accPermille);
        // 没有 best_seen 键 → 读回空纪录，而不是"0 分的上局"
        RunSummary empty = new RunSummary();
        empty.readFrom(kv, "last_");
        assertFalse(empty.present());
    }

    @Test
    public void firstRunIsAlwaysANewRecord() {
        MemStore kv = new MemStore();
        RunRecords rec = new RunRecords();
        rec.load(kv);
        RunSummary s = new RunSummary();
        s.capture(stats(1, 1, 1, 0, 0, 0, 5f), 10L, 1, 0);
        assertTrue(rec.commit(kv, s));
    }

    @Test
    public void tieIsNotANewRecordButStillBecomesLast() {
        MemStore kv = new MemStore();
        RunRecords rec = new RunRecords();
        rec.commit(kv, summaryAt(500L));
        rec.load(kv);
        assertFalse("等分不算破纪录", rec.commit(kv, summaryAt(500L)));
        assertTrue(rec.best().present());
        assertEquals(500L, rec.last().score);
    }

    @Test
    public void bestSurvivesAWorseLaterRun() {
        MemStore kv = new MemStore();
        RunRecords rec = new RunRecords();
        assertTrue(rec.commit(kv, summaryAt(900L)));
        assertFalse(rec.commit(kv, summaryAt(100L)));
        assertEquals(900L, rec.best().score);
        assertEquals(100L, rec.last().score);
    }

    @Test
    public void reloadSeesWhatThePreviousProcessWrote() {
        MemStore kv = new MemStore();
        RunRecords first = new RunRecords();
        first.commit(kv, summaryAt(4321L));
        RunRecords second = new RunRecords();
        second.load(kv);
        assertEquals(4321L, second.best().score);
        assertTrue(second.best().present());
    }

    @Test
    public void sheetKeepsThePreCommitRecordsForComparing() {
        MemStore kv = new MemStore();
        RunRecords rec = new RunRecords();
        rec.commit(kv, summaryAt(1000L));    // 上局 = 旧纪录 = 1000
        RunRecords reloaded = new RunRecords();
        reloaded.load(kv);

        ResultSheet sheet = new ResultSheet();
        sheet.fill(stats(50, 100, 80, 12, 0, 2, 60f), 2000L, 6, 5, false, reloaded, kv);
        assertTrue(sheet.newBest);
        assertEquals(1000L, sheet.bestBefore.score);     // 比的是破纪录**之前**那份
        assertEquals(1000L, sheet.lastBefore.score);
        assertEquals(2000L, sheet.current.score);
        assertEquals(800, sheet.current.accPermille);
        assertEquals(6, sheet.current.waveReached);
    }

    /**
     * 通关标记住在快照里，而且**每次 fill 都必须重写它**。
     *
     * <p>第二条断言才是这条存在的理由：{@code ResultSheet} 是一局里复用的同一份对象。若
     * {@code fill} 只在通关时写 true、平时不碰，那么"上一局打到底、这一局半路死掉"会把残留的
     * true 带上结算页——页面顶着「全线贯通」画一次阵亡。那比"根本没有通关页"更坏，因为它看着像
     * 已经修好了。所以这里两头都钉。
     */
    @Test
    public void victoryFlagBelongsToTheSnapshotAndFillOwnsIt() {
        MemStore kv = new MemStore();
        RunRecords rec = new RunRecords();
        ResultSheet sheet = new ResultSheet();
        assertFalse("默认值不该是通关", sheet.victory);
        sheet.fill(stats(50, 100, 80, 12, 0, 2, 60f), 2000L, 40, 40, true, rec, kv);
        assertTrue(sheet.victory);
        sheet.fill(stats(50, 100, 80, 12, 0, 2, 60f), 100L, 3, 2, false, rec, kv);
        assertFalse("fill 之后残留的通关标记", sheet.victory);
    }

    @Test
    public void dimensionsAndLetterComeOutOfTheTableNotTheScore() {
        MemStore kv = new MemStore();
        RunRecords rec = new RunRecords();
        ResultSheet sheet = new ResultSheet();
        // 全零：生存/击杀/效率/超载/风格都归 0，等级落最低档，不该出 NaN
        sheet.fill(new RunStats(), 0L, 0, 0, false, rec, kv);
        for (int i = 0; i < Rating.COUNT; i++) {
            assertEquals(0f, sheet.dims[i], 1e-6f);
        }
        assertEquals(0f, sheet.overall, 1e-6f);
        assertEquals(0, sheet.letterIndex);
        assertEquals(0, sheet.worstDim());
    }

    @Test
    public void worstDimPointsAtTheLaggingOne() {
        MemStore kv = new MemStore();
        RunRecords rec = new RunRecords();
        ResultSheet sheet = new ResultSheet();
        // 击杀拉满、效率最差（一枪未中）
        RunStats s = stats(500, 100, 0, 300, 0, 6, 400f);
        sheet.fill(s, 99999L, 30, 29, false, rec, kv);
        assertEquals(0f, sheet.dims[Rating.EFFICIENCY], 1e-6f);
        assertEquals(1f, sheet.dims[Rating.KILL], 1e-6f);
        assertEquals(Rating.EFFICIENCY, sheet.worstDim());
    }

    private static RunSummary summaryAt(long score) {
        RunSummary s = new RunSummary();
        s.capture(stats(1, 1, 1, 0, 0, 0, 5f), score, 1, 0);
        return s;
    }
}
