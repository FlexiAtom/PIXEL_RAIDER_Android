package com.flexiatom.pixelraider.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import com.flexiatom.pixelraider.core.RectI;
import com.flexiatom.pixelraider.gfx.BitmapFont;
import com.flexiatom.pixelraider.gfx.GlowAtlas;
import com.flexiatom.pixelraider.gfx.SpriteFactory;
import com.flexiatom.pixelraider.gfx.TextCache;

/**
 * 一块界面上共用的落笔原语（结算页、暂停面板、设置页、图鉴页都要这几件事）。
 *
 * 抽出来的理由不是"少写几行"，是**淡入的口径必须只有一处**：
 * <ul>
 *   <li>中文与辉光一律用**固定不透明颜色**烘焙/取色，透明度只走 {@code Paint.setAlpha}。
 *       反过来（烘焙一个半透明颜色的字）会让每帧一次 cache miss 造出真 Bitmap。</li>
 *   <li>点阵字同理——{@code BitmapFont} 的图集 key 已经抹平 alpha 位。</li>
 *   <li>每个方法离开前把自己动过的 alpha 复位到 255，不然下一处绘制会拿着上一处的淡入值画实色块。</li>
 * </ul>
 *
 * 每个界面各持一份实例（各自的 Paint），这样一块面板漏复位 alpha 不会串到另一块面板上。
 */
public final class DrawKit {

    /** 标签与数值之间的那一个像素缝。 */
    public static final int GAP = 2;

    public final BitmapFont font;
    public final TextCache text;
    public final GlowAtlas glow;
    public final Paint fill = new Paint();
    public final Paint stroke = new Paint();
    public final Paint ink = new Paint();
    /**
     * 面板层的形状笔。**这是全工程唯一开抗锯齿的一笔**，理由见 {@link #roundRect}。
     * 战场与 HUD 仍然走 {@link #fill}/{@link #stroke}/{@link #ink}，像素三禁照旧。
     */
    public final Paint shape = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 复用的落笔矩形（规格 §三：不在渲染路径里 new RectF）。 */
    public final RectF rf = new RectF();

