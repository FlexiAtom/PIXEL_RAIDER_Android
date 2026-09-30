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

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

/**
 * 自绘 5x7 点阵字库（规格 §三字体规则）。
 *
 * 数字/大写字母（FPS、伤害飘字、核心数值）走这里；中文一律走系统字体 + TextCache 预烘焙。
 * 理由：系统字体是矢量的，6px 小字糊成一团，且每帧 measureText 是纯浪费——这里每字符宽度是
 * 常量 CELL_W，整串宽度用算术算出，一次 measureText 都不做。
 *
 * 字模按颜色各缓存一份（ARGB_8888 图集），运行期只 drawBitmap，不 setColorFilter。
 */
public final class BitmapFont {

    public static final int GLYPH_W = 5;
    public static final int GLYPH_H = 7;
    public static final int CELL_W = 6;  // 5 + 1 空格
    public static final int CELL_H = 8;  // 7 + 1 行距

    private static final int FIRST = 32; // 空格起
    private static final int LAST = 126;
    private static final int COUNT = LAST - FIRST + 1;

    /** 每字符 7 行、每行 5 bit（bit4 = 最左）。未定义的字符渲染为空格。 */
    private static final int[][] GLYPHS = new int[COUNT][];

    static {
        put(' ', 0, 0, 0, 0, 0, 0, 0);
        put('0', 0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110);
        put('1', 0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110);
        put('2', 0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111);
        put('3', 0b11111, 0b00010, 0b00100, 0b00010, 0b00001, 0b10001, 0b01110);
        put('4', 0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010);
        put('5', 0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110);
        put('6', 0b00110, 0b01000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110);
        put('7', 0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000);
        put('8', 0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110);
        put('9', 0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00010, 0b01100);
        put('A', 0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001);
        put('B', 0b11110, 0b10001, 0b10001, 0b11110, 0b10001, 0b10001, 0b11110);
        put('C', 0b01110, 0b10001, 0b10000, 0b10000, 0b10000, 0b10001, 0b01110);
        put('D', 0b11100, 0b10010, 0b10001, 0b10001, 0b10001, 0b10010, 0b11100);
        put('E', 0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111);
        put('F', 0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b10000);
        put('G', 0b01110, 0b10001, 0b10000, 0b10111, 0b10001, 0b10001, 0b01111);
        put('H', 0b10001, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001);
        put('I', 0b01110, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110);
        put('J', 0b00111, 0b00010, 0b00010, 0b00010, 0b00010, 0b10010, 0b01100);
        put('K', 0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001);
        put('L', 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b11111);
        put('M', 0b10001, 0b11011, 0b10101, 0b10101, 0b10001, 0b10001, 0b10001);
        put('N', 0b10001, 0b11001, 0b10101, 0b10011, 0b10001, 0b10001, 0b10001);
        put('O', 0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110);
        put('P', 0b11110, 0b10001, 0b10001, 0b11110, 0b10000, 0b10000, 0b10000);
        put('Q', 0b01110, 0b10001, 0b10001, 0b10001, 0b10101, 0b10010, 0b01101);
        put('R', 0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001);
        put('S', 0b01111, 0b10000, 0b10000, 0b01110, 0b00001, 0b00001, 0b11110);
        put('T', 0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100);
        put('U', 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110);
        put('V', 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100);
        put('W', 0b10001, 0b10001, 0b10001, 0b10101, 0b10101, 0b11011, 0b10001);
        put('X', 0b10001, 0b10001, 0b01010, 0b00100, 0b01010, 0b10001, 0b10001);
        put('Y', 0b10001, 0b10001, 0b01010, 0b00100, 0b00100, 0b00100, 0b00100);
        put('Z', 0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b10000, 0b11111);
        put('-', 0, 0, 0, 0b11111, 0, 0, 0);
        put('.', 0, 0, 0, 0, 0, 0, 0b00100);
        put(',', 0, 0, 0, 0, 0, 0b00100, 0b01000);
        put(':', 0, 0b00100, 0, 0, 0, 0b00100, 0);
        put('/', 0b00001, 0b00010, 0b00010, 0b00100, 0b01000, 0b01000, 0b10000);
        put('+', 0, 0, 0b00100, 0b11111, 0b00100, 0, 0);
        put('=', 0, 0, 0b11111, 0, 0b11111, 0, 0);
        put('%', 0b11001, 0b11010, 0b00010, 0b00100, 0b01000, 0b01011, 0b10011);
        put('*', 0, 0b00100, 0b10101, 0b01110, 0b10101, 0b00100, 0);
        put('!', 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0, 0b00100);
        put('?', 0b01110, 0b10001, 0b00001, 0b00110, 0b00100, 0, 0b00100);
        put('<', 0b00010, 0b00100, 0b01000, 0b10000, 0b01000, 0b00100, 0b00010);
        put('>', 0b01000, 0b00100, 0b00010, 0b00001, 0b00010, 0b00100, 0b01000);
        put('(', 0b00010, 0b00100, 0b01000, 0b01000, 0b01000, 0b00100, 0b00010);
        put(')', 0b01000, 0b00100, 0b00010, 0b00010, 0b00010, 0b00100, 0b01000);
        put('[', 0b01110, 0b01000, 0b01000, 0b01000, 0b01000, 0b01000, 0b01110);
        put(']', 0b01110, 0b00010, 0b00010, 0b00010, 0b00010, 0b00010, 0b01110);
        put('#', 0b01010, 0b11111, 0b01010, 0b01010, 0b11111, 0b01010, 0);
        put('x', 0, 0, 0b10001, 0b01010, 0b00100, 0b01010, 0b10001);
        // 小写 v：商店角标「Lv 3」需要它。缺模的字符**不画但照常占宽**（绘制与测宽两侧各自
        // 把 GLYPHS[...] == null 兜底成空格），所以漏这个字不会崩、也不会报错——
        // 只会把角标画成「L 3」，一种肉眼在真机前看不出来的错。
        // x 高度与 'x' 对齐（第 3..7 行），两个小写并排时才不像一个大一个小。
        put('v', 0, 0, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100);
    }

