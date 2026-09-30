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
package com.flexiatom.pixelraider.ui;

/**
 * 面板按压态的**状态机**（MD3 的 pressed 层）：谁被按住、松手时算不算一次提交。
 *
 * <p>它只认**目标下标**，不认矩形。几何一律由调用方（面板）用**它自己那套命中解析**算好再交进来
 * ——这不是省事，而是唯一能让"按下"与"抬起"保持一致的做法。暂停面板把三行出口外扩到 48dp 之后
 * 行与行必然互压（实测：40 逻辑像素的 touch floor 下压掉 18 格，而行的间距只有 22 格），
 * 于是"抬起时另算一次谁在手指下面"会和"按下时谁被选中"给出不同答案，后果是一大片点击静默失效。
 * 两端口径同源，这条裂缝才不存在。
 *
 * <p>**提交权在抬起端**：按下只登记，抬起时解析结果仍是同一枚才算一次动作，滑开就取消。
 * 这条对暂停面板尤其值钱——"重开本局"作废本局，按住之后发现不对，把手指滑开就能撤。
 *
 * <p>纯 Java、无分配、不碰 Canvas（规格 §九.20）。
 */
public final class PressSelector {

    public static final int NONE = -1;

    private int index = NONE;
    private int pointerId = NONE;

    /** 当前被按住的目标下标；绘制端按它决定要不要铺状态层。 */
    public int pressed() {
        return index;
    }

    public void clear() {
        index = NONE;
        pointerId = NONE;
    }

    /**
     * 按下。调用方已经把手指下面第几枚目标解析好了（"淡完才可点"那类闸门也都在它那边）。
     *
     * <p>{@code NONE} 也要调：**第二根手指按在空白处，前一根手指的按压态必须立刻松开**。
     * Android 对普通 View 不是这么做的（它拒绝后来者），但这是游戏里的暂停面板——多指本就是噪声，
     * 让高亮挂在一根已经抬起的手指上，比被抢走难看得多。
     */
    public void pressDown(int resolved, int pointerId) {
        this.index = resolved;
        this.pointerId = resolved == NONE ? NONE : pointerId;
    }

    /**
     * 拖动：解析结果换了别人才松开，换不动就继续按着。
     * 只有武装那根手指能改状态——别的指头在面板上乱划，不该把高亮从正被按住的按钮上抢走。
     */
    public void dragTo(int resolved, int pointerId) {
        if (index == NONE || pointerId != this.pointerId) return;
        if (resolved != index) {
            index = NONE;
            this.pointerId = NONE;
        }
    }

    /**
     * 抬起。只有**武装那根指头**抬起才动状态：别人的手指收起来，不该把这一枚的高亮也带走
     * （他还按着呢）。武装指抬起时无论提不提交都清干净——留着它就等于屏幕上挂一枚永远按着的按钮。
     *
     * @param resolved 抬起点用调用方那套解析算出的目标下标（系统发 ACTION_CANCEL 时是 {@link #NONE}）
     */
    public int releaseTo(int resolved, int pointerId) {
        if (index == NONE || pointerId != this.pointerId) return NONE;
        int armed = index;
        clear();
        return resolved == armed ? armed : NONE;
    }
}
