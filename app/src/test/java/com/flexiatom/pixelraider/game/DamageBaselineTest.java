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
 * 伤害系统重做（档二）的**基线**：九条落点的输出钉成字面数。
 *
 * <p>为什么单独一个文件而不往 {@code PlayerRulesTest} 里塞：这个件的生命周期就是"重构一路绿"，
 * 重构收口后它的价值变成"下限只剩一份"的证据，与玩法测试不是一族。
 *
 * <p>它的用法是**先于重构写、重构时不许改期望值**。2026-10-04 落码前后各跑一次：落码前那一次
 * 打的是散在各处的算式（{@code WeaponFire.damageOf(float)}、{@code Game} 里那记
 * {@code max(1, round(…))}），落码后那一次打的是 {@link DamageRules#resolve} 的各个源档——
 * **两边这些字面数一个字都没动**，这才是"值没漂"的证明。调用写法改了而数没改，正是这次重构
 * 承诺的全部；反过来若某条断言在重构时不得不改期望值，那就是一次玩法改动，得单独裁定。
 *
 * <p>三条路径今天已被 {@code PlayerRulesTest} 用字面数钉住，本件**不重复钉**：
 * 撞小怪 50/75、Boss 撞 35/49、自爆贴脸 60、下限 1、{@code takenMul} 44/35/70
 * （见 {@code contactDamageScalesWithMaxHp} / {@code bursterOnlyHitsHardAtPointBlank} /
 * {@code contactFloorKeepsEveryHitDeadly} / {@code statusLayerMultipliesIntoEveryDamagePath}）。
 * 本件补的是那几条**没有字面数**的：玩家弹、导弹弹体、连续杆、碎片、超载、炸弹、敌弹。
 */
public class DamageBaselineTest {

    private final int[] out = new int[3];

    /** 打**无护甲**目标——今天场上人人如此，所以九条落点全都走这一格。 */
    private static int againstNothing(int source, float base) {
        return DamageRules.resolve(source, DamageRules.ARMOR_NONE, base);
    }

    /** 落点①玩家弹 → 敌（{@code Game.playerShotsAgainstEnemies}）：round 之后兜到至少 1。 */
    @Test
    public void shotDamageRoundsThenFloorsToOne() {
        assertEquals(1, againstNothing(DamageRules.SRC_SHOT, 0.4f));   // round=0 ⇒ 下限把它抬回 1
        assertEquals(1, againstNothing(DamageRules.SRC_SHOT, 1.2f));
        assertEquals(2, againstNothing(DamageRules.SRC_SHOT, 1.5f));   // Math.round 是"四舍六入五取偶上位"
        assertEquals(3, againstNothing(DamageRules.SRC_SHOT, 2.6f));
        assertEquals(7, againstNothing(DamageRules.SRC_SHOT, 7.2f));
        assertEquals(1, againstNothing(DamageRules.SRC_SHOT, 0f));     // ⚠ 0 点也掉 1：今天就是这条行为
        assertEquals(1, againstNothing(DamageRules.SRC_SHOT, -5f));    // 负数不会加血
        // 弹身那一侧的糖只是把同一档接上 b.damage，不另做决定
        BulletPool.Bullet b = new BulletPool(1).spawn();
        b.damage = 7.2f;
        assertEquals(7, WeaponFire.damageOf(b));
    }

    /**
     * 落点②导弹弹体 → 敌（{@code Game.settleImpact}）：与①**同一个出口、不是同一个函数**。
     *
     * <p>这条断言的形状在重构时变了，而数没变——重构前它钉的是"共用 {@code damageOf(float)}
     * 所以不可能漂"；重构后共用的是 {@code resolve}，源档却是分开的（普通导弹与格斗弹也要分），
     * 于是"同一个数"这件事必须由值相等来钉，不能再靠"共用函数"来推。
     */
    @Test
    public void missileBodyDamageSharesTheRoundingRuleButNotTheSource() {
        assertEquals(againstNothing(DamageRules.SRC_SHOT, 7.2f),
                againstNothing(DamageRules.SRC_MISSILE_BODY, 7.2f));
        assertEquals(7, againstNothing(DamageRules.SRC_MISSILE_BODY, 7.2f));
        assertTrue("普通导弹与格斗弹必须分档，否则他那句「仅格斗弹百分百」无处可写",
                DamageRules.SRC_MISSILE_BODY != DamageRules.SRC_DOGFIGHT_BODY);
    }

    /** 落点③连续杆 → 敌（{@code WarheadRules.spawnRod}）：伤害在生出那一刻已经取整过。 */
    @Test
    public void rodDamageIsRoundedAtSpawnTime() {
        assertEquals(7, againstNothing(DamageRules.SRC_ROD, 6.9f));    // 与 WarheadRulesTest 那个 6.9f 同值
        assertEquals(1, againstNothing(DamageRules.SRC_ROD, 0.2f));
    }

    /** 落点③′碎片 → 敌（{@code WarheadRules.shardDamage}）：整除四分之一、最小 1（他逐字的数）。 */
    @Test
    public void shardDamageIsQuarterOfRodRoundedDownFlooredAtOne() {
        assertEquals(25, WarheadRules.shardDamage(100));
        assertEquals(3, WarheadRules.shardDamage(12));
        assertEquals(2, WarheadRules.shardDamage(8));
        assertEquals(1, WarheadRules.shardDamage(4));
        assertEquals("3/4 整除是 0，必须兜到 1", 1, WarheadRules.shardDamage(3));
        assertEquals(1, WarheadRules.shardDamage(1));
    }

    /** 落点④超载扫描 → 全场敌（{@code Game.triggerOverload}）：固定点数，不吃状态层。 */
    @Test
    public void overloadDamageIsTheFixedSweepValue() {
        assertEquals(45, DamageRules.overloadDamage());
        assertEquals(Balance.damage.sweepDamage, DamageRules.overloadDamage());
    }

    /**
     * 落点⑤炸弹 → 全场敌（{@code Game.detonateBomb}）。⚠ 这条**重构前是写在 Game 里的算式**，
     * 没有任何纯函数承载，所以当时是用复写算式的方式钉值的（{@code max(1, round(100 × 0.5))}）；
     * 现在它有了自己的档（{@link DamageRules#SRC_BOMB}）与函数，**期望值一个字没动**。
     *
     * <p>{@code 1 → 1} 那格是它的下限证据：比例打在 1 血目标上是 0.5，向下会掉 0 点。
     */
    @Test
    public void bombDamageIsRatioOfTargetMaxHpFlooredAtOne() {
        assertEquals(50, DamageRules.bombDamage(100));
        assertEquals(70, DamageRules.bombDamage(140));
        assertEquals("1 血怪被炸也只能掉 1", 1, DamageRules.bombDamage(1));
    }

    /** 落点⑥敌弹 → 玩家：固定点数（Boss 20、小怪 12），不随玩家上限变。 */
    @Test
    public void enemyBulletDamageIsFixedPoints() {
        assertEquals(12, DamageRules.bulletDamage(false));
        assertEquals(20, DamageRules.bulletDamage(true));
        // 出膛写的是基准，打到人那一刻过 SRC_ENEMY_BULLET：整数过一遍 round + 下限，当然还是它自己
        assertEquals(12, againstNothing(DamageRules.SRC_ENEMY_BULLET, DamageRules.bulletDamage(false)));
        assertEquals(20, againstNothing(DamageRules.SRC_ENEMY_BULLET, DamageRules.bulletDamage(true)));
    }

    /** 落点⑥′敌弹打到玩家身上：盾先吸收，盾不够才掉血。 */
    @Test
    public void enemyBulletAgainstShieldAndHpEndToEnd() {
        DamageRules.applyToPlayer(0, 100, DamageRules.bulletDamage(false), 1f, out);
        assertEquals(0, out[0]);
        assertEquals(88, out[1]);
        assertEquals(0, out[2]);

        DamageRules.applyToPlayer(5, 100, DamageRules.bulletDamage(true), 1f, out);
        assertEquals("Boss 弹 20：盾吃 5、血掉 15", 0, out[0]);
        assertEquals(85, out[1]);
        assertEquals("盾从有到无 ⇒ 破碎旗标", 1, out[2]);
    }
}
