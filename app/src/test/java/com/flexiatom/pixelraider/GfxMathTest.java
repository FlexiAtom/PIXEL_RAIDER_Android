package com.flexiatom.pixelraider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.flexiatom.pixelraider.gfx.FxRegistry;
import com.flexiatom.pixelraider.gfx.OffscreenCache;
import com.flexiatom.pixelraider.gfx.Palette;
import com.flexiatom.pixelraider.gfx.SphereLight;
import com.flexiatom.pixelraider.gfx.SpriteGrid;
import com.flexiatom.pixelraider.gfx.Starfield;

import org.junit.Test;

import android.graphics.Canvas;

/**
 * 渲染地基里**能在 JVM 上判对错**的部分（规格 §九.20：布局与算法写成纯函数，用 JVM 单测直接验证）。
 * Bitmap 产物本身要真机/截图才能看，不在这里假装验证。
 */
public class GfxMathTest {

    // ---- 字符网格 -----------------------------------------------------------------

    @Test
    public void gridMapsPaletteAndTransparentCells() {
        int[] pal = { 0xFF112233, 0xFF445566 };
        int[] px = SpriteGrid.toPixels(new String[] { "01", ".." }, pal);
        assertEquals(4, px.length);
        assertEquals(0xFF112233, px[0]);
        assertEquals(0xFF445566, px[1]);
        assertEquals(SpriteGrid.TRANSPARENT, px[2]);
        assertEquals(SpriteGrid.TRANSPARENT, px[3]);
    }

    @Test
    public void raggedRowsPadWithTransparencyInsteadOfThrowing() {
        int[] px = SpriteGrid.toPixels(new String[] { "00", "1" }, new int[] { 0xFF000001, 0xFF000002 });
        assertEquals(4, px.length);
        assertEquals(0xFF000002, px[2]);
        assertEquals(SpriteGrid.TRANSPARENT, px[3]);
    }

    @Test
    public void outOfRangePaletteIndexIsTransparentNotCrash() {
        assertEquals(SpriteGrid.TRANSPARENT, SpriteGrid.colorAt('z', new int[] { 0xFF000001 }));
    }

    @Test
    public void nearestScaleKeepsPixelBlocksSquareAtIntegerFactor() {
        int[] src = SpriteGrid.toPixels(new String[] { "01", "10" }, new int[] { 0xFFFF0000, 0xFF00FF00 });
        int[] up = SpriteGrid.scaleNearest(src, 2, 2, 4, 4);
        assertEquals(0xFFFF0000, up[0]);
        assertEquals(0xFFFF0000, up[1]);   // 2× 放大：一个源像素 = 2×2 目标像素
        assertEquals(0xFF00FF00, up[2]);
        assertEquals(0xFF00FF00, up[7]);
    }

    // ---- drawPixelGrid 的 ceil 规则：断缝用，不用"重算公式"自证 ----------------------

    @Test
    public void integerTruncatedStrideLeavesASeamButCeilCoverageDoesNot() {
        final int TOTAL = 10, CELLS = 4;
        int truncatedStride = TOTAL / CELLS;                  // 2
        int ceilPaintSize = SpriteGrid.cellSize(TOTAL, CELLS); // 3
        // 用截断步进、截断宽度铺 4 格：最后一格右边界只到 8，右侧 2px 是缝。
        assertEquals(8, CELLS * truncatedStride);
        // ceil 覆盖必须顶到 total，且任意相邻格重叠而非留空。
        assertEquals(0, SpriteGrid.cellLeft(TOTAL, CELLS, 0));
        assertEquals(3, SpriteGrid.cellRight(TOTAL, CELLS, 0));
        assertEquals(10, SpriteGrid.cellRight(TOTAL, CELLS, CELLS - 1));
        for (int i = 1; i < CELLS; i++) {
            assertTrue("相邻格之间不得留缝",
                    SpriteGrid.cellRight(TOTAL, CELLS, i - 1) >= SpriteGrid.cellLeft(TOTAL, CELLS, i));
        }
        // ceil 的代价是重叠：第一格被画了 3px 而不是 2.5px。
        assertTrue(ceilPaintSize > truncatedStride);
    }

    @Test
    public void renderGridCoversEveryPixelOfANonDivisibleTarget() {
        int cells = 7;
        int[] colors = new int[cells * cells];
        for (int i = 0; i < colors.length; i++) colors[i] = 0xFF808080;
        int[] px = SpriteGrid.renderGrid(colors, cells, cells, 10, 10);
        for (int i = 0; i < 100; i++) {
            assertNotEquals("像素 " + i + " 是缝隙", SpriteGrid.TRANSPARENT, px[i]);
        }
    }

