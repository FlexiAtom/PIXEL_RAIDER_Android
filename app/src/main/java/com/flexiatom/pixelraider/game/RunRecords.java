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

import com.flexiatom.pixelraider.plat.KeyValue;

/**
 * 局外纪录：最高分那一局 + 上一局（规格 §四"1.45s 纪录判定 / 2.7s 对比上局"的数据源）。
 *
 * 只按**分数**判定纪录。波次、击杀数各判一套会逼着结算页说三次"新纪录"，玩家读不出到底哪个更重要；
 * 想扩成多纪录，先把结算页的版式一起改了再说。
 */
public final class RunRecords {

    private static final String BEST = "best_";
    private static final String LAST = "last_";

    private final RunSummary best = new RunSummary();
    private final RunSummary last = new RunSummary();

    public RunSummary best() {
        return best;
    }

    public RunSummary last() {
        return last;
    }

    public void load(KeyValue kv) {
        best.readFrom(kv, BEST);
        last.readFrom(kv, LAST);
    }

    /** @return 本局是否刷新最高分（**等分不算**——"又是 12000 分"不是纪录）。 */
    public boolean commit(KeyValue kv, RunSummary finished) {
        boolean newBest = !best.present() || finished.score > best.score;
        if (newBest) best.copyFrom(finished);
        last.copyFrom(finished);
        best.writeTo(kv, BEST);
        last.writeTo(kv, LAST);
        kv.flush();
        return newBest;
    }
}
