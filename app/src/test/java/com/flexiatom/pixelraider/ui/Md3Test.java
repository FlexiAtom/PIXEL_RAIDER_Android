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
package com.flexiatom.pixelraider.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/**
 * MD3 token 表的自足性（纯算术，JVM 可测）。
 *
 * 这里**不用生产公式自证**：色阶用 WCAG 的对比度公式（另一套独立定义）当尺子，
 * 圆角用"四分之一圆面积"当尺子。表本身只是把 MD3 的 tone 数字抄进来，
 * 真正会错的是"抄完之后角色之间还成不成一套能读的配色"。
 */
public class Md3Test {

    @After
    public void restoreDefaultSeed() {
        Md3.applySeed(Md3.DEFAULT_SEED);
    }

    private static int luma(int argb) {
        return (argb >> 16 & 0xFF) * 299 + (argb >> 8 & 0xFF) * 587 + (argb & 0xFF) * 114;
    }

    /** WCAG 2.x 的相对亮度（sRGB 线性化），对比度用它算——与本工程任何一处实现都无关。 */
    private static double relLum(int argb) {
        double[] k = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = (argb >> (16 - 8 * i) & 0xFF) / 255.0;
            k[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * k[0] + 0.7152 * k[1] + 0.0722 * k[2];
    }

    private static double contrast(int a, int b) {
        double hi = Math.max(relLum(a), relLum(b));
        double lo = Math.min(relLum(a), relLum(b));
        return (hi + 0.05) / (lo + 0.05);
    }

    @Test
    public void toneRampNeverGoesBackwards() {
        int prev = -1;
        for (int t = 0; t <= Md3.TONE_MAX; t += 5) {
            int l = luma(Md3.tone(t));
            assertTrue("明度阶在 tone " + t + " 处回退了", l >= prev);
            prev = l;
        }
        assertEquals(0xFF000000, Md3.tone(0));        // L* = 0 只能是黑
        assertTrue("tone 100 不比 tone 80 亮，色阶就是反的",
                luma(Md3.tone(100)) > luma(Md3.tone(80)));
    }

    @Test
    public void outOfRangeTonesClampInsteadOfThrowing() {
        assertEquals(Md3.tone(0), Md3.tone(-7));
        assertEquals(Md3.tone(100), Md3.tone(999));
    }

    @Test
    public void containerFamilyRisesWithElevation() {
        int a = luma(Md3.surfaceContainerLowest());
        int b = luma(Md3.surfaceContainerLow());
        int c = luma(Md3.surfaceContainer());
        int d = luma(Md3.surfaceContainerHigh());
        int e = luma(Md3.surfaceContainerHighest());
        assertTrue("MD3 的层级就是容器色阶：五档必须一路走高", a < b && b < c && c < d && d < e);
    }

    @Test
    public void textRolesClearWcagAAOnTheirOwnContainer() {
        assertPair(Md3.onSurface(), Md3.surface());
        assertPair(Md3.onSurfaceVariant(), Md3.surfaceContainerHighest());
        assertPair(Md3.onPrimary(), Md3.primary());
        assertPair(Md3.onPrimaryContainer(), Md3.primaryContainer());
        assertPair(Md3.onSecondaryContainer(), Md3.secondaryContainer());
        assertPair(Md3.onErrorContainer(), Md3.errorContainer());
    }

    private static void assertPair(int fg, int bg) {
        double ratio = contrast(fg, bg);
        assertTrue("对比度只有 " + ratio, ratio >= 4.5);
    }

    @Test
    public void errorColorsDoNotFollowTheSeed() {
        Md3.applySeed(0xFF00FF00);                    // 换成一枚绿 seed
        int err = Md3.error();
        assertTrue("error 跟着 seed 走就不是 error 了", (err >> 16 & 0xFF) > (err >> 8 & 0xFF));
        assertEquals(Md3.MD3_ERROR, err);
        assertEquals(Md3.MD3_ERROR_CONTAINER, Md3.errorContainer());
    }

    @Test
    public void everySeedKeepsItsHueInTheRamp() {
        Md3.applySeed(0xFFFF00AA);
        int s = Md3.surface();
        assertTrue("面板底该还认得出这个 seed 的色相", (s & 0xFF) > (s >> 8 & 0xFF));
    }

    @Test
    public void shapeStepsComeOutOfTheReferenceWidthRatio() {
        assertEquals(3, Md3.shapeForDp(4));
        assertEquals(5, Md3.shapeForDp(8));
        assertEquals(8, Md3.shapeForDp(12));
        assertEquals(11, Md3.shapeForDp(16));
        assertEquals(19, Md3.shapeForDp(28));
        assertEquals(Md3.shapeForDp(8), Md3.R_SMALL);
        assertTrue(Md3.R_EXTRA_SMALL < Md3.R_SMALL && Md3.R_SMALL < Md3.R_MEDIUM
                && Md3.R_MEDIUM < Md3.R_LARGE && Md3.R_LARGE < Md3.R_EXTRA_LARGE);
    }

    @Test
    public void typeScaleUsesOnlyExistingPixelSizes() {
        // 汉字只走内嵌像素字体的原生网格（12）与其两倍（24）——像素字按非原生网格渲染会把
        // 等宽像素格变成宽窄不均的糊边。四个 MD3 角色因此并到两档，层级改由颜色承担。
        // ASCII 数字不受这条约束：它走自绘点阵 BitmapFont，仍是 8/16。
        // 规格 §三：精灵与字只走整数倍缩放。这里挡住"顺手加个 9px 字号"的那一类改动。
        assertEquals(12, Md3.PX_LABEL);
        assertEquals(12, Md3.PX_BODY);
        assertEquals(24, Md3.PX_TITLE);
        assertEquals(24, Md3.PX_DISPLAY);
        // 并档不许把层级并反：小角色不能比大角色还高。
        assertTrue(Md3.PX_LABEL <= Md3.PX_BODY
                && Md3.PX_BODY <= Md3.PX_TITLE
                && Md3.PX_TITLE <= Md3.PX_DISPLAY);
    }

    @Test
    public void stateLayerAlphasAreTheMd3OverlayPercentages() {
        assertEquals(26, Md3.STATE_PRESSED_ALPHA);    // 10%
        assertEquals(31, Md3.STATE_FOCUS_ALPHA);      // 12%
        assertEquals(38, Md3.STATE_DRAGGED_ALPHA);    // 15%
        assertEquals(0.38f, Md3.DISABLED_ALPHA_PERMILLE / 1000f, 1e-6f);
    }
}
