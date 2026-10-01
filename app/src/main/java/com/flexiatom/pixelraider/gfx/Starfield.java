/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
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

import android.graphics.Canvas;
import android.graphics.Paint;

import com.flexiatom.pixelraider.plat.QualityProfile;

import java.util.Random;

/**
 * 五层视差星场（规格 §三第 4 层）。速度比 1 : 0.6 : 0.35 : 0.18 : 0.08——**三层同速等于没有视差**，
 * 所以比率必须严格单调，这里把它当常量钉住并单测。
 *
 * 每层只存**星点数组**，运行期逐星 drawRect。这里有过一次反向的"优化"：先前每层预烘焙成一张
 * 与逻辑画布等大的 Bitmap，理由是"别逐星 drawRect"。真机分段计量的结果是
 * {@code star=18.7ms/帧}——五层 × 纵向平铺两次 = **十次全屏 alpha 混合，每帧 5.1M 像素，
 * 只为画 154 个点**。稀疏内容要按内容计费，不是按画布计费；154 个 1~3px 的 drawRect
 * 在软件光栅下比十次全屏 blit 便宜两个数量级，"满屏 drawRect 会卡"的前提是数量真到满屏。
 */
public final class Starfield {

    public static final int LAYERS = 5;
    private static final float[] RATIOS = { 1.0f, 0.6f, 0.35f, 0.18f, 0.08f };
    /** 每层星数：越近的层星越少越亮，越远的层星越多越暗。 */
    private static final int[] DENSITY = { 10, 16, 26, 40, 62 };
    /** 0 = 1px 暗点，2 = 3px 亮点。 */
    private static final int[] STAR_PX = { 1, 1, 2, 2, 3 };

    /**
     * 星点专用画笔：不复用 {@link SpriteFactory#PIXEL}——它画位图时 color 无意义，改它的颜色是埋雷。
     * 也**不做成 static**：static 初始化会在任何一次类加载时 new Paint，把 {@code ratioOf} 这种
     * 纯算术出口从 JVM 单测里拽出来（点阵/比率的测试就是这么挂的）。首帧在渲染线程上建一次，
     * 之后一直是同一个对象，稳态仍然零分配。
     */
    private Paint starPaint;

    private final int[][] starX = new int[LAYERS][];
    private final int[][] starY = new int[LAYERS][];
    private final int[][] starColor = new int[LAYERS][];
    private final float[] offset = new float[LAYERS];

    private int spanW = -1, spanH = -1;
    /** 基准速度：逻辑像素/秒。 */
    public float baseSpeed = 26f;

    public static float ratioOf(int layer) {
        return layer < 0 || layer >= LAYERS ? 0f : RATIOS[layer];
    }

    /** 纵向平铺滚动：返回本帧的 y 位移。纯算术，单测覆盖。 */
    public static int tileTopY(float offset, int h) {
        int o = (int) (offset % h);
        if (o < 0) o += h;
        return o;
    }

    public void step(float dt) {
        for (int i = 0; i < LAYERS; i++) {
            offset[i] += baseSpeed * RATIOS[i] * dt;
            if (offset[i] > 1e6f) offset[i] %= Math.max(1, spanH);  // 防 float 精度耗尽
        }
    }

    /**
     * 尺寸变化后重撒星点。**不持有任何 Bitmap**，所以没有 recycle 的责任，也不占堆。
     * 分布按整块逻辑画布撒，纵向 wrap 回绕，因此滚动是无缝的。
     */
    public void rebuild(int w, int h, int qualityTier, int seed) {
        if (w == spanW && h == spanH && starX[0] != null) return;
        spanW = w;
        spanH = h;
        float keep = qualityTier >= QualityProfile.QUALITY_HIGH ? 1f
                : qualityTier >= QualityProfile.QUALITY_MID ? 0.7f : 0.45f;
        Random rnd = new Random(seed * 0x5DEECE66L + 11);
        for (int i = 0; i < LAYERS; i++) {
            int n = Math.max(0, Math.round(DENSITY[i] * keep));
            int[] xs = new int[n];
            int[] ys = new int[n];
            int[] cols = new int[n];
            for (int k = 0; k < n; k++) {
                xs[k] = rnd.nextInt(Math.max(1, w));
                ys[k] = rnd.nextInt(Math.max(1, h));
                // 近层亮、远层暗；再叠一层随机闪度
                float a = (0.30f + i * 0.16f) * (0.55f + rnd.nextFloat() * 0.45f);
                cols[k] = SpriteGrid.withAlpha(0xFFFFFFFF, Math.round(a * 255f));
            }
            starX[i] = xs;
            starY[i] = ys;
            starColor[i] = cols;
        }
    }

    public void draw(Canvas c) {
        int h = spanH;
        if (h <= 0) return;
        Paint p = starPaint;
        if (p == null) {
            p = new Paint();
            p.setAntiAlias(false);
            p.setFilterBitmap(false);
            p.setStyle(Paint.Style.FILL);
            starPaint = p;
        }
        for (int i = 0; i < LAYERS; i++) {
            int[] xs = starX[i];
            if (xs == null) continue;
            int[] ys = starY[i];
            int[] cols = starColor[i];
            int size = STAR_PX[i];
            int off = tileTopY(offset[i], h);
            for (int k = 0; k < xs.length; k++) {
                p.setColor(cols[k]);
                int x = xs[k];
                int y = ys[k] + off;
                if (y >= h) y -= h;
                c.drawRect(x, y, x + size, y + size, p);
                // 跨下边缘的那几颗补上顶部的一半，否则每层滚动到接缝处会"缺一条"
                if (y + size > h) c.drawRect(x, y - h, x + size, y - h + size, p);
            }
        }
    }

    public void release() {
        for (int i = 0; i < LAYERS; i++) {
            starX[i] = null;
            starY[i] = null;
            starColor[i] = null;
        }
        spanW = spanH = -1;
    }

    public boolean isReady() { return starX[0] != null; }

    /** 第 layer 层实际撒了多少颗星——单测用它钉"成本按星数计费"这条不变量。 */
    public int starCount(int layer) {
        return layer < 0 || layer >= LAYERS || starX[layer] == null ? 0 : starX[layer].length;
    }
}
