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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 图标缓存 key 的变体区分（规格 §三"缓存 key 必须含目标尺寸"的延伸）。
 *
 * 真实事故形状：key 写成 {@code keyOf(id,w,h) ^ (0xFF000000L & color)}，
 * 不透明色的那一项永远等于 0xFF000000——换主题色后命中的仍是旧色的图，
 * 表现是"改了颜色，图标不动"，且只有第二次进入同一尺寸时才看得到。
 */
public class SpriteCacheKeyTest {

    @Test
    public void opaqueColoursDoNotCollapseToTheSameKey() {
        int cyan = 0xFF00E5FF, magenta = 0xFFFF3BD7;
        long naiveCyan = OffscreenCache.keyOf(3, 16, 16) ^ (0xFF000000L & cyan);
        long naiveMagenta = OffscreenCache.keyOf(3, 16, 16) ^ (0xFF000000L & magenta);
        assertEquals("旧写法确实撞了（这条断言用来钉住事故形状）", naiveCyan, naiveMagenta);
        assertNotEquals(OffscreenCache.keyOf(3, 16, 16, ColorTags.tagOf(cyan)),
                OffscreenCache.keyOf(3, 16, 16, ColorTags.tagOf(magenta)));
    }

    @Test
    public void colorTagIsStableAndDistinctWithinCapacity() {
        int a = ColorTags.tagOf(0xFF00E5FF);
        assertEquals(a, ColorTags.tagOf(0xFF00E5FF));
        int b = ColorTags.tagOf(0xFFFF3BD7);
        assertNotEquals(a, b);
        assertTrue(b < OffscreenCache.MAX_TAGS);
    }

    @Test
    public void tagIdAndSizeAllMoveTheKey() {
        long k = OffscreenCache.keyOf(3, 16, 16, 1);
        assertNotEquals(k, OffscreenCache.keyOf(4, 16, 16, 1));
        assertNotEquals(k, OffscreenCache.keyOf(3, 17, 16, 1));
        assertNotEquals(k, OffscreenCache.keyOf(3, 16, 17, 1));
        assertNotEquals(k, OffscreenCache.keyOf(3, 16, 16, 0));
        assertTrue(OffscreenCache.distinctSizeKeys(3, 16, 16, 17));
    }
}
