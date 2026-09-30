/*
 * PIXEL RAIDER — 原生 Android 纵版弹幕射击
 * Copyright (C) 2026 Flexiatom
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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.flexiatom.pixelraider.core.RectI;
import com.flexiatom.pixelraider.gfx.BitmapFont;
import org.junit.Test;

/**
 * 主菜单页的**静态面**——绘制要 Canvas，测不了；但字面量与 {@link MenuLayout} 的带区是跨文件耦合：
 * 有人把「开始新一局」加长一个字，或把标题换成汉字，画面不会报错，只会把字切掉或者画成空白。
 *
 * <p>这里不 {@code new MenuScreen(...)}：构造它会建 {@link DrawKit}，而它的 Paint 在未 mock 的
 * android 桩上是会炸的。只读静态字段不需要实例。
 */
public class MenuScreenTest {

    private final RectI run = new RectI();
    private final RectI growth = new RectI();
    private final RectI unused = new RectI();

    @Test
    public void actionCodesAreDistinctAndLeaveZeroForIdle() {
        assertEquals(0, MenuScreen.ACTION_NONE);
        assertEquals(1, MenuScreen.ACTION_RUN);
        assertEquals(2, MenuScreen.ACTION_GROWTH);
        assertNotEquals(MenuScreen.ACTION_RUN, MenuScreen.ACTION_GROWTH);
        // 「谁都不做」必须是 0——Game 里那串 if 靠 0 走 fallthrough
        assertTrue(MenuScreen.ACTION_NONE < MenuScreen.ACTION_RUN);
    }

    @Test
    public void buttonLabelsFitTheirOwnHalfOfTheRow() {
        MenuLayout.buttons(run, growth);
        // 半宽 108：五个 12px 汉字（60）还留着余量，第七个字才开始压到邻钮
        assertEquals(5, MenuScreen.L_RUN.length());
        assertEquals(2, MenuScreen.L_GROWTH.length());
        assertTrue(MenuScreen.L_RUN, MenuScreen.L_RUN.length() * Md3.PX_BODY <= run.width());
        assertTrue(MenuScreen.L_GROWTH, MenuScreen.L_GROWTH.length() * Md3.PX_BODY <= growth.width());
        assertNotEquals("两枚按钮同名 → 余光里读不出主次",
                MenuScreen.L_RUN, MenuScreen.L_GROWTH);
        assertTrue("按钮不该超出自己那半行", run.right <= growth.left);
    }

    @Test
    public void titleIsPureAsciiAndFitsInsideTheMargins() {
        // 标题走点阵而不是内嵌中文字体：点阵只覆盖 32..126，越界字符按空格走位（画出来是一段空白）
        HudText hud = new HudText(32);
        hud.reset().text(MenuScreen.L_TITLE);
        assertEquals("标题不许被 HudText 的容量截掉", MenuScreen.L_TITLE.length(), hud.length());
        for (int i = 0; i < MenuScreen.L_TITLE.length(); i++) {
            char ch = MenuScreen.L_TITLE.charAt(i);
            assertTrue("点阵通道不认这个字符: " + ch, ch >= 32 && ch <= 126);
        }
        int w = BitmapFont.textWidth(hud.buffer(), hud.length(), 2);
        assertEquals("12 字符 × (5+1) − 1 = 71，放大两倍 142", 142, w);
        // 居中后仍要留在左右 8px 边距内（240 − 8 − 8 = 224）
        assertTrue("标题溢出边距", w <= MenuLayout.RIGHT - MenuLayout.LEFT);
        int midX = (MenuLayout.LEFT + MenuLayout.RIGHT) / 2;
        unused.set(midX - w / 2, 0, midX + w / 2, 0);
        assertTrue(unused.left >= MenuLayout.LEFT);
        assertTrue(unused.right <= MenuLayout.RIGHT);
    }
}
