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
package com.flexiatom.pixelraider.gfx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.flexiatom.pixelraider.game.Balance;
import com.flexiatom.pixelraider.game.Drops;
import com.flexiatom.pixelraider.ui.ShopLayout;
import org.junit.Test;

import java.lang.reflect.Field;

/**
 * 手工像素网格的结构检查（规格 §三：精灵由字符网格生成）。
 *
 * 遍历而不是手写清单：新增网格自动进入检查，改网格时少打一个字符会立刻红——
 * 那种错画出来是"飞机歪了半格"，肉眼在 11px 精灵上很难判，截图对比才发现。
 */
public class SpriteSheetsTest {

    @Test
    public void everySheetIsSquareWithUniformRowWidths() throws Exception {
        int checked = 0;
        for (Field f : SpriteSheets.class.getDeclaredFields()) {
            if (f.getType() != String[].class) continue;
            String[] rows = (String[]) f.get(null);
            checked++;
            String where = f.getName();
            assertEquals(where + " 必须是正方形网格", rows.length, SpriteGrid.widthOf(rows));
            for (int y = 0; y < rows.length; y++) {
                assertEquals(where + " 第 " + y + " 行宽度不一致（会让整行右移）",
                        rows.length, rows[y].length());
            }
        }
        assertTrue("没找到任何网格，遍历本身失效了", checked >= 6);
    }

    @Test
    public void everyCellIsTransparentOrAPaletteIndex() throws Exception {
        for (Field f : SpriteSheets.class.getDeclaredFields()) {
            if (f.getType() != String[].class) continue;
            String[] rows = (String[]) f.get(null);
            for (String row : rows) {
                for (int i = 0; i < row.length(); i++) {
                    char ch = row.charAt(i);
                    boolean ok = ch == '.' || (ch >= '0' && ch < '5');
                    assertTrue(f.getName() + " 含非法字符 '" + ch + "'（iconPalette 只有 5 档）", ok);
                }
            }
        }
    }

    @Test
    public void shipSpriteHasNoHoleInTheHull() {
        int[] pal = new int[5];
        Palette.iconPalette(C_SHIP, pal);
        int[] px = SpriteGrid.toPixels(SpriteSheets.SHIP, pal);
        int opaque = 0;
        for (int v : px) if (v != SpriteGrid.TRANSPARENT) opaque++;
        assertTrue("机体实心像素过少（" + opaque + "），网格八成写坏了", opaque >= 40);
    }

    private static final int C_SHIP = 0xFF00E5FF;

    /**
     * 实体表 ↔ 精灵表的一致性。
     *
     * 两张表各写一处，编译期抓不到：数组字面量长度不符只是多退少补，运行时才以
     * "精英怪画成了直線怪" 的形式暴露。绘制尺寸那半条更隐蔽——13 格的图放大到 15，
     * 最近邻缩放会让一部分像素宽一格、一部分宽两格，看着就是"这飞机糊了"。
     */
    @Test
    public void entityTablesAndSpriteTablesLineUp() {
        assertEquals("敌人表与精灵表长度不同", Balance.enemies.length, SpriteSheets.ENEMY_SHEETS.length);
        assertEquals("敌人表与 id 表长度不同", Balance.enemies.length, SpriteSheets.ENEMY_IDS.length);
        assertEquals(Balance.bosses.length, SpriteSheets.BOSS_SHEETS.length);
        assertEquals(Balance.bosses.length, SpriteSheets.BOSS_IDS.length);
        assertEquals(Drops.KIND_COUNT, SpriteSheets.DROP_SHEETS.length);
        assertEquals(Drops.KIND_COUNT, SpriteSheets.DROP_IDS.length);

        for (int k = 0; k < Balance.enemies.length; k++) {
            checkSquareSheet(Balance.enemies[k].name, SpriteSheets.ENEMY_SHEETS[k]);
        }
        for (int k = 0; k < Balance.bosses.length; k++) {
            checkSquareSheet(Balance.bosses[k].name, SpriteSheets.BOSS_SHEETS[k]);
        }
        for (int k = 0; k < Drops.KIND_COUNT; k++) {
            checkSquareSheet("掉落 " + Drops.label(k), SpriteSheets.DROP_SHEETS[k]);
        }
        assertIdsAreUnique(SpriteSheets.ENEMY_IDS);
        assertIdsAreUnique(SpriteSheets.BOSS_IDS);
        assertIdsAreUnique(SpriteSheets.DROP_IDS);
    }

    /**
     * 商店卡表 ↔ 图标表的一致性。
     *
     * <p>{@code SHOP_ICON_IDS} 上**不**跑唯一性断言：维修/护盾/弹药/超载四张卡故意复用掉落的那
     * 几个 id（同一形状的第二处引用），撞 key 只在"两个不同形状共用一个 id"时才致命。
     */
    @Test
    public void shopCardTablesLineUpWithTheCardList() {
        assertEquals(Balance.shopCards.length, SpriteSheets.SHOP_ICON_SHEETS.length);
        assertEquals(Balance.shopCards.length, SpriteSheets.SHOP_ICON_IDS.length);
        for (int k = 0; k < Balance.shopCards.length; k++) {
            checkSquareSheet("商店卡 " + Balance.shopCards[k].name, SpriteSheets.SHOP_ICON_SHEETS[k]);
            assertEquals("第 " + k + " 张卡的 iconId 与图标表错位（会画成隔壁那张的图）",
                    SpriteSheets.SHOP_ICON_IDS[k], Balance.shopCards[k].iconId);
            assertTrue(SpriteSheets.shopIconSheet(k) == SpriteSheets.SHOP_ICON_SHEETS[k]);
            assertEquals(SpriteSheets.shopIconId(k), SpriteSheets.SHOP_ICON_IDS[k]);
        }
    }

