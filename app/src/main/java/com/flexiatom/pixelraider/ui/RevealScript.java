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

/**
 * 结算页的分阶段揭示表（规格 §四 明文给出的十个时间点）。
 *
 * 这十个数字是**设计决定**而不是实现细节：它们决定"先读分数还是先读等级"，所以只住在这里一处，
 * 由 {@code RevealScriptTest} 逐个钉死。绘制端一律问它"第 n 段现在该露出多少"，不自己比时间。
 *
 * 揭示用 UI 时钟（规格 §五）：死亡那一刻起算的是界面时间，暂停不该把动画冻在半路。
 */
public final class RevealScript {

    public static final int TITLE = 0;
    public static final int DIVIDER = 1;
    public static final int SCORE = 2;
    public static final int RECORD = 3;
    public static final int OVERALL = 4;
    public static final int RADAR = 5;
    public static final int CARDS = 6;
    public static final int ACHIEVEMENTS = 7;
    public static final int COMPARE = 8;
    public static final int BUTTONS = 9;
    public static final int COUNT = 10;

    /** 每段自身入场的推进时长（规格只给"何时开始"，没给"用多久"）。[可调] */
    public static final float IN_SEC = 0.18f;
    /** 数据卡逐行弹入的行间隔（规格 §四："1.9s 数据卡逐行弹入"，逐行要有步长）。[可调] */
    public static final float CARD_ROW_STEP = 0.1f;
    /** 按钮组出现即"防误触窗口"结束：3.2s 之前点了不算。 */
    public static final float BUTTONS_AT = 3.2f;
    /** 数据卡行数（击杀 / 命中率 / 最高连击 / 到达波次 / 存活时长）。加行要同时改行步长。 */
    public static final int CARD_ROWS = 5;
    /** 重开转场时长：0.45s 全屏淡入（规格 §四）。 */
    public static final float RESTART_FADE_SEC = 0.45f;

    private static final float[] START = {
            0.55f,   // TITLE       标题砸入
            0.90f,   // DIVIDER     分隔线
            1.05f,   // SCORE       分数
            1.45f,   // RECORD      纪录判定
            1.55f,   // OVERALL     综合评级
            1.75f,   // RADAR       五维评级
            1.90f,   // CARDS       数据卡逐行弹入
            2.50f,   // ACHIEVEMENTS 成就进度
            2.70f,   // COMPARE     对比上局
            BUTTONS_AT, // BUTTONS  按钮组
    };

    public static float startAt(int stage) {
        return stage >= 0 && stage < COUNT ? START[stage] : Float.MAX_VALUE;
    }

    /** 该段的整体推进度：0 = 还没轮到，1 = 完全露出。 */
    public static float progressOf(int stage, float t) {
        float start = startAt(stage);
        if (t <= start) return 0f;
        float k = (t - start) / IN_SEC;
        return k >= 1f ? 1f : k;
    }

    /** 数据卡第 row 行的推进度（同一时刻多行不同相，才是"逐行弹入"）。 */
    public static float cardProgressOf(int row, float t) {
        return progressOf(CARDS, t - row * CARD_ROW_STEP);
    }

    /** 数据卡逐行全部露完的时刻：成就段必须排在它之后，否则两段会叠在一起。 */
    public static final float CARDS_DONE_AT = START[CARDS] + (CARD_ROWS - 1) * CARD_ROW_STEP + IN_SEC;

    private RevealScript() { }

    /** 按钮可读但**不可点**的窗口：3.2s 之前点屏幕不算重开（规格 §四 取消"点任意处重开"）。 */
    public static boolean buttonsLive(float t) {
        return t >= BUTTONS_AT;
    }
}
