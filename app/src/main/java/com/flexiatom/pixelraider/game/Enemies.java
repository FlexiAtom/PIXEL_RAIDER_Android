/*
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
 * 敌人池（规格 §三）：定长对象数组 + 活跃计数 + free-list，每帧零分配。
 *
 * 槽号即身份：{@link SpatialGrid} 里存的是这里的 **obj 槽号**，不是对象引用。
 * 于是每帧重建网格只需要 {@code clear()} + 逐个 {@code insert(slot, x, y)}，
 * 不 new 任何迭代器/列表（规格 §五 点名 HashMap 装箱）。
 *
 * ⚠ 复用必须重置全部字段。{@code EnemiesTest} 用反射遍历字段守住 {@link Enemy#reset()}，
 * 手写清单会在加字段时悄悄失真。
 *
 * 本类不 import android.graphics：数值进、数值出，绘制端读值自己画。
 */
public final class Enemies {

    public static final class Enemy {
        /** {@code Balance.Enemy.STRAIGHT..BURSTER}，或 {@link #BOSS_KIND}。 */
        public int kind;
        /** Boss 也用同一池：kind 指向 {@link Balance#bosses}，靠这个标志分流。 */
        public boolean boss;
        /** 二阶段阈值（Boss 专用），小怪恒为 0。 */
        public float phase2AtRatio;
        public boolean phase2;

        public int hp;
        public int maxHp;
        public float x, y;
        public float vx, vy;
        /** 上一帧位置：碰撞扫掠与"是否穿过敌人"要用；复用必须重置。 */
        public float px, py;
        public float radius;
        public float age;
        /** 横向摆动的**中心**：入场时的 x。绕它做位移（不是绕速度积分），否则摆着摆着整体漂移。 */
        public float anchorX;
        /** 运动相位（横摆/巡边）与 Boss 的驻场计时共用这一个槽，语义由 kind 决定。 */
        public float phase;
        public float fireTimer;
        public float hitFlash;
        public boolean locked;
        public int score;
        /** 出场渐入：从 0 涨到 1，绘制端据此决定透明度，不参与碰撞。 */
        public float enter;
        /**
         * 生成代次戳（= 打上它时的 {@link #spawnTotal()}）。**槽号不是身份的全部**：
         * {@link #killAt} 把槽放回 free-list 后，下一个敌人会复用同一槽号，于是"槽号相等"
         * 可以指向一只从没被锁定过的怪。导引武器要长期持有目标，所以持 **(槽号, born) 一对**，
         * 有效性判据是 {@link #isLive} ∧ {@code objAt(slot).born == 存下的 born}。
         * 0 是合法的"从未生成过"值——{@link #spawn()} 里 {@code spawnTotal} 先自增再赋，
         * 第一只怪拿到的是 1，于是 {@code reset()} 清 0 之后不会被读成"有效"。
         */
        public long born;

        void reset() {
            kind = 0;
            boss = false;
            phase2AtRatio = 0f;
            phase2 = false;
            hp = 0; maxHp = 0;
            x = 0f; y = 0f;
            vx = 0f; vy = 0f;
            px = 0f; py = 0f;
            radius = 0f;
            age = 0f; phase = 0f;
            anchorX = 0f;
            fireTimer = 0f;
            hitFlash = 0f;
            locked = false;
            score = 0;
            enter = 0f;
            born = 0L;
        }
    }

    /** kind 的保留值：> BURSTER，表示这一条读 {@link Balance#bosses} 而不是 {@link Balance#enemies}。 */
    public static final int BOSS_KIND = 6;

    private final Enemy[] objs;
    private final int[] slotOfObj;
    private final int[] active;
    private final int[] free;
    private int activeCount;
    private int freeCount;
    private long spawnTotal;
    private long reuseTotal;
    private final boolean[] everUsed;