    public DrawKit(BitmapFont font, TextCache text, GlowAtlas glow) {
        this.font = font;
        this.text = text;
        this.glow = glow;
        pixel(fill);
        pixel(stroke);
        pixel(ink);
        shape.setStyle(Paint.Style.FILL);
        shape.setFilterBitmap(false);
        shape.setDither(false);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1f);
    }

    /** 像素三禁（规格 §三）：这三条漏一条，自绘界面在放大倍率下就糊成渐变。 */
    private static void pixel(Paint p) {
        p.setAntiAlias(false);
        p.setFilterBitmap(false);
        p.setDither(false);
    }

    /** 铺一层遮罩色（alpha 由调用方按入场进度算好）。 */
    public void rect(Canvas c, int left, int top, int right, int bottom, int color, int alpha) {
        fill.setColor(color);
        fill.setAlpha(alpha);
        rf.set(left, top, right, bottom);
        c.drawRect(rf, fill);
        fill.setAlpha(255);
    }

    public void fillRect(Canvas c, RectI r, int color, int alpha) {
        rect(c, r.left, r.top, r.right, r.bottom, color, alpha);
    }

    public void strokeRect(Canvas c, RectI r, int color, int alpha) {
        stroke.setColor(color);
        stroke.setAlpha(alpha);
        rf.set(r.left, r.top, r.right, r.bottom);
        c.drawRect(rf, stroke);
        stroke.setAlpha(255);
    }

    /**
     * 圆角容器（MD3 的面板/按钮/卡片底）。
     *
     * <p><b>2026-09-24 决策：纯血 MD3，剔掉一切像素装饰。</b>这里原本是"逐行削角"——每行左右各缩
     * {@code cornerInset} 格，用横竖矩形拼出一条阶梯，好让像素三禁在圆角上也成立。真机否掉了它：
     * 一个逻辑像素在这台机上等于 3 个物理像素，r=8 的阶梯被放大成 24px 高的方块拱；更糟的是相邻
     * 卡片之间只有 4 格的缝，两边各往里削 8 格，缝就在卡片底被撑成一个向下的漏斗（用户圈画处）。
     *
     * <p>所以现在直接交抗锯齿的 {@code drawRoundRect}。这不违反三禁——三禁管的是**精灵**（位图被
     * 重采样会糊掉像素网格），面板是矢量形状，它本来就该是圆的。战场与 HUD 仍然用 {@link #fill}
     * 那三支不抗锯齿的笔，一笔都不串。
     *
     * <p>半径夹到短边一半：细长的按钮不会两头削穿。
     */
    public void roundRect(Canvas c, RectI r, int radius, int color, int alpha) {
        roundRect(c, r.left, r.top, r.right, r.bottom, radius, color, alpha);
    }

    /** 与 {@link #rect} 同形的版本：给"要从绘制框内缩一格"的容器用，省掉一个每帧分配的 RectI。 */
    public void roundRect(Canvas c, int left, int top, int right, int bottom,
                          int radius, int color, int alpha) {
        int w = right - left;
        int h = bottom - top;
        if (w <= 0 || h <= 0) return;
        float rad = Math.min(radius, Math.min(w / 2f, h / 2f));
        shape.setColor(color);
        shape.setAlpha(alpha);
        if (rad <= 0f) {
            rf.set(left, top, right, bottom);
            c.drawRect(rf, shape);
        } else {
            rf.set(left, top, right, bottom);
            c.drawRoundRect(rf, rad, rad, shape);
        }
        shape.setAlpha(255);
    }

    /** 状态层：MD3 不换底色，只在容器上叠一层 onState（{@code alpha} 取 {@link Md3} 那三个常量）。 */
    public void stateLayer(Canvas c, RectI r, int radius, int onStateColor, int alpha) {
        roundRect(c, r, radius, onStateColor, alpha);
    }

    /**
     * 烘焙好的中文贴图。
     *
     * @param leftAlign true 时 x 是左边界，否则是中心（并列元素一律按中心，标签是例外）
     */
    public void baked(Canvas c, String s, int px, int color, int x, int cy, int alpha, boolean leftAlign) {
        Bitmap b = text.bake(s, px, color);
        ink.setAlpha(alpha);
        SpriteFactory.draw(c, b, leftAlign ? x : x - b.getWidth() / 2,
                cy - b.getHeight() / 2, b.getWidth(), b.getHeight(), ink);
        ink.setAlpha(255);
    }

    public void bakedCentered(Canvas c, String s, int px, int color, int cx, int cy, int alpha) {
        baked(c, s, px, color, cx, cy, alpha, false);
    }

    /** 辉光：贴图按 kind 取区域，颜色只在 atlasFor 里选一张固定的着色图集。 */
    public void glowAt(Canvas c, int kind, int cx, int cy, int size, int color, int alpha) {
        if (size <= 0) return;
        fill.setAlpha(alpha < 0 ? 0 : (alpha > 255 ? 255 : alpha));
        SpriteFactory.drawRegion(c, glow.atlasFor(color),
                GlowAtlas.regionX(kind), GlowAtlas.regionY(kind),
                GlowAtlas.regionW(kind), GlowAtlas.regionH(kind),
                cx - size / 2, cy - size / 2, size, size, fill);
        fill.setAlpha(255);
    }

    /** 一段不抗锯齿的 1px 直线（折线图与边框流光都用它：像素画面里的"线"就该是阶梯状的）。 */
    public void line(Canvas c, int x1, int y1, int x2, int y2, int color, int alpha) {
        stroke.setColor(color);
        stroke.setAlpha(alpha);
        c.drawLine(x1, y1, x2, y2, stroke);
        stroke.setAlpha(255);
    }

    /** 点阵数字：x 是左边界或右边界（{@link #numberRight} 用于贴右沿的读数）。 */
    public void number(Canvas c, HudText hud, int x, int cy, int scale, int color, int alpha) {
        ink.setColor(color);
        ink.setAlpha(alpha);
        font.drawScaled(c, hud.buffer(), hud.length(), x,
                cy - BitmapFont.GLYPH_H * scale / 2, scale, ink);
        ink.setAlpha(255);
    }

    public void numberRight(Canvas c, HudText hud, int rightEdge, int cy, int scale, int color, int alpha) {
        int w = BitmapFont.textWidth(hud.buffer(), hud.length(), scale);
        number(c, hud, rightEdge - w, cy, scale, color, alpha);
    }

    /** 「中文标签 + ASCII 数值」拼成一整串后按 midX 居中（HUD 行1、结算页、卡片都用这套排法）。 */
    public void labelNumber(Canvas c, HudText hud, String label, int midX, int cy,
                            int labelColor, int numberColor, int alpha, int scale) {
        int dw = BitmapFont.textWidth(hud.buffer(), hud.length(), scale);
        Bitmap b = text.bake(label, Md3.PX_LABEL, labelColor);
        int left = midX - (b.getWidth() + GAP + dw) / 2;
        ink.setAlpha(alpha);
        SpriteFactory.draw(c, b, left, cy - b.getHeight() / 2, b.getWidth(), b.getHeight(), ink);
        ink.setAlpha(255);
        number(c, hud, left + b.getWidth() + GAP, cy, scale, numberColor, alpha);
    }

    /** 同 {@link #labelNumber}，但整串贴右沿对齐（HUD 行1、面板标题栏这类"读数靠边"的排法）。 */
    public void labelNumberRight(Canvas c, HudText hud, String label, int rightEdge, int cy,
                                 int labelColor, int numberColor, int alpha, int scale) {
        int dw = BitmapFont.textWidth(hud.buffer(), hud.length(), scale);
        Bitmap b = text.bake(label, Md3.PX_LABEL, labelColor);
        labelNumber(c, hud, label, rightEdge - (b.getWidth() + GAP + dw) / 2, cy,
                labelColor, numberColor, alpha, scale);
    }
}
