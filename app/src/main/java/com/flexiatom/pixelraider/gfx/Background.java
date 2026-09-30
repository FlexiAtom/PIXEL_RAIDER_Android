package com.flexiatom.pixelraider.gfx;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import com.flexiatom.pixelraider.core.FrameProbe;

import java.util.Random;

/**
 * 六层太空背景（规格 §三）。绘制顺序即层级，逐层显式：
 * 1 深空气辉 → 2 星云（双相位平铺叠加）→ 3 行星 → 4 五层视差星场 → 5 尘埃带 → 6 流星。
 *
 * 除星场外的重层全部预烘焙，每帧只剩 drawBitmap：
 * - 星云 160×160 一张，同图以两个不同速度/相位滚动、按自带 alpha 叠加，两图交叠处自然变亮。
 *   这里刻意**不用 PorterDuff.LIGHTEN**：软件光栅下通用混合模式每像素一次慢路径，
 *   真机量到 9.2ms/帧，而 src-over 拿到同样的"交叠更亮"只用一半时间。
 * - 空气辉预合成到清屏底色上烘成不透明图，于是那一次全屏 blit 走整块拷贝而不是逐像素混合。
 * - 行星统一光照来自**左上 45°**，右下自动变暗，边缘带 rim light（亮度函数在 {@link SphereLight}，纯算术、可单测；一旦光照方向写反，行星看起来就是"底部发光"，很廉价）。
 *
 * 每帧零分配：Bitmap/Rect/Paint/Xfermode 全是字段或 static final，帧内不 new、不拼字符串。
 */
public final class Background {

    public static final int NEBULA_SIZE = 160;

    /**
     * 星云用**普通 source-over**，不用 {@code PorterDuff.Mode.LIGHTEN}。
     * 双相位交叠变亮这个效果 src-over 一样有（1-(1-a)(1-b) 自然叠加），
     * 但 LIGHTEN 在软件光栅里走不到任何已知快路径，每像素一次通用混合；
     * 真机量到 neb=9.2ms / 帧，是仅次于星场的第二块。位图自带的 alpha 就是强度，
     * 画笔 alpha 只做两次相位的明暗差。
     */
    private static final Paint NEBULA_PAINT = new Paint();
    static {
        NEBULA_PAINT.setAntiAlias(false);
        NEBULA_PAINT.setFilterBitmap(false);
    }

    private final Starfield starfield = new Starfield();
    private final Rect src = new Rect();
    private final Rect dst = new Rect();

    private Bitmap glow;        // 层 1
    private Bitmap nebulaA;     // 层 2（一张图两个相位）
    private Bitmap planet;      // 层 3
    private Bitmap dust;        // 层 5

    private float nebulaOffA, nebulaOffB;
    private float planetX, planetY;
    private float dustOff;
    private float dustLife, dustMaxLife;

    private float meteorWait;
    private float meteorX, meteorY, meteorVX, meteorVY, meteorLife;

    private int w = -1, h = -1;
    private int themeBase = 0xFF1B2A4A;
    private int themeAccent = 0xFF3C6E9F;

    // ---- 可调参数（S3 集中进数值表） ------------------------------------------------
    public float nebulaSpeedA = 3.5f, nebulaSpeedB = 2.1f;
    public float planetDriftX = 1.2f, planetDriftY = 2.6f;
    public float dustSpeed = 5f;
    public float dustMinSec = 14f, dustMaxSec = 24f;
    public float meteorMinSec = 5f, meteorMaxSec = 13f;
    public float meteorSpeed = 320f;

    private final Random rnd = new Random(0x5EEDL);

    public Starfield starfield() { return starfield; }

    public void setTheme(int base, int accent) {
        if (base == themeBase && accent == themeAccent) return;
        themeBase = base;
        themeAccent = accent;
        int cw = w, ch = h;
        w = -1;                      // 强制重建烘焙层
        rebuild(cw, ch, 1, 0);
    }

    /** surface 尺寸变化 / 变档 / onTrimMemory 后重建。旧 Bitmap 一律 recycle。 */
    public void rebuild(int w, int h, int qualityTier, int seed) {
        if (this.w == w && this.h == h && glow != null) return;
        release();
        this.w = w;
        this.h = h;
        glow = buildGlow(w, h);
        nebulaA = buildNebula(seed);
        planet = buildPlanet(w, h, qualityTier);
        dust = buildDust(w, h);
        starfield.rebuild(w, h, qualityTier, seed + 7);
        dustLife = 0f;
        dustMaxLife = 0f;
        meteorWait = nextMeteorWait();
    }

    private float nextMeteorWait() {
        return meteorMinSec + rnd.nextFloat() * (meteorMaxSec - meteorMinSec);
    }