    /** 两个实体共用一个 sprite id = 在 SpriteFactory 的缓存里撞 key，会画成对方的图。 */
    private static void assertIdsAreUnique(int[] ids) {
        for (int i = 0; i < ids.length; i++) {
            for (int j = i + 1; j < ids.length; j++) {
                assertTrue("sprite id " + ids[i] + " 被用了两次", ids[i] != ids[j]);
            }
        }
    }

    @Test
    public void drawSizesAreWholeMultiplesOfTheirGrid() {
        for (int k = 0; k < Balance.enemies.length; k++) {
            assertWholeScale(Balance.enemies[k].name, Balance.enemies[k].spriteSize,
                    SpriteSheets.ENEMY_SHEETS[k]);
        }
        for (int k = 0; k < Balance.bosses.length; k++) {
            assertWholeScale(Balance.bosses[k].name, Balance.bosses[k].spriteSize,
                    SpriteSheets.BOSS_SHEETS[k]);
        }
        for (int k = 0; k < Drops.KIND_COUNT; k++) {
            assertWholeScale("掉落 " + Drops.label(k), Balance.drop.drawSize,
                    SpriteSheets.DROP_SHEETS[k]);
        }
    }

    /**
     * 商店卡图标的整数倍哨兵。
     *
     * <p>卡面图标位是 18px：自建的那批（火力/射速/会心/推进/装甲/磁吸）是 9×9 网格，2 倍正好；
     * 补给类（生命/护盾/炸弹/能量）和金币沿用掉落那批 7×7 网格，18 不是 7 的整数倍，像素点会踩半格。
     * <p><b>归属分两半（2026-09-27 二次核，推翻我 09-27 早先那一版）</b>：
     * 「补给卡图标没有问题」<b>是用户逐字</b>——L12803（{@code queued_command} /
     * {@code origin.kind=human}，UTC 2026-09-24T16:43:17.604Z ＝<b>本地</b> 09-25T00:43），
     * 那条同时给了「火力强化取消百分比和上限，改为固定的+1」。
     * 但<b>「在实机上看过货架」这个前提交由这条记录不能确立</b>：「货架」「实机」在全部第一手
     * 通道里 0 命中，而 L12803 早于他声明「真机已连接」（L13322，本地 09-25T13:33）。
     * ⚠ 早先那一版我写着"整句查无第一手"，那是<b>我的取证工具有 bug</b>造成的假阴性
     * （读了 {@code attachment.content} 而真键是 {@code attachment.prompt}；又把
     * {@code message.content} 当块数组、漏掉它是裸字符串的那些记录），不是证据。
     * <p>不改成 14/21 这个<b>动作</b>与那条理由（18px 下踩半格可接受）是我的；
     * 图标本身没有问题这个<b>判断</b>有他的字。
     * 因此这里不把尺寸改成 14 或 21，只把这项偏离钉成白名单——换成别的网格尺寸（8×8、11×11…）
     * 就是新的一次偏离，得红。
     */
    @Test
    public void shopIconsAreWholeScaledOrOnTheSignedDeviationList() {
        for (int k = 0; k < SpriteSheets.SHOP_ICON_SHEETS.length; k++) {
            String[] rows = SpriteSheets.SHOP_ICON_SHEETS[k];
            String name = "商店卡 " + Balance.shopCards[k].name;
            int grid = SpriteGrid.widthOf(rows);
            if (ShopLayout.ICON % grid == 0) {
                assertWholeScale(name, ShopLayout.ICON, rows);
            } else {
                assertEquals(name + " 的 " + grid + "×" + grid + " 网格不在已签字的 7×7 例外里",
                        7, grid);
                assertTrue(name + " 图标位连网格都装不下", ShopLayout.ICON >= grid);
            }
        }
    }

    /** 越界退回第 0 张而不是抛：渲染中途抛异常会整帧中断，宁画画错也不崩。 */
    @Test
    public void outOfRangeLookupsFallBackWithoutThrowing() {
        assertTrue(SpriteSheets.enemySheet(999) == SpriteSheets.ENEMY_SHEETS[0]);
        assertTrue(SpriteSheets.bossSheet(-1) == SpriteSheets.BOSS_SHEETS[0]);
        assertTrue(SpriteSheets.dropSheet(999) == SpriteSheets.DROP_SHEETS[0]);
    }

    private static void checkSquareSheet(String name, String[] rows) {
        assertEquals(name + " 的精灵必须是正方形", rows.length, SpriteGrid.widthOf(rows));
    }

    private static void assertWholeScale(String name, int drawSize, String[] rows) {
        int grid = SpriteGrid.widthOf(rows);
        assertTrue(name + " 绘制尺寸 " + drawSize + " 小于网格 " + grid, drawSize >= grid);
        assertEquals(name + " 绘制尺寸 " + drawSize + " 不是网格 " + grid + " 的整数倍",
                0, drawSize % grid);
    }
}
