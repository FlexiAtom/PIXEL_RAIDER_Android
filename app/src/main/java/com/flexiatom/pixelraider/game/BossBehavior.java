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

import com.flexiatom.pixelraider.game.Enemies.Enemy;

/**
 * Boss 行为（纯算术、无 android）。与小怪共用一个池，但走**另一条**分支：
 * 小怪是"穿过战斗区的消耗品"，Boss 是"驻场射击的固定靶"，两者的运动学不是一回事。
 *
 * 三种 Boss 的差别全在**弹道形状**上（规格：形态先于颜色）：
 * - 毁灭者：朝玩家扇形齐射——逼玩家横向跑位。
 * - 蜘蛛：窄张角慢速丝，二阶段 those 丝会追踪——逼玩家别贴着直线走。
 * - 堡垒：横扫的弹幕墙，**留一个缺口**——墙没有缺口就是必中，必中的弹幕不是难度是惩罚。
 *
 * 本类不生成子弹：{@link #advance} 只报"这一帧开火了"，弹数与方向由 {@link #shots}
 * 按当期状态现算给调用方。行为与实例化分开，弹幕形状才能在没有渲染器的 JVM 里被断言。
 */
public final class BossBehavior {

    public static final int FLAG_NONE = 0;
    public static final int FLAG_FIRED = 1;
    public static final int FLAG_PHASE2 = 2;

    /** 一次齐射的最大弹数，{@link #shots} 的输出缓冲按这个长度给。 */
    public static final int MAX_SHOTS = 16;

    private static final float DESCENT_MUL = 2.2f;
    private static final float WALL_BASE_DEG = 90f;      // 90° = 屏幕正下方

    private BossBehavior() { }

    /**
     * @return {@link #FLAG_FIRED} / {@link #FLAG_PHASE2} 的位或
     */
    public static int advance(Enemy e, Balance.Boss spec, float dt,
                              float playerX, float playerY, int logicW, int battleTop) {
        int flags = FLAG_NONE;
        e.age += dt;
        e.px = e.x;
        e.py = e.y;
        if (e.hitFlash > 0f) {
            e.hitFlash -= dt;
            if (e.hitFlash < 0f) e.hitFlash = 0f;
        }
        if (e.enter < 1f) {
            e.enter += dt / EnemyBehavior.ENTER_SEC;
            if (e.enter > 1f) e.enter = 1f;
        }

        float targetY = battleTop + spec.holdY;
        if (!e.locked) {
            // 入场段：只往下走，不开火。还没到位就打分会让"Boss 从上面压进来"这段读不出来。
            e.vy = spec.speed * DESCENT_MUL;
            e.vx = 0f;
            e.y += e.vy * dt;
            if (e.y >= targetY) {
                e.y = targetY;
                e.locked = true;
                e.anchorX = e.x;
                e.vx = e.vy = 0f;
                // 到位后先给一整个间隔再开第一轮：落地瞬间糊脸一发弹幕没有解。
                e.fireTimer = fireGapFor(spec, e);
            }
            return flags;
        }

        if (spec.amp > 0f) {
            e.phase += dt * (spec.speed / spec.amp);      // 角速度按幅度归一：切向速度恒等于 spec.speed
            EnemyBehavior.strafe(e, spec.amp, logicW, dt);
        }
        float m = e.radius + 2f;
        if (e.x < m) e.x = m;
        else if (e.x > logicW - m) e.x = logicW - m;
        e.y = targetY;

        if (phase2Ratio(spec, e) > 0f && !e.phase2
                && e.maxHp > 0 && (float) e.hp / e.maxHp <= phase2Ratio(spec, e)) {
            e.phase2 = true;
            flags |= FLAG_PHASE2;
        }

        e.fireTimer -= dt;
        if (e.fireTimer <= 0f) {
            e.fireTimer = fireGapFor(spec, e);
            flags |= FLAG_FIRED;
        }
        return flags;
    }