    private static void put(int ch, int r0, int r1, int r2, int r3, int r4, int r5, int r6) {
        GLYPHS[ch - FIRST] = new int[] { r0, r1, r2, r3, r4, r5, r6 };
    }

    /** 少量颜色各烘焙一张图集；不建 Map，定长数组线性找（条目 <= 8）。 */
    private static final int MAX_ATLASES = 8;
    private final int[] atlasColors = new int[MAX_ATLASES];
    private final Bitmap[] atlases = new Bitmap[MAX_ATLASES];
    private final Rect src = new Rect();
    private final Rect dst = new Rect();

    public BitmapFont() { }

    /**
     * 图集按**颜色**取，alpha 位一律抹平：淡入用的 {@code paint.setAlpha()} 由 drawBitmap 生效，
     * 不该参与选图集。否则每变一次 alpha 就新造一张图集，MAX_ATLASES 用尽后还会退回错色的那张。
     */
    private Bitmap atlasFor(int color) {
        color |= 0xFF000000;
        for (int i = 0; i < MAX_ATLASES; i++) {
            if (atlases[i] != null && atlasColors[i] == color) return atlases[i];
        }
        Bitmap b = buildAtlas(color);
        for (int i = 0; i < MAX_ATLASES; i++) {
            if (atlases[i] == null) {
                atlases[i] = b;
                atlasColors[i] = color;
                return b;
            }
        }
        b.recycle();
        return atlases[0];
    }

    private static Bitmap buildAtlas(int color) {
        Bitmap b = Bitmap.createBitmap(COUNT * CELL_W, CELL_H, Bitmap.Config.ARGB_8888);
        int opaque = color | 0xFF000000;
        int transparent = color & 0x00FFFFFF;
        int[] px = new int[GLYPH_W];
        for (int g = 0; g < COUNT; g++) {
            int[] rows = GLYPHS[g];
            int ox = g * CELL_W;
            for (int y = 0; y < GLYPH_H; y++) {
                int bits = rows == null ? 0 : rows[y];
                for (int x = 0; x < GLYPH_W; x++) {
                    px[x] = ((bits >> (GLYPH_W - 1 - x)) & 1) == 1 ? opaque : transparent;
                }
                b.setPixels(px, 0, GLYPH_W, ox, y, GLYPH_W, 1);
            }
        }
        return b;
    }

    /** 整串宽度纯算术得出：不做一次 measureText。 */
    public static int textWidth(String s) {
        return s == null ? 0 : s.length() * CELL_W - 1;
    }