    public void step(float dt) {
        starfield.step(dt);
        nebulaOffA += nebulaSpeedA * dt;
        nebulaOffB += nebulaSpeedB * dt;
        dustOff += dustSpeed * dt;
        planetX += planetDriftX * dt;
        planetY += planetDriftY * dt;
        if (w > 0) {
            if (planetX > w) planetX -= w * 2f;
            if (planetY > h) planetY -= h * 2f;
        }
        stepDust(dt);
        stepMeteor(dt);
    }

    /** 尘埃带：14~24 秒一个生命周期，淡入-持续-自然消散（不是到点硬切）。 */
    private void stepDust(float dt) {
        if (dustMaxLife <= 0f) {
            dustMaxLife = dustMinSec + rnd.nextFloat() * (dustMaxSec - dustMinSec);
            dustLife = 0.0001f;
        }
        dustLife += dt;
        if (dustLife >= dustMaxLife) {
            dustMaxLife = 0f;
            dustLife = 0f;
        }
    }

    private void stepMeteor(float dt) {
        if (meteorLife > 0f) {
            meteorX += meteorVX * dt;
            meteorY += meteorVY * dt;
            meteorLife -= dt;
            return;
        }
        meteorWait -= dt;
        if (meteorWait <= 0f) {
            meteorWait = nextMeteorWait();
            meteorX = rnd.nextFloat() * Math.max(1, w) * 0.6f;
            meteorY = -10f - rnd.nextFloat() * Math.max(1, h * 0.2f);
            float ang = (float) Math.toRadians(28f + rnd.nextFloat() * 14f);
            meteorVX = (float) Math.cos(ang) * meteorSpeed;
            meteorVY = (float) Math.sin(ang) * meteorSpeed;
            meteorLife = 0.55f + rnd.nextFloat() * 0.35f;
        }
    }

    /** 六层逐层显式，每层一段独立计时交给 {@code probe}（可为 null：只看总数时不必分账）。 */
    public void draw(Canvas c, FrameProbe probe) {
        if (w <= 0) return;
        if (glow != null) {
            mark(probe, FrameProbe.BG_GLOW, true);
            SpriteFactory.draw(c, glow, 0, 0, w, h, SpriteFactory.PIXEL);
            mark(probe, FrameProbe.BG_GLOW, false);
        }
        mark(probe, FrameProbe.BG_NEB, true);
        drawNebula(c);
        mark(probe, FrameProbe.BG_NEB, false);
        if (planet != null) {
            mark(probe, FrameProbe.BG_PLAN, true);
            int pw = planet.getWidth();
            SpriteFactory.draw(c, planet, (int) planetX - pw / 2, (int) planetY - pw / 2, pw, planet.getHeight(),
                    SpriteFactory.PIXEL);
            mark(probe, FrameProbe.BG_PLAN, false);
        }
        mark(probe, FrameProbe.BG_STAR, true);
        starfield.draw(c);
        mark(probe, FrameProbe.BG_STAR, false);
        mark(probe, FrameProbe.BG_DUST, true);
        drawDust(c);
        drawMeteor(c);
        mark(probe, FrameProbe.BG_DUST, false);
    }

    private static void mark(FrameProbe probe, int section, boolean begin) {
        if (probe == null) return;
        if (begin) {
            probe.begin(section);
        } else {
            probe.end(section);
        }
    }

    private void drawNebula(Canvas canvas) {
        if (nebulaA == null) return;
        int s = NEBULA_SIZE;
        src.set(0, 0, s, s);
        tileDraw(canvas, nebulaA, nebulaOffA, s, 110);
        tileDraw(canvas, nebulaA, nebulaOffB * 1.37f + s / 3f, s, 80);
    }

    /** 同一张图以两个相位平铺，交叠处因 alpha 叠加而自然变亮。 */
    private void tileDraw(Canvas canvas, Bitmap b, float off, int s, int alpha) {
        int ox = tileOffset(off, s);
        int oy = tileOffset(off * 0.61f, s);
        int saved = NEBULA_PAINT.getAlpha();
        NEBULA_PAINT.setAlpha(alpha);
        // 只覆盖到画面边界：原先多铺一圈行列，45 张里近一半整张落在画外，
        // Skia 会裁掉但每次调用仍有设置成本。
        for (int y = -oy; y < h; y += s) {
            for (int x = -ox; x < w; x += s) {
                dst.set(x, y, x + s, y + s);
                canvas.drawBitmap(b, src, dst, NEBULA_PAINT);
            }
        }
        NEBULA_PAINT.setAlpha(saved);
    }

    /** off 对 s 取模并归一到 [0,s)；负偏移（往回滚）也落在这个区间里。 */
    private static int tileOffset(float off, int s) {
        int o = (int) (off % s);
        return o < 0 ? o + s : o;
    }