    @Test
    public void transparentCellsDoNotOverwriteThePlate() {
        int[] px = SpriteGrid.renderGrid(new int[] { SpriteGrid.TRANSPARENT }, 1, 1, 2, 2);
        assertEquals(SpriteGrid.TRANSPARENT, px[0]);
    }

    @Test
    public void withAlphaKeepsRgbAndClampsAlpha() {
        assertEquals(0x80FF8040, SpriteGrid.withAlpha(0xFFFF8040, 0x80));
        assertEquals(0xFFFFFFFF, SpriteGrid.withAlpha(0x00FFFFFF, 9999));
        assertEquals(SpriteGrid.TRANSPARENT, SpriteGrid.withAlpha(0xFFFF8040, 0));
    }

    @Test
    public void lightenTakesPerChannelMaximum() {
        assertEquals(0xFF0FFF00, SpriteGrid.lighten(0xFF0F0000, 0xFF00FF00));
    }

    // ---- 调色板 5 档：暗部不能塌成同一档 --------------------------------------------

    @Test
    public void fiveTonePaletteStaysDistinctOnADarkBase() {
        int[] out = new int[5];
        Palette.iconPalette(0xFF202A38, out);
        for (int i = 1; i < 5; i++) {
            assertNotEquals("色阶 " + i + " 与前一档塌成同色", luminance(out[i - 1]), luminance(out[i]));
        }
        assertTrue(out[0] != out[4]);
        assertTrue("高光档必须比主色亮", luminance(out[4]) > luminance(out[2]));
        assertTrue("亮档与高光档之间也要有台阶", luminance(out[3]) > luminance(out[2]));
    }

    private static int luminance(int c) {
        return ((c >> 16) & 0xFF) * 3 + ((c >> 8) & 0xFF) * 6 + (c & 0xFF);
    }

    // ---- 离屏缓存 key：尺寸必须入 key ------------------------------------------------

    @Test
    public void cacheKeySeparatesSameIdAtDifferentSizes() {
        long a = OffscreenCache.keyOf(7, 24, 24);
        long b = OffscreenCache.keyOf(7, 24, 25);
        long c = OffscreenCache.keyOf(8, 24, 24);
        assertNotEquals(a, b);
        assertNotEquals(a, c);
        assertEquals(a, OffscreenCache.keyOf(7, 24, 24));
        assertTrue(OffscreenCache.distinctSizeKeys(7, 24, 24, 25));
    }

    // ---- 视差：比率严格递减，"三层同速 = 没有视差" -------------------------------------

    @Test
    public void parallaxRatiosAreStrictlyDecreasingAndDistinct() {
        float prev = Float.POSITIVE_INFINITY;
        for (int i = 0; i < Starfield.LAYERS; i++) {
            float r = Starfield.ratioOf(i);
            assertTrue("第 " + i + " 层比率必须严格小于上一层", r < prev);
            prev = r;
        }
        assertEquals(1f, Starfield.ratioOf(0), 0f);
        assertEquals(0.6f, Starfield.ratioOf(1), 0f);
        assertEquals(0.35f, Starfield.ratioOf(2), 0f);
        assertEquals(0.18f, Starfield.ratioOf(3), 0f);
        assertEquals(0.08f, Starfield.ratioOf(4), 0f);
    }

    @Test
    public void starTileWrapStaysInsideOnePeriodAndIsGapFree() {
        final int H = 320;
        float[] offsets = { 0f, 7.5f, 319f, 320f, 1000.3f, -13f };
        for (float off : offsets) {
            int top = Starfield.tileTopY(off, H);
            assertTrue("top=" + top + " off=" + off, top >= 0 && top < H);
            // 两张图 [top, top+H) 与 [top-H, top) 拼起来必须覆盖任意扫描线
            for (int y = 0; y < H; y++) {
                boolean inFirst = y >= top && y < top + H;
                boolean inSecond = y >= top - H && y < top;
                assertTrue("扫描线 " + y + " 漏了", inFirst || inSecond);
            }
        }
    }

