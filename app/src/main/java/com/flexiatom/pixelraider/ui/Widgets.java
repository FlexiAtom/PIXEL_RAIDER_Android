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

import com.flexiatom.pixelraider.core.RectI;
import com.flexiatom.pixelraider.plat.Screen;

/**
 * 布局与命中的纯函数集合（规格 §九.20：这部分必须能在 JVM 单测里直接验，不靠 Robolectric，
 * 也不靠"重算公式"自证——所以一切几何都在这里算完并写回，绘制端与命中端读同一份矩形）。
 */
public final class Widgets {

    private Widgets() { }

    /**
     * 触控最小尺寸的逻辑像素下限（规格 §二：暂停按钮画 18x18，命中区外扩到 34x34）。
     *
     * @param pxPerLogic 一个逻辑像素对应多少物理像素
     * @param density      屏幕密度（1dp = density px）
     */
    public static int minTouchLogic(float pxPerLogic, float density) {
        return Screen.minTouchLogic(pxPerLogic, density);
    }

    /** 并排等宽等高两枚按钮，中间留 gap。主次靠样式区分，不靠大小（规格 §四 主菜单动作行）。 */
    public static void equalHalves(int x, int y, int w, int h, int gap, RectI leftOut, RectI rightOut) {
        int each = (w - gap) / 2;
        leftOut.set(x, y, x + each, y + h);
        rightOut.set(x + w - each, y, x + w, y + h);
    }

    /** 把绘制框外扩成命中框（不改绘制框自身）。 */
    public static void hitRect(RectI draw, int minTouchLogic, RectI out) {
        draw.grownToAtLeast(minTouchLogic, out);
    }

    /** 校验用：某绘制框在当前机型上是否已达 48dp（未达标就必须外扩命中区）。 */
    public static boolean meetsTouchFloor(RectI draw, int minTouchLogic) {
        return draw.width() >= minTouchLogic && draw.height() >= minTouchLogic;
    }

    /** 网格：cols x rows 均分，返回第 i 个格子的矩形。 */
    public static void gridCell(int x, int y, int w, int h, int cols, int rows, int gap, int i, RectI out) {
        if (cols <= 0 || rows <= 0) {
            out.set(x, y, x, y);
            return;
        }
        int col = i % cols;
        int row = i / cols;
        int cw = (w - gap * (cols - 1)) / cols;
        int ch = (h - gap * (rows - 1)) / rows;
        int lx = x + col * (cw + gap);
        int ty = y + row * (ch + gap);
        // 末列吸收整除余数，避免右侧出现空隙
        int rx = (col == cols - 1) ? x + w : lx + cw;
        int by = (row == rows - 1) ? y + h : ty + ch;
        out.set(lx, ty, rx, by);
    }
}
