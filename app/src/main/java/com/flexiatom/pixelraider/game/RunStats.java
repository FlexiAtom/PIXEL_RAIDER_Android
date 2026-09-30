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
 * 一局跑下来的原始计数（纯算术，JVM 可测）。
 *
 * 结算页的五维评级、数据卡、"对比上局"全都从这里读，而不是从散落的现场状态猜：
 * 现场状态（score/chips/wave）本来就住在 {@code Game} 里，但命中率、最高连击、超载次数
 * 这类"只有累计才有意义"的量必须显式记账，否则结算页只能拿 0 去评级。
 *
 * 实例常驻、字段可变，整局零分配。
 */
public final class RunStats {

    public long shotsFired;
    public long shotsHit;
    public long kills;
    public long critKills;
    public long bombUses;
    public long overloadUses;
    public long powerupsTaken;
    /** 死亡次数（重生一次算一次），生存维度按它扣分。 */
    public int deaths;
    /** 本局最高连击，来自 {@link ComboMeter#peakHits()}，在 reset 时快照。 */
    public int peakCombo;
    /** 存活的世界秒数（不是真实秒数：暂停与定格不该算进时长）。 */
    public float worldSeconds;

    public void reset() {
        shotsFired = 0;
        shotsHit = 0;
        kills = 0;
        critKills = 0;
        bombUses = 0;
        overloadUses = 0;
        powerupsTaken = 0;
        deaths = 0;
        peakCombo = 0;
        worldSeconds = 0f;
    }

    public void addShots(int n) {
        shotsFired += n;
    }

    /** 一发命中（弹体用完即回收，所以命中与消耗是一回事）。 */
    public void addHit() {
        shotsHit++;
    }

    /**
     * 命中率：0..1，一枪未开时是 0 而不是 NaN。
     * 开局就按 0 处理而不是"暂无数据"：评级读它，NaN 会把整张评级表传染成 NaN。
     */
    public float accuracy() {
        return shotsFired <= 0 ? 0f : (float) shotsHit / shotsFired;
    }

    /** 存活分钟秒串的数据源（显示端自己格式化，这里只给算术）。 */
    public int elapsedWholeSeconds() {
        return (int) worldSeconds;
    }
}
