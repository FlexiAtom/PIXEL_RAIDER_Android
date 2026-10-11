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
package com.flexiatom.pixelraider.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.flexiatom.pixelraider.core.RectI;
import com.flexiatom.pixelraider.game.SaveSlots;
import com.flexiatom.pixelraider.gfx.BitmapFont;
import com.flexiatom.pixelraider.plat.Screen;
import org.junit.Test;

/**
 * 存档页几何。期望值**手推写死**（照 {@link ShopLayoutTest} 的规矩）。
 *
 * <p>这一屏的重灾区不是"按钮挤不挤"而是**分页算术**：50 个槽、每页几行、末页剩几行、页号越界钳到
 * 哪一页。那些数错一格的画面是"第二页第一行点下去命中的是第一页的槽"——截图上两页长得一模一样，
 * 只有按下之后少掉一份档才发现。所以这里的页账全部钉成等式，不钉成"看起来对"。
 */
public class SaveLayoutTest {

    /** 手推：内容列 216 = 3×69 + 2×4 + 1（末列吸收余数），列位与 {@link #theTwoRowsAreThreeCellsWithUniformGutters} 同一次推导。 */
    private static final int COL0_L = 12, COL0_R = 81;
    private static final int COL1_L = 85, COL1_R = 154;
    private static final int COL2_L = 158, COL2_R = 228;

    private static void assertRect(String what, RectI r, int left, int top, int right, int bottom) {
        assertEquals(what + " left", left, r.left);
        assertEquals(what + " top", top, r.top);
        assertEquals(what + " right", right, r.right);
        assertEquals(what + " bottom", bottom, r.bottom);
    }

    /**
     * 每页几行是**反解**出来的，页高是它的推论，两者必须回头一致。
     *
     * <p>手推：{@code pageH(rows) = 60 + rows·24 + (rows−1)·2 + 4 + 20 + 4 + 18}。
     * rows=8 → 266 + 46 = 312 ≤ 320；rows=9 → 292 + 46 = 338 &gt; 320。所以 8 是"最矮画布装得下"
     * 的最大值，而 {@link SaveLayout#PAGE_H} 用的就是它。
     *
     * <p>这条钉的是那一整条推导链的**闭合**：{@code Game.render} 拿 {@code pageTop(PAGE_H)} 平移整页，
     * 而 {@code SaveScreen.layout} 把同一批矩形平移到屏幕坐标。要是 PAGE_H 与实际末行底边不一致，
     * 翻页行就会有一半悬在 pageTop + PAGE_H 之外——外面那块在遮罩**之下**，玩家看得见却点不动。
     */
    @Test
    public void thePageHeightIsDerivedFromTheShortestCanvasNotChosen() {
        assertEquals("反解结果：8 行", 8, SaveLayout.ROWS_PER_PAGE);
        assertEquals("9 行越过最矮画布，所以它不该被解出来", 338, SaveLayout.pageH(9));
        assertTrue("9 行必须装不下，否则每页几行就不是反解而是随手挑的",
                SaveLayout.pageH(9) > Screen.LOGIC_H_MIN);
        assertEquals("整页高 = 8 行那一档", SaveLayout.pageH(8), SaveLayout.PAGE_H);
        assertEquals(312, SaveLayout.PAGE_H);
        assertTrue("固定页越过最矮画布：矮屏装不下自己的命中框",
                SaveLayout.PAGE_H <= Screen.LOGIC_H_MIN);
        assertEquals("翻页行底边就是页底，不留一条没人点的空地",
                SaveLayout.PAGE_H, SaveLayout.NAV_BOTTOM);
    }

    /** 行账的边界：0 行不塌成负高，越界的请求至少还剩一行可点（空架画一张全空列表，不是崩）。 */
    @Test
    public void theRowCountNeverGoesNegativeOrZero() {
        assertEquals("0 行时列表塌成一条线，不倒翻", SaveLayout.LIST_TOP, SaveLayout.listBottom(0));
        assertEquals(1, SaveLayout.rowsFitting(0));
        assertEquals(1, SaveLayout.rowsFitting(1));
        assertEquals(8, SaveLayout.rowsFitting(Screen.LOGIC_H_MIN));
        assertEquals(8, SaveLayout.rowsFitting(SaveLayout.PAGE_H));
        for (int rows = 0; rows <= 12; rows++) {
            assertTrue("rows=" + rows + " 列表底边越过了页底",
                    SaveLayout.listBottom(rows) <= SaveLayout.pageH(rows));
            assertTrue("rows=" + rows + " 页高倒翻", SaveLayout.pageH(rows) > 0);
        }
    }

