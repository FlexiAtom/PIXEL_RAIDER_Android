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

import com.flexiatom.pixelraider.plat.KeyValue;

/**
 * 局外成长树的状态：六项等级 + 一个芯片钱包（纯算术，JVM 可测）。
 *
 * 方法名**必须**是 {@code buyTreeUpgrade}：规格 §五 点名它与局内商店的 {@code buyUpgrade(index)}
 * 语义相近，而 Java 只在同名同签名时才报错——一旦签名不同（重载）或走接口实现，
 * "点了没反应、芯片不扣"就会静默发生。命名区分是这条规格唯一能被编译器兜住的地方。
 *
 * 只存等级，不存倍率：{@link #attackMul()} 这些每次现算。存了倍率就等于存第二份真值，
 * 改 {@code Balance.growth} 的每级数值（调试面板运行期改表）就会出现"表改了、加成没改"。
 *
 * 效果一律是**乘数**，由调用方与局内 buff 相乘（规格 §五：叠乘而非覆盖）。
 */
public final class GrowthTree {

    /** {@link #nextCost(int)} 在满级时的返回值——不是 0，0 会被读成"免费"。 */
    public static final int PRICE_MAXED = -1;

    private static final String PREFIX = "growth_";
    private static final String WALLET = PREFIX + "wallet";

    private final int[] level = new int[Balance.Growth.ITEMS];
    private int wallet;

    public int level(int id) {
        return valid(id) ? level[id] : 0;
    }

    public boolean maxed(int id) {
        return valid(id) && level[id] >= Balance.Growth.MAX_LEVEL;
    }

    /** 下一级多少钱；满级返回 {@link #PRICE_MAXED}。 */
    public int nextCost(int id) {
        if (!valid(id)) return PRICE_MAXED;
        int lv = level[id];
        if (lv >= Balance.Growth.MAX_LEVEL) return PRICE_MAXED;
        return Balance.Growth.COST[lv];
    }

    /**
     * 购买：越界、满级、余额不足三种拒绝都**不动任何状态**（拒绝却不扣费是对的，但反过来说
     * "扣了费没升级"才是这类系统最致命的 bug，所以判定一律前置）。
     */
    public boolean buyTreeUpgrade(int id) {
        int cost = nextCost(id);
        if (cost == PRICE_MAXED || wallet < cost) return false;
        wallet -= cost;
        level[id]++;
        return true;
    }

    public int wallet() {
        return wallet;
    }

    /** 死亡时把这局捡到的芯片存进钱包；负数来自被改坏的调用方，直接当 0。 */
    public void addWallet(int chips) {
        if (chips > 0) wallet += chips;
    }

    // ---- 六项效果（等级 0 时全部是恒等值）--------------------------------------------

    public float attackMul() {
        return 1f + Balance.growth.attackPerLevel * level[Balance.Growth.ATTACK];
    }

    public float rateMul() {
        return 1f + Balance.growth.ratePerLevel * level[Balance.Growth.RATE];
    }

    public float speedMul() {
        return 1f + Balance.growth.mobilePerLevel * level[Balance.Growth.MOBILE];
    }

    public int shieldBonus() {
        return Balance.growth.shieldPerLevel * level[Balance.Growth.SHIELD];
    }

    public int critBonusPercent() {
        return Balance.growth.critPerLevel * level[Balance.Growth.CRIT];
    }

    public int extraBombs() {
        return Balance.growth.bombPerLevel * level[Balance.Growth.BOMB];
    }

    /** 已投入的总等级（菜单标题"成长 7/30"用）。 */
    public int totalLevels() {
        int sum = 0;
        for (int i = 0; i < level.length; i++) sum += level[i];
        return sum;
    }

    // ---- 落盘（渲染线程只在死亡与购买这两个低频时机走到这里）--------------------------

    /** 前缀下共 7 个键，读写成对；漏一个就会读回半棵树。 */
    public void writeTo(KeyValue kv) {
        for (int i = 0; i < level.length; i++) {
            kv.putLong(PREFIX + "l" + i, level[i]);
        }
        kv.putLong(WALLET, wallet);
    }

    /** 越界等级只可能来自被改坏或版本不符的存档：钳回来，别让菜单画出第 6 级。 */
    public void readFrom(KeyValue kv) {
        for (int i = 0; i < level.length; i++) {
            long lv = kv.getLong(PREFIX + "l" + i, 0);
            level[i] = lv < 0 ? 0 : (lv > Balance.Growth.MAX_LEVEL ? Balance.Growth.MAX_LEVEL : (int) lv);
        }
        long w = kv.getLong(WALLET, 0);
        wallet = w < 0 ? 0 : (w > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) w);
    }

    /** 写完就落地：一次购买是一笔交易，不是一次可以丢的广播。 */
    public void save(KeyValue kv) {
        writeTo(kv);
        kv.flush();
    }

    private static boolean valid(int id) {
        return id >= 0 && id < Balance.Growth.ITEMS;
    }
}
