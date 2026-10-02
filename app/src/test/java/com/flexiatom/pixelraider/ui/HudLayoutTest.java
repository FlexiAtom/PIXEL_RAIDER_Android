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
package com.flexiatom.pixelraider.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.flexiatom.pixelraider.core.RectI;

import org.junit.Test;

/**
 * HUD 几何（规格 §四）。期望值一律手推写死，不在测试里重算一遍公式——那是自证，不是验证。
 */
public class HudLayoutTest {

    private static final RectI A = new RectI();
    private static final RectI B = new RectI();
    private static final RectI C = new RectI();

    @Test
    public void specPinnedNumbersStayPinned() {
        // 规格明文：行1 y2~16、数值右对齐 208、暂停 214~232、胶囊右对齐 215。
        // 行2 的 y22~62 是**唯一被有意顶破的一条**（12px 汉字原生网格，2026-09-24 用户指令
        // "同步加大相关的UI部分配合12px"）——钉在这里，是为了让"顶破了多少"始终可查。
        assertEquals(2, HudLayout.ROW1_TOP);
        assertEquals(16, HudLayout.ROW1_BOTTOM);
        assertEquals(22, HudLayout.ROW2_TOP);
        assertEquals("顶破规格明文 62：四条 ×12 格间距", 70, HudLayout.ROW2_BOTTOM);
        assertEquals(208, HudLayout.INFO_RIGHT);
        assertEquals(214, HudLayout.PAUSE_LEFT);
        assertEquals(232, HudLayout.PAUSE_RIGHT);
        assertEquals(215, HudLayout.CAP_RIGHT);
    }

    @Test
    public void infoRowCannotReachIntoThePauseButton() {
        assertTrue("数值行右边界必须停在暂停按钮之前", HudLayout.INFO_RIGHT < HudLayout.PAUSE_LEFT);
        // 暂停按钮右边界 = W-8，帧数面板与它对齐（规格 §四：右边贴暂停按钮右边界）
        assertEquals(HudLayout.W - 8, HudLayout.PAUSE_RIGHT);
        HudLayout.fpsRect(A);
        assertEquals(232, A.right);
    }

    @Test
    public void fourBarsAreVerticallyAlignedInOneColumn() {
        for (int row = 0; row < HudLayout.BAR_ROWS; row++) {
            boolean main = row == 0;
            HudLayout.barRect(row, main, A);
            HudLayout.labelRect(row, B);
            assertEquals("第 " + row + " 条的条体左边必须与其它条同列", HudLayout.BAR_X, A.left);
            assertEquals("条体右边同列", HudLayout.BAR_X + HudLayout.BAR_W, A.right);
            assertEquals("标签同列", HudLayout.LABEL_X, B.left);
            assertTrue("标签不得压到条体", B.right <= A.left);
            // 手推中心线：行顶 22/34/46/58，间距 BAR_PITCH=12，中心 = 行顶 + 6
            int mid = 28 + row * 12;
            assertEquals(mid, (A.top + A.bottom) / 2);
            assertEquals(mid, (B.top + B.bottom) / 2);
        }
        // 生命条 6px、通用条 4px（规格：生命条保留视觉权重）
        HudLayout.barRect(0, true, A);
        HudLayout.barRect(1, false, B);
        assertEquals(6, A.height());
        assertEquals(4, B.height());
    }

    @Test
    public void adjacentRowsTouchButNeverOverlap() {
        for (int row = 0; row + 1 < HudLayout.BAR_ROWS; row++) {
            HudLayout.labelRect(row, A);
            HudLayout.labelRect(row + 1, B);
            assertTrue("标签行 " + row + " 压到下一行", A.bottom <= B.top);
            HudLayout.barRect(row, true, A);        // 最坏情况：两条都是 6px
            HudLayout.barRect(row + 1, true, B);
            assertTrue("条体行 " + row + " 压到下一行", A.bottom <= B.top);
        }
    }

    @Test
    public void rowsStackInOrderWithoutColliding() {
        HudLayout.pauseRect(A);                    // 行1
        assertTrue(A.bottom <= HudLayout.ROW2_TOP);
        assertTrue(HudLayout.ROW2_BOTTOM <= HudLayout.ROW3_TOP);
        HudLayout.fpsRect(A);
        assertTrue("胶囊行压到帧数面板", HudLayout.ROW3_TOP + HudLayout.CAP_RING <= A.top);
        HudLayout.bossLabelRect(B);
        assertTrue("帧数面板压到 Boss 名", A.bottom <= B.top);
        HudLayout.bossBarRect(B);
        assertTrue("Boss 名压到 Boss 血条", HudLayout.BOSS_LABEL_BOTTOM <= B.top);
        assertEquals(82, HudLayout.ROW3_TOP + HudLayout.CAP_RING);
        assertEquals(84, HudLayout.FPS_TOP);
        assertEquals(98, HudLayout.FPS_BOTTOM);
        assertEquals(100, HudLayout.BOSS_LABEL_TOP);
        assertEquals(114, HudLayout.BOSS_TOP);
        // 整块 HUD 的高度预算：121 逻辑像素以内（画布最矮 320 时约占 38%，再高就要重排）
        assertTrue(HudLayout.BOSS_BOTTOM <= 121);
    }

