package com.flexiatom.pixelraider.game;

/**
 * 敌人运动与开火判定（纯函数，JVM 可测）。
 *
 * 只写状态、不碰任何池与渲染：**行为是算术，刷弹和回收是 Game 的事**。
 * 这样"射手怪会不会一直往下跑出战斗区"这种问题能在单测里跑 600 帧钉死，
 * 而不是等真机上看见一只怪溜到底。
 *
 * 所有"驻场线/锁定线"都相对 {@code battleTop} 给，不写绝对 y——
 * 战斗区高度固定 320，但逻辑画布高度随机型在 320~560 之间变（2026-09-24 适配决策），画布原点也会跟着移。
 */
public final class EnemyBehavior {

    public static final int FLAG_NONE = 0;
    public static final int FLAG_FIRED = 1;
    /** 自爆怪进入爆心：Game 据此结算贴脸伤害并把怪移出场。 */
    public static final int FLAG_EXPLODE = 2;

    /** 出场渐入时长（秒），只影响透明度，不影响碰撞。 */
    public static final float ENTER_SEC = 0.25f;

    public static final float WEAVE_FREQ = 2.4f;
    public static final float PATROL_FREQ = 1.5f;

    /** [可调] 摆幅（逻辑像素）。240 宽的画面上 26 ≈ 十分之一屏，读得出"摆"又不会横穿全场。 */
    public static final float WEAVE_AMP = 26f;
    public static final float PATROL_AMP = 18f;
    public static final float PATROL_AMP_ELITE = 24f;

    /** [可调] 冲刺锁定后的加速倍率：不加速的话"冲刺"读起来和普通怪没区别。 */
    public static final float RUSH_BOOST = 1.9f;

    private EnemyBehavior() { }

    /**
     * @param spec 该 kind 的数值（{@link Balance#enemies}）
     * @return {@link #FLAG_FIRED} / {@link #FLAG_EXPLODE} 的位或
     */
    public static int advance(Enemies.Enemy e, Balance.Enemy spec, float dt,
                              float playerX, float playerY, int logicW, int battleTop) {
        int flags = FLAG_NONE;
        e.px = e.x;
        e.py = e.y;
        e.age += dt;
        if (e.enter < 1f) {
            e.enter += dt / ENTER_SEC;
            if (e.enter > 1f) e.enter = 1f;
        }
        if (e.hitFlash > 0f) {
            e.hitFlash -= dt;
            if (e.hitFlash < 0f) e.hitFlash = 0f;
        }

        float speed = spec.speed;
        switch (spec.id) {
            case Balance.Enemy.STRAIGHT:
                e.vx = 0f;
                e.vy = speed;
                break;

            case Balance.Enemy.WEAVE:
                e.phase += dt * WEAVE_FREQ;
                e.vy = speed * 0.72f;             // 横向摆的同时纵向慢一点，才看得出"摆"
                break;

            case Balance.Enemy.SHOOTER: {
                float hold = holdY(battleTop, e.anchorX);
                if (e.y < hold) {
                    e.vy = speed;
                    e.vx = 0f;
                } else {
                    if (!e.locked) {              // 第一次到线：把这里记成巡边中心
                        e.locked = true;
                        e.anchorX = e.x;
                    }
                    e.vy = 0f;
                    e.phase += dt * PATROL_FREQ;
                }
                break;
            }

            case Balance.Enemy.RUSHER:
                if (!e.locked) {
                    e.vx = 0f;
                    e.vy = speed * 0.32f;         // 锁定前慢，给玩家看清"这只要冲下来了"
                    if (e.y >= rushLockY(battleTop)) {
                        lockRush(e, playerX, playerY, speed * RUSH_BOOST);
                    }
                }
                break;

            case Balance.Enemy.ELITE: {
                float hold = battleTop + 46f;
                if (e.y < hold) {
                    e.vy = speed;
                    e.vx = 0f;
                } else {
                    if (!e.locked) {
                        e.locked = true;
                        e.anchorX = e.x;
                    }
                    e.vy = 0f;
                    e.phase += dt * PATROL_FREQ;
                }
                break;
            }

            case Balance.Enemy.BURSTER:
                e.vx = homeVx(e.x, playerX, speed);
                e.vy = speed * 0.8f;
                if (withinBlast(e, playerX, playerY, spec.burstRadius)) flags |= FLAG_EXPLODE;
                break;

            default:
                e.vx = 0f;
                e.vy = speed;
                break;
        }
        e.x += e.vx * dt;
        e.y += e.vy * dt;
        if (spec.id == Balance.Enemy.WEAVE) {
            strafe(e, WEAVE_AMP, logicW, dt);
        } else if (e.locked && (spec.id == Balance.Enemy.SHOOTER || spec.id == Balance.Enemy.ELITE)) {
            strafe(e, spec.id == Balance.Enemy.ELITE ? PATROL_AMP_ELITE : PATROL_AMP, logicW, dt);
        }
        keepInsideCorridor(e, logicW);

        if (spec.fireGap > 0f) {
            e.fireTimer -= dt;
            if (e.fireTimer <= 0f) {
                e.fireTimer = spec.fireGap;
                flags |= FLAG_FIRED;
            }
        }
        return flags;
    }

