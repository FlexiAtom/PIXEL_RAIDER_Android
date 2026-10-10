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
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.flexiatom.pixelraider.game;

/**
 * 光束池（丙：独立光束池，他 2026-10-07 逐字裁定，见池件 {@code laser-weapon-form}）：
 * 定长环形槽 + 活跃窗口，每帧零分配。
 *
 * <p>形状照 {@link Missiles}，但**三条差别都是形态给的**，不是随手省掉的：
 * <ol>
 *   <li><b>不积分</b>。光束不飞：出膛那一刻它就已经从机头打到了战场顶边，之后没有任何
 *       "往哪儿走"可算。所以这里没有 {@code stepAndCompact}，只有寿命衰减。</li>
 *   <li><b>命中不在本类</b>。判据是几何，住在 {@link BeamRules}（纯函数、JVM 可测）；
 *       扣血与记账住在 {@code Game}（渲染类，结构上测不到）。本类只持数据。</li>
 *   <li><b>满了覆盖最老的一道</b>，而且覆盖**没有玩法代价**——伤害在出膛那一步就结完了，
 *       槽位剩的只是画面残亮。这是它跟"弹池满了会淘汰玩家自己的弹"那条最本质的不同：
 *       那边丢的是结算机会，这边丢的是一帧好不好看。</li>
 * </ol>
 *
 * <p>⚠ 一道光束**只结算一次**，靠 {@link Beam#settled} 那一位。它亮着的每一帧都还在池里，
 * 少了这一位就是一束光把沿途每架各打 {@code 6~7} 轮（0.1s ÷ 1/60 步）——画面上看不出来，
 * 因为伤害早就结完了，只有分数会莫名其妙地涨。
 *
 * <p>本类不 import android.graphics：数值进、数值出，绘制端读值自己画。
 */
public final class Beams {

    /** 一道光束：一条**竖直**线段加它的画面时长。 */
    public static final class Beam {
        /** 束的 x（= 出膛那一刻的机头 x，此后不动）。 */
        public float x;
        /** 束的**下端**（机头 y）。上端是战场顶边，由绘制与结算各自现读几何，不存这里。 */
        public float y;
        /** 束宽（逻辑像素），来自 {@code Weapon.size}。 */
        public float width;
        /** 出膛这一刻冻结的浮点伤害（含攻击乘子、固定加点与暴击倍率），过 {@code DamageRules} 取整。 */
        public float damage;
        public int color;
        public int weaponId;
        /** 这一代是否暴击（判定一次，整束共享，与弹丸同一条口径）。 */
        public boolean crit;
        /** 剩余亮度（秒）。≤0 表示不再画，槽位还在等下一次覆盖。 */
        public float life;
        /** 本道是否已经结算过。⚠ 见类注释那条 ⚠：漏了它一束光等于打了十几遍。 */
        public boolean settled;