    /**
     * 逐字 drawBitmap，四参重载（src/dst 显式），不依赖精灵原始尺寸。
     * @param y 文字顶边（逻辑像素）
     */
    public void draw(Canvas c, String s, int x, int y, Paint p) {
        if (s == null || s.isEmpty()) return;
        Bitmap atlas = atlasFor(p.getColor());
        int cx = x;
        for (int i = 0; i < s.length(); i++) {
            int ch = s.charAt(i);
            if (ch < FIRST || ch > LAST || GLYPHS[ch - FIRST] == null) {
                cx += CELL_W;
                continue;
            }
            int gx = (ch - FIRST) * CELL_W;
            src.set(gx, 0, gx + GLYPH_W, GLYPH_H);
            dst.set(cx, y, cx + GLYPH_W, y + GLYPH_H);
            c.drawBitmap(atlas, src, dst, p);
            cx += CELL_W;
        }
    }

    /** 以 (cx, y) 为水平中心绘制（规格 §八：并列按钮一律按指定中心，不按屏幕居中）。 */
    public void drawCentered(Canvas c, String s, int cx, int y, Paint p) {
        draw(c, s, cx - textWidth(s) / 2, y, p);
    }

    // ---- char[] 通道 -------------------------------------------------------------------------
    // HUD 每帧都要重排数字。用 String 拼接的话，每帧一个 StringBuilder + 一个 String，
    // 直接违反规格 §六 的"每帧零分配"。所以格式化写进调用方复用的 char[]，这里只读不造。

    public static int textWidth(char[] buf, int len) {
        return len <= 0 ? 0 : len * CELL_W - 1;
    }

    /** 逐字 drawBitmap，四参重载；buf 里超出点阵范围的字符按空格走位（不画，但占宽）。 */
    public void draw(Canvas c, char[] buf, int len, int x, int y, Paint p) {
        if (buf == null || len <= 0) return;
        if (len > buf.length) len = buf.length;
        Bitmap atlas = atlasFor(p.getColor());
        int cx = x;
        for (int i = 0; i < len; i++) {
            int ch = buf[i];
            if (ch < FIRST || ch > LAST || GLYPHS[ch - FIRST] == null) {
                cx += CELL_W;
                continue;
            }
            int gx = (ch - FIRST) * CELL_W;
            src.set(gx, 0, gx + GLYPH_W, GLYPH_H);
            dst.set(cx, y, cx + GLYPH_W, y + GLYPH_H);
            c.drawBitmap(atlas, src, dst, p);
            cx += CELL_W;
        }
    }

    public void drawCentered(Canvas c, char[] buf, int len, int cx, int y, Paint p) {
        draw(c, buf, len, cx - textWidth(buf, len) / 2, y, p);
    }

    /** 放大后的串宽：只有整数倍合法（规格 §三：非整数倍会让像素宽窄不均）。 */
    public static int textWidth(char[] buf, int len, int scale) {
        int w = textWidth(buf, len);
        return w <= 0 ? 0 : w * scale;
    }

    /**
     * 整数倍放大的数字（结算页的分数与等级要压住 HUD 的小读数，同一套点阵只放大不重画）。
     *
     * 放大必须配 {@code FILTER_BITMAP=false} 的 Paint，否则双线性插值会把硬边像素糊成渐变。
     */
    public void drawScaled(Canvas c, char[] buf, int len, int x, int y, int scale, Paint p) {
        if (buf == null || len <= 0 || scale <= 1) {
            draw(c, buf, len, x, y, p);
            return;
        }
        if (len > buf.length) len = buf.length;
        Bitmap atlas = atlasFor(p.getColor());
        int cx = x;
        for (int i = 0; i < len; i++) {
            int ch = buf[i];
            if (ch < FIRST || ch > LAST || GLYPHS[ch - FIRST] == null) {
                cx += CELL_W * scale;
                continue;
            }
            int gx = (ch - FIRST) * CELL_W;
            src.set(gx, 0, gx + GLYPH_W, GLYPH_H);
            dst.set(cx, y, cx + GLYPH_W * scale, y + GLYPH_H * scale);
            c.drawBitmap(atlas, src, dst, p);
            cx += CELL_W * scale;
        }
    }

    public void drawScaledCentered(Canvas c, char[] buf, int len, int cx, int y, int scale, Paint p) {
        drawScaled(c, buf, len, cx - textWidth(buf, len, scale) / 2, y, scale, p);
    }

    /** 右对齐到 rightEdge（规格 §四 行1：数值整行右对齐到 208，避开暂停按钮）。 */
    public void drawRight(Canvas c, char[] buf, int len, int rightEdge, int y, Paint p) {
        draw(c, buf, len, rightEdge - textWidth(buf, len), y, p);
    }

    public void recycle() {
        for (int i = 0; i < MAX_ATLASES; i++) {
            if (atlases[i] != null) {
                atlases[i].recycle();
                atlases[i] = null;
            }
        }
    }
}
