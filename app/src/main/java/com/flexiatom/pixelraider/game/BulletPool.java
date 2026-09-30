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
 * 子弹池：定长对象数组 + 活跃计数 + free-list，每帧零分配（规格 §三）。
 *
 * [规格] 玩家子弹超限**淘汰最早出膛的那发**。所有弹一律直飞，弹与弹之间没有"投资"差别，
 * 所以按年龄淘汰就够了。先前那套"追踪弹不许被淘汰"的保护服务于一个假功能，
 * 而且自己制造了一条真死锁：池满且场上全是追踪弹时玩家**整轮零发**、冷却照付。
 * 淘汰路径因此**永不拒发**（只要 capacity &gt; 0）。
 *
 * "最早"靠每发子弹出膛时盖一个单调递增的 {@link Bullet#seq}，不靠数组顺序——
 * free-list 复用的槽位顺序和出膛顺序无关，用槽号判先后会判错（这是个真会踩的坑）。
 * 淘汰路径只在满帧触发，线性扫描可以接受；热路径 {@link #spawn()} 仍是 O(1)。
 *
 * ⚠ 复用必须重置**全部**字段（含 px/py）。漏一个就是"新子弹从屏幕另一头飞过来"的鬼影，
 * 而它只在池转起来之后才出现。{@code BulletAndGridTest} 用反射遍历字段守住 {@link Bullet#reset()}。
 *
 * 本类不 import android.graphics：弹道是纯数值，绘制端读值自己画。
 */
public final class BulletPool {

    public static final class Bullet {
        public float x, y;
        public float vx, vy;
        /** 上一帧位置：扫掠碰撞与拖尾要用；复用必须重置，否则第一帧线段横跨全屏。 */
        public float px, py;
        public float life;
        public float maxLife;
        public float damage;
        public float size;
        public int color;
        public int weaponId;
        /** 出膛序号，单调递增；淘汰"最早的那发"按它比，不按槽号比。 */
        public long seq;
        public boolean crit;
        /** true = 敌方弹幕（不受玩家额度约束，单独一个池）。 */
        public boolean hostile;

        void reset() {
            x = 0f; y = 0f;
            vx = 0f; vy = 0f;
            px = 0f; py = 0f;
            life = 0f; maxLife = 0f;
            damage = 0f; size = 0f;
            color = 0; weaponId = 0;
            seq = 0L;
            crit = false; hostile = false;
        }
    }

    private final Bullet[] objs;
    private final int[] active;      // 活跃 obj 索引，前 activeCount 个有效
    private final int[] free;        // 空闲 obj 索引栈
    private int activeCount;
    private int freeCount;
    private long nextSeq = 1L;
    private long spawnTotal;
    private long evictTotal;
    private long refuseTotal;

    public BulletPool(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        objs = new Bullet[capacity];
        active = new int[capacity];
        free = new int[capacity];
        for (int i = 0; i < capacity; i++) {
            objs[i] = new Bullet();
            free[i] = capacity - 1 - i;   // 弹出顺序 0,1,2...
        }
        freeCount = capacity;
    }

    public int capacity() { return objs.length; }
    public int activeCount() { return activeCount; }
    public int freeCount() { return freeCount; }
    public long spawnTotal() { return spawnTotal; }
    public long evictTotal() { return evictTotal; }
    public long refuseTotal() { return refuseTotal; }

    /** 有空位就发，没有返回 null。调用方自己决定降级还是不开火。 */
    public Bullet spawn() {
        if (freeCount == 0) return null;
        return occupy(free[--freeCount]);
    }

    /**
     * [规格] 满了就淘汰最早出膛的那发腾位，**永不拒发**（capacity &gt; 0 时）。
     * @param allowEvict false 时等价于 {@link #spawn()}——敌方弹幕用这条：
     *                   少一发敌弹玩家看不出来，踢掉一发已在途的弹却会让弹幕凭空缺个口子
     */
    public Bullet spawnEvictingOldest(boolean allowEvict) {
        if (freeCount > 0) return occupy(free[--freeCount]);
        if (!allowEvict) {
            refuseTotal++;
            return null;
        }
        // freeCount==0 蕴含 activeCount==capacity>0（occupy/killAt 双向搬运，两者之和恒为 capacity），
        // 所以这里不存在"没得淘汰"的分支——构造器已经把 capacity<=0 挡在门外。
        killAt(oldestActiveIndex());
        evictTotal++;
        return occupy(free[--freeCount]);
    }

    /** 返回**活跃表下标**（不是 obj 槽号）：调用方要的正是 {@link #killAt(int)} 的那个下标。 */
    private int oldestActiveIndex() {
        int best = 0;
        long bestSeq = objs[active[0]].seq;
        for (int i = 1; i < activeCount; i++) {
            long s = objs[active[i]].seq;
            if (s < bestSeq) {
                bestSeq = s;
                best = i;
            }
        }
        return best;
    }

    private Bullet occupy(int oi) {
        Bullet b = objs[oi];
        b.reset();
        b.seq = nextSeq++;
        active[activeCount++] = oi;
        spawnTotal++;
        return b;
    }

    public Bullet activeAt(int i) {
        return i < 0 || i >= activeCount ? null : objs[active[i]];
    }

    /** 移除活跃表中第 i 个：末位交换，O(1)。 */
    public void killAt(int i) {
        if (i < 0 || i >= activeCount) return;
        int oi = active[i];
        activeCount--;
        active[i] = active[activeCount];
        free[freeCount++] = oi;
    }

    /** 倒序遍历 + 就地压缩：正序删除会跳过被换到当前位置的那个元素。 */
    public int stepAndCompact(float dt, int w, int h, int margin) {
        int i = 0;
        while (i < activeCount) {
            Bullet b = objs[active[i]];
            b.px = b.x;
            b.py = b.y;
            b.x += b.vx * dt;
            b.y += b.vy * dt;
            b.life += dt;
            boolean dead = b.life >= b.maxLife
                    || b.x < -margin || b.x > w + margin
                    || b.y < -margin || b.y > h + margin;
            if (dead) killAt(i);
            else i++;
        }
        return activeCount;
    }

    /** 清场（超载扫描、换波次、回主菜单）。对象留在池里，下次复用。 */
    public void clear() {
        while (activeCount > 0) killAt(activeCount - 1);
    }
}
