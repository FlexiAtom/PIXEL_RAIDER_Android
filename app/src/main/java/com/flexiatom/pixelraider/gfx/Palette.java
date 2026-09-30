package com.flexiatom.pixelraider.gfx;

/**
 * 调色与色阶派生（规格 §三）。
 *
 * shadeHex 的 k>1 向白靠拢、k<1 向黑靠拢——不是简单乘除：简单乘法在暗部会把 3,3,3 和
 * 30,30,30 压成同一档，5 档色阶在低亮度主色上直接塌成两档。
 *
 * 这里**不用 android.graphics.Color**：那些方法就是位运算，而一旦调用它们，本类在纯 JVM 单测里
 * 会抛 "not mocked"（规格 §九.20 要求可判对错的算法必须能在 JVM 上测）。色阶对不对，恰恰是
 * 只有断言能看出来、肉眼在截图中很难看出来的事。
 */
public final class Palette {

    private Palette() { }

    public static int alphaOf(int c) { return (c >>> 24) & 0xFF; }
    public static int redOf(int c) { return (c >> 16) & 0xFF; }
    public static int greenOf(int c) { return (c >> 8) & 0xFF; }
    public static int blueOf(int c) { return c & 0xFF; }
    public static int argb(int a, int r, int g, int b) {
        return (clamp255(a) << 24) | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
    }

    /**
     * @param k 0.34 深暗 / 0.62 暗 / 1 主色 / 1.45 亮 / 2.1 高光
     */
    public static int shade(int base, float k) {
        int a = alphaOf(base) << 24;
        int r = redOf(base), g = greenOf(base), b = blueOf(base);
        if (k < 1f) {
            r = Math.round(r * k);
            g = Math.round(g * k);
            b = Math.round(b * k);
        } else {
            float t = (k - 1f) * 0.6f; // 1.0 → 原色，2.1 → 向白推进
            r = Math.round(r + (255 - r) * t);
            g = Math.round(g + (255 - g) * t);
            b = Math.round(b + (255 - b) * t);
        }
        return a | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /** 5 档：深暗 / 暗 / 主色 / 亮 / 高光。 */
    public static void iconPalette(int base, int[] out5) {
        out5[0] = shade(base, 0.34f);
        out5[1] = shade(base, 0.62f);
        out5[2] = base;
        out5[3] = shade(base, 1.45f);
        out5[4] = shade(base, 2.1f);
    }

    /** 带 alpha 的版本：像素图标常画半透明底，alpha 不能被 shade 丢掉。 */
    public static int alpha(int c, int a) {
        return (clamp255(a) << 24) | (c & 0x00FFFFFF);
    }

    public static int scaleAlpha(int c, float k) {
        return alpha(c, Math.round(alphaOf(c) * k));
    }

    /** 感知亮度（3-6-1 Rec.601 近似），色阶是否"塌档"用它判。 */
    public static int luminance(int c) {
        return redOf(c) * 3 + greenOf(c) * 6 + blueOf(c);
    }

    /**
     * 非法输入必须返回 fallback（规格 §四）：Color.parseColor 抛 IllegalArgumentException，
     * 而它常在 render 中途被调用，一次抛错就是整帧中断甚至崩溃。
     * 这里自己解析 #rgb / #rrggbb / #aarrggbb：非法就返回 fallback，不抛。
     */
    public static final class SafeColor {
        private SafeColor() { }

        public static int parse(String hex, int fallback) {
            if (hex == null || hex.isEmpty() || hex.charAt(0) != '#') return fallback;
            int len = hex.length() - 1;
            if (len != 3 && len != 6 && len != 8) return fallback;
            long v = 0;
            for (int i = 1; i < hex.length(); i++) {
                int d = Character.digit(hex.charAt(i), 16);
                if (d < 0) return fallback;
                v = (v << 4) | d;
            }
            if (len == 3) {
                int r = (int) ((v >> 8) & 0xF), g = (int) ((v >> 4) & 0xF), b = (int) (v & 0xF);
                return argb(255, r * 17, g * 17, b * 17);
            }
            if (len == 6) v |= 0xFF000000L;
            return (int) (v & 0xFFFFFFFFL);
        }
    }
}