    /**
     * 槽数只有一处真源：槽位账本的上限。版面抄一份 50，就等于给"改上限忘改列表"留一条**画得出来
     * 但存不进去**的路（{@code SaveSlots.allocateFree} 按账本发号，列表按自己的 50 摆行）。
     *
     * <p>顺带钉住 {@code 50 ≤ 64} 那格运气：占用状态是一枚 long 掩码，上限一旦涨到 65，
     * 掩码就要换成两枚或 {@code long[]}，落盘接口签名跟着动。这条断言不替谁做决定，只是在越界
     * 那天让测试先响，而不是让第 65 槽永远分配不出去。
     */
    @Test
    public void theSlotCountComesFromTheLedger() {
        assertEquals(SaveSlots.CAPACITY, SaveLayout.SLOTS);
        assertEquals(50, SaveLayout.SLOTS);
        assertTrue("槽位掩码是一枚 long：" + SaveLayout.SLOTS + " 已经贴到上限",
                SaveLayout.SLOTS <= Long.SIZE);
    }

    /**
     * 分页账：每一页接住上一页的末行，全架恰好列一遍，末页不满但不为空。
     *
     * <p>手推：{@code PAGES = ⌈50/8⌉ = 7}，末页 {@code pageStart = 48}、{@code shown = 2}。
     * 这条是整份列表里唯一一处"错一格就漏一槽"的算术——漏掉的那一槽在账本里占着位（掩码说它满），
     * 列表却永远翻不到它：那份档删不掉、读不出，只能等上限改动。
     */
    @Test
    public void everySlotIsListedExactlyOnceAcrossPages() {
        assertEquals(7, SaveLayout.PAGES);
        int seen = 0;
        for (int page = 0; page < SaveLayout.PAGES; page++) {
            assertEquals("第 " + page + " 页的起始槽不接续",
                    seen, SaveLayout.pageStart(page));
            int shown = SaveLayout.shownOn(page);
            assertTrue("第 " + page + " 页一行都不摆", shown > 0);
            assertTrue("第 " + page + " 页摆超出每页上限", shown <= SaveLayout.ROWS_PER_PAGE);
            assertEquals("第 " + page + " 页越界列到全架之外",
                    Math.min(SaveLayout.ROWS_PER_PAGE, SaveLayout.SLOTS - seen), shown);
            seen += shown;
        }
        assertEquals("全架列了一遍少一槽或多一槽", SaveLayout.SLOTS, seen);
        assertEquals("末页只有两行", 2, SaveLayout.shownOn(SaveLayout.PAGES - 1));
    }

    /**
     * 每一槽恰好落在某一页上——不重不漏，且<b>末页那一侧的边界是开口的</b>。
     *
     * <p>这条钉的是存档页三枚改数据的键的受理闸（看不见目标的那一格不许是活的）。它有两种错法，
     * 表现完全不同：把 {@code <} 写成 {@code <=} 会让末页"多出一个不存在的第 3 行"，玩家按删除
     * 打到一个从没画过的槽上；而漏判（区间收得太窄）会让选中格永远点不动那三枚键。
     * 越界的槽号（-1＝没选中、{@code SLOTS}＝脏数据）一律假，不抛。
     */
    @Test
    public void everySlotBelongsToExactlyOnePage() {
        for (int slot = 0; slot < SaveLayout.SLOTS; slot++) {
            int hits = 0;
            for (int page = 0; page < SaveLayout.PAGES; page++) {
                if (SaveLayout.onPage(page, slot)) hits++;
            }
            assertEquals("槽 " + slot + " 不在任何一页上", 1, hits);
        }
        assertFalse("没选中（NONE）不该被算成看得见", SaveLayout.onPage(0, SaveSlots.NONE));
        assertFalse(SaveLayout.onPage(SaveLayout.PAGES - 1, SaveSlots.NONE));
        assertFalse("全架之外的槽号不该落在任何一页", SaveLayout.onPage(SaveLayout.PAGES - 1, SaveLayout.SLOTS));
        assertTrue("末页最后一行该是活的（末页只有两行，最容易收错的就是这里）",
                SaveLayout.onPage(SaveLayout.PAGES - 1, SaveLayout.SLOTS - 1));
        assertFalse("第一页的末行不许认第二页的第一槽",
                SaveLayout.onPage(0, SaveLayout.ROWS_PER_PAGE));
        assertTrue(SaveLayout.onPage(1, SaveLayout.ROWS_PER_PAGE));
    }

