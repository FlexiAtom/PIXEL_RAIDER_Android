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
package com.flexiatom.pixelraider.gfx;

/**
 * 界面用色的**唯一出处**（规格 §三 调色）。
 *
 * 这些常数原先长在 {@code Game} 里，结算页要独立成类时才发现它们拿不到——复制一份就是两处真值，
 * 改主题时必然漏改一处。所以全部搬到这里：纯 int，不 import android，JVM 侧也断言得了。
 */
public final class Ink {

    /** 画布底色（战斗区之外）。 */
    public static final int BG_DEEP = 0xFF05070D;
    /** 战斗区底色。 */
    public static final int BATTLE = 0xFF141A24;
    /** 描边 / 分隔线。 */
    public static final int EDGE = 0xFF2A3646;
    /** 次级文字：EDGE 是描边用的，压深底上读得出，当文字用就太暗了。 */
    public static final int DIM = 0xFF5A6C85;
    public static final int TEXT = 0xFF9FE8FF;
    public static final int PROBE = 0xFF00E5FF;
    public static final int WARN = 0xFFFF3B5C;
    public static final int HP = 0xFF6BE8A0;
    public static final int SHIELD = 0xFF4DD2FF;
    /**
     * 越限护盾：商店护盾卡可以加到上限之上，而条子的宽度是钳过 0..1 的（{@code fillWidth}），
     * 130/100 与 100/100 画出来一模一样。溢出段唯一说得出口的表意就是换色——掉回上限那一刻
     * 颜色翻回去，玩家才看得到"刚才那几发是打在多出来的那一截上"。
     */
    public static final int SHIELD_OVER = 0xFFD6FBFF;
    public static final int PICK = 0xFFFF6AD2;
    public static final int OVERLOAD = 0xFFFFD24A;
    /** 模态遮罩：盖住战场但不抹掉它，玩家的"我刚死在哪"靠这 10% 的可见度维持。 */
    public static final int MASK = 0xE605070D;

    /**
     * 两色线性插值（面板"渐变底"用）。
     *
     * <p>不做 Shader 渐变：一块 240 宽的按钮底，分四段量化渐变在像素画面上比一条真渐变
     * 更像这台机器该有的样子，而且少一个运行期对象。alpha 一律走 {@link #MASK} 那种显式浓度。
     */
    public static int mix(int from, int to, float k) {
        float t = Float.isNaN(k) ? 0f : (k < 0f ? 0f : (k > 1f ? 1f : k));
        int r = Math.round(((from >> 16 & 0xFF) * (1f - t)) + ((to >> 16 & 0xFF) * t));
        int g = Math.round(((from >> 8 & 0xFF) * (1f - t)) + ((to >> 8 & 0xFF) * t));
        int b = Math.round(((from & 0xFF) * (1f - t)) + ((to & 0xFF) * t));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private Ink() { }
}