    // ---- 可单独断言的几块算术 ---------------------------------------------------------------

    /**
     * 横向**位移**式摆动：{@code x = anchorX + amp × sin(phase)}。
     * 为什么不写成 {@code vx = sin(phase) × speed} 再积分——那样位移是 {@code 1-cos}，
     * 每只怪都会朝一侧整体漂移，摆着摆着就贴到墙上了（规格 §零 "形态要读得出来"的反面）。
     * 位移式绕入场中心严格对称，纵向仍由速度积分，两条轴不相干。
     */
    public static void strafe(Enemies.Enemy e, float amplitudePx, int logicW, float dt) {
        float m = e.radius + 2f;
        float want = e.anchorX + (float) Math.sin(e.phase) * amplitudePx;
        float x = want < m ? m : (want > logicW - m ? logicW - m : want);
        e.vx = dt > 0f ? (x - e.x) / dt : 0f;     // vx 记的是**实际**位移，绘制与扫掠碰撞都要读
        e.x = x;
    }

    /** 射手驻场线：战斗区顶往下 44~96。种子用入场 x，同一道永远同一条线。 */
    public static float holdY(int battleTop, float laneSeed) {
        float lane = (float) ((laneSeed * 0.6180339887f) % 1f);   // 黄金分割散列，稳定且不需要 RNG
        return battleTop + 44f + lane * 52f;
    }

    /** 冲刺锁定线：越过这条线才决定方向，之后不再修正——可躲。 */
    public static float rushLockY(int battleTop) {
        return battleTop + 64f;
    }

    /** 锁定瞬间把目标方向存进 vx/vy；之后纯直线，玩家能读出来。 */
    public static void lockRush(Enemies.Enemy e, float playerX, float playerY, float speed) {
        float dx = playerX - e.x, dy = playerY - e.y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.001f) {
            e.vx = 0f;
            e.vy = speed;
        } else {
            e.vx = dx / len * speed;
            e.vy = dy / len * speed;
        }
        e.locked = true;
    }

    /** 自爆怪横向追踪玩家、纵向恒速：追踪太狠会变成必中。 */
    public static float homeVx(float x, float targetX, float speed) {
        float d = targetX - x;
        float limit = speed * 0.75f;
        return d < -limit ? -limit : (d > limit ? limit : d);
    }

    /** 爆心判定用**半径和的平方**，不开方。 */
    public static boolean withinBlast(Enemies.Enemy e, float playerX, float playerY, float burstRadius) {
        float r = e.radius + burstRadius;
        float dx = playerX - e.x, dy = playerY - e.y;
        return dx * dx + dy * dy <= r * r;
    }

    /** 横向别飞出画面；纵向不管（离场由 {@link Enemies#cullDeadAndOffscreen} 处理）。 */
    public static void keepInsideCorridor(Enemies.Enemy e, int logicW) {
        float m = e.radius + 2f;
        float right = logicW - m;
        if (e.x < m) {
            e.x = m;
            if (e.vx < 0f) e.vx = -e.vx;          // 撞墙弹回，速度式机动的怪不会粘在边上
        } else if (e.x > right) {
            e.x = right;
            if (e.vx > 0f) e.vx = -e.vx;
        }
    }
}