    /** 越界页号钳到边界页，不返回空架也不抛：页号来自持久化过一次的面板状态，重启后可能比 PAGES 大。 */
    @Test
    public void outOfRangePagesClampInsteadOfEmptyingTheList() {
        assertEquals(0, SaveLayout.clampPage(-5));
        assertEquals(0, SaveLayout.clampPage(0));
        assertEquals(SaveLayout.PAGES - 1, SaveLayout.clampPage(SaveLayout.PAGES));
        assertEquals(SaveLayout.PAGES - 1, SaveLayout.clampPage(9999));
        assertEquals("pageStart 自己走同一道钳，不在调用方数一遍",
                SaveLayout.pageStart(SaveLayout.PAGES - 1), SaveLayout.pageStart(9999));
        assertTrue(SaveLayout.shownOn(-1) > 0);
    }

    /**
     * 纵向分带：标题 → 读数 → 八行 → 动作行 → 翻页行，谁也不骑到谁上，行间距恒定。
     *
     * <p>末行底边 266 与 {@code ACTION_TOP} 270 之间那条 4 格的缝就是全部余量：这页在 312 格里
     * 已经排满，任何一带"多留一点呼吸位"都会把翻页行挤出页底（页高不随画布长高，见类头）。
     */
    @Test
    public void theBandsTileThePageWithoutCrossing() {
        RectI r = new RectI();
        SaveLayout.titleRect(r);
        assertRect("title", r, 8, 10, 232, 38);
        SaveLayout.metaRect(r);
        assertRect("meta", r, 12, 42, 228, 56);
        assertTrue("读数压进列表", SaveLayout.META_BOTTOM <= SaveLayout.LIST_TOP);
        for (int i = 0; i < SaveLayout.ROWS_PER_PAGE; i++) {
            SaveLayout.rowRect(i, r);
            assertEquals("第 " + i + " 行行高不是 24", SaveLayout.ROW_H, r.height());
            assertEquals("第 " + i + " 行左边不在行底上", SaveLayout.LEFT, r.left);
            assertEquals("第 " + i + " 行右边不在行底上", SaveLayout.RIGHT, r.right);
            assertEquals("第 " + i + " 行的顶边不等距", SaveLayout.LIST_TOP + i * (SaveLayout.ROW_H + SaveLayout.ROW_GAP), r.top);
            if (i > 0) {
                RectI prev = new RectI();
                SaveLayout.rowRect(i - 1, prev);
                assertEquals("第 " + i + " 行与上一行间距变了", SaveLayout.ROW_GAP, r.top - prev.bottom);
            }
        }
        SaveLayout.rowRect(SaveLayout.ROWS_PER_PAGE - 1, r);
        assertEquals("末行底边该正好落在列表底", SaveLayout.listBottom(SaveLayout.ROWS_PER_PAGE), r.bottom);
        assertEquals("末行与动作行之间只剩一道 ACTION_GAP",
                SaveLayout.ACTION_TOP, r.bottom + SaveLayout.ACTION_GAP);
        assertEquals("动作行底边 + NAV_GAP = 翻页行顶边",
                SaveLayout.NAV_TOP, SaveLayout.ACTION_BOTTOM + SaveLayout.NAV_GAP);
        assertEquals("翻页行底边 = 页底", SaveLayout.PAGE_H, SaveLayout.NAV_BOTTOM);
    }