    private void drawDust(Canvas canvas) {
        if (dust == null || dustMaxLife <= 0f) return;
        float k = dustLife / dustMaxLife;
        float env = k < 0.2f ? k / 0.2f : k > 0.75f ? (1f - k) / 0.25f : 1f;
        int alpha = Math.round(env * 90f);
        if (alpha <= 0) return;
        int dh = dust.getHeight();
        int oy = (int) (dustOff % dh);
        src.set(0, 0, dust.getWidth(), dh);
        int saved = SpriteFactory.GLOW.getAlpha();
        SpriteFactory.GLOW.setAlpha(alpha);
        for (int y = -oy; y < h; y += dh) {
            dst.set(0, y, w, y + dh);
            canvas.drawBitmap(dust, src, dst, SpriteFactory.GLOW);
        }
        SpriteFactory.GLOW.setAlpha(saved);
    }

    /** 流星 = 一条按速度矢量拉长的拖尾 + 头部亮点，不用矩形贴一张纸在后面。 */
    private void drawMeteor(Canvas canvas) {
        if (meteorLife <= 0f) return;
        float len = 46f;
        float nx = meteorVX, ny = meteorVY;
        float m = (float) Math.sqrt(nx * nx + ny * ny);
        if (m <= 0f) return;
        nx /= m;
        ny /= m;
        float tailX = meteorX - nx * len;
        float tailY = meteorY - ny * len;
        float half = 1.4f;
        float px = -ny * half, py = nx * half;
        METEOR_PATH.rewind();
        METEOR_PATH.moveTo(meteorX + px, meteorY + py);
        METEOR_PATH.lineTo(meteorX - px, meteorY - py);
        METEOR_PATH.lineTo(tailX, tailY);
        METEOR_PATH.close();
        int a = Math.round(Math.min(1f, meteorLife / 0.5f) * 200f);
        METEOR_PAINT.setAlpha(a);
        canvas.drawPath(METEOR_PATH, METEOR_PAINT);
        SpriteFactory.draw(canvas, meteorHead(), (int) (meteorX - 2), (int) (meteorY - 2), 5, 5,
                SpriteFactory.PIXEL);
    }

    private static final android.graphics.Path METEOR_PATH = new android.graphics.Path();
    private static final Paint METEOR_PAINT = new Paint();
    private static Bitmap meteorHeadCache;
    static {
        METEOR_PAINT.setAntiAlias(false);
        METEOR_PAINT.setColor(0xFFDCEBFF);
    }

    private static Bitmap meteorHead() {
        if (meteorHeadCache == null || meteorHeadCache.isRecycled()) {
            meteorHeadCache = Bitmap.createBitmap(
                    new int[] { 0x00FFFFFF, 0xC8FFFFFF, 0xFFFFFFFF, 0xC8FFFFFF, 0x00FFFFFF,
                                0x00FFFFFF, 0xC8FFFFFF, 0xFFFFFFFF, 0xC8FFFFFF, 0x00FFFFFF,
                                0x00FFFFFF, 0xC8FFFFFF, 0xFFFFFFFF, 0xC8FFFFFF, 0x00FFFFFF, },
                    5, 3, Bitmap.Config.ARGB_8888);
        }
        return meteorHeadCache;
    }

    // ---- 烘焙 --------------------------------------------------------------------

