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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.flexiatom.pixelraider.plat.MemStore;
import org.junit.Test;

/**
 * 成长树的账本（规格 §五：6 项 ×5 级、3→5→7→9→11、单项 35、全点满 210）。
 *
 * 期望值全部**手推**，不从生产公式反算：这一层错了的表现是"玩家攒了半天买不起"，
 * 用被测代码自己算一遍期望值等于没测。
 */
public class GrowthTreeTest {

    @Test
    public void costLadderSumsToTheSpecified35PerItem() {
        int sum = 0;
        for (int c : Balance.Growth.COST) sum += c;
        assertEquals("单项满级 35（规格）", 35, sum);
        assertEquals("阶梯长度 = 等级上限", Balance.Growth.MAX_LEVEL, Balance.Growth.COST.length);
        assertEquals(3, Balance.Growth.COST[0]);
        assertEquals(11, Balance.Growth.COST[Balance.Growth.MAX_LEVEL - 1]);
    }

    @Test
    public void nextCostWalksTheLadderThenReportsMaxed() {
        GrowthTree g = new GrowthTree();
        g.addWallet(35);
        for (int i = 0; i < Balance.Growth.MAX_LEVEL; i++) {
            assertEquals(Balance.Growth.COST[i], g.nextCost(Balance.Growth.ATTACK));
            assertTrue(g.buyTreeUpgrade(Balance.Growth.ATTACK));
        }
        assertEquals(GrowthTree.PRICE_MAXED, g.nextCost(Balance.Growth.ATTACK));
        assertTrue(g.maxed(Balance.Growth.ATTACK));
        assertEquals(0, g.wallet());
    }

    @Test
    public void maxingEveryItemCostsTheSpecified210Chips() {
        GrowthTree g = new GrowthTree();
        g.addWallet(210);
        for (int id = 0; id < Balance.Growth.ITEMS; id++) {
            for (int lv = 0; lv < Balance.Growth.MAX_LEVEL; lv++) {
                assertTrue("第 " + id + " 项第 " + lv + " 级买不起", g.buyTreeUpgrade(id));
            }
            assertTrue(g.maxed(id));
        }
        assertEquals(0, g.wallet());
        assertEquals(30, g.totalLevels());
    }

    @Test
    public void oneItemDoesNotDrainAnotherOnesPrice() {
        GrowthTree g = new GrowthTree();
        g.addWallet(10);                       // 够攻击买两级（3+5），不够买三级
        assertTrue(g.buyTreeUpgrade(Balance.Growth.ATTACK));
        assertTrue(g.buyTreeUpgrade(Balance.Growth.ATTACK));
        assertEquals(2, g.wallet());           // 10-3-5
        assertEquals(7, g.nextCost(Balance.Growth.ATTACK));
        assertFalse(g.buyTreeUpgrade(Balance.Growth.ATTACK));
        assertEquals("拒绝的购买不该扣费", 2, g.wallet());
        assertEquals(2, g.level(Balance.Growth.ATTACK));
    }

    @Test
    public void emptyWalletAndBadIdsAreRefusedWithoutTouchingState() {
        GrowthTree g = new GrowthTree();
        g.addWallet(2);                       // 连最便宜的第一级（3）都买不起
        assertFalse(g.buyTreeUpgrade(Balance.Growth.RATE));
        assertEquals(2, g.wallet());
        assertFalse(g.buyTreeUpgrade(-1));
        assertFalse(g.buyTreeUpgrade(Balance.Growth.ITEMS));
        assertEquals(0, g.level(-1));
        assertFalse(g.maxed(999));
    }

    @Test
    public void levelZeroIsTheIdentityAndLevelFiveIsTheSpecifiedCap() {
        GrowthTree g = new GrowthTree();
        assertEquals(1f, g.attackMul(), 1e-6f);
        assertEquals(1f, g.rateMul(), 1e-6f);
        assertEquals(1f, g.speedMul(), 1e-6f);
        assertEquals(0, g.shieldBonus());
        assertEquals(0, g.critBonusPercent());
        assertEquals(0, g.extraBombs());
        for (int id = 0; id < Balance.Growth.ITEMS; id++) {
            g.addWallet(35);
            for (int lv = 0; lv < Balance.Growth.MAX_LEVEL; lv++) g.buyTreeUpgrade(id);
        }
        assertEquals("攻击满级 +15%（成长树侧的乘子；商店火力卡已改成定值加伤，两条通道叠在 WeaponFire 里）",
                1.15f, g.attackMul(), 1e-6f);
        assertEquals(1.20f, g.rateMul(), 1e-6f);
        assertEquals(1.15f, g.speedMul(), 1e-6f);
        assertEquals(50, g.shieldBonus());
        assertEquals(10, g.critBonusPercent());
        assertEquals(5, g.extraBombs());
    }

    @Test
    public void negativeDepositsAreIgnored() {
        GrowthTree g = new GrowthTree();
        g.addWallet(10);
        g.addWallet(-999);
        assertEquals(10, g.wallet());
    }

    @Test
    public void treeRoundTripsThroughTheStore() {
        MemStore kv = new MemStore();
        GrowthTree g = new GrowthTree();
        g.addWallet(50);
        g.buyTreeUpgrade(Balance.Growth.SHIELD);
        g.buyTreeUpgrade(Balance.Growth.SHIELD);
        g.buyTreeUpgrade(Balance.Growth.BOMB);
        g.save(kv);
        assertEquals(1, kv.flushes);                       // 一次购买一次落盘，不是三键三次

        GrowthTree back = new GrowthTree();
        back.readFrom(kv);
        assertEquals(2, back.level(Balance.Growth.SHIELD));
        assertEquals(1, back.level(Balance.Growth.BOMB));
        assertEquals(0, back.level(Balance.Growth.ATTACK));
        assertEquals(39, back.wallet());                   // 50 -(3+5)-3
        assertEquals(20, back.shieldBonus());
        assertEquals(1, back.extraBombs());
    }

    @Test
    public void corruptStoreIsClampedNotTrusted() {
        MemStore kv = new MemStore();
        kv.putLong("growth_l0", 99);
        kv.putLong("growth_l1", -4);
        kv.putLong("growth_wallet", -1);
        GrowthTree g = new GrowthTree();
        g.readFrom(kv);
        assertEquals(Balance.Growth.MAX_LEVEL, g.level(Balance.Growth.ATTACK));
        assertEquals(0, g.level(Balance.Growth.RATE));
        assertEquals(0, g.wallet());
    }

    @Test
    public void missingStoreReadsAsAFreshTree() {
        GrowthTree g = new GrowthTree();
        g.readFrom(com.flexiatom.pixelraider.plat.KeyValue.EMPTY);   // 没挂真存储时的兜底
        assertEquals(0, g.totalLevels());
        assertEquals(0, g.wallet());
    }
}
