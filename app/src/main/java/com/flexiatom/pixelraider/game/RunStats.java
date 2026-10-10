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
    /**
     * 本局最高连击。除 {@link #reset()} 归零外，写点只有一处：{@code Game.killEnemy} 在每次击杀后
     * 把它抬到不低于 {@link ComboMeter#peakHits()}（那里那句注释解释了为什么必须逐次记、不能只在
     * 死亡瞬间快照）。读取点（评级、结算、暂停面板）一律读这里，因为这里正是结算页的输入。
     */
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

    // ---- 快照 -----------------------------------------------------------------------------

    /**
     * 十格原始计数全存。读档继续打的局，结算页拿到的是<b>整局</b>的命中率与最高连击——
     * 丢了它们，"存档→读档→打死"这一条路会给出一个 0% 命中率的评级，而那正是本类注释
     * 当初要解决的病（拿 0 去评级）。
     *
     * <p>{@code peakCombo} 是这一格的一处<b>双份</b>：连击计里也有一个峰值，两处由
     * {@code Game.killEnemy} 同一句里前后写。这里不校验两者相等，因为它要的是"结算页的输入
     * 与暂停面板同源"，不是"跨类对齐"——校验得让两处都住进同一个对象才有意义，那是另一件事。
     */
    public void writeSnapshot(Buf w) {
        w.writeLong(shotsFired);
        w.writeLong(shotsHit);
        w.writeLong(kills);
        w.writeLong(critKills);
        w.writeLong(bombUses);
        w.writeLong(overloadUses);
        w.writeLong(powerupsTaken);
        w.writeInt(deaths);
        w.writeInt(peakCombo);
        w.writeFloat(worldSeconds);
    }

    public void restoreSnapshot(Buf.Reader r) {
        long fired = r.readLong();
        long hit = r.readLong();
        long kill = r.readLong();
        long critKill = r.readLong();
        long bomb = r.readLong();
        long over = r.readLong();
        long powerup = r.readLong();
        int d = r.readInt();
        int peak = r.readInt();
        float secs = r.readFloat();
        // 三条"子集不超过全集"：命中不可能多于发出、暴杀不可能多于击杀——它们不是洁癖，
        // 评级与结算页那几行读数全靠这些数<b>彼此自洽</b>，错位半格就会给出一个 130% 的命中率
        // （accuracy() 自己钳不住，它只除）。
        if (fired < 0 || hit < 0 || hit > fired) throw new Buf.Invalid("shots " + hit + "/" + fired);
        if (kill < 0 || critKill < 0 || critKill > kill) throw new Buf.Invalid("crit kills " + critKill + "/" + kill);
        if (bomb < 0 || over < 0 || powerup < 0 || d < 0 || peak < 0) throw new Buf.Invalid("negative event counters");
        if (Float.isNaN(secs) || secs < 0f) throw new Buf.Invalid("worldSeconds " + secs);
        shotsFired = fired;
        shotsHit = hit;
        kills = kill;
        critKills = critKill;
        bombUses = bomb;
        overloadUses = over;
        powerupsTaken = powerup;
        deaths = d;
        peakCombo = peak;
        worldSeconds = secs;
    }
}