        /**
         * 复用槽位：把这一道的读数归零到位。⚠ 新增字段必须在这里也写一遍——
         * {@code BeamTest} 用反射钉"复用不留旧值"，漏一个字段就是那一格带着上一道的伤害飞出去。
         */
        void occupy(float x, float y, float width, float damage, int color, int weaponId,
                    boolean crit, float life) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.damage = damage;
            this.color = color;
            this.weaponId = weaponId;
            this.crit = crit;
            this.life = life;
            this.settled = false;
        }
    }

    private final Beam[] slots;
    /** 下一次写入的位置（环形游标）。 */
    private int cursor;
    /** 已写入过的道数，上界是 {@link #slots} 的长度——活跃窗口的大小。 */
    private int count;

    public Beams(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive: " + capacity);
        slots = new Beam[capacity];
        for (int i = 0; i < capacity; i++) slots[i] = new Beam();
    }

    /**
     * 写入一道：覆盖环形游标上那一个槽（也就是**最老的那一道**），并把它点亮。
     *
     * <p>恒成功、恒不为 null，与 {@code BulletPool.spawnEvictingOldest} 同一条约定：
     * 玩家按下去那一下永远有一道光束出现。差别只在被覆盖的那一道**不需要任何补偿动画**
     * （它的伤害早就结完了），所以这里没有 {@link Missiles#evicted} 那种"池外唯一副本"。
     */
    public Beam spawn(float x, float y, float width, float damage, int color, int weaponId,
                      boolean crit, float life) {
        Beam b = slots[cursor];
        cursor++;
        if (cursor == slots.length) cursor = 0;
        if (count < slots.length) count++;
        b.occupy(x, y, width, damage, color, weaponId, crit, life);
        return b;
    }

    /** 活跃窗口里的第 {@code i} 道，**从最老到最新**。 */
    public Beam at(int i) {
        if (i < 0 || i >= count) throw new IndexOutOfBoundsException("i=" + i + " count=" + count);
        int idx = cursor - count + i;
        if (idx < 0) idx += slots.length;
        return slots[idx];
    }

    /** 活跃窗口的大小（含"已经灭掉但槽位还没被复用"的那些）。 */
    public int activeCount() {
        return count;
    }

    /** 寿命衰减一步；≤0 的槽位**不摘**（环形槽没有"摘"这个动作，由绘制端跳过）。 */
    public void advance(float dt) {
        for (int i = 0; i < count; i++) {
            Beam b = at(i);
            if (b.life > 0f) {
                b.life -= dt;
                if (b.life < 0f) b.life = 0f;
            }
        }
    }

    /** 清场：换局用。 */
    public void clear() {
        for (int i = 0; i < slots.length; i++) {
            slots[i].life = 0f;
            slots[i].settled = true;      // 灭掉的槽不该在复用前被结一次
        }
        cursor = 0;
        count = 0;
    }

    // ---- 快照 -----------------------------------------------------------------------------

    /**
     * 拍下环形窗口：<b>{@code cursor} 与 {@code count} 一起存，窗口按 {@link #at(int)} 的顺序（最老→最新）写</b>。
     *
     * <p>这一座与其余五座<b>不同形</b>：没有 free 栈，槽位是被游标覆盖的，所以还原不能"重放 spawn"
     * （{@link Beam#occupy} 会把 {@code settled} 强行写成 false，而快照里那道可能已经结过账）。
     * 于是这里<b>逐字段直写</b>，把 {@code settled} 一起带走——漏掉它，恢复后的第一帧就会把已经
     * 结过的那一束再结一遍（类注释那条 ⚠ 说的是同一件事，只是换了触发点）。
     *
     * <p>窗口之外的那些槽<b>不存</b>：{@link #at(int)} 走不到它们。{@link #restoreSnapshot} 开头那句
     * {@link #clear()} 会把它们的 {@code life}/{@code settled} 归位（归位不是为了正确性，是为了游标
     * 转过来之前那几格不留旧值）——等游标覆盖时 {@code occupy} 会把九个字段全写一遍，所以这里
     * 少存哪一格都不影响读数。
     */
    public void writeSnapshot(Buf w, int bandTop, int bandH) {
        w.writeInt(cursor);
        w.writeInt(count);
        for (int i = 0; i < count; i++) writeBeam(w, at(i), bandTop, bandH);
    }

    public void restoreSnapshot(Buf.Reader r, int bandTop, int bandH) {
        int c = r.readInt();
        int n = r.readInt();
        if (c < 0 || c >= slots.length) throw new Buf.Invalid("beam cursor " + c + " out of " + slots.length);
        if (n < 0 || n > slots.length) throw new Buf.Invalid("beam count " + n + " out of " + slots.length);
        clear();
        for (int i = 0; i < n; i++) readBeam(r, atWriteTarget(c, n, i), bandTop, bandH);
        cursor = c;
        count = n;
    }

    /** 与 {@link #at(int)} 严格同一道换算：读第 i 道就该落在它当初所在的位置上。 */
    private Beam atWriteTarget(int restoredCursor, int restoredCount, int i) {
        int idx = restoredCursor - restoredCount + i;
        if (idx < 0) idx += slots.length;
        if (idx < 0 || idx >= slots.length) throw new Buf.Invalid("beam window index " + idx);
        return slots[idx];
    }

    /**
     * {@link Beam#y} 走 {@link Snapshot} 的战场带归一化——它是机头那一刻的 y，而玩家那段也按同一条
     * 规则存。两边不同进的话，跨机型读出的第一帧会出现"一道光束和战机差了半屏"的画面。
     */
    private static void writeBeam(Buf w, Beam b, int bandTop, int bandH) {
        w.writeFloat(b.x);
        Snapshot.putY(w, b.y, bandTop, bandH);
        w.writeFloat(b.width);
        w.writeFloat(b.damage);
        w.writeInt(b.color);
        w.writeInt(b.weaponId);
        w.writeBoolean(b.crit);
        w.writeFloat(b.life);
        w.writeBoolean(b.settled);
    }

    private static void readBeam(Buf.Reader r, Beam b, int bandTop, int bandH) {
        b.x = r.readFloat();
        b.y = Snapshot.getY(r, bandTop, bandH);
        b.width = r.readFloat();
        b.damage = r.readFloat();
        b.color = r.readInt();
        b.weaponId = r.readInt();
        b.crit = r.readBoolean();
        b.life = r.readFloat();
        b.settled = r.readBoolean();
    }
}
