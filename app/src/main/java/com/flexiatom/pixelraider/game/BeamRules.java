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
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.flexiatom.pixelraider.game;

/**
 * 光束几何（纯函数，JVM 可测）：一道竖束与敌圆的相交判据。
 *
 * <p>形状照 {@link WarheadRules}：**只回答"打没打到"，不扣血、不渲染**。留在 {@code Game} 里的
 * 只有"打到之后做什么"（扣血、{@code killEnemy}、撒火花、响一声），那几行在单测里是结构性零覆盖
 * ——{@code Game} 是渲染类（静态块 {@code new Paint} ⇒ JVM 直调必 {@code ExceptionInInitializerError}）。
 *
 * <p>⚠ **束恒为竖直**，这是从武器表读出来的事实而不是假设：激光那一格 {@code pellets == 1} 且
 * {@code spreadDeg == 0}（{@code BeamTest.laserRowIsASingleUprightBeam} 钉着它）。所以判据是
 * "点到竖线段的距离"，不需要一般线段。哪天激光有了张角或多道扇开，这里要换成
 * {@link WarheadRules#sweptBandHitsCircle} 那种带旋转的写法——**别把角度当 0 传进去**，
 * 那正是"画得直但打得歪"的来路。
 */
public final class BeamRules {

    private BeamRules() { }

    /**
     * 敌圆与这道束是否相交。**穿**：相交即命中，一道的纵向跨度上有几架就打几架，各结一次，
     * 互不吸收（这是"穿透"的全部内容——不是伤害衰减，是结算对象不止一个）。
     *
     * <p>判据是**圆心到竖线段的距离 ≤ 敌半径 + 束的半宽**。三个细节各有出处：
     * <ul>
     *   <li><b>两端用 clamp 补成圆角端</b>：判据是**线段 ⊕ 半径**的胶囊，不是无限直线。
     *       {@code top} 与 {@code bottom} 是段的真端点，端点之外只剩圆的那一圈
     *       （敌半径 + 半宽 + pad），没有"顺着那一列一直打出去"。</li>
     *   <li><b>余量各向同性</b>：{@code hitPad} 加在同一个半径上，四边一样松——与
     *       {@code Bullet.hostileHitPad} 同语义。别把它读成"纵向也超程"：超程是段长给的，
     *       段长就是画出来的那一段。</li>
     *   <li><b>不开方</b>：全程平方比较，与 {@code playerShotsAgainstEnemies} 同一条口径。</li>
     * </ul>
     *
     * @param top    束的上端（战场上边，由调用方从 {@code Metrics.battleTop()} 现读——
     *               **判据与绘制必须读同一个数**，否则就是"打得比画得长"）
     * @param bottom 束的下端 = 出膛那一刻的机头 y（{@link Beams.Beam#y}）
     * @param halfWidth 束宽的一半（{@code Weapon.size ÷ 2}，**不减** hitPad：pad 是加上去的）
     * @param hitPad [可调] {@code Balance.beam.hitPad}，擦边算不算中
     * @param er     敌半径
     */
    public static boolean hits(float beamX, float top, float bottom, float halfWidth, float hitPad,
                               float ex, float ey, float er) {
        float dx = ex - beamX;
        // 圆心落到段的纵向区间外的哪一端，就取那一端做最近点（两次 clamp，不开方）
        float nearest = ey < top ? top : (ey > bottom ? bottom : ey);
        float dy = ey - nearest;
        float r = er + halfWidth + hitPad;
        return dx * dx + dy * dy <= r * r;
    }

    /**
     * 这一道的伤害：与直飞弹**同一档护甲**（{@link DamageRules#SRC_SHOT}）。
     *
     * <p>为什么不开第二个源：他裁护甲表时给的那一列是「子弹 vs 除子弹外」，激光这一把是**玩家的枪**，
     * 打的正是子弹那一档；新加一格 {@code SRC_BEAM} 等于替他决定"光束该不该被某种护甲减免"，
     * 那句话他没说。数值因此与弹丸同一条路：{@code resolve} 一个出口。
     *
     * <p>⚠ 与 {@code WeaponFire.damageOf} 同一条**不是永久的真话**：今天没有带"反光束"护甲的目标，
     * 等 #67 让光束也能打敌弹时，护甲要么在这条链上重新解析，要么由那张表一并裁成打不穿。
     */
    public static int damageOf(Beams.Beam b) {
        return DamageRules.resolve(DamageRules.SRC_SHOT, DamageRules.ARMOR_REGULAR, b.damage);
    }
}