    /**
     * 星场按**星点数组**生成，不按画布面积。这条是 2026-09-24 那次掉帧的墓碑：
     * 当时每层烘一张与画布等大的 Bitmap，五层×平铺两次 = 每帧十次全屏 alpha 混合，
     * 真机量到 18.7ms/帧（占整帧 49%），而画面上总共只有 154 个点。
     *
     * <p>{@code rebuild} 现在不碰 Bitmap，所以整段能在 JVM 上跑——这本身就是"没有全屏位图"的证据。
     */
    @Test
    public void starfieldCostScalesWithStarCountNotCanvasArea() {
        final int W = 240, H = 533;
        Starfield high = new Starfield();
        high.rebuild(W, H, 2, 7);
        int total = 0;
        for (int i = 0; i < Starfield.LAYERS; i++) {
            assertTrue("第 " + i + " 层要有星", high.starCount(i) > 0);
            total += high.starCount(i);
        }
        // 满幅 127920 像素里只撒几百个点：数量级必须是"百"，一旦回到"万"就是又去按面积计费了
        assertTrue("total=" + total, total > 100 && total < 400);

        Starfield low = new Starfield();
        low.rebuild(W, H, 0, 7);
        int lowTotal = 0;
        for (int i = 0; i < Starfield.LAYERS; i++) lowTotal += low.starCount(i);
        assertTrue("低画质要真的少撒星：" + lowTotal + " vs " + total, lowTotal < total);

        // 同种子同尺寸必须复现同一套星点，否则换档时星场会"跳"
        Starfield again = new Starfield();
        again.rebuild(W, H, 2, 7);
        for (int i = 0; i < Starfield.LAYERS; i++) {
            assertEquals(high.starCount(i), again.starCount(i));
        }
    }

    // ---- 背景层级顺序是数据，不是调用先后 ----------------------------------------------
    @Test
    public void fxLayersDrawInOrderRegardlessOfRegistrationSequence() {
        FxRegistry reg = new FxRegistry();
        reg.register(named(FxRegistry.ORDER_FLOAT_TEXTS, 'F'));
        reg.register(named(FxRegistry.ORDER_MUZZLES, 'M'));
        reg.register(named(FxRegistry.ORDER_NOVAS, 'N'));
        StringBuilder seq = new StringBuilder();
        for (int i = 0; i < reg.layerCount(); i++) {
            FxRegistry.Layer l = reg.layerAt(i);
            if (i > 0) assertTrue("order 必须升序", l.order() >= reg.layerAt(i - 1).order());
            seq.append(((Named) l).tag);
        }
        assertTrue("实际 " + seq, "NMF".contentEquals(seq));
    }

    @Test
    public void sameOrderLayersKeepRegistrationSequence() {
        FxRegistry reg = new FxRegistry();
        reg.register(named(FxRegistry.ORDER_PARTICLES, 'A'));
        reg.register(named(FxRegistry.ORDER_PARTICLES, 'B'));
        assertEquals('A', ((Named) reg.layerAt(0)).tag);
        assertEquals('B', ((Named) reg.layerAt(1)).tag);
    }

    @Test
    public void drawAllSkipsEmptyLayersWithoutAllocating() {
        FxRegistry reg = new FxRegistry();
        Named empty = named(FxRegistry.ORDER_SPARKS, 'x');
        empty.alive = false;
        reg.register(empty);
        Named hit = named(FxRegistry.ORDER_SPARKS + 5, 'y');
        reg.register(hit);
        reg.drawAll(null);          // empty 被跳过，只有 hit 真的画了一次
        assertEquals(1, hit.calls);
        assertEquals(0, empty.calls);
        assertEquals(1, reg.emptyLayersThisFrame());
    }

    private static class Named implements FxRegistry.Layer {
        final int order;
        final char tag;
        boolean alive = true;
        int calls;

        Named(int order, char tag) {
            this.order = order;
            this.tag = tag;
        }

        @Override public int order() { return order; }
        @Override public boolean isEmpty() { return !alive; }
        @Override public void draw(Canvas canvas) { calls++; }
    }

    private static Named named(int order, char tag) {
        return new Named(order, tag);
    }

    // ---- 行星光照方向：左上亮、右下暗、rim 在下右 --------------------------------------

    @Test
    public void planetIsLitFromTopLeft45() {
        float topLeft = SphereLight.lambert(-0.5f, -0.5f, 0.707f);
        float bottomRight = SphereLight.lambert(0.5f, 0.5f, 0.707f);
        assertTrue("左上 " + topLeft + " 必须亮于右下 " + bottomRight, topLeft > bottomRight);
        assertEquals(1f, SphereLight.lambert(-0.707f, -0.707f, 0f), 1e-3f);
        // 背光面留环境光，不能纯黑（纯黑就成剪影）
        assertTrue(SphereLight.lambert(0.707f, 0.707f, 0f) > 0.1f);
    }

    @Test
    public void rimLightSitsOnTheAntiSunSideEdge() {
        float lowerRight = SphereLight.rimLight(0.707f, 0.707f);
        float upperLeft = SphereLight.rimLight(-0.707f, -0.707f);
        float center = SphereLight.rimLight(0f, 0f);
        assertTrue("rim 应落在右下 " + lowerRight, lowerRight > 0.9f);
        assertEquals("受光侧不该有 rim", 0f, upperLeft, 1e-3f);
        assertEquals("球心不该有 rim", 0f, center, 1e-3f);
        assertEquals("rim 只有一圈，不是整面渐变", 0f, SphereLight.rimLight(0.35f, 0.35f), 1e-3f);
    }
}
