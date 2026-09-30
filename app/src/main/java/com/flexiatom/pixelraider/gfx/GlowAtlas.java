package com.flexiatom.pixelraider.gfx;

import android.graphics.Bitmap;

/**
 * 发光图集（规格 §三）：四种烘焙材质，**禁止** Paint.setShadowLayer / BlurMaskFilter 做实时辉光
 * ——每帧多次高斯模糊直接把帧时间翻三倍，硬件加速下行为还不一致。
 *
 * 四者必须真的不一样：同一张径向渐变贴图会让白核、按钮、标题、火花发同一种光，
 * 于是"发光"退化成"什么都糊一层"。
 *
 * | 材质 | 形态 | 衰减 | 用在哪 |
 * |---|---|---|---|
 * | halo   | 64×64 大软低频 | power 1.5，无硬边 | 超载环、标题底光 |
 * | spark  | 16×16 小硬高频 | 实心白核 + power 5 急收 | 命中火花、枪口 |
 * | volume | 48×48 有厚度 | 双峰：内盘 + 中环，中心不透 | 爆炸光晕、Boss 待机 |
 * | sheen  | 64×16 方向性 | x 向 cos、y 向高斯 | 按钮流光、激光刃 |
 *
 * 每种材质按颜色各烘焙一张（最多 8 色），运行期只 drawBitmap，不 setColorFilter。
 */
public final class GlowAtlas {

    public static final int HALO = 0, SPARK = 1, VOLUME = 2, SHEEN = 3;
    public static final int KIND_COUNT = 4;

    /** 各材质在图集内的行偏移，图集统一宽 64。 */
    private static final int[] Y0 = { 0, 64, 80, 128 };
    private static final int[] HGT = { 64, 16, 48, 16 };
    private static final int[] WID = { 64, 16, 48, 64 };
    public static final int ATLAS_W = 64;
    public static final int ATLAS_H = 144;

    private static final int MAX_COLORS = 8;
    private final int[] colors = new int[MAX_COLORS];
    private final Bitmap[] atlases = new Bitmap[MAX_COLORS];

    public GlowAtlas() { }

    public static int regionX(int kind) { return 0; }
    public static int regionY(int kind) { return Y0[kind]; }
    public static int regionW(int kind) { return WID[kind]; }
    public static int regionH(int kind) { return HGT[kind]; }

    public Bitmap atlasFor(int color) {
        for (int i = 0; i < MAX_COLORS; i++) {
            if (atlases[i] != null && colors[i] == color) return atlases[i];
        }
        Bitmap b = build(color);
        for (int i = 0; i < MAX_COLORS; i++) {
            if (atlases[i] == null) {
                atlases[i] = b;
                colors[i] = color;
                return b;
            }
        }
        b.recycle();
        return atlases[0];
    }

    private static Bitmap build(int color) {
        int[] px = new int[ATLAS_W * ATLAS_H];
        paintHalo(px, color);
        paintSpark(px, color);
        paintVolume(px, color);
        paintSheen(px, color);
        return Bitmap.createBitmap(px, ATLAS_W, ATLAS_H, Bitmap.Config.ARGB_8888);
    }

    private static void paintHalo(int[] px, int color) {
        int c = Y0[HALO] * ATLAS_W;
        float half = (WID[HALO] - 1) / 2f;
        for (int y = 0; y < HGT[HALO]; y++) {
            for (int x = 0; x < WID[HALO]; x++) {
                float dx = (x - half) / half, dy = (y - half) / half;
                float r = (float) Math.sqrt(dx * dx + dy * dy);
                float a = SpriteGrid.falloff(r * 0.5f, 1.5f) * (r > 1f ? 0f : 1f);
                px[c + y * ATLAS_W + x] = argb(color, a * 0.55f);
            }
        }
    }

    private static void paintSpark(int[] px, int color) {
        int c = Y0[SPARK] * ATLAS_W;
        float half = (WID[SPARK] - 1) / 2f;
        for (int y = 0; y < HGT[SPARK]; y++) {
            for (int x = 0; x < WID[SPARK]; x++) {
                float dx = (x - half) / half, dy = (y - half) / half;
                float r = (float) Math.sqrt(dx * dx + dy * dy);
                float a = r < 0.3f ? 1f : SpriteGrid.falloff((r - 0.3f) / 0.7f, 5f);
                // 小硬高频：核心接近纯白，外圈直接吃满主色
                float hot = r < 0.3f ? 1f : 0f;
                px[c + y * ATLAS_W + x] = argb(mixWhite(color, hot), a);
            }
        }
    }

    private static void paintVolume(int[] px, int color) {
        int c = Y0[VOLUME] * ATLAS_W;
        float half = (WID[VOLUME] - 1) / 2f;
        for (int y = 0; y < HGT[VOLUME]; y++) {
            for (int x = 0; x < WID[VOLUME]; x++) {
                float dx = (x - half) / half, dy = (y - half) / half;
                float r = (float) Math.sqrt(dx * dx + dy * dy);
                // 双峰 = "有厚度"：内盘 + 中环，中心不透，看起来像一团而非一片
                float inner = SpriteGrid.falloff(r * 1.6f, 2.2f) * 0.85f;
                float ring = SpriteGrid.falloff(Math.abs(r - 0.62f) * 3.2f, 2.6f) * 0.6f;
                float a = Math.min(1f, inner + ring) * (r > 1f ? 0f : 1f);
                px[c + y * ATLAS_W + x] = argb(color, a * 0.8f);
            }
        }
    }

    private static void paintSheen(int[] px, int color) {
        int c = Y0[SHEEN] * ATLAS_W;
        for (int y = 0; y < HGT[SHEEN]; y++) {
            float ny = (y - (HGT[SHEEN] - 1) / 2f) / (HGT[SHEEN] / 2f);
            float gy = (float) Math.exp(-(ny * ny) * 3.2f);
            for (int x = 0; x < WID[SHEEN]; x++) {
                float nx = x / (WID[SHEEN] - 1f);
                float along = (float) Math.pow(Math.sin(Math.PI * nx), 1.4f); // 两端收到 0
                px[c + y * ATLAS_W + x] = argb(mixWhite(color, along * 0.5f), along * gy);
            }
        }
    }

    private static int mixWhite(int c, float t) {
        int r = (int) (((c >> 16) & 0xFF) + (255 - ((c >> 16) & 0xFF)) * t);
        int g = (int) (((c >> 8) & 0xFF) + (255 - ((c >> 8) & 0xFF)) * t);
        int b = (int) ((c & 0xFF) + (255 - (c & 0xFF)) * t);
        return (c & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static int argb(int c, float a) {
        if (a <= 0f) return 0;
        int ai = Math.round(Math.min(1f, a) * 255f);
        return (ai << 24) | (c & 0x00FFFFFF);
    }

    public void recycle() {
        for (int i = 0; i < MAX_COLORS; i++) {
            if (atlases[i] != null) {
                atlases[i].recycle();
                atlases[i] = null;
            }
        }
    }
}
