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
 * 精灵工厂（规格 §三）：字符网格 + 调色板 → Bitmap，全部在启动阶段完成。
 *
 * 两条来自踩坑的规则写死在这里：
 * 1. **绘制必须走 drawBitmap(bmp, srcRect, dstRect, paint) 四参重载**。只传 x,y 的三参版按
 *    精灵原始尺寸画，8px 精灵放进 24px 格子只占中心一小块——这不是"偏小"，是布局错误。
 * 2. **像素 Paint 一次建好共用**：不开 ANTI_ALIAS、不开 FILTER_BITMAP_FLAG、setDither(false)。
 *    任何一处借用别人的带 AA 的 Paint，像素边就糊了，且糊不糊在高分屏上肉眼难辨、截图对比才发现。
 *
 * 静态图标一律走离屏缓存，key 含目标尺寸（非整数倍缩放会让像素宽窄不均）。
 */
public final class SpriteFactory {

    /** 唯一的像素绘制 Paint。渲染线程内不得 new Paint。 */
    public static final Paint PIXEL = new Paint();
    /** 半透明叠加用（发光、拖尾）：仍然不抗锯齿，只放开 alpha 混合。 */
    public static final Paint GLOW = new Paint();

    static {
        PIXEL.setAntiAlias(false);
        PIXEL.setFilterBitmap(false);
        PIXEL.setDither(false);
        PIXEL.setAlpha(255);
        GLOW.setAntiAlias(false);
        GLOW.setFilterBitmap(false);
        GLOW.setDither(false);
    }

    private static final Rect SRC = new Rect();
    private static final Rect DST = new Rect();

    /** id|colorTag|w|h → 已烘焙 Bitmap。图标数量有限，256 桶足够。 */
    private static final OffscreenCache ICONS = new OffscreenCache(0, 256);

    private SpriteFactory() { }

    public static Bitmap makeSprite(String[] rows, int[] palette) {
        int w = SpriteGrid.widthOf(rows);
        int h = SpriteGrid.heightOf(rows);
        return Bitmap.createBitmap(SpriteGrid.toPixels(rows, palette), w, h, Bitmap.Config.ARGB_8888);
    }

    /** 生成并按最近邻缩放到 dw×dh；缩放结果同样进缓存（key 含目标尺寸）。 */
    public static Bitmap makeSpriteScaled(String[] rows, int[] palette, int dw, int dh) {
        int[] px = SpriteGrid.toPixels(rows, palette);
        int[] scaled = SpriteGrid.scaleNearest(px, SpriteGrid.widthOf(rows), rows.length, dw, dh);
        return Bitmap.createBitmap(scaled, dw, dh, Bitmap.Config.ARGB_8888);
    }

    /**
     * 取（或烘焙）一枚静态图标。
     * @param spriteId 网格编号（SpriteSheets 里的常量）
     * @param baseColor 主色，5 档色阶由它派生
     */
    public static Bitmap icon(final String[] rows, final int spriteId, final int baseColor, final int w, final int h) {
        // 5 档调色板只在**未命中**时才算：命中路径上 new int[5] 就是帧内分配
        long key = OffscreenCache.keyOf(spriteId, w, h, ColorTags.tagOf(baseColor));
        return ICONS.getOrCreate(key, w, h, (bw, bh) -> {
            int[] palette = new int[5];
            Palette.iconPalette(baseColor, palette);
            int[] px = SpriteGrid.toPixels(rows, palette);
            int[] scaled = SpriteGrid.scaleNearest(px, SpriteGrid.widthOf(rows), rows.length, bw, bh);
            return Bitmap.createBitmap(scaled, bw, bh, Bitmap.Config.ARGB_8888);
        });
    }

    /** 四参绘制：src 整图 → dst 指定矩形。复用 static Rect，帧内零分配。 */
    public static void draw(Canvas c, Bitmap b, int dx, int dy, int dw, int dh, Paint p) {
        if (b == null || b.isRecycled() || dw <= 0 || dh <= 0) return;
        SRC.set(0, 0, b.getWidth(), b.getHeight());
        DST.set(dx, dy, dx + dw, dy + dh);
        c.drawBitmap(b, SRC, DST, p);
    }

    /** 从图集里取一块画到目标矩形（BitmapFont / GlowAtlas 都走这里）。 */
    public static void drawRegion(Canvas c, Bitmap b, int sx, int sy, int sw, int sh,
                                  int dx, int dy, int dw, int dh, Paint p) {
        if (b == null || b.isRecycled() || dw <= 0 || dh <= 0) return;
        SRC.set(sx, sy, sx + sw, sy + sh);
        DST.set(dx, dy, dx + dw, dy + dh);
        c.drawBitmap(b, SRC, DST, p);
    }

    /** 同尺寸快路径：仍走四参，不落到三参重载。 */
    public static void drawAt(Canvas c, Bitmap b, int dx, int dy, Paint p) {
        if (b == null || b.isRecycled()) return;
        draw(c, b, dx, dy, b.getWidth(), b.getHeight(), p);
    }

    public static int iconCacheSize() { return ICONS.size(); }
}
