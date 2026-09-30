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

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

/**
 * 中文 UI 文本预烘焙（规格 §三"字体分两套"）。
 *
 * <p>两套的分工：**ASCII 走自绘点阵 {@link BitmapFont}，汉字走这里**。汉字在 8px 的格子上
 * 无论如何都不可读（笔画数摆在那儿），所以它必须由真正的字体引擎来画；但每帧 measureText +
 * drawText 是纯浪费，所以**烘焙成离屏 Bitmap**，运行期只做一次 drawBitmap，key = 内容 + 字号 + 颜色。
 *
 * <p>2026-09-24 起这里画的是**内嵌的 Fusion Pixel 12px Mono 子集**（{@link #ASSET}）：像素中文字体，
 * 原生网格 12px。于是三件事一起变了口径——
 * <ul>
 *   <li>字号只准取 12 的整数倍（12 / 24）。非整数倍等于把像素网格重新糊掉，那条决策就白做了；</li>
 *   <li><b>烘焙时关抗锯齿</b>。贴图随后被整数倍放大（本机 1 逻辑像素 = 3 物理像素），
 *       AA 烘进去的边毛会跟着放大三倍。像素三禁管的是精灵重采样，这里同理：网格对齐靠最近邻，不靠 AA；</li>
 *   <li>像素三禁的边界照旧——战场与 HUD 精灵仍不许 AA。</li>
 * </ul>
 *
 * <p>探测时逐项比对 text/size/color，不靠哈希相等判定：hashCode 撞车的后果是"菜单标题画成
 * 上一局的分数"，比崩更难查。
 */
public final class TextCache {

    /** 内嵌字体的资产名。子集化的词表覆盖由 {@code EmbeddedFontTest} 钉住。 */
    public static final String ASSET = "fonts/pr-cjk-12px.otf";

    /** 内嵌像素中文的原生网格（逻辑 px）。字号只能是它的整数倍。 */
    public static final int GRID_PX = 12;

    private static final int CAP = 512;          // 2 的幂
    /** 槽位状态。线性探测删中间一格必须留墓碑，见 {@link #DEAD}。 */
    private static final byte EMPTY = 0, LIVE = 1, DEAD = 2;
    private final byte[] slot = new byte[CAP];
    private final String[] texts = new String[CAP];
    private final int[] sizes = new int[CAP];
    private final int[] colors = new int[CAP];
    private final Bitmap[] bitmaps = new Bitmap[CAP];
    private final int[] baselines = new int[CAP];
    private final int[] stamps = new int[CAP];
    private int clock;
    private int size;

    private static final Paint BAKE = new Paint();
    static {
        // 默认（未装字体 = 系统字的兜底态）才开 AA：8px 系统汉字不开 AA 根本读不出来。
        BAKE.setAntiAlias(true);
    }

