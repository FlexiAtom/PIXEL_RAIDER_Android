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
package com.flexiatom.pixelraider.core;

/**
 * 自制的不可依赖 Android 运行时的整型矩形。
 *
 * 存在的唯一理由：规格 §九.20 要求「布局与命中矩形写成纯函数，用 JVM 单测直接验证（不依赖
 * Robolectric）」，而 android.graphics.Rect 在纯 JVM 单测里是抛 "not mocked" 的桩。
 * 绘制端在进入 Canvas 前才用 Widgets.toAndroid 复制到复用的 android.graphics.Rect。
 */
public final class RectI {

    public int left, top, right, bottom;

    public RectI() { }

    public RectI(int l, int t, int r, int b) {
        set(l, t, r, b);
    }

    public void set(int l, int t, int r, int b) {
        left = l;
        top = t;
        right = r;
        bottom = b;
    }

    public void copyFrom(RectI o) {
        set(o.left, o.top, o.right, o.bottom);
    }

    public int width() {
        return right - left;
    }

    public int height() {
        return bottom - top;
    }

    public int centerX() {
        return (left + right) / 2;
    }

    public int centerY() {
        return (top + bottom) / 2;
    }

    public boolean contains(int x, int y) {
        return x >= left && x < right && y >= top && y < bottom;
    }

    public boolean sameAs(RectI o) {
        return o != null && left == o.left && top == o.top && right == o.right && bottom == o.bottom;
    }

    /** 整块平移。布局用相对坐标、命中用屏幕坐标时，只有这一处需要换算。 */
    public void offsetInPlace(int dx, int dy) {
        left += dx;
        right += dx;
        top += dy;
        bottom += dy;
    }

    /** 以自身为中心外扩到至少 minSize 见方（绘制尺寸不变，只把命中框撑大）。 */
    public RectI grownToAtLeast(int minSize, RectI out) {
        int w = right - left;
        int h = bottom - top;
        int dw = Math.max(0, minSize - w);
        int dh = Math.max(0, minSize - h);
        int dl = dw / 2;
        int dt = dh / 2;
        out.set(left - dl, top - dt, right + (dw - dl), bottom + (dh - dt));
        return out;
    }

    @Override
    public String toString() {
        return "[" + left + "," + top + "," + right + "," + bottom + "]";
    }
}