    /**
     * 行下标越界钳到页内首末行，**不返回翻转矩形**。
     *
     * <p>{@code RectI.contains} 在 {@code right < left} 时恒假，而绘制端 {@code drawRect} 对反向矩形
     * 静默吞掉：命中与画面同时失效，表现是"末页那两行下面那行点不动"。钳位让两处读到同一份越界结果。
     */
    @Test
    public void offByOneRowIndicesClampInsteadOfInverting() {
        RectI a = new RectI();
        RectI b = new RectI();
        SaveLayout.rowRect(-3, a);
        SaveLayout.rowRect(0, b);
        assertRect("越界负下标钳到首行", a, b.left, b.top, b.right, b.bottom);
        SaveLayout.rowRect(SaveLayout.ROWS_PER_PAGE + 4, a);
        SaveLayout.rowRect(SaveLayout.ROWS_PER_PAGE - 1, b);
        assertRect("越界正下标钳到末行", a, b.left, b.top, b.right, b.bottom);
        assertTrue("钳完还是反向矩形", a.bottom > a.top && a.right > a.left);
    }

    /**
     * 动作行与翻页行：同一组列位、两条不同的带。间隙 4、列宽 69/69/70（216 = 3×69 + 2×4 + 1）。
     *
     * <p>与暂停页第三行的**不同**处写在 {@link SaveLayout#CELLS} 那条注释旁边：那里内容列 212
     * 整除成三格 68，这里 216 除不尽，余数由末列吸收。吸收的是**格宽**不是**间距**——两两之间
     * 仍是恒定的 4 格缝，而每枚键的字按自己那格的中心排（{@code bakedCentered(r.centerX())}），
     * 所以读者看到的只有"最右那格宽一像素"，没有偏心。这一行有边框，偏心缝才是看得见的东西。
     */
    @Test
    public void theTwoRowsAreThreeCellsWithUniformGutters() {
        RectI a0 = new RectI(), a1 = new RectI(), a2 = new RectI();
        RectI n0 = new RectI(), n1 = new RectI(), n2 = new RectI();
        SaveLayout.actionCell(0, true, a0);
        SaveLayout.actionCell(1, true, a1);
        SaveLayout.actionCell(2, true, a2);
        SaveLayout.navCell(0, n0);
        SaveLayout.navCell(1, n1);
        SaveLayout.navCell(2, n2);
        assertRect("载入", a0, COL0_L, SaveLayout.ACTION_TOP, COL0_R, SaveLayout.ACTION_BOTTOM);
        assertRect("存档", a1, COL1_L, SaveLayout.ACTION_TOP, COL1_R, SaveLayout.ACTION_BOTTOM);
        assertRect("删除", a2, COL2_L, SaveLayout.ACTION_TOP, COL2_R, SaveLayout.ACTION_BOTTOM);
        assertRect("上一页", n0, COL0_L, SaveLayout.NAV_TOP, COL0_R, SaveLayout.NAV_BOTTOM);
        assertRect("返回", n1, COL1_L, SaveLayout.NAV_TOP, COL1_R, SaveLayout.NAV_BOTTOM);
        assertRect("下一页", n2, COL2_L, SaveLayout.NAV_TOP, COL2_R, SaveLayout.NAV_BOTTOM);
        assertEquals("动作行左缝变了", SaveLayout.CELL_GAP, a1.left - a0.right);
        assertEquals("动作行右缝变了", SaveLayout.CELL_GAP, a2.left - a1.right);
        assertEquals("翻页行左缝变了", SaveLayout.CELL_GAP, n1.left - n0.right);
        assertEquals("翻页行右缝变了", SaveLayout.CELL_GAP, n2.left - n1.right);
        assertEquals("三格铺满内容列", SaveLayout.CONTENT_LEFT, a0.left);
        assertEquals("三格铺满内容列", SaveLayout.CONTENT_RIGHT, a2.right);
        assertEquals("两行的列位一模一样（翻页不动横账）", a1.left, n1.left);
    }

