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
 * 导弹池（提案 missile-warhead-sim）：定长对象数组 + 活跃计数 + free-list，每帧零分配。
 * 形状照 {@link Enemies}，但有三条**仿真需要**的差别：
 *
 * 1. **命中不回收**。普通弹一进命中循环就 {@code killAt}（打中即消失），而导弹撞上是走引信、
 *    展开战斗部之后才退场。把导弹塞进 {@link BulletPool} 就得给那张表加"不可回收"标志，
 *    为一个分支污染整张 14 字段的结构。
 * 2. **寿命不同**。{@code BulletPool} 的 {@code maxLife} 由开火端写（普通弹 4s），导弹读
 *    {@link Balance.Missile#maxLifeSec} = 3s 且**由导引头自己判**（{@code MissileBehavior.advance}）。
 *    ⚠ 这里原先写的是"中途**不可**被出界判断收掉（它会拐弯，可能飞出又飞回）"——那句**与代码不符**：
 *    {@code MissileBehavior} 的出界判据照样收导弹，margin 与弹链同用 {@code Game.MARGIN}=16。
 *    按 L40806 落下的 B3 档，这条实测有代价（横摆组 1/8 → 0/8，弹追着目标折出屏顶被回收，见池件 §27），
 *    所以它现在是一条**待裁的设计题**，不是既成事实：留（出界即回收，画面上不会出现"从屏外飞回来"的弹）
 *    还是改（导引中的弹豁免出界）都由他定；在他裁定之前**行为不动**，本注释只负责说实话。
 * 3. **满池淘汰最老的一枚，只留自毁动画**（{@link #evicted}），于是"永不拒发"与"没有弹会无声消失"
 *    同时成立——玩家按下去的那一下永远会出弹。（R22：被淘汰那枚不展开战斗部、不结算，见 `Game.stepEvictions`。）
 *
 * 本类不 import android.graphics：数值进、数值出，绘制端读值自己画。
 */
public final class Missiles {

    public static final class Missile {
        /**
         * 出膛点火中：推力开着。⚠ **L41661 之后这一位只管推力，不管索敌**——他那句逐字是
         * 「在抵达发射单元描述的位置后扫描附近敌机，发现后就转向，开眼，锁定最近的目标」，
         * 扫描门只读 {@link #seekerOpen}。旧口径"本相位不索敌"是我按 L14750「再次点火」那两字"再次"
         * （feedback(status=rejected)，UTC 2026-09-25T09:19:51.727Z ＝本地 2026-09-25 17:19:51）补的
         * 推导，方案里编号 R9；它在 B3 档把第四段饿死（48 发导引步数总和 0，池件 §28.2）⇒ 作废。
         * ⚠ 空视场计时读的是 {@link #seekerOpen}（导引头开没开眼），不是相位。
         */
        public static final int PH_BOOST = 0;
        /** 滑行段：推力关、只剩阻力。索敌与否**不再**由它决定（见 {@link #PH_BOOST} 那条）。 */
        public static final int PH_COAST = 1;
        /**
         * 无目标：不改变方向、不点火，按出膛方向直飞。⚠ 他的字面只到「若视场内无敌人则自爆」（同 L14750），
         * 与直飞冲突 ⇒ 是我把冲突摆成选项、他挑了「不锁定、直飞，看到再锁」这一档
         * （L14773 answers-OPT，UTC 2026-09-25T09:29:00.463Z ＝本地 2026-09-25 17:29:00：决策他的、措辞我的。方案里编号 R11）
         */
        public static final int NO_TARGET = -1;
        /** {@link #targetSlot} 的保留值：还没做射前雷达锁定，由 {@code MissileBehavior} 在下一帧补做。 */
        public static final int RADAR_PENDING = -2;

        /**
         * 出膛序号，单调递增。淘汰"最老的那一枚"按它比，不按槽号比——
         * free-list 复用的槽位顺序与出膛顺序无关（{@link BulletPool} 踩过同一条坑）。
         * 复用时由 {@link Missiles#occupy} **故意**写新值，所以它不在反射归零断言里。
         */
        public long seq;
        public int weaponId;
        public float x, y;
        /** 上一帧位置：绘制与扫掠判据要用；复用必须重置。 */
        public float px, py;
        public float vx, vy;
        /** 飞行时长（秒），与 {@link Balance.Missile#maxLifeSec} 比，到点静默回收。 */
        public float life;
        /** 本相位的点火计时（秒）。"该相位还剩多久"由 {@code MissileBehavior} 与 Balance 比出来。 */
        public float boostT;
        /** 导引头**连续**空视场的时长（秒）。只在 {@link #seekerOpen} 之后累加，锁到目标即归零。 */
        public float seekT;
        public int phase;
        /** 出膛那一刻已含攻击乘子、火力卡定值与暴击 ×2；结算端仍走 {@code WeaponFire.damageOf} 取整。 */
        public float damage;
        /** ⚠ 必须有：伤害里烘了 ×2，**分数没烘**（{@code killEnemy} 的 critKillMul 走这个布尔）。 */
        public boolean crit;
        public float size;
        public int color;
        /**
         * 敌人池的 **obj 槽号**（不是活跃表下标——{@code Enemies.killAt} 做末位交换只动
         * {@code active[]}，obj 槽号在敌人存活期内稳定）。
         * {@link #NO_TARGET} = 无目标直飞，{@link #RADAR_PENDING} = 待射前锁定。
         */
        public int targetSlot;
        /**
         * 目标的代次戳。槽号会被 free-list 复用 ⇒ 光有槽号会悬垂到"后来生成进同一槽的新敌人"，
         * 必须 {@code slot} 与 {@code born} 同时相等才是"还是那一只"。
         */
        public long targetBorn;
        /**
         * 发射单元的装订点：出膛那一步用目标的当前位置与瞬时速度（匀速 CV）解出的拦截位置。
         * 「装订发射单元」与「抵达发射单元描述的位置后扫描附近敌机」是他的字
         * （L38831 plain，UTC 2026-09-29T13:21:15.566Z ＝本地 09-29 21:21:15）；怎么解（CV、
         * 无解时退化为目标此刻的位置）是我的实现。{@link #unitSet} 为 false 时这两个数无意义。
         */
        public float unitX, unitY;
        /** 装订成功了吗。射前雷达锥里没有可装订的目标 ⇒ false，此后一路直飞到寿命/出界。 */
        public boolean unitSet;
        /**
         * 射前雷达**装订**的那一只敌人的槽号（簇 II「中段引导」要逐帧追的就是它）。
         *
         * <p>它与 {@link #targetSlot} 是**两个独立的名词**：装订只回答"往哪飞"，不锁、不占目标
         * （mode0 那一段一路直飞，进圈才竞争）；只有 {@code targetSlot} 进
         * {@code MissileBehavior} 的占用位图。两个字段合成一个的话，中段引导就会顺手把目标占走，
         * 多枚弹朝同一只飞的场景当场消失。
         */
        public int boundSlot;
        /** {@link #boundSlot} 的代次戳，判法与 {@link #targetBorn} 同（槽会被 free-list 复用）。 */
        public long boundBorn;
        /**
         * 开眼了吗：弹与 {@link #unitX}/{@link #unitY} 的距离进 {@code Balance.missile.seekerRange}
         * 那一刻置位，**置位前不索敌、不转向、不累 seekT**（L40027 裁的"装订段按设计字面直飞"）。
         */
        public boolean seekerOpen;

        void reset() {
            seq = 0L;
            weaponId = 0;
            x = 0f; y = 0f;
            px = 0f; py = 0f;
            vx = 0f; vy = 0f;
            life = 0f;
            boostT = 0f;
            seekT = 0f;
            phase = PH_BOOST;
            damage = 0f;
            crit = false;
            size = 0f;
            color = 0;
            targetSlot = NO_TARGET;
            targetBorn = 0L;
            boundSlot = NO_TARGET;
            boundBorn = 0L;
            unitX = 0f; unitY = 0f;
            unitSet = false;
            seekerOpen = false;
        }
    }

    private final Missile[] objs;
    private final int[] active;
    private final int[] free;
    private int activeCount;
    private int freeCount;
    private long nextSeq = 1L;
    private long spawnTotal;
    private long evictTotal;

    /**
     * 池外的一只普通对象：满池淘汰时把**被淘汰那枚的字段副本**写在这里，交给 {@code Game} 播自毁动画。
     * ⚠ 同帧淘汰多枚时只留最后一条——这条路径由 {@link Balance.Missile#capacity} 的 1.7× 余量
     * 保证走不到（{@code MissilesTest} 的产出口径 bound 钉着），所以不为它开队列。
     * 它是池外的独立对象，永远不进 {@link #objs}，也不会被 {@code reset()} 归零断言扫到。
     */
    public final Missile evicted = new Missile();

    public Missiles(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        objs = new Missile[capacity];
        active = new int[capacity];
        free = new int[capacity];
        for (int i = 0; i < capacity; i++) {
            objs[i] = new Missile();
            free[i] = capacity - 1 - i;      // 弹出顺序 0,1,2...
        }
        freeCount = capacity;
    }

    public int capacity() { return objs.length; }
    public int activeCount() { return activeCount; }
    public int freeCount() { return freeCount; }
    public long spawnTotal() { return spawnTotal; }
    public long evictTotal() { return evictTotal; }

    /** 有空位就发，满了返回 null——调用方要么走 {@link #spawnEvictingOldest()}，要么明确接受少一发。 */
    public Missile spawn() {
        if (freeCount == 0) return null;
        return occupy(free[--freeCount]);
    }

    /**
     * 玩家侧用这条：**永不拒发**——"永不拒发"和"玩家侧走这条"都是我的措辞，他没有一条原文提到拒发。
     * 满池时淘汰最老的一枚（按 {@link Missile#seq} 比），
     * 把它的字段副本写进 {@link #evicted} 让调用方就地播自毁动画（R22：不结算），然后占它腾出的槽。
     */
    public Missile spawnEvictingOldest() {
        if (freeCount > 0) return occupy(free[--freeCount]);
        int i = oldestActiveIndex();
        copyInto(objs[active[i]], evicted);
        killAt(i);
        evictTotal++;
        return occupy(free[--freeCount]);
    }

    /** 返回**活跃表下标**：调用方要的正是 {@link #killAt(int)} 的那个下标。 */
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

    private Missile occupy(int oi) {
        Missile m = objs[oi];
        m.reset();
        m.seq = nextSeq++;
        m.targetSlot = Missile.RADAR_PENDING;   // 射前雷达锁定由 MissileBehavior 在下一帧补做
        active[activeCount++] = oi;
        spawnTotal++;
        return m;
    }

    /** 把一只导弹的字段复制到另一只（{@link #evicted} 那条交接路径用）。目标不在池里。 */
    static void copyInto(Missile from, Missile to) {
        to.seq = from.seq;
        to.weaponId = from.weaponId;
        to.x = from.x; to.y = from.y;
        to.px = from.px; to.py = from.py;
        to.vx = from.vx; to.vy = from.vy;
        to.life = from.life;
        to.boostT = from.boostT;
        to.seekT = from.seekT;
        to.phase = from.phase;
        to.damage = from.damage;
        to.crit = from.crit;
        to.size = from.size;
        to.color = from.color;
        to.targetSlot = from.targetSlot;
        to.targetBorn = from.targetBorn;
        to.boundSlot = from.boundSlot;
        to.boundBorn = from.boundBorn;
        to.unitX = from.unitX; to.unitY = from.unitY;
        to.unitSet = from.unitSet;
        to.seekerOpen = from.seekerOpen;
    }

    public Missile activeAt(int i) {
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

    public void clear() {
        while (activeCount > 0) killAt(activeCount - 1);
    }
}