    /** 二阶段切换线：实体上的覆盖值优先（随机事件可能改它），没给就用数值表。 */
    public static float phase2Ratio(Balance.Boss spec, Enemy e) {
        return e.phase2AtRatio > 0f ? e.phase2AtRatio : spec.phase2AtRatio;
    }

    /** 二阶段只是"更快更多"，不改弹道形状——形状变了玩家会以为是另一只 Boss。 */
    public static float fireGapFor(Balance.Boss spec, Enemy e) {
        float g = spec.fireGap;
        if (e.phase2) g *= spec.phase2GapMul;
        return g < Balance.wave.spawnGapMin ? Balance.wave.spawnGapMin : g;
    }

    public static int pelletsFor(Balance.Boss spec, Enemy e) {
        int n = spec.pellets + (e.phase2 ? spec.phase2ExtraPellets : 0);
        return n > MAX_SHOTS ? MAX_SHOTS : (n < 1 ? 1 : n);
    }

    /**
     * 算出本次齐射的单位方向，写进 {@code outVX/outVY}，返回弹数。
     * 不写速度：弹速由 {@link Balance.Boss#bulletSpeed} 乘在外面，方向归一化才便于断言。
     */
    public static int shots(Enemy e, Balance.Boss spec, float playerX, float playerY,
                           float[] outVX, float[] outVY) {
        int n = pelletsFor(spec, e);
        if (outVX.length < n || outVY.length < n) n = Math.min(outVX.length, outVY.length);
        if (spec.id == Balance.Boss.FORTRESS) {
            return wallShots(e, spec, playerX, playerY, n, outVX, outVY);
        }
        // 扇形：以"指向玩家"为中心线，张角对称铺开
        float cx = playerX - e.x, cy = playerY - e.y;
        double base = Math.atan2(cy, cx);
        double spread = Math.toRadians(spec.spreadDeg);
        for (int i = 0; i < n; i++) {
            double a = n == 1 ? base : base - spread / 2d + spread * i / (n - 1);
            outVX[i] = (float) Math.cos(a);
            outVY[i] = (float) Math.sin(a);
        }
        return n;
    }

    /**
     * 堡垒的墙：整排朝下扫开 {@code spreadDeg}，**跳过离玩家最近的那一发**留缺口。
     * 缺口跟着玩家的横向位置动，所以它是"往哪跑"的选择题，不是"站着别动"的抽奖。
     */
    private static int wallShots(Enemy e, Balance.Boss spec, float playerX, float playerY,
                                 int n, float[] outVX, float[] outVY) {
        double spread = Math.toRadians(spec.spreadDeg);
        double aim = Math.atan2(playerY - e.y, playerX - e.x);
        int gap = 0;
        double gapErr = Double.MAX_VALUE;
        double[] ang = ANGLES;
        for (int i = 0; i < n; i++) {
            double a = n == 1 ? Math.toRadians(WALL_BASE_DEG)
                    : -spread / 2d + spread * i / (n - 1d) + Math.toRadians(WALL_BASE_DEG);
            ang[i] = a;
            double err = Math.abs(angleDiff(a, aim));
            if (err < gapErr) {
                gapErr = err;
                gap = i;
            }
        }
        int out = 0;
        for (int i = 0; i < n; i++) {
            if (i == gap && n > 3) continue;         // 少于 4 发就别再扣了，否则墙变成稀稀拉拉三两颗
            outVX[out] = (float) Math.cos(ang[i]);
            outVY[out] = (float) Math.sin(ang[i]);
            out++;
        }
        return out;
    }

    /** 归一到 -π..π 的角差。 */
    public static double angleDiff(double a, double b) {
        double d = a - b;
        while (d > Math.PI) d -= 2d * Math.PI;
        while (d < -Math.PI) d += 2d * Math.PI;
        return d;
    }

    private static final double[] ANGLES = new double[MAX_SHOTS];
}