    /**
     * 层 1：星区主题色的纵向渐变空气辉，底部最亮（画面重心在下方时不抢玩法区）。
     *
     * **烘成不透明**：这一层底下就是 {@code Ink.BG_DEEP} 清屏，与其每帧做一次全屏 alpha 混合
     * （量到 3.8ms），不如烘焙时就把渐变预合成到那个底色上。之后这张图是一次整块拷贝，
     * 而且它是全屏的，所以清屏那一步实际已经被它做掉了。
     */
    private Bitmap buildGlow(int w, int h) {
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            float t = h <= 1 ? 0f : y / (float) (h - 1);
            int col = lerp(themeBase, themeAccent, t * t);
            int a = Math.round(60f + 90f * t);
            int v = SpriteGrid.blend(Ink.BG_DEEP, col, a);
            int base = y * w;
            for (int x = 0; x < w; x++) px[base + x] = 0xFF000000 | (v & 0x00FFFFFF);
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    /** 层 2：星云 160×160，值噪声叠三 octave，按主题色上色；像素自带的 alpha 就是它的强度。 */
    private Bitmap buildNebula(int seed) {
        Random r = new Random(seed * 31 + 5);
        int s = NEBULA_SIZE;
        float[][] n = valueNoise(s, 4, r);
        float[][] m = valueNoise(s, 9, r);
        int[] px = new int[s * s];
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                float v = n[y][x] * 0.72f + m[y][x] * 0.28f;
                v = (float) Math.pow(Math.max(0f, v - 0.34f) / 0.66f, 1.7f);
                int col = lerp(themeBase, themeAccent, v);
                px[y * s + x] = SpriteGrid.withAlpha(col, Math.round(v * 200f));
            }
        }
        return Bitmap.createBitmap(px, s, s, Bitmap.Config.ARGB_8888);
    }

    private static float[][] valueNoise(int size, int cells, Random r) {
        float[] g = new float[cells * cells];
        for (int i = 0; i < g.length; i++) g[i] = r.nextFloat();
        float[][] out = new float[size][size];
        for (int y = 0; y < size; y++) {
            float fy = (float) y / size * cells;
            int y0 = (int) fy % cells, y1 = (y0 + 1) % cells;
            float ty = smooth(fy - (int) fy);
            for (int x = 0; x < size; x++) {
                float fx = (float) x / size * cells;
                int x0 = (int) fx % cells, x1 = (x0 + 1) % cells;
                float tx = smooth(fx - (int) fx);
                float a = g[y0 * cells + x0], b = g[y0 * cells + x1];
                float c = g[y1 * cells + x0], d = g[y1 * cells + x1];
                out[y][x] = a + (b - a) * tx + (c - a) * ty + (a - b - c + d) * tx * ty;
            }
        }
        return out;
    }

    private static float smooth(float t) {
        return t * t * (3f - 2f * t);
    }

    /**
     * 层 3：行星。光照固定来自左上 45°，右下自动变暗，边缘一条 rim light。
     * 亮度函数在 {@link SphereLight}（纯算术，单测直接钉"左上比右下亮、rim 在下右侧"）。
     */
    private Bitmap buildPlanet(int w, int h, int qualityTier) {
        int d = qualityTier >= 2 ? 96 : qualityTier >= 1 ? 72 : 56;
        float half = (d - 1) / 2f;
        int[] px = new int[d * d];
        int rockA = 0xFF6B5E7A, rockB = 0xFF2A2140;
        for (int y = 0; y < d; y++) {
            for (int x = 0; x < d; x++) {
                float nx = (x - half) / half, ny = (y - half) / half;
                float r2 = nx * nx + ny * ny;
                if (r2 > 1f) {
                    px[y * d + x] = SpriteGrid.TRANSPARENT;
                    continue;
                }
                float nz = (float) Math.sqrt(1f - r2);
                float lum = SphereLight.lambert(nx, ny, nz);
                float bands = 0.5f + 0.5f * (float) Math.sin((ny * 6.2f) + nz * 1.6f);
                int col = lerp(rockB, rockA, bands * 0.55f + 0.45f * lum);
                col = Palette.shade(col, 0.35f + 0.95f * lum);
                float rim = SphereLight.rimLight(nx, ny);
                col = SpriteGrid.blend(col, lerp(themeAccent, 0xFFFFFFFF, 0.5f), Math.round(rim * 200f));
                px[y * d + x] = 0xFF000000 | (col & 0x00FFFFFF);
            }
        }
        return Bitmap.createBitmap(px, d, d, Bitmap.Config.ARGB_8888);
    }

    /** 层 5：斜向半透明光带，向上缓慢移动。 */
    private Bitmap buildDust(int w, int h) {
        int bandH = Math.max(48, h / 4);
        int[] px = new int[w * bandH];
        int cx = w / 2, cy = bandH / 2;
        float sx = w * 0.62f, sy = bandH * 0.30f;
        float cosA = 0.86f, sinA = 0.51f;
        for (int y = 0; y < bandH; y++) {
            for (int x = 0; x < w; x++) {
                float dx = x - cx, dy = y - cy;
                float along = dx * cosA + dy * sinA;
                float across = -dx * sinA + dy * cosA;
                float a = (1f - Math.min(1f, Math.abs(along) / sx))
                        * (float) Math.exp(-Math.pow(across / (sy * 0.5f), 2f));
                px[y * w + x] = SpriteGrid.withAlpha(themeAccent, Math.round(Math.max(0f, a) * 70f));
            }
        }
        return Bitmap.createBitmap(px, w, bandH, Bitmap.Config.ARGB_8888);
    }

    private static int lerp(int a, int b, float t) {
        float k = t < 0f ? 0f : t > 1f ? 1f : t;
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * k);
        int g = Math.round(ag + (bg - ag) * k);
        int bl = Math.round(ab + (bb - ab) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    public void release() {
        glow = recycle(glow);
        nebulaA = recycle(nebulaA);
        planet = recycle(planet);
        dust = recycle(dust);
        starfield.release();
        w = h = -1;
    }

    private static Bitmap recycle(Bitmap b) {
        if (b != null && !b.isRecycled()) b.recycle();
        return null;
    }
}
