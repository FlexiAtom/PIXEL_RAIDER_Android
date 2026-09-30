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
 * 状态层：**拾取层 × 环境层，两层相乘、不互相覆盖**。
 *
 * 规格 §五 点名的原型缺陷就在这里：{@code takenMul()} 定义了却从未被任何伤害路径调用，
 * 于是铁壁/脆化只剩两个亮着的图标——玩家看到"受伤 -30%"，实际掉血一分不少。
 * 这类 bug 肉眼测不出来，所以本类是纯算术，且 {@link DamageRules#scaleTaken} 是伤害的**唯一**入口。
 *
 * 两条规格决定，都不是凭手感：
 * - [规格 §拾取层] 拾取层**同时只保留一个**，新的顶掉旧的（12 秒重新计）。
 *   所以这里存的是"当前那一个 + 剩余秒数"，不是一组叠加的 buff。
 * - [规格 §环境] 玩家刚吃到"狂热"不会被环境状态顶掉——环境层是另一条轴，只相乘、不写入拾取槽。
 */
public final class StatusLayers {

    /** 拾取层状态 id：与图标、HUD 顺序一一对应，新增必须同步 {@link #label}。 */
    public static final int FRENZY = 0, IRON = 1, LUCKY = 2, SWIFT = 3;
    public static final int LAG = 4, BRITTLE = 5, BARREN = 6, STICKY = 7;
    public static final int PICKUP_COUNT = 8;
    public static final int NONE = -1;

    /** 环境层：由星区决定，同时只有一个。 */
    public static final int ENV_NONE = 0, ENV_COLD = 1, ENV_FORGE = 2, ENV_STORM = 3, ENV_VOID = 4;

    private int pickupKind = NONE;
    private float pickupRemaining;
    private final boolean[] everUsed = new boolean[PICKUP_COUNT];
    private int envKind = ENV_NONE;

    public void step(float dt) {
        if (pickupRemaining <= 0f) return;
        pickupRemaining -= dt;
        if (pickupRemaining <= 0f) {
            pickupRemaining = 0f;
            pickupKind = NONE;
        }
    }

    /** [规格] 拾取层生效 12 秒；再次拾取（含同名）只刷新时长，不叠层数。 */
    public void activate(int kind) {
        if (kind < 0 || kind >= PICKUP_COUNT) return;
        pickupKind = kind;              // 新的顶掉旧的
        pickupRemaining = Balance.status.pickupSec;
        everUsed[kind] = true;
    }

    public void expire() {
        pickupKind = NONE;
        pickupRemaining = 0f;
    }

    public void clearPickups() {
        expire();
    }

    public void setEnvironment(int env) {
        envKind = env < ENV_NONE || env > ENV_VOID ? ENV_NONE : env;
    }

    /**
     * 星区 → 环境层。[规格] 环境状态是**纯函数实时算**（读 zoneIndexForWave），不存变量——
     * 波次一变结果就变，因此没有"离开星区要记得清理"这种会漏的分支。
     * 前 4 区（0..3）无惩罚，后 4 区依次 冰封 / 熔炉 / 磁暴 / 虚空。
     */
    public static int envForZone(int zoneIndex) {
        switch (zoneIndex) {
            case 4: return ENV_COLD;
            case 5: return ENV_FORGE;
            case 6: return ENV_STORM;
            case 7: return ENV_VOID;
            default: return ENV_NONE;
        }
    }

    public int environment() {
        return envKind;
    }

    /** 当前拾取状态；无生效状态为 {@link #NONE}。 */
    public int pickup() {
        return pickupKind;
    }

    public boolean has(int kind) {
        return pickupKind == kind && pickupKind != NONE;
    }

    public float remainingOf(int kind) {
        return pickupKind == kind ? pickupRemaining : 0f;
    }

    public int activeCount() {
        return pickupKind == NONE ? 0 : 1;
    }

    /** 把当前生效的拾取状态塞进 out[0]，返回 0 或 1；HUD 每帧读它，不分配。 */
    public int activeKinds(int[] out) {
        if (pickupKind == NONE || out.length == 0) return 0;
        out[0] = pickupKind;
        return 1;
    }

    /** 玩家见过没有：教学与图鉴读它，跨波次保留。 */
    public boolean everUsed(int kind) {
        return kind >= 0 && kind < PICKUP_COUNT && everUsed[kind];
    }

    /**
     * 受伤系数 = 拾取层 × 环境层。**小于 1 是减伤，大于 1 是增伤**。
     * 乘的是两层、不是两个 buff：熔炉不会清掉铁壁，铁壁也不会中和熔炉。
     */
    public float takenMul() {
        return pickupTaken() * envTaken();
    }

    public float rateMul() {
        return pickupRate() * envRate();
    }

    public float speedMul() {
        return pickupSpeed() * envSpeed();
    }

    public float dropMul() {
        return pickupDrop() * envDrop();
    }

    private float pickupTaken() {
        switch (pickupKind) {
            case IRON: return Balance.status.ironTakenMul;
            case BRITTLE: return Balance.status.brittleTakenMul;
            default: return 1f;
        }
    }

    private float pickupRate() {
        switch (pickupKind) {
            case FRENZY: return Balance.status.frenzyRateMul;
            case LAG: return Balance.status.lagRateMul;
            default: return 1f;
        }
    }

    private float pickupSpeed() {
        switch (pickupKind) {
            case FRENZY: return Balance.status.frenzySpeedMul;
            case SWIFT: return Balance.status.swiftSpeedMul;
            case LAG: return Balance.status.lagSpeedMul;
            case STICKY: return Balance.status.stickySpeedMul;
            default: return 1f;
        }
    }

    private float pickupDrop() {
        switch (pickupKind) {
            case LUCKY: return Balance.status.luckyDropMul;
            case BARREN: return Balance.status.barrenDropMul;
            default: return 1f;
        }
    }

    private float envTaken() {
        switch (envKind) {
            case ENV_FORGE: return Balance.status.forgeTakenMul;
            case ENV_VOID: return Balance.status.voidTakenMul;
            default: return 1f;
        }
    }

    private float envRate() {
        switch (envKind) {
            case ENV_COLD: return Balance.status.coldRateMul;
            case ENV_STORM: return Balance.status.stormRateMul;
            default: return 1f;
        }
    }

    private float envSpeed() {
        switch (envKind) {
            case ENV_COLD: return Balance.status.coldSpeedMul;
            case ENV_VOID: return Balance.status.voidSpeedMul;
            default: return 1f;
        }
    }

    private float envDrop() {
        switch (envKind) {
            case ENV_STORM: return Balance.status.stormDropMul;
            default: return 1f;
        }
    }

    /** 增益/减益的分界：0..3 是增益，4..7 是减益（索引固定，永不换位）。HUD 用它选颜色。 */
    public static boolean isBuff(int kind) {
        return kind >= FRENZY && kind <= SWIFT;
    }

    /** 名称一律简体（规格全篇简体）。中文要上屏，字符集就不该和规格分叉。 */
    public static String label(int kind) {
        switch (kind) {
            case FRENZY: return "狂热";
            case IRON: return "铁壁";
            case LUCKY: return "幸运";
            case SWIFT: return "迅捷";
            case LAG: return "迟滞";
            case BRITTLE: return "脆化";
            case BARREN: return "贫瘠";
            case STICKY: return "粘滞";
            default: return "?";
        }
    }

    public static String environmentLabel(int env) {
        switch (env) {
            case ENV_COLD: return "冰封";
            case ENV_FORGE: return "熔炉";
            case ENV_STORM: return "磁暴";
            case ENV_VOID: return "虚空";
            default: return "";
        }
    }
}
