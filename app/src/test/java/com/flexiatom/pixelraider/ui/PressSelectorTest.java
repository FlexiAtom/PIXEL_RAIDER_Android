/*
 * PIXEL RAIDER — 原生 Android 纵版弹幕射击
 * Copyright (C) 2026 Flexiatom
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

import org.junit.Test;

/**
 * 按压态状态机——MD3 在触屏上唯一有真实后果的那半套（抬起才提交），所以边角情形一条条钉死。
 *
 * 它只认下标不认矩形，所以这里可以完全脱离几何来测；几何与解析那半住在 {@link PauseScreenTest}。
 */
public class PressSelectorTest {

    @Test
    public void pressThenReleaseOnTheSameTargetCommits() {
        PressSelector p = new PressSelector();
        p.pressDown(3, 7);
        assertEquals(3, p.pressed());
        assertEquals(3, p.releaseTo(3, 7));
        assertEquals(PressSelector.NONE, p.pressed());
    }

    @Test
    public void slidingToAnotherTargetCancelsThePress() {
        PressSelector p = new PressSelector();
        p.pressDown(3, 7);
        p.dragTo(4, 7);
        assertEquals(PressSelector.NONE, p.pressed());
        assertEquals(PressSelector.NONE, p.releaseTo(4, 7));
    }

    /**
     * 一旦解析到别处，这一手势就**永久取消**，滑回来也不复活。
     *
     * Android 的 View 会重新按下；这里刻意不做：状态机少一份"曾经按过谁"的记忆，
     * 而撤掉一个不可逆动作（重开本局）本来只需要"划开"这一个动作，不需来回蹭的第二次机会。
     */
    @Test
    public void onceTheFingerLeavesTheGestureIsCancelledForGood() {
        PressSelector p = new PressSelector();
        p.pressDown(3, 7);
        p.dragTo(4, 7);
        p.dragTo(3, 7);
        assertEquals(PressSelector.NONE, p.pressed());
        assertEquals(PressSelector.NONE, p.releaseTo(3, 7));
    }

    /** 抬手时解析回另一枚 = 取消。这里钉的是"提交要求两端同解"，不是"必须回到按下点"。 */
    @Test
    public void releasingOnADifferentTargetCancels() {
        PressSelector p = new PressSelector();
        p.pressDown(3, 7);
        assertEquals(PressSelector.NONE, p.releaseTo(4, 7));
        assertEquals(PressSelector.NONE, p.pressed());
    }

    @Test
    public void aSecondFingerCannotStealOrCancelTheArmedPress() {
        PressSelector p = new PressSelector();
        p.pressDown(3, 7);
        p.dragTo(4, 8);                           // 别人的手指划到第二枚上
        assertEquals(3, p.pressed());
        assertEquals(PressSelector.NONE, p.releaseTo(4, 8));   // 别人先抬起
        assertEquals(3, p.pressed());              // 抬起者不是武装指 → 按压态原样保留
        assertEquals(3, p.releaseTo(3, 7));        // 武装指照常提交
    }

    /**
     * 第二指按在**空白处**要松开——与上一条相反，这是有意的不对称。
     *
     * 划过去的手指不改状态（它可能只是路过），但"按下来"是一个明确的新意图；这时还挂着高亮，
     * 就等于屏幕上有一枚没人按着的按钮在亮。代价见 {@link #aPressLostToARacingFingerIsInert}。
     */
    @Test
    public void aSecondFingerOnBlankGroundReleasesThePress() {
        PressSelector p = new PressSelector();
        p.pressDown(3, 7);
        p.pressDown(PressSelector.NONE, 8);
        assertEquals(PressSelector.NONE, p.pressed());
        assertEquals(PressSelector.NONE, p.releaseTo(3, 7));
    }

    /** 上一条的代价：被抢走的那根指头抬起时什么也不发生（不会误提交），但这一击确实丢了。 */
    @Test
    public void aPressLostToARacingFingerIsInert() {
        PressSelector p = new PressSelector();
        p.pressDown(3, 7);
        p.pressDown(4, 8);                         // 第二指直接按在另一枚上
        assertEquals(4, p.pressed());
        assertEquals(PressSelector.NONE, p.releaseTo(3, 7));   // 先抬起的原指：丢了，但不误触
        assertEquals(4, p.releaseTo(4, 8));                     // 后按下的那枚照常生效
    }

    @Test
    public void releaseWithoutPressIsInert() {
        PressSelector p = new PressSelector();
        assertEquals(PressSelector.NONE, p.releaseTo(3, 7));
        assertEquals(PressSelector.NONE, p.releaseTo(PressSelector.NONE, 7));
    }

    /** 系统发 ACTION_CANCEL 时抬起点解析为 NONE：必须落进"取消"而不是"提交"。 */
    @Test
    public void syntheticCancelCancels() {
        PressSelector p = new PressSelector();
        p.pressDown(3, 7);
        assertEquals(PressSelector.NONE, p.releaseTo(PressSelector.NONE, 7));
        assertEquals(PressSelector.NONE, p.pressed());
    }

    /** 面板重开不许继承上一轮的按压态（抬起事件被环满丢掉时，这是唯一的兜底）。 */
    @Test
    public void clearDropsBothStateAndOwnership() {
        PressSelector p = new PressSelector();
        p.pressDown(3, 7);
        p.clear();
        assertEquals(PressSelector.NONE, p.pressed());
        assertEquals(PressSelector.NONE, p.releaseTo(3, 7));
    }
}