    @Test
    public void capsuleGroupRightAlignsAndClearsTheBarColumn() {
        // 最多两枚（拾取层同时只留一个 + 环境层），最右一枚的名字右边界就是 215
        HudLayout.capsuleRects(0, B, C);
        assertEquals(215, C.right);
        assertEquals(C.left, B.right + HudLayout.CAP_GAP);
        assertEquals(HudLayout.CAP_RING, B.width());
        // 第二枚往左挪一格，且整组不许越到状态条那一列上
        HudLayout.capsuleRects(1, B, C);
        assertEquals(215 - HudLayout.CAP_STEP, C.right);
        assertTrue("胶囊越到状态条列上了", B.left > HudLayout.BAR_RIGHT);
        assertTrue("胶囊越出画布左边", B.left >= 0);
    }

    @Test
    public void fillWidthClampsOutOfRange() {
        assertEquals(0, HudLayout.fillWidth(0f));
        assertEquals(44, HudLayout.fillWidth(0.5f));
        assertEquals(88, HudLayout.fillWidth(1f));
        assertEquals(0, HudLayout.fillWidth(-3f));
        assertEquals(88, HudLayout.fillWidth(9f));
    }

    @Test
    public void ringTreatsMissingTimerAsFullRing() {
        // 环境层没有倒计时（离开地带即失效）。除零若返 0，图标从第一帧就是空环 = 看着像"已经结束了"
        assertEquals(1f, HudLayout.ringRemaining01(9f, 0f), 0f);
        assertEquals(0.5f, HudLayout.ringRemaining01(6f, 12f), 0f);
        assertEquals(1f, HudLayout.ringRemaining01(15f, 12f), 0f);
        assertEquals(0f, HudLayout.ringRemaining01(-1f, 12f), 0f);
    }

    @Test
    public void trailOnlyFallsTowardTruthAndSnapsOnHeal() {
        // 掉血：以 2.0/秒 的速率往下追，一帧 1/60 秒降 1/30
        assertEquals(1f - 1f / 30f, HudLayout.trailNext(1f, 0.6f, 1f / 60f, 2f), 1e-6f);
        // 追上就停住，不许越过真值（越过的话血条会短于实际血量）：0.62 一帧降 1/30 会到 0.5867
        assertEquals(0.6f, HudLayout.trailNext(0.62f, 0.6f, 1f / 60f, 2f), 0f);
        // 回血：残影立刻等于真值，不然看着像还在掉
        assertEquals(0.9f, HudLayout.trailNext(0.5f, 0.9f, 1f / 60f, 2f), 0f);
        // 定格帧（dt=0）不动
        assertEquals(0.8f, HudLayout.trailNext(0.8f, 0.2f, 0f, 2f), 0f);
    }

    /**
     * 炸弹按钮（规格 §掉落物"入栏，由玩家决定何时放"）。
     *
     * <p>它钉在战斗区右下角而不是 HUD 那一组里：行1~行3 与 Boss 条已经占满顶部，
     * 而"由玩家决定何时放"要求它是一个**第二指随时够得着**的按钮。
     */
    @Test
    public void bombButtonSitsInTheBattlefieldCornerAndFitsTwoDigits() {
        HudLayout.bombRect(320, A);
        assertEquals(200, A.left);
        assertEquals(294, A.top);
        assertEquals(234, A.right);                    // W − 内缩 6
        assertEquals(314, A.bottom);
        HudLayout.bombRect(533, B);
        assertEquals("整块跟着战斗区底边长，不留在 320 那一档", 527, B.bottom);
        assertEquals(A.height(), B.height());
        // 两位存货数（99）也得装下：一位数字 7 宽、两位 11 宽（CELL_W 6 ×2 −1）
        int twoDigits = 2 * 6 - 1;
        assertTrue("数字会画出按钮右缘",
                HudLayout.BOMB_PAD + HudLayout.BOMB_ICON + HudLayout.BOMB_NUM_GAP + twoDigits
                        <= HudLayout.BOMB_W);
        // 图标边长是 8 格精灵的整数倍（规格 §三：像素只能整数倍放大）
        assertEquals(0, HudLayout.BOMB_ICON % 8);
        // 与顶部那一组读数谁也不压谁（最矮的战斗区上量）
        HudLayout.bombRect(320, C);
        assertTrue("按钮顶到了 Boss 血条", C.top > HudLayout.BOSS_BOTTOM);
    }

