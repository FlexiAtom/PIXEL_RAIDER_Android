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
package com.flexiatom.pixelraider.gfx;

/**
 * 字符网格 → 像素缓冲的纯计算核（规格 §三：所有精灵用字符网格 + 调色板生成，不是手绘位图）。
 *
 * 与 Bitmap 解耦是刻意的：android.graphics.Bitmap 在纯 JVM 单测里抛 "not mocked"，而这里
 * 每格取整/调色板映射/缩放规则恰恰是最容易画错又最难靠肉眼看出的部分。SpriteFactory 只负责
 * 把本类算出的 int[] 塞进 Bitmap。
 *
 * 缩放一律最近邻（scaleNearest），不做双线性——像素风一旦被插值就糊。
 */
public final class SpriteGrid {

    /** 透明格。ARGB 全 0，比 0xFF000000 更适合直接当 int[] 里的空洞。 */
    public static final int TRANSPARENT = 0;

    private SpriteGrid() { }

    public static int widthOf(String[] rows) {
        int w = 0;
        for (String r : rows) if (r.length() > w) w = r.length();
        return w;
    }

    public static int heightOf(String[] rows) {
        return rows.length;
    }

    /**
     * 网格字符 → 调色板下标。'.'/空格 = 透明；0-9 与 a-f 走十六进制下标；其余按大写十六进制处理。
     * 越界返回透明而不是抛异常：美术网格改色调时palette 变短是常事，崩在渲染中途不值。
     */
    public static int colorAt(char ch, int[] palette) {
        if (ch == '.' || ch == ' ') return TRANSPARENT;
        int idx;
        if (ch >= '0' && ch <= '9') idx = ch - '0';
        else if (ch >= 'a' && ch <= 'z') idx = ch - 'a' + 10;
        else if (ch >= 'A' && ch <= 'Z') idx = ch - 'A' + 10;
        else return TRANSPARENT;
        return idx < palette.length ? palette[idx] : TRANSPARENT;
    }

    /** rows.length 行、每行 widthOf(rows) 列，短行右侧补透明。 */
    public static int[] toPixels(String[] rows, int[] palette) {
        int w = widthOf(rows), h = rows.length;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            String row = rows[y];
            int base = y * w;
            for (int x = 0; x < w; x++) {
                char ch = x < row.length() ? row.charAt(x) : '.';
                px[base + x] = colorAt(ch, palette);
            }
        }
        return px;
    }

    /** 最近邻放大/缩小；dw/dh 与源尺寸成整数倍时像素正好等宽，非整数倍时宽窄不均是预期行为。 */
    public static int[] scaleNearest(int[] src, int sw, int sh, int dw, int dh) {
        int[] out = new int[dw * dh];
        for (int y = 0; y < dh; y++) {
            int sy = (int) ((long) y * sh / dh);
            if (sy >= sh) sy = sh - 1;
            int srow = sy * sw;
            int drow = y * dw;
            for (int x = 0; x < dw; x++) {
                int sx = (int) ((long) x * sw / dw);
                if (sx >= sw) sx = sw - 1;
                out[drow + x] = src[srow + sx];
            }
        }
        return out;
    }

    // ---- drawPixelGrid 的取整规则 ----------------------------------------------------
    // 每格 Math.ceil：取整（int 截断）会在最后一格右侧留下缝隙——总推进量 cells*floor(size/cells)
    // 小于 size；ceil 让相邻格重叠，代价是重叠像素，但缝隙是没有的。

    public static int cellSize(int totalPx, int cells) {
        return (int) Math.ceil((double) totalPx / cells);
    }

    public static int cellAdvance(int totalPx, int cells) {
        return totalPx / cells;
    }

    /** 第 i 格覆盖的左边界（含）。 */
    public static int cellLeft(int totalPx, int cells, int i) {
        return (int) Math.floor((double) i * totalPx / cells);
    }

    /** 第 i 格覆盖的右边界（不含）；ceil 保证最后一格顶到 totalPx。 */
    public static int cellRight(int totalPx, int cells, int i) {
        int r = (int) Math.ceil((double) (i + 1) * totalPx / cells);
        return r > totalPx ? totalPx : r;
    }

    /**
     * 把 cellsX×cellsY 的色块网格铺满 outW×outH，按 ceil 覆盖，返回 ARGB 缓冲。
     * 透明格不覆盖已有内容（保留底板）。
     */
    public static int[] renderGrid(int[] cellColors, int cellsX, int cellsY, int outW, int outH) {
        int[] px = new int[outW * outH];
        for (int cy = 0; cy < cellsY; cy++) {
            int y0 = cellLeft(outH, cellsY, cy), y1 = cellRight(outH, cellsY, cy);
            for (int cx = 0; cx < cellsX; cx++) {
                int col = cellColors[cy * cellsX + cx];
                if (col == TRANSPARENT) continue;
                int x0 = cellLeft(outW, cellsX, cx), x1 = cellRight(outW, cellsX, cx);
                for (int y = y0; y < y1; y++) {
                    int base = y * outW;
                    for (int x = x0; x < x1; x++) px[base + x] = col;
                }
            }
        }
        return px;
    }

    /** 不透明度混合：把 color 以 a（0..255）叠到 dst 上，src-over。dst 须是不透明底。 */
    public static int blend(int dst, int color, int a) {
        if (a <= 0) return dst;
        int sa = a > 255 ? 255 : a;
        int dr = (dst >> 16) & 0xFF, dg = (dst >> 8) & 0xFF, db = dst & 0xFF;
        int sr = (color >> 16) & 0xFF, sg = (color >> 8) & 0xFF, sb = color & 0xFF;
        int r = dr + ((sr - dr) * sa >> 8);
        int g = dg + ((sg - dg) * sa >> 8);
        int b = db + ((sb - db) * sa >> 8);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * 在**透明底**上落一个半透明像素（星场、尘埃带这类"往空图集里点色"的场景）。
     * 不能用 blend：blend 输出恒定 alpha=255，画到透明图集上会变成实心不透明方块。
     */
    public static int withAlpha(int color, int a) {
        if (a <= 0) return TRANSPARENT;
        return (Math.min(255, a) << 24) | (color & 0x00FFFFFF);
    }

    /** LIGHTEN 混合：逐通道取大（星云双图滚动用，规格 §三第 2 层）。 */
    public static int lighten(int a, int b) {
        int aa = Math.max((a >>> 24), (b >>> 24));
        int r = Math.max((a >> 16) & 0xFF, (b >> 16) & 0xFF);
        int g = Math.max((a >> 8) & 0xFF, (b >> 8) & 0xFF);
        int bl = Math.max(a & 0xFF, b & 0xFF);
        return (aa << 24) | (r << 16) | (g << 8) | bl;
    }

    /** 平滑衰减 0..1：x 从 0（中心）到 1（边缘），power 越大边缘收得越快。 */
    public static float falloff(float x, float power) {
        if (x <= 0f) return 1f;
        if (x >= 1f) return 0f;
        return (float) Math.pow(1f - x, power);
    }
}
