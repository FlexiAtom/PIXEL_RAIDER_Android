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

    @Test
    public void trailWidthIsTheLostHpItself() {
        // 满条没有残影；掉 10% 血时残影段恰好等于那 10%（88 * 0.1 = 8.8 → 9）
        assertEquals(0, HudLayout.trailWidth(1f, 1f));
        assertEquals(0, HudLayout.trailWidth(0.8f, 0.5f));   // 已经在真值之内：没有残影
        assertEquals(9, HudLayout.trailWidth(0.5f, 0.6f));
        assertEquals(88, HudLayout.trailWidth(0f, 1f));      // 最坏情况 = 整条，仍是正的可画宽度
    }
}