    /**
     * 只读宿主（从主菜单进页，没有"正在打的这一局"可拍）：写入格**不摆**，动作行重新摊成两格。
     *
     * <p>空矩形是"非战役局：已裁＝不存在"那一条的同形手法（{@code PauseLayout} 的 campaign/debug
     * 就是这么处理的），换的是"这一格压根不存在"而不是"这一格灰着"——灰着的按钮比没有按钮更糟。
     *
     * <p>两格形**不是**把三格形中间那一格抹掉：那样左右两格各自留在原位，中间空出一道 69 宽的洞，
     * 读起来像"这里少了一个按钮"。这里两格重新铺满同一条内容列（216 = 2×106 + 4），缝仍是那个 4。
     */
    @Test
    public void readOnlyHostDropsTheWriteCellAndRepacksTheRow() {
        RectI load = new RectI(), save = new RectI(), del = new RectI();
        SaveLayout.actionCell(0, false, load);
        SaveLayout.actionCell(1, false, save);
        SaveLayout.actionCell(2, false, del);
        assertRect("载入（两格形）", load, 12, SaveLayout.ACTION_TOP, 118, SaveLayout.ACTION_BOTTOM);
        assertRect("删除（两格形）", del, 122, SaveLayout.ACTION_TOP, 228, SaveLayout.ACTION_BOTTOM);
        assertEquals("不摆＝空矩形", 0, save.width());
        assertEquals("不摆＝空矩形", 0, save.height());
        assertFalse("空矩形不许还占着任何一处坐标", save.contains(0, 0));
        assertEquals("缝仍是那一道 4", SaveLayout.CELL_GAP, del.left - load.right);
        assertEquals("两格照样铺满内容列", SaveLayout.CONTENT_LEFT, load.left);
        assertEquals("两格照样铺满内容列", SaveLayout.CONTENT_RIGHT, del.right);
        assertEquals("两格等宽", load.width(), del.width());
        // 三格形一字未动：分叉只发生在中间那一格上，暂停页那侧读不到任何变化
        SaveLayout.actionCell(0, true, load);
        SaveLayout.actionCell(2, true, del);
        assertRect("载入（三格形）", load, COL0_L, SaveLayout.ACTION_TOP, COL0_R, SaveLayout.ACTION_BOTTOM);
        assertRect("删除（三格形）", del, COL2_L, SaveLayout.ACTION_TOP, COL2_R, SaveLayout.ACTION_BOTTOM);
    }

    /**
     * 行内五列单调推进且都不越过右界；每列**自己**的宽度加间隙之后才轮到下一列，不留重叠。
     *
     * <p>这一屏的列是定宽的（见 {@link SaveLayout#COL_SLOT_W} 那条理由）：槽号两位、波号三位。
     * 定宽换不来"更整齐"，换的是**行与行对齐**——而它一旦与最宽的实际值不符就会串行：
     * 分数十一位排到 155 起笔，而波列右界在 126，中间那道 29 格的缝是「分」标签的位置。
     */
    @Test
    public void theColumnsMarchAndNobodyCrossesTheRightEdge() {
        RectI row = new RectI();
        SaveLayout.rowRect(0, row);
        int slot = SaveLayout.slotLeft(row);
        int mode = SaveLayout.modeLeft(row);
        int tier = SaveLayout.tierLeft(row);
        int wave = SaveLayout.waveLeft(row);
        int scoreFloor = SaveLayout.scoreLabelLeftFloor(row);
        int right = SaveLayout.textRight(row);
        assertTrue("槽列没留出「槽」+两位", slot + SaveLayout.COL_SLOT_W <= mode);
        assertTrue("模式列没留宽", mode + SaveLayout.COL_MODE_W <= tier);
        assertTrue("档位列没留宽", tier + SaveLayout.COL_TIER_W <= wave);
        assertTrue("波列没留宽（标签 + 三位）", wave + SaveLayout.COL_WAVE_W <= scoreFloor);
        assertTrue("分数标签的落点越过了右界", scoreFloor < right);
        assertEquals("手推 12", slot, SaveLayout.CONTENT_LEFT);
        assertEquals(41, mode);
        assertEquals(67, tier);
        assertEquals(95, wave);
        assertEquals(130, scoreFloor);
        assertEquals(228, right);
        assertEquals("行右界就是内容列右界", SaveLayout.CONTENT_RIGHT, right);
    }

