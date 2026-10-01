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

/**
 * 特效统一注册表（规格 §三）：11 类特效注册到一处，**图层顺序是显式 order 字段，
 * 不是 render() 里的调用先后**。
 *
 * 理由：靠调用顺序隐含层级，意味着"把某个特效挪一层"要挪代码块；而注册表 + order 让
 * 层级成为数据，新增特效只需注册一条，画漏一层也会在 validate 阶段暴露成"注册了但没画"。
 *
 * 排序只在注册时做一次（插入排序进定长数组），每帧绘制路径上不排序、不迭代器、不分配。
 * order 相同时按注册先后稳定排列——同层的 muzzle 与 flash 谁先画不该随 HashMap 顺序变。
 */
public final class FxRegistry {

    public interface Layer {
        int order();
        void draw(Canvas canvas);
        boolean isEmpty();
    }

    /** 规格 §三列出的 11 类；数值即默认 order，间隔留 10 便于日后插层。 */
    public static final int ORDER_NOVAS = 10;
    public static final int ORDER_SHOCKWAVES = 20;
    public static final int ORDER_ARCS = 30;
    public static final int ORDER_BLASTS = 40;
    public static final int ORDER_DEBRIS = 50;
    public static final int ORDER_PARTICLES = 60;
    public static final int ORDER_EMBERS = 70;
    public static final int ORDER_SPARKS = 80;
    public static final int ORDER_MUZZLES = 90;
    public static final int ORDER_FLASHES = 100;
    public static final int ORDER_FLOAT_TEXTS = 110;

    public static final int MAX_LAYERS = 24;

    private final Layer[] layers = new Layer[MAX_LAYERS];
    private int count;
    private final Layer[] skipped = new Layer[MAX_LAYERS]; // 诊断用：本帧空层
    private int skippedCount;

    public void register(Layer layer) {
        if (layer == null || count == MAX_LAYERS) return;
        int o = layer.order();
        int i = count;
        while (i > 0 && layers[i - 1].order() > o) {   // > 而非 >=：同 order 保持注册先后
            layers[i] = layers[i - 1];
            i--;
        }
        layers[i] = layer;
        count++;
    }

    public int layerCount() { return count; }

    /** 第 i 层（按 order 升序）。 */
    public Layer layerAt(int i) {
        return i < 0 || i >= count ? null : layers[i];
    }

    /** 按 order 升序绘制，跳过空层。整帧零分配：不建列表、不装箱。 */
    public void drawAll(Canvas canvas) {
        skippedCount = 0;
        for (int i = 0; i < count; i++) {
            Layer l = layers[i];
            if (l.isEmpty()) {
                skipped[skippedCount++] = l;
                continue;
            }
            l.draw(canvas);
        }
    }

    /** 诊断：本帧被跳过的空层数（HUD 调试行用）。 */
    public int emptyLayersThisFrame() { return skippedCount; }

    /** 世界重置时清空所有层的活跃计数（层自己实现）。 */
    public void resetAll() {
        for (int i = 0; i < count; i++) {
            if (layers[i] instanceof Resettable) ((Resettable) layers[i]).resetFx();
        }
    }

    public interface Resettable {
        void resetFx();
    }
}
