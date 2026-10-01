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
 * 一局的可读小结（结算页的数据源，同时是落盘格式）。
 *
 * 为什么存**千分比整数**而不是浮点：SharedPreferences 只认 long，浮点自己乘除一遍就会在读回来时
 * 差最后一位，而结算页要显示"命中率 87%"——差的那一位会在"上局 87% / 本局 86%"之间制造一个
 * 不存在的成绩倒退。秒数同理，一律取整。
 */
public final class RunSummary {

    public long score;
    /** 到达波次（正在打的那一波，不是已清场数——那属于评级输入，不落盘）。 */
    public int waveReached;
    public int kills;
    /** 命中率 × 1000，0..1000。 */
    public int accPermille;
    public int seconds;
    public int peakCombo;
    public int letterIndex;

    private boolean present;

    public boolean present() {
        return present;
    }

    public float accuracy01() {
        return accPermille / 1000f;
    }

    public void capture(RunStats s, long score, int waveReached, int letterIndex) {
        this.score = score;
        this.waveReached = waveReached;
        this.kills = (int) Math.min(Integer.MAX_VALUE, s.kills);
        this.accPermille = Math.round(Rating.clamp01(s.accuracy()) * 1000f);
        this.seconds = s.elapsedWholeSeconds();
        this.peakCombo = s.peakCombo;
        this.letterIndex = letterIndex;
        this.present = true;
    }

    public RunSummary copyFrom(RunSummary o) {
        score = o.score;
        waveReached = o.waveReached;
        kills = o.kills;
        accPermille = o.accPermille;
        seconds = o.seconds;
        peakCombo = o.peakCombo;
        letterIndex = o.letterIndex;
        present = o.present;
        return this;
    }

    /** 同一条纪录 = 同一个前缀下的八个键；读写必须成对，漏一个键就会读回半条纪录。 */
    public void writeTo(KeyValue kv, String prefix) {
        kv.putLong(prefix + "seen", present ? 1 : 0);
        kv.putLong(prefix + "score", score);
        kv.putLong(prefix + "wave", waveReached);
        kv.putLong(prefix + "kills", kills);
        kv.putLong(prefix + "acc", accPermille);
        kv.putLong(prefix + "sec", seconds);
        kv.putLong(prefix + "combo", peakCombo);
        kv.putLong(prefix + "letter", letterIndex);
    }

    /** 越界值只可能来自被改坏的存档：钳回去，别让结算页读出"命中率 137%"。 */
    public void readFrom(KeyValue kv, String prefix) {
        present = kv.getLong(prefix + "seen", 0) != 0;
        score = kv.getLong(prefix + "score", 0);
        waveReached = (int) kv.getLong(prefix + "wave", 0);
        kills = (int) kv.getLong(prefix + "kills", 0);
        accPermille = Math.round(Rating.clamp01(kv.getLong(prefix + "acc", 0) / 1000f) * 1000f);
        seconds = (int) kv.getLong(prefix + "sec", 0);
        peakCombo = (int) kv.getLong(prefix + "combo", 0);
        letterIndex = (int) kv.getLong(prefix + "letter", 0);
    }
}