    /**
     * 切枪键（他 2026-10-02 逐字「切换在炸弹附近（总之是底部），加个切换键」）。
     *
     * <p>三组数各挡一种错法：
     * <ul>
     *   <li><b>同底线、同宽、同高</b>（手推 158..192 × 294..314）：底部那一组必须看起来是<b>一对键</b>，
     *       长歪的一枚会被读成"屏幕上另一个东西"而不是"另一个能按的键"。</li>
     *   <li><b>绘制框不相交</b>（192 &lt; 200，中间净空 {@code SWITCH_GAP} = 8 格）：两框一叠，
     *       玩家按下时看到的描边就是一整块，那已经不是两个键了。</li>
     *   <li><b>各自的中心不落在对方的框里</b>：这条才是命中判定真正的依据。{@code Widgets.hitRect}
     *       按<b>中心</b>外扩到 48dp 下限，外扩量 {@code T} 由屏幕密度算出来，{@code T ≥ 42} 的
     *       低分辨率屏上两条命中框会在中间那 8 格咬上——但咬上的是两键<b>之间</b>那段，
     *       两键自己的落点仍各自归属自己（中心在框内 ⇒ 外扩后的框以中心对称）。
     *       剩下那一段重叠谁赢，由 {@code Game.onPointerDown} 的判定顺序定（切枪在前，
     *       误判方向是"多震一下"而不是"炸掉一件存了整波的手牌"）。</li>
     * </ul>
     */
    @Test
    public void switchKeySharesTheBombBaselineAndKeepsItsOwnCenter() {
        HudLayout.switchRect(320, A);
        assertEquals(158, A.left);
        assertEquals(294, A.top);
        assertEquals(192, A.right);
        assertEquals(314, A.bottom);
        HudLayout.bombRect(320, B);
        assertEquals("与炸弹不同一条底线", B.bottom, A.bottom);
        assertEquals("与炸弹不同一条顶线", B.top, A.top);
        assertEquals("底部那一对必须看起来是一对", B.width(), A.width());
        assertTrue("两枚键的绘制框咬上了", A.right < B.left);
        // 中心各自留在自己框内（左右两侧都留出台阶，才谈得上"命中框按中心归属"）
        assertTrue("切枪键的中心越进了炸弹框", (A.left + A.right) / 2 < B.left);
        assertTrue("炸弹键的中心越进了切枪框", (B.left + B.right) / 2 > A.right);
        // 跟着战斗区底边长（高屏那一档），且不留在 320 那一档
        HudLayout.switchRect(533, C);
        assertEquals(527, C.bottom);
        assertEquals(A.height(), C.height());
        assertEquals("横向不跟着屏高长", A.left, C.left);
        assertTrue("键顶到了 Boss 血条", A.top > HudLayout.BOSS_BOTTOM);
    }

    /**
     * 键上印的是<b>当前这把</b>的名字（复用 {@code Balance.Weapon.name}，不新增文案字面量，
     * 所以这条顺带把"六个卡名各是两个字"这件事变成几何前提）。
     *
     * <p>12px 汉字是一字一格，两字 24 格、装得进 34 格宽；哪天真把「脉冲」改成「脉冲弹」，
     * 36 &gt; 34 而标签是**居中**画的——它不裁切，而是从两侧各溢出一点，压到战斗区里去。
     * 这条断的是那个改名的动作得同时改这里。
     */
    @Test
    public void everyWeaponNameFitsTheSwitchKey() {
        for (com.flexiatom.pixelraider.game.Balance.Weapon w
                : com.flexiatom.pixelraider.game.Balance.weapons) {
            assertTrue(w.name + " 在切枪键上画不下（一字一格 ×12）",
                    w.name.length() * 12 <= HudLayout.SWITCH_W);
        }
    }

    @Test
    public void trailWidthIsTheLostHpItself() {
        // 满条没有残影；掉 10% 血时残影段恰好等于那 10%（88 * 0.1 = 8.8 → 9）
        assertEquals(0, HudLayout.trailWidth(1f, 1f));
        assertEquals(0, HudLayout.trailWidth(0.8f, 0.5f));   // 已经在真值之内：没有残影
        assertEquals(9, HudLayout.trailWidth(0.5f, 0.6f));
        assertEquals(88, HudLayout.trailWidth(0f, 1f));      // 最坏情况 = 整条，仍是正的可画宽度
    }
}