    /**
     * 分数带（标签 + 十一位点阵）不压波列、时间戳带不压分数标签。
     *
     * <p>第一带最长形态是「分」+ 10 位（{@code BITS_SCORE = 32} 的十进制位数上限）。第二带是
     * 「MM-dd HH:mm」十一位。两带各自贴右排在 228，所以判据化成"左界够不够"：分数带左界
     * 155 ≥ 130，时间带左界 163 ≥ 分数带左界。这条一旦红，画面是两串数字尾首相接读不出哪串是哪个。
     */
    @Test
    public void theTwoRightAlignedBandsHaveRoomForTheirWidestForm() {
        RectI row = new RectI();
        SaveLayout.rowRect(0, row);
        int floor = SaveLayout.scoreLabelLeftFloor(row);
        int right = SaveLayout.textRight(row);
        int scoreBandW = Md3.PX_LABEL + DrawKit.GAP + SaveLayout.digitWidth(10);
        assertTrue("分数带（标签 + 十位）会压到波列：" + scoreBandW,
                right - scoreBandW >= floor);
        int stampW = SaveLayout.digitWidth(11);
        assertTrue("时间戳带越过分数带左界", right - stampW >= floor);
        assertEquals(65, stampW);
    }

    /**
     * meta 那条左端读数的右界，与页面最长一句提示的左端：两者不许碰上。
     *
     * <p>判据在版面、文案住在面板（{@link SaveScreen#S_FULL} 十三字是算出来的上界，见那条注释）。
     * 提示贴右排在 228，左端 = {@code 228 − 字数 × 12}；读数右界 67。十三字起笔 72，缝只有五格——
     * 第十四字就会盖住「已存 50/50」那个数，而那个数是"还能不能存"的唯一依据。所以这里钉的是
     * **文案侧**的一次加字，而不是版面侧的改动。
     */
    @Test
    public void theLongestHintStillClearsTheUsageReadout() {
        assertEquals(67, SaveLayout.META_READOUT_RIGHT);
        String[] hints = {SaveScreen.S_FULL, SaveScreen.S_LOAD_FAILED, SaveScreen.L_LOADING};
        for (String hint : hints) {
            int left = SaveLayout.CONTENT_RIGHT - hint.length() * Md3.PX_LABEL;
            assertTrue("「" + hint + "」起笔 " + left + " 顶到占用读数（右界 "
                    + SaveLayout.META_READOUT_RIGHT + "）", left >= SaveLayout.META_READOUT_RIGHT);
        }
    }

    /**
     * 点阵宽度按字符格算，最后一格不留尾隙（口径同 {@code BitmapFont.textWidth}）。
     * 位数从 0 起的一格是"这串根本没画"，返回值必须是 0 而不是负数——负宽会让整行右对齐算出左移。
     */
    @Test
    public void digitWidthCountsCellsNotPixels() {
        assertEquals(0, SaveLayout.digitWidth(0));
        assertEquals(0, SaveLayout.digitWidth(-4));
        assertEquals(BitmapFont.CELL_W - 1, SaveLayout.digitWidth(1));
        assertEquals(11, SaveLayout.digitWidth(2));
        assertEquals(29, SaveLayout.digitWidth(5));
        for (int n = 1; n <= 12; n++) {
            assertEquals("digits=" + n, n * BitmapFont.CELL_W - 1, SaveLayout.digitWidth(n));
        }
    }

    /**
     * 两条文字带的中心线都在行框之内，且带间距是 2 格（汉字带 12px、点阵带 7px，各占 12 格中心高）。
     * 行高 24 是这两带加上上下各 2 的全部预算；中心线一挪，第一带就会骑到行底外面。
     */
    @Test
    public void theTwoTextBandsFitInsideTheRowBudget() {
        RectI row = new RectI();
        SaveLayout.rowRect(0, row);
        assertEquals(row.top + 8, SaveLayout.line1CenterY(row));
        assertEquals(row.top + 18, SaveLayout.line2CenterY(row));
        assertEquals(SaveLayout.ROW_H, row.height());
        assertTrue("第一带的下缘出了行框",
                SaveLayout.line1CenterY(row) + Md3.PX_LABEL / 2 <= row.bottom);
        assertTrue("第二带的下缘出了行框",
                SaveLayout.line2CenterY(row) + BitmapFont.GLYPH_H / 2 <= row.bottom);
    }
}