    /**
     * 装上内嵌像素中文。只有启动时一次，走 plat 注入（渲染线程拿不到 Context，规格 §八 的边界）。
     *
     * <p>装上之后关掉 AA 与亚像素定位：贴图要按整数倍放大，任何亚像素偏移都会在放大后变成
     * 一格宽窄不均的糊边。
     *
     * @return false = 资产读不出来，继续用系统字（画面会变样，但不该因此起不来）
     */
    public static boolean installTypeface(AssetManager assets) {
        try {
            Typeface face = Typeface.createFromAsset(assets, ASSET);
            BAKE.setTypeface(face);
            BAKE.setAntiAlias(false);
            BAKE.setSubpixelText(false);
            BAKE.setLinearText(false);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 字号向 12 的整数倍靠：像素网格之外的尺寸画不得，宁可改读数也不让它糊。 */
    public static int snapToGrid(int px) {
        int steps = Math.max(1, Math.round((float) px / GRID_PX));
        return steps * GRID_PX;
    }

    private static final Paint.FontMetrics FFM = new Paint.FontMetrics();

    private long hits, misses, evictions;

    public TextCache() { }

    public static int keyOf(String text, int px, int color) {
        int h = 0x811C9DC5;
        for (int i = 0; i < text.length(); i++) {
            h ^= text.charAt(i);
            h *= 0x01000193;
        }
        h ^= px * 0x9E37;
        h ^= color;
        return h & (CAP - 1);
    }

    /**
     * 找到这条文本该待的槽：已烘的返回它自己，否则返回一个可写槽（优先复占墓碑）。
     *
     * <p>搜索**不许停在墓碑上**：撞过车的条目是顺着墓碑后面排的，把它当作空位就等于"这条没烘过"
     * ——后果不是画错字，而是每次命中都白烘一份，旧那份还占着内存再也查不到。
     */
    private int slotFor(String text, int px, int color) {
        int i = keyOf(text, px, color);
        int firstDead = -1;
        for (int n = 0; n < CAP && slot[i] != EMPTY; n++) {
            if (slot[i] == LIVE) {
                if (sizes[i] == px && colors[i] == color && texts[i].equals(text)) return i;
            } else if (firstDead < 0) {
                firstDead = i;
            }
            i = (i + 1) & (CAP - 1);
        }
        // 走满一圈还没遇到 EMPTY 只可能是"活 + 墓碑"铺满整表，那 firstDead 必然有值
        // （装活条目就有 128 格的逐出余量在前头挡着）。
        return firstDead >= 0 ? firstDead : i;
    }

    /** 取（或烘焙）一条文本。返回的 Bitmap 顶部即基线上方起点，drawBitmap 到 (x, y) 就是文字顶边。 */
    public Bitmap bake(String text, int px, int color) {
        px = snapToGrid(px);        // 像素网格之外不落笔：键值也用吸附后的尺寸，免得同串字烘两份
        int i = slotFor(text, px, color);
        if (slot[i] == LIVE) {
            hits++;
            stamps[i] = ++clock;
            return bitmaps[i];
        }
        misses++;
        if (size >= CAP - (CAP >> 2)) evictOne();
        Bitmap b = render(text, px, color);
        if (slot[i] == EMPTY) size++;      // 复占墓碑不增条目数：那一格本来就没算在 size 里
        slot[i] = LIVE;
        texts[i] = text;
        sizes[i] = px;
        colors[i] = color;
        bitmaps[i] = b;
        baselines[i] = ascentOf(px);
        stamps[i] = ++clock;
        return b;
    }

    private static int ascentOf(int px) {
        BAKE.setTextSize(px);
        Paint.FontMetrics fm = FFM;
        BAKE.getFontMetrics(fm);
        return Math.round(-fm.ascent);
    }

    private Bitmap render(String text, int px, int color) {
        BAKE.setTextSize(px);
        BAKE.setColor(Color.argb(255, Color.red(color), Color.green(color), Color.blue(color)));
        Paint.FontMetrics fm = FFM;
        BAKE.getFontMetrics(fm);
        int w = Math.max(1, Math.round(BAKE.measureText(text)));
        int ascent = Math.round(-fm.ascent);
        int descent = Math.round(fm.descent);
        int h = Math.max(1, ascent + descent);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawText(text, 0f, ascent, BAKE);
        return b;
    }

    /** 顶边对齐绘制（与 BitmapFont 的 y 语义一致：y 是文字顶，不是基线）。 */
    public void draw(Canvas c, String text, int x, int y, int px, int color, Paint p) {
        if (text == null || text.isEmpty()) return;
        Bitmap b = bake(text, px, color);
        SpriteFactory.draw(c, b, x, y, b.getWidth(), b.getHeight(), p);
    }

    /** 以 (cx, y) 为水平中心（规格 §八：并列按钮一律按指定中心，不按屏幕居中）。 */
    public void drawCentered(Canvas c, String text, int cx, int y, int px, int color, Paint p) {
        if (text == null || text.isEmpty()) return;
        Bitmap b = bake(text, px, color);
        SpriteFactory.draw(c, b, cx - b.getWidth() / 2, y, b.getWidth(), b.getHeight(), p);
    }

    /** 文本行高，布局用（烘焙一次即缓存，不每帧 measureText）。 */
    public int lineHeight(int px) {
        Bitmap b = bake("国", px, Color.WHITE);
        return b.getHeight();
    }

    private void evictOne() {
        int oldest = -1, oldStamp = Integer.MAX_VALUE;
        for (int j = 0; j < CAP; j++) {
            if (slot[j] == LIVE && stamps[j] < oldStamp) {
                oldStamp = stamps[j];
                oldest = j;
            }
        }
        if (oldest < 0) return;
        bitmaps[oldest].recycle();
        bitmaps[oldest] = null;
        texts[oldest] = null;
        slot[oldest] = DEAD;        // 墓碑，不是空位：见 slotFor
        size--;
        evictions++;
    }

    public int clear() {
        int n = 0;
        for (int i = 0; i < CAP; i++) {
            if (slot[i] == LIVE) {
                bitmaps[i].recycle();
                n++;
            }
            slot[i] = EMPTY;        // 墓碑一并抹平：清完是一张干净的新表
            bitmaps[i] = null;
            texts[i] = null;
        }
        size = 0;
        return n;
    }

    public int size() { return size; }
    public long hits() { return hits; }
    public long misses() { return misses; }
    public long evictions() { return evictions; }
}
