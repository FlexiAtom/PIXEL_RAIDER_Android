package com.flexiatom.pixelraider.game;

/**
 * 伤害结算规则（纯函数）。
 *
 * [规格] 撞小怪 = 最大生命 50%，自爆怪贴脸 = 60%，Boss 撞击 = 35%，Boss 子弹 = 20，小怪子弹 = 12。
 * **撞机类随生命上限缩放，子弹类固定点数**——这两类不能混在一个算法里：
 * 前者在玩家点了成长树/升级后会变，后者永远不会。
 */
public final class DamageRules {

    private DamageRules() { }

    /** out[0]=新护盾 out[1]=新生命 out[2]=1 表示护盾**这次真正归零**（触发破碎短无敌）。 */
    public static void applyToPlayer(int shield, int hp, int rawDamage, float takenMul, int[] out) {
        int dmg = scaleTaken(rawDamage, takenMul);
        int absorbed = shield < dmg ? shield : dmg;
        int newShield = shield - absorbed;
        int newHp = hp - (dmg - absorbed);
        out[0] = newShield < 0 ? 0 : newShield;
        out[1] = newHp < 0 ? 0 : newHp;
        // 破盾判的是"从有到无"，不要求溢出：伤害刚好等于剩余盾时盾同样碎了，不该少给那 0.5 秒无敌。
        out[2] = (shield > 0 && newShield == 0) ? 1 : 0;
    }

    /**
     * 状态层乘算后至少掉 1 点。
     * ⚠ takenMul() 在原型里定义了却从未被调用——铁壁/脆化只显示图标不生效。
     *   这里它是**唯一**的伤害入口，护盾与血量结算前必须过它。
     */
    public static int scaleTaken(int raw, float takenMul) {
        int v = Math.round(raw * takenMul);
        return v < Balance.damage.contactFloor ? Balance.damage.contactFloor : v;
    }

    /** 撞机伤害：按最大生命比例，含该种怪的 contactBonus 修正。 */
    public static int contactDamage(Balance.Enemy e, int maxHp) {
        float ratio = ratioFor(e);
        int v = Math.round(maxHp * ratio);
        return v < Balance.damage.contactFloor ? Balance.damage.contactFloor : v;
    }

    /** 自爆怪只有在**贴脸**时才吃 60%，非贴脸的擦碰按普通撞机。 */
    public static int contactDamageAt(Balance.Enemy e, int maxHp, boolean pointBlank) {
        float ratio = e.id == Balance.Enemy.BURSTER && pointBlank
                ? Balance.damage.bursterHitRatio
                : ratioFor(e);
        int v = Math.round(maxHp * ratio);
        return v < Balance.damage.contactFloor ? Balance.damage.contactFloor : v;
    }

    private static float ratioFor(Balance.Enemy e) {
        return Balance.damage.gruntHitRatio + e.contactBonus;
    }

    public static int bossContactDamage(int maxHp) {
        int v = Math.round(maxHp * Balance.damage.bossHitRatio);
        return v < Balance.damage.contactFloor ? Balance.damage.contactFloor : v;
    }

    /** 子弹类固定点数：Boss 弹 20、小怪弹 12。 */
    public static int bulletDamage(boolean fromBoss) {
        return fromBoss ? Balance.damage.bossBullet : Balance.damage.gruntBullet;
    }

    /** 超载扫描：固定大伤害，随状态层不缩放（它是玩家主动释放，不是承受）。 */
    public static int overloadDamage() {
        return Balance.overload.sweepDamage;
    }

    /**
     * 护盾来源的增量提示：满盾时不能谎报 +35。
     * [规格] 增量提示显示**实际增加量**。
     * 上限由调用方传：成长树把护盾上限抬到 150 之后，读 {@link Balance.player} 的 100 会算错实收。
     *
     * <p>**商店护盾卡不走这里**（它全额入账、可越限，见 {@link PlayerState#addShieldBeyondCap}）——
     * 不是漏改：这张卡的卡面在点下去之前就把收益写死了，按剩余空间打折就是"卡面写了收益、场上少给"。
     */
    public static int shieldGainActuallyApplied(int currentShield, int maxShield, int wanted) {
        int room = maxShield - currentShield;
        return wanted > room ? Math.max(0, room) : wanted;
    }
}