    public Enemies(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        objs = new Enemy[capacity];
        slotOfObj = new int[capacity];
        active = new int[capacity];
        free = new int[capacity];
        everUsed = new boolean[capacity];
        for (int i = 0; i < capacity; i++) {
            objs[i] = new Enemy();
            slotOfObj[i] = -1;
            free[i] = capacity - 1 - i;      // 弹出顺序 0,1,2...
        }
        freeCount = capacity;
    }

    public int capacity() { return objs.length; }
    public int activeCount() { return activeCount; }
    public int freeCount() { return freeCount; }
    public long spawnTotal() { return spawnTotal; }
    public long reuseTotal() { return reuseTotal; }

    /** 池满返回 null：波次导演据此把这一只排回队列，而不是不刷。 */
    public Enemy spawn() {
        if (freeCount == 0) return null;
        int oi = free[--freeCount];
        Enemy e = objs[oi];
        e.reset();
        if (!everUsed[oi]) everUsed[oi] = true; else reuseTotal++;
        spawnTotal++;
        e.born = spawnTotal;
        slotOfObj[oi] = activeCount;
        active[activeCount++] = oi;
        return e;
    }

    public Enemy activeAt(int i) {
        return i < 0 || i >= activeCount ? null : objs[active[i]];
    }

    public Enemy objAt(int slot) {
        return slot < 0 || slot >= objs.length ? null : objs[slot];
    }

    /** 活跃表中第 i 个 → obj 槽号（喂给 {@link SpatialGrid#insert}）。 */
    public int slotOfActive(int i) {
        return i < 0 || i >= activeCount ? -1 : active[i];
    }

    /**
     * 这个 obj 槽号**当前是否还在活跃表里**。不是新状态——就是把 {@link #killAt} 已经在维护的
     * {@code slotOfObj}（spawn 写表内下标、killAt 写回 −1）开放一次读，因为导引武器只持有
     * 槽号 + {@link Enemy#born}，它没有任何办法自己问"这只还在吗"。
     *
     * <p>⚠ 名字要说准：这里"live"= "槽号仍指向表内某只"，**不等于活着**。{@code killEnemy}
     * 之后、{@link #cullDeadAndOffscreen} 摘表之前，血已归零的怪仍然返回 true（摘表延后是刻意的，
     * 见 {@code Game} 结算段）。所以完整的"目标仍有效"判据是三条：{@code isLive(slot)}
     * ∧ {@code objAt(slot).born == 存下的 born} ∧ {@code hp > 0}。
     */
    public boolean isLive(int objSlot) {
        return objSlot >= 0 && objSlot < slotOfObj.length && slotOfObj[objSlot] >= 0;
    }

    /** 移除活跃表中第 i 个：末位交换，O(1)。 */
    public void killAt(int i) {
        if (i < 0 || i >= activeCount) return;
        int oi = active[i];
        activeCount--;
        int moved = active[activeCount];
        active[i] = moved;
        slotOfObj[moved] = i;
        slotOfObj[oi] = -1;
        free[freeCount++] = oi;
    }

    public void kill(Enemy e) {
        if (e == null) return;
        for (int i = 0; i < activeCount; i++) {
            if (objs[active[i]] == e) {
                killAt(i);
                return;
            }
        }
    }

    /**
     * 回收死亡与离场。**倒序遍历**：正序删除会跳过被末位交换顶到当前位置的那一只。
     * 死亡判据用 {@code hp <= 0}（规格 §五 点名别用 indexOf）。
     */
    public void cullDeadAndOffscreen(int battleBottom, int margin) {
        for (int i = activeCount - 1; i >= 0; i--) {
            Enemy e = objs[active[i]];
            if (e.hp <= 0 || e.y > battleBottom + margin) killAt(i);
        }
    }

    public void clear() {
        while (activeCount > 0) killAt(activeCount - 1);
    }

    /** 场上存活且未死亡的总数——波次进度条与"清场判定"都读这个。 */
    public int aliveCount() {
        int n = 0;
        for (int i = 0; i < activeCount; i++) {
            if (objs[active[i]].hp > 0) n++;
        }
        return n;
    }
}
