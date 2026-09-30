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
import com.flexiatom.pixelraider.game.TrendSampler;
import com.flexiatom.pixelraider.plat.Screen;
import org.junit.Test;

/**
 * 暂停面板的静态面——只钉"行数与动作码对得上"这一类跨包耦合。
 *
 * 绘制要 Canvas，实例化会当场撞上未 mock 的 android 桩（{@code returnDefaultValues = false}），
 * 所以这里一条都不碰 {@code new PauseScreen(...)}：类加载只有字符串数组，测得了。
 * 面板是激战中被反复拉开的那块，行数是 grid 的分母——{@link PauseLayout#CARDS} 改了而
 * {@link PauseScreen#L_CARD} 没跟着改，不是少一行字，是绘制线程每帧一次越界异常。
 */
public class PauseScreenTest {

    @Test
    public void cardLabelsMatchTheGridSize() {
        assertEquals(PauseLayout.CARDS, PauseScreen.L_CARD.length);
        assertEquals(PauseLayout.CARD_ROWS * PauseLayout.CARD_COLS, PauseScreen.L_CARD.length);
    }

    @Test
    public void tabLabelsMatchTheSamplerMetricCount() {
        assertEquals(TrendSampler.TABS, PauseScreen.L_TAB.length);
    }

    @Test
    public void labelsAreDistinctAndShortEnoughForTheirCells() {
        // 卡片列宽在矮屏上约 105px、tab 列宽约 71px；8px 汉字按 6 字与 4 字各留一档余量
        for (String card : PauseScreen.L_CARD) {
            assertTrue(card, card.length() >= 2 && card.length() <= 6);
        }
        for (String tab : PauseScreen.L_TAB) {
            assertTrue(tab, tab.length() >= 2 && tab.length() <= 4);
        }
        // 同名行在余光里读不出区别；卡片与 tab 之间也不许撞名（"击杀"与"击杀/秒"是两回事）
        for (int i = 0; i < PauseScreen.L_CARD.length; i++) {
            for (int j = i + 1; j < PauseScreen.L_CARD.length; j++) {
                assertNotEquals(PauseScreen.L_CARD[i], PauseScreen.L_CARD[j]);
            }
        }
        for (int i = 0; i < PauseScreen.L_TAB.length; i++) {
            for (int j = i + 1; j < PauseScreen.L_TAB.length; j++) {
                assertNotEquals(PauseScreen.L_TAB[i], PauseScreen.L_TAB[j]);
            }
        }
    }

    /**
     * 动作码必须互不相同，且 NONE 是 0：{@code Game} 用一串 if 比较它们，
     * 两个码撞在一起就等于其中一个出口永远走不到（而这类错在真机上表现为"点错了按钮"）。
     */
    @Test
    public void actionCodesDoNotCollide() {
        int[] codes = {PauseScreen.ACTION_NONE, PauseScreen.ACTION_RESUME,
                PauseScreen.ACTION_SETTINGS, PauseScreen.ACTION_RESTART, PauseScreen.ACTION_MENU};
        assertEquals(0, PauseScreen.ACTION_NONE);
        for (int i = 0; i < codes.length; i++) {
            for (int j = i + 1; j < codes.length; j++) {
                assertNotEquals(codes[i], codes[j]);
            }
        }
    }

    /**
     * 出口表的下标对应与"亮着必须有真动作"（2026-09-29 授权补，配套 {@code EXIT_LIVE}/
     * {@code EXIT_ACTION} 放宽到包内可见）。
     *
     * <p>钉三件事，全都是只改一行就能撞上的形态：
     * ① 两张表与 {@code PauseLayout} 的五枚出口同长——错一格就是把某枚出口的灰度配到了别人身上；
     * ② 灰度位与那三个具名布尔逐格对齐——{@code EXIT_LIVE} 里把 {@code MENU_LIVE} 写到 1 号格，
     *    画面上是"成就"能点而"返回主菜单"是灰的，JVM 与真机都不报错；
     * ③ 亮着的出口必须带真动作码——那句 javadoc 说"落地一个就把布尔翻成 true 并给真动作"，
     *    只翻布尔的结果是一颗淡入完成、按下去什么都不做的按钮（最恶性的是主操作那颗：
     *    {@code openPause} 已经把时钟冻住，出口全空就等于把玩家永久冻在面板上）。
     */
    @Test
    public void liveExitsAlwaysCarryARealActionAndGreyOnesLineUpWithTheirFlags() {
        // 五枚 = 继续作战/成就/设置/重开本局/返回主菜单，与 PauseLayout 那五个框一一对应
        // （drawn(i) 的 switch 是那条映射，drawnExitRectsNeverOverlap 逐框列了同样的五个）
        assertEquals(5, PauseScreen.EXIT_ACTION.length);
        assertEquals(PauseScreen.EXIT_ACTION.length, PauseScreen.EXIT_LIVE.length);

        assertTrue("主操作不许被灰度掉：那是面板上唯一的解冻路", PauseScreen.EXIT_LIVE[0]);
        assertEquals(PauseScreen.ACHIEVEMENTS_LIVE, PauseScreen.EXIT_LIVE[1]);
        assertEquals(PauseScreen.SETTINGS_LIVE, PauseScreen.EXIT_LIVE[2]);
        assertTrue("重开本局是第二条解冻路", PauseScreen.EXIT_LIVE[3]);
        assertEquals(PauseScreen.MENU_LIVE, PauseScreen.EXIT_LIVE[4]);

        for (int i = 0; i < PauseScreen.EXIT_LIVE.length; i++) {
            if (PauseScreen.EXIT_LIVE[i]) {
                assertNotEquals("第 " + i + " 枚出口亮着却没有真动作",
                        PauseScreen.ACTION_NONE, PauseScreen.EXIT_ACTION[i]);
            }
        }
    }

    /**
     * 五枚出口的**绘制框**在任何逻辑高上都不互相压。
     *
     * 这条是"按下先认绘制框、再认外扩框"那套两遍解析的前提：外扩到 48dp 之后行与行必然互压
     * （实测 40 逻辑像素的 touch floor 下压掉 18 格，而行间距只有 22 格），互压那一带归谁，
     * 全靠"绘制框不重叠"才有一个确定答案。MD3 的抬起提交读同一个解析结果——绘制框一旦重叠，
     * 抬手时会解析到另一枚，那一次"重开本局"就静默失效了。
     */
    @Test
    public void drawnExitRectsNeverOverlap() {
        PauseLayout box = new PauseLayout();
        for (int logicH = Screen.BATTLE_H; logicH <= Screen.LOGIC_H_MAX; logicH++) {
            // 出口那三行是从面板底往上贴的，安全区只动顶边；两种取值都要扫到
            box.layout(logicH, 0, 0);
            assertExitsDisjoint(box, logicH);
            box.layout(logicH, 0, 24);
            assertExitsDisjoint(box, logicH);
        }
    }

    private static void assertExitsDisjoint(PauseLayout box, int logicH) {
        RectI[] drawn = {box.resume, box.achievements, box.settings, box.restart, box.menu};
        for (int i = 0; i < drawn.length; i++) {
            for (int j = i + 1; j < drawn.length; j++) {
                assertTrue("logicH=" + logicH + " 第 " + i + " 与第 " + j + " 枚出口压上了",
                        !overlaps(drawn[i], drawn[j]));
            }
        }
    }

    private static boolean overlaps(RectI a, RectI b) {
        return Math.min(a.right, b.right) > Math.max(a.left, b.left)
                && Math.min(a.bottom, b.bottom) > Math.max(a.top, b.top);
    }
}
