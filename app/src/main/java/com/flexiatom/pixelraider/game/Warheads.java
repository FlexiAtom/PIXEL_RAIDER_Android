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
 * 战斗部池（提案 missile-warhead-sim）：连续杆亮线 + 它破碎后的碎片，共用一张表。
 *
 * 为什么不开两张池：杆与碎片的**唯一区别**是几何（线段 vs 质点）与伤害倍率，而寿命、节拍、
 * 接触采样、结算四件事的形状完全一样。两张池就要两套遍历、两份 `collide` 段——那是把一件事
 * 写成两遍，而"写两遍"正是本仓最恨的第二个真源的入口。
 *
 * 为什么不塞进 {@link ParticlePool}：`Particle` **没有 damage 字段，而且 `collide` 根本不扫它**。
 * 往 gfx 里塞游戏语义会破这仓库明令守的边界（{@code BulletPool} 类注释、{@code Game} 类注释）。
 * 碎片看着像粒子，但它是**会造成伤害的实体**，账要归玩法层。
 *
 * ⚠ {@link Warhead#contact} 是一个 `int` 位图，位号 = 敌人的 **obj 槽号**，于是要求
 * {@code enemyCapacity ≤ 32}。这条不写在构造期就会静默溢出（{@code 1 << 32 == 1}），
 * 所以 {@link #Warheads(int, int)} 收敌人容量并当场断言。
 *
 * 本类不 import android：数值进、数值出，绘制端读值自己画。
 */
public final class Warheads {

    public static final class Warhead {
        /**
         * 连续杆：中心在 {@link #x},{@link #y}、垂直弹轴，半长随 {@link #life} 从 0 铺到
         * {@code Balance.missile.rodLength/2}——唯一真源是 {@link WarheadRules#rodHalfLen}，
         * 命中、绘制、破碎三处都读它，不在别处再算一遍。
         */
        public static final int KIND_ROD = 0;
        /** 碎片：质点，杆到期时按 {@code Balance.missile.shardCount} 一次生出。 */
        public static final int KIND_SHARD = 1;

        public int kind;
        /** 杆 = 线段中点；碎片 = 质点。 */
        public float x, y;
        /**
         * 上一帧位置：绘制拖尾**与 {@link WarheadRules#sweptBandHitsCircle} 的接触带**都读它；
         * 复用必须重置——它一旦与 {@link #x},{@link #y} 脱节，判据就比画面多一条看不见的尾巴。
         */
        public float px, py;
        public float vx, vy;
        /**
         * 本格的**完整伤害**（点/次结算）。出膛那一刻已经过 {@code WeaponFire.damageOf} 取整，
         * 碎片则由 {@link WarheadRules#shardDamage} 从杆值派生——**不是**第三个来源。
         */
        public int damage;
        /**
         * 纯绘制尺寸：杆是弹体粗细（继承 {@code Missile.size}，命中判定不吃它，见
         * {@link Balance.Missile#warheadHitPad}），碎片是方块边长。
         */
        public float size;
        public int color;
        /** 飞行时长（秒），与 {@link #maxLife} 比。 */
        public float life;
        /**
         * 寿命。⚠ 它**不是**独立可调的数——由「这根该跳几次」推出来
         * （{@code (rodTicks + 0.5f) × tickSec}），理由见 {@link Balance.Missile#rodLifeSec}。
         */
        public float maxLife;
        /**
         * 结算节拍累计器（秒）。**保留余数** ⇒ 长期平均节拍精确，与帧率无关。
         * 0.08s 这个节拍数连同它的理由都是我的措辞——他只在 L14779 answers-OPT 的选项标签
         * 「固定节拍 0.08s（帧率无关）」里挑了这一档（utc 2026-09-25T09:33:09.768Z ⇒ 本地
         * 2026-09-25 17:33:09；方案里编号 R12）。
         * 禁止把它实现成"每 N 帧一次"。
         */
        public float tickAcc;
        /** ⚠ 必须从导弹透传下来：{@code killEnemy} 的 1.5× 分数走这个布尔，伤害里没烘。 */
        public boolean crit;
        /**
         * 接触位图，位号 = 敌人 obj 槽号。**这是"每步采样"与"每 tick 计费"之间唯一的桥**：
         * 敌人心可能整段穿过亮线而两个 tick 瞬间都不在接触区内（小怪 9px 弦 ÷ 180px/s 闭合
         * = 0.05s &lt; 0.08s），只在 tick 那一刻测会漏掉它 ⇒ 每步 OR 进来，到 tick 时统一结算并清零。
         *
         * <p>⚠ 敌人容量一旦 &gt; 32 就要换成 `long[]` 或 `int[]`，否则 {@code 1 << slot} 静默回绕。
         * 这条由 {@link Warheads#Warheads(int, int)} 的构造期断言守住，不靠注释。
         */
        public int contact;

        void reset() {
            kind = KIND_ROD;
            x = 0f; y = 0f;
            px = 0f; py = 0f;
            vx = 0f; vy = 0f;
            damage = 0;
            size = 0f;
            color = 0;
            life = 0f;
            maxLife = 0f;
            tickAcc = 0f;
            crit = false;
            contact = 0;
        }
    }

    private final Warhead[] objs;
    private final int[] active;
    private final int[] free;
    private int activeCount;
    private int freeCount;
    private long refuseTotal;

    /**
     * @param capacity      {@link Balance.Missile#warheadCapacity}
     * @param enemyCapacity {@code Balance.wave.maxAlive + 2}——只为断言位图装得下它，见类注释
     */
    public Warheads(int capacity, int enemyCapacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        if (enemyCapacity > 32) {
            // ⚠ 消息写英文是有意的：EmbeddedFontTest 扫 main 里全部非 ASCII 字面量并要求有点阵字模，
            // 而这条走 logcat、永不上屏。中文理由留在这里——Warhead.contact 是单个 int 位图，
            // 槽号 ≥32 会静默回绕成另一位，命中判给隔壁那个敌人，没有任何地方会红。
            throw new IllegalStateException("enemy capacity " + enemyCapacity
                    + " > 32: Warhead.contact is a single int bitmask, slot indices would wrap"
                    + " silently -- switch it to long[] or int[] first");
        }
        objs = new Warhead[capacity];
        active = new int[capacity];
        free = new int[capacity];
        for (int i = 0; i < capacity; i++) {
            objs[i] = new Warhead();
            free[i] = capacity - 1 - i;      // 弹出顺序 0,1,2...
        }
        freeCount = capacity;
    }

    public int capacity() { return objs.length; }
    public int activeCount() { return activeCount; }
    public int freeCount() { return freeCount; }
    /**
     * 满池拒生的次数。这里**允许**少生几片（玩家看不出），但拒绝必须可数——
     * "静默截断"和"看不出来的截断"不是一回事，前者是这仓库的头号敌人。
     */
    public long refuseTotal() { return refuseTotal; }

    public Warhead spawn() {
        if (freeCount == 0) {
            refuseTotal++;
            return null;
        }
        int oi = free[--freeCount];
        Warhead w = objs[oi];
        w.reset();
        active[activeCount++] = oi;
        return w;
    }

    public Warhead activeAt(int i) {
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
