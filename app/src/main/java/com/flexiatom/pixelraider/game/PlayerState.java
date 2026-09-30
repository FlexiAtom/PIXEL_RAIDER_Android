package com.flexiatom.pixelraider.game;

/**
 * 玩家机体状态（纯算术，JVM 可测）。
 *
 * 只管"数值与计时器"，不管输入怎么来的、画在哪：
 * 输入反算在 {@code InputRouter}，位置钳制在 {@link #clampToBattle}，都在 Game 里调。
 *
 * 三条规格约束都落在这里，且都在 {@link #hurt} 这一个出口上：
 * - [规格] 护盾点数制、**先扣盾再扣血**、不随时间衰减；
 * - [规格] 受击无敌 0.7 秒；护盾破碎另有 0.5 秒；
 * - [规格] 血包溢出转护盾（小 +25 / 大 +50），不是"满血就浪费"。
 */
public final class PlayerState {

    public static final int FLAG_NONE = 0;
    public static final int FLAG_HIT = 1;
    public static final int FLAG_SHIELD_BROKEN = 2;
    public static final int FLAG_DIED = 4;

    public float x, y;
    public float px, py;
    public int hp;
    public int shield;
    public int maxHp = Balance.player.maxHp;
    public int maxShield = Balance.player.maxShield;
    public float radius = Balance.player.radius;
    public float invuln;
    public int weaponId = Balance.Weapon.PULSE;
    public float fireTimer;
    public boolean alive = true;
    /** {@link DamageRules#applyToPlayer} 的输出缓冲，提为字段以免每次受伤 new（规格 §三）。 */
    private final int[] scratch = new int[3];

    public void respawn(float startX, float startY) {
        x = px = startX;
        y = py = startY;
        maxHp = Balance.player.maxHp;
        maxShield = Balance.player.maxShield;
        hp = maxHp;
        shield = maxShield;
        invuln = Balance.player.invulnSec;
        fireTimer = 0f;
        alive = true;
    }

    /** 成长树抬上限时同步调用；当前值不会自动缩水。 */
    public void raiseCaps(int newMaxHp, int newMaxShield) {
        if (newMaxHp > maxHp) {
            hp += newMaxHp - maxHp;
            maxHp = newMaxHp;
        }
        if (newMaxShield > maxShield) {
            shield += newMaxShield - maxShield;
            maxShield = newMaxShield;
        }
    }

    public boolean invulnerable() {
        return invuln > 0f;
    }

    public void grantInvuln(float sec) {
        if (sec > invuln) invuln = sec;      // 只加长，不缩短：破碎不该把受击无敌抹掉
    }

    public void stepTimers(float dt) {
        if (invuln > 0f) {
            invuln -= dt;
            if (invuln < 0f) invuln = 0f;
        }
        if (fireTimer > 0f) {
            fireTimer -= dt;
            if (fireTimer < 0f) fireTimer = 0f;
        }
    }

    /**
     * 受伤：先过 {@code takenMul}（规格 §五 点名的漏接线），再盾后血。
     * 无敌期直接返回 {@link #FLAG_NONE}——调用方据此决定要不要播受击特效。
     */
    public int hurt(int rawDamage, float takenMul) {
        if (!alive || invuln > 0f) return FLAG_NONE;
        DamageRules.applyToPlayer(shield, hp, rawDamage, takenMul, scratch);
        shield = scratch[0];
        hp = scratch[1];
        int flags = FLAG_HIT;
        if (scratch[2] == 1) {
            flags |= FLAG_SHIELD_BROKEN;
            grantInvuln(Balance.shield.breakInvulnSec);
        }
        if (hp <= 0) {
            hp = 0;
            alive = false;
            flags |= FLAG_DIED;
        } else {
            grantInvuln(Balance.player.invulnSec);
        }
        return flags;
    }

    /** 血包：溢出部分转护盾（[规格] 小 +25 / 大 +50），返回实际回血。 */
    public int healHp(int amount, int overflowShield) {
        int before = hp;
        int want = hp + amount;
        hp = want > maxHp ? maxHp : want;
        int healed = hp - before;
        if (want > maxHp) {
            addShield(overflowShield);
        }
        return healed;
    }

    /** 护盾道具/升级：按**实际收到**的量加，返回增量（增量提示不能谎报 +35）。 */
    public int addShield(int amount) {
        int applied = DamageRules.shieldGainActuallyApplied(shield, maxShield, amount);
        shield += applied;
        return applied;
    }

    /**
     * 商店护盾卡专用：全额入账，允许加到 {@code maxShield} 之上。
     *
     * <p>为什么必须是另一条通道而不是把上面那条的截断去掉：截断对护盾道具（+35）与血包溢出
     * （+25/+50）是**规格要求的**行为，只有商店卡不行——它的卡面在玩家点下去之前就把 "+50 点"
     * 写死了，结算再按剩余空间打折就是"卡面写了收益、场上少给"。
     * 裁定是用户第一手逐字（transcript L13322，2026-09-25T05:33:42.940Z，纯文本自敲）
     * 「护盾充能卡允许溢出上限」；<b>而这一句"卡面写了收益、场上少给"的机理表述是我的</b>，
     * 下面那个上架闸门「满盾不上架」同样出自我给的选项标签（L13890，answers-**OPT**）。
     *
     * <p>越限不是无限的：{@code ShopRules.isValid} 仍然只在 {@code shield < maxShield} 时上架，
     * 所以这条通道最多把人顶到 {@code maxShield + fromUpgrade - 1}，掉回上限之内才会重新出现。
     * 那个闸门是溢出额度的唯一真源，别在这儿再钳一次（两处钳 = 谁生效看不出来）。
     *
     * @return 实际收到（恒等于 {@code amount}，与 {@link #addShield(int)} 同一个返回契约）
     */
    public int addShieldBeyondCap(int amount) {
        shield += amount;
        return amount;
    }

    /** 射速受状态层影响：迟滞时同一段时间内打不出去，就是"变慢"。 */
    public boolean canFire(float rateMul) {
        return alive && fireTimer <= 0f && cooldownFor(rateMul) > 0f;
    }

    /** 换弹间隔（秒）。rateMul ≤ 0 视为配置错误，当作 1 而不是除零。 */
    public float cooldownFor(float rateMul) {
        float gap = Balance.weapons[weaponId].fireGap;
        if (rateMul <= 0f) rateMul = 1f;
        return gap / rateMul;
    }

    public void consumeShot(float rateMul) {
        fireTimer = cooldownFor(rateMul);
    }

    public Balance.Weapon weapon() {
        return Balance.weapons[weaponId];
    }

    /** 位置钳在战斗区内（含机体半径），越界会毁掉朝向计算与命中判定。 */
    public void clampToBattle(int logicW, int battleTop, int battleH) {
        float m = radius + 1f;
        x = clampf(x, m, logicW - m);
        y = clampf(y, battleTop + m, battleTop + battleH - m);
    }

    public float hpRatio() {
        return maxHp <= 0 ? 0f : (float) hp / maxHp;
    }

    public float shieldRatio() {
        return maxShield <= 0 ? 0f : (float) shield / maxShield;
    }

    private static float clampf(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
