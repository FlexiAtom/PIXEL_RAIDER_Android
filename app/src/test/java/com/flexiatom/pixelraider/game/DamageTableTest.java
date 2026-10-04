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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 「源 × 护甲」那张表本身（{@link DamageRules#resolve}）。
 *
 * <p>与 {@code DamageBaselineTest} 的分工：那一件钉的是**九条落点的输出**（重构前后同一批字面数），
 * 这一件钉的是**表的形状**——维度齐不齐、无护甲那一列是不是恒等、"百分之 0"会不会被下限吃掉。
 * 两边都在，才是"值没漂"＋"规则只有一份"两件事各自有证据。
 *
 * <p>表驱动而不是逐格一个 @Test：二十格写二十条用例，改一次数值就要改一片测试，那种测试留不住。
 * 循环里必须留一条**只在真跑过几轮时才成立**的断言（下面的 {@code cells} 计数），否则空区间
 * 会给出零断言假绿——这条教训的先例是 {@code everyFirepowerLevelSurvivesThePerShotRounding}。
 */
public class DamageTableTest {

    /** 基准点数取一个**非整数**的值：既测得到打折，也测得到取整口径没被绕开。 */
    private static final float BASE = 7.2f;

    /**
     * 表的**声明维度**：9 条结算路径 / 10 档（弹体按池分两档）、2 种护甲档位。
     *
     * <p>写成字面数而不是读 {@code DamageRules.SRC_COUNT}——那两个常量现在由 {@code MUL.length}
     * 推导（见 DamageRules 里的说明），拿它们互推等于恒真。这里的字面数才是那条"新增一档"的 trap：
     * 谁在表里加一行，这两个数就会和实际长度对上不上，测试变红，逼他为新那一格想清楚两列的倍率。
     */
    private static final int SOURCES = 10;
    private static final int ARMORS = 2;

    /** 无护甲列 = 恒等（打折 1.0），所以下限之外不该有任何别的事发生。 */
    @Test
    public void noArmorColumnIsTheIdentityAndKeepsEveryOldNumber() {
        int cells = 0;
        for (int src = 0; src < DamageRules.SRC_COUNT; src++) {
            cells++;
            int got = DamageRules.resolve(src, DamageRules.ARMOR_NONE, BASE);
            int floor = floorOf(src);
            assertEquals("源 " + src + " 打无护甲目标不该被做任何事",
                    Math.max(floor, Math.round(BASE)), got);
            assertEquals("源 " + src + " 的无护甲倍率必须是 1", 1f, DamageRules.mulOf(src, DamageRules.ARMOR_NONE), 0f);
        }
        assertEquals("循环没跑 ⇒ 这条测试成了零断言假绿", SOURCES, cells);
    }

    /**
     * 下限**按源分家**，而且承受侧那一族读的是表里的旋钮而不是字面 1。
     *
     * <p>这条存在的理由：档二把三处下限合成一份，合成之后最容易出事的就是"顺手把两种下限当一种"
     * ——那样一改 {@code contactFloor} 会连带改掉机炮的下限，而两者本是两句话。
     */
    @Test
    public void floorsAreSplitBetweenPlayerSideAndAttackSide() {
        assertEquals(Balance.damage.contactFloor,
                DamageRules.resolve(DamageRules.SRC_ENEMY_BULLET, DamageRules.ARMOR_NONE, 0f));
        assertEquals(Balance.damage.contactFloor,
                DamageRules.resolve(DamageRules.SRC_CONTACT, DamageRules.ARMOR_NONE, 0f));
        assertEquals(Balance.damage.contactFloor,
                DamageRules.resolve(DamageRules.SRC_BOSS_CONTACT, DamageRules.ARMOR_NONE, 0f));
        for (int src = 0; src < DamageRules.SRC_COUNT; src++) {
            if (isPlayerFacing(src)) continue;
            assertEquals("攻击侧源 " + src + " 的下限恒为 1，不吃 contactFloor",
                    1, DamageRules.resolve(src, DamageRules.ARMOR_NONE, 0f));
        }
    }

    /**
     * 他那句逐字裁定的可执行形式：「目前仅格斗弹对子弹护甲的伤害是百分百」「其他武器…为百分之0」。
     */
    @Test
    public void onlyTheDogfightBodyPiercesBulletArmor() {
        for (int src = 0; src < DamageRules.SRC_COUNT; src++) {
            int got = DamageRules.resolve(src, DamageRules.ARMOR_BULLET, 100f);
            if (src == DamageRules.SRC_DOGFIGHT_BODY) {
                assertEquals("格斗弹对子弹护甲必须是全额", 100, got);
            } else {
                assertEquals("源 " + src + " 打子弹护甲必须是 0 点", 0, got);
            }
        }
    }

    /**
     * ⚠ 整件重构的核心不变量：**百分之 0 不吃"至少 1 点"那条下限**。
     *
     * <p>下限与零倍率共用一条 {@code max} 就会得到"有 0% 护甲的目标每次被擦掉 1 点"——那是这件
     * 事最坏也最安静的失败模式：卡面上写着"其他武器打不动它"，场上每下掉 1 点，而且没有任何
     * 异常。所以这条要单独有一节测试，不能混在上面那条循环的 0 里。
     */
    @Test
    public void zeroPercentNeverCollectsTheMinimum() {
        assertEquals(0, DamageRules.resolve(DamageRules.SRC_SHOT, DamageRules.ARMOR_BULLET, 0.4f));
        assertEquals(0, DamageRules.resolve(DamageRules.SRC_SHOT, DamageRules.ARMOR_BULLET, 0f));
        assertEquals(0, DamageRules.resolve(DamageRules.SRC_ROD, DamageRules.ARMOR_BULLET, 9999f));
        assertEquals(0, DamageRules.resolve(DamageRules.SRC_BOMB, DamageRules.ARMOR_BULLET, 1f));
        // 承受侧那三档也一样：0 就是 0，别让"至少掉一点"越过护甲
        assertEquals(0, DamageRules.resolve(DamageRules.SRC_ENEMY_BULLET, DamageRules.ARMOR_BULLET, 20f));
    }

    /**
     * 子弹护甲那一列今天只能是"全不通"或"全通"。
     *
     * <p>一旦出现小数（比如谁把某格填成 0.5），"倍率先于取整"这条次序就需要一条**新的**断言来钉
     * （现在没有小数格子，那条次序测不出来，写一条假的成功断言比不写更坏）。这条就是那个提醒，
     * 它挂在测试里而不挂在注释里，因为注释不会变红。
     */
    @Test
    public void bulletArmorColumnIsStillBinarySoNoFractionalRoundingOrderToPin() {
        for (int src = 0; src < DamageRules.SRC_COUNT; src++) {
            float mul = DamageRules.mulOf(src, DamageRules.ARMOR_BULLET);
            assertTrue("源 " + src + " 对子弹护甲的倍率是 " + mul
                            + "：这一格出现小数，就该补一条「先打折后取整」的断言再来改这条",
                    mul == 0f || mul == 1f);
        }
    }

    /**
     * 表的维度就是那两个字面数，而且每一格都真读得到。
     *
     * <p>两件事各管一种漂法：{@code SRC_COUNT}/{@code ARMOR_COUNT} 由 {@code MUL} 推导，所以
     * 「常量与表漂开」在编译期就没了——但「往表里加一档而没人裁它的两列」恰恰是这条要拦的：
     * 加一行就让 {@code assertEquals(SOURCES, SRC_COUNT)} 变红，逼来人改表的同时改这条期望。
     * 逐格 {@code mulOf} 再读一次管另一种：某一行少写一个数（数组参差），这里抛
     * {@code ArrayIndexOutOfBounds}，而不是留到场上某次结算里炸。
     */
    @Test
    public void tableDimensionsAreTheDeclaredOnesAndEveryCellIsReachable() {
        assertEquals("伤害的源有 " + SOURCES + " 档（9 条路径，弹体按池分两档）",
                SOURCES, DamageRules.SRC_COUNT);
        assertEquals("今天只有两种护甲档位：没有护甲 ＋ 子弹护甲", ARMORS, DamageRules.ARMOR_COUNT);
        for (int src = 0; src < DamageRules.SRC_COUNT; src++) {
            for (int armor = 0; armor < DamageRules.ARMOR_COUNT; armor++) {
                DamageRules.mulOf(src, armor);
            }
        }
    }

    /** 与 {@code DamageRules.floorFor} 同一句话的两个副本——**测试端的副本是有意的**：
     *  如果两边都写错成同一个样子，那才是这条不变量真正的失效信号。 */
    private static int floorOf(int source) {
        return isPlayerFacing(source) ? Balance.damage.contactFloor : 1;
    }

    private static boolean isPlayerFacing(int source) {
        return source == DamageRules.SRC_ENEMY_BULLET
                || source == DamageRules.SRC_CONTACT
                || source == DamageRules.SRC_BOSS_CONTACT;
    }
}
