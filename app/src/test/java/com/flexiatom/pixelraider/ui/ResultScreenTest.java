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

import com.flexiatom.pixelraider.game.Rating;
import org.junit.Test;

/**
 * 结算页的两张标签表——只钉"行数与标签对得上"这一件事。
 *
 * 绘制本身要 Canvas，测不了；但行数是**跨包的耦合**：{@link RevealScript#CARD_ROWS} 加一行、
 * {@link Rating} 加一维而这里没跟着加，结果不是少一行字，而是绘制线程每帧一次越界异常。
 * 类加载不碰 android（静态区只有字符串），所以这一条测得了。
 */
public class ResultScreenTest {

    @Test
    public void cardLabelsMatchTheRevealRowCount() {
        assertEquals(RevealScript.CARD_ROWS, ResultScreen.L_CARD.length);
    }

    @Test
    public void dimLabelsMatchTheRatingDimensionCount() {
        assertEquals(Rating.COUNT, ResultScreen.L_DIM.length);
    }

    @Test
    public void labelsFitTheLabelColumnAndDoNotCollide() {
        // 五维标签要和条体同列：LABEL_W = 16 = 两个 8px 汉字，第三个字就会骑到条上
        for (String dim : ResultScreen.L_DIM) {
            assertEquals(dim, 2, dim.length());
        }
        for (String card : ResultScreen.L_CARD) {
            assertTrue(card, card.length() >= 2);
        }
        // 两张表内部不许重名；同名行在余光里读不出区别
        for (int i = 0; i < ResultScreen.L_DIM.length; i++) {
            for (int j = i + 1; j < ResultScreen.L_DIM.length; j++) {
                assertNotEquals(ResultScreen.L_DIM[i], ResultScreen.L_DIM[j]);
            }
        }
        for (int i = 0; i < ResultScreen.L_CARD.length; i++) {
            for (int j = i + 1; j < ResultScreen.L_CARD.length; j++) {
                assertNotEquals(ResultScreen.L_CARD[i], ResultScreen.L_CARD[j]);
            }
        }
    }
}
