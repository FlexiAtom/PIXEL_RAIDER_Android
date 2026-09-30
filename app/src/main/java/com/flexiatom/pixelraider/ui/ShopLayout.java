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

import com.flexiatom.pixelraider.core.RectI;
import com.flexiatom.pixelraider.game.Balance;
import com.flexiatom.pixelraider.plat.Screen;

/**
 * 升级商店面板的全部**可测数字**（规格 §升级商店：每波三选一、图标+名字+核心数字+tip；
 * 卡的总数不在这里写死，读 {@code Balance.Shop.CARDS}）。
 *
 * <p>与暂停面板的分工：那块是"激战里拉开的数据板"，八张卡要在 320~560 的每种逻辑高上分账，
 * 所以它铺满整屏；这块每一页的卡**内容高度是固定的**（图标行 + 数字行 + 说明行），
 * 长高不带来任何信息。所以它是**底部弹层**：面板高度等于内容高度，多出来的高度还给战场
 * （玩家站在商店前面得能看见自己面对的是什么），只有内容装进不去时才退回贴满可用区。
 *
 * <p>**一页摆几张由可用高度倒推，不由卡数写死**（{@link #capacityFor}）。2026-09-30 双入口分区之后
 * 这家店有两种货架：回合店抽三张，暂停店摆整条非通用侧（簇 II 之后十张）——一个定长版面装不下
 * 后者。装不下的翻到下一页（{@link #pages}），而不是把卡压矮：卡一矮，先掉出去的是说明行，
 * 而说明正是玩家判断"这张要不要"的那条信息。一页的下限是一次三选一，比它更挤的局才落到压卡分支。
 *
 * <p>坐标一律是**逻辑画布坐标**（0..logicH），不做 {@code translate(battleTop)}。
 */
public final class ShopLayout {

    public static final int LEFT = 8;
    public static final int RIGHT = Screen.LOGIC_W - LEFT;
    public static final int PANEL_GAP = 6;
    public static final int BORDER = 2;
    public static final int PAD = 4;

    public static final int TITLE_H = 28;
    /**
     * 卡内图标边长。自建的六张图标是 9×9 网格，2 倍缩放正好 18（规格 §三：整数倍是硬线）；
     * 补给类与金币沿用掉落的 7×7 网格，18 不是 7 的整数倍——用户逐字说过「补给卡图标没有问题」
     * （L12803，{@code queued_command}／{@code origin.kind=human}，UTC 2026-09-24T16:43:17.604Z
     * ＝本地 09-25T00:43），所以尺寸不动，偏离由 {@code SpriteSheetsTest} 的白名单钉着。
     * ⚠ 这里先前写着「用户<b>实机</b>看过<b>货架</b>后判定」，那两个词是他的字里<b>没有</b>的：
     * 「货架」「实机」在五条第一手通道里全部 0 命中，而 L12803 还早于他声明「真机已连接」
     * （L13322，本地 09-25T13:33）⇒ 判断有他的字，"在设备上看过"这个前提是我的推断，别再当裁定引。
     */
    public static final int ICON = 18;
    /** 卡的三条内容带：图标+名字+价格 / 核心数字+等级 / 说明。字号只有 12 与 24 两档（Md3）。 */
    public static final int ROW1_H = 18;
    public static final int ROW2_H = 16;
    public static final int ROW3_H = 14;
    public static final int ROW_GAP = 2;
    /** 卡内上下留白。 */
    public static final int CARD_PAD = 4;
    public static final int CARD_H = CARD_PAD * 2 + ROW1_H + ROW_GAP + ROW2_H + ROW_GAP + ROW3_H;
    /**
     * 版面最多摆几张 = 卡表条数。**不是**"一次上架几张"——那个数住在 {@code Balance.Shop.OFFER}
     * （回合店）与 {@code ShopRules#listShelf} 的返回值（暂停店整条）里，两边都可能小于它。
     * 这里只决定数组长度与分页上限。
     */
    public static final int MAX_CARDS = Balance.Shop.CARDS;
    public static final int CARD_GAP = 4;

    /** 标题在上边框内的呼吸位。 */
    public static final int TITLE_TOP_PAD = 2;
    /** 标题与卡区、卡区与出口按钮之间的固定间隙。 */
    public static final int POOL_TOP_GAP = 6, POOL_BOTTOM_GAP = 8;

    /** 唯一出口：离开这家店。买与不买都是"离开商店"，所以它既是主操作也是关闭动作。 */
    public static final int ROW_PRIMARY_H = 22;
    public static final float PRIMARY_W_RATIO = 0.76f;
    /** 需要翻页时，出口那一行切成的格数：上一页 / 出口 / 下一页。 */
    public static final int PAGE_ROW_COLS = 3;

    public static final int PANEL_LIFT = 28;

    /**
     * 弹层里**与卡数无关**的那一截：两条边框 + 标题位 + 池子上下间隙 + 出口行 + 卡区下留白。
     * 翻页按钮挤在出口行里，所以翻不翻这一截都一样——这是 {@link #capacityFor} 能只看卡数的原因。
     */
    public static final int CHROME_H = BORDER + TITLE_TOP_PAD + TITLE_H + POOL_TOP_GAP
            + POOL_BOTTOM_GAP + ROW_PRIMARY_H + PAD + BORDER;

    /** c 张卡时弹层**理想**高度（装得下的前提下面板就等于它，长高不带来信息）。 */
    public static int sheetHeight(int c) {
        return CHROME_H + Math.max(0, c) * CARD_H + Math.max(0, c - 1) * CARD_GAP;
    }

    /**
     * 高 {@code usable} 的可用区一页摆得下几张——把 {@link #sheetHeight} 反解出来：
     * {@code c·CARD_H + (c−1)·CARD_GAP ≤ usable − CHROME_H}
     * ⇔ {@code c ≤ (usable − CHROME_H + CARD_GAP) / (CARD_H + CARD_GAP)}。
     *
     * <p>**下限是 {@code Balance.Shop.OFFER} 而不是 1**：三选一是这家店的设计单位（规格 §升级商店
     * "每波三选一"），把一次选择拆到两页上等于把选择拆成两次"要不要"——玩家看不到第三个候选就
     * 没法比较。厚安全区叠上矮画布时宁可让 {@link #layout} 的压卡分支把说明带削掉几像素，
     * 也不翻页。
     */
    public static int capacityFor(int usable) {
        int c = (usable - CHROME_H + CARD_GAP) / (CARD_H + CARD_GAP);
        return Math.max(Balance.Shop.OFFER, Math.min(MAX_CARDS, c));
    }

    public final RectI panel = new RectI();
    public final RectI titleBar = new RectI();
    public final RectI pool = new RectI();
    public final RectI[] cards = boxes(MAX_CARDS);
    public final RectI next = new RectI();
    /** 翻页箭头。{@link #pages} 为 1 时这两格是空矩形（{@code contains} 恒假），命中注册由面板负责。 */
    public final RectI pagePrev = new RectI();
    public final RectI pageNext = new RectI();

    public int contentLeft, contentRight, contentWidth;

    /** 本次 {@link #layout} 算出的每页张数、页数、当前页、当前页第一张在全架里的下标、这一页实际摆几张。 */
    public int capacity, pages, page, pageStart, shown;

    private static RectI[] boxes(int n) {
        RectI[] r = new RectI[n];
        for (int i = 0; i < n; i++) r[i] = new RectI();
        return r;
    }

    /**
     * @param logicH     当前逻辑画布高
     * @param safeTop    顶部安全区内缩（逻辑 px）——商店常在矮屏刘海机上拉开，不能压进挖孔带
     * @param safeBottom 底部安全区内缩
     * @param count      这次开架一共几张（回合店 3，暂停店整条非通用侧）
     * @param page       请求的页；越界由这里钳，钳完的结果读 {@link #page}
     */
    public void layout(int logicH, int safeTop, int safeBottom, int count, int page) {
        int h = logicH <= 0 ? Screen.BATTLE_H : logicH;
        int bottom = h - safeBottom - PANEL_GAP;
        int usable = Math.max(0, bottom - safeTop - PANEL_GAP);

        capacity = capacityFor(usable);
        pages = Math.max(1, (Math.max(0, count) + capacity - 1) / capacity);
        this.page = Math.max(0, Math.min(page, pages - 1));
        pageStart = this.page * capacity;
        shown = Math.max(0, Math.min(capacity, count - pageStart));

        // 底边贴可用区下沿、顶边由**这一页**的内容决定。装不下时才长到贴满可用区，由下面的压卡分支收账。
        panel.set(LEFT, bottom - Math.min(sheetHeight(shown), usable), RIGHT, bottom);
        contentLeft = LEFT + BORDER + PAD;
        contentRight = RIGHT - BORDER - PAD;
        contentWidth = contentRight - contentLeft;

        int titleTop = panel.top + BORDER + TITLE_TOP_PAD;
        titleBar.set(contentLeft, titleTop, contentRight, titleTop + TITLE_H);

        int rowBottom = panel.bottom - BORDER - PAD;
        int rowTop = rowBottom - ROW_PRIMARY_H;
        if (pages > 1) {
            // 三格等宽：主次靠配色区分，不靠大小（{@link Widgets#equalHalves} 那条规矩的三格版）。
            // 箭头不与出口比宽，玩家找的是中间那枚一直亮着的实心按钮。
            for (int i = 0; i < PAGE_ROW_COLS; i++) {
                RectI out = i == 0 ? pagePrev : i == 1 ? next : pageNext;
                Widgets.gridCell(contentLeft, rowTop, contentWidth, ROW_PRIMARY_H,
                        PAGE_ROW_COLS, 1, CARD_GAP, i, out);
            }
        } else {
            int pw = primaryWidth();
            int px = contentLeft + (contentWidth - pw) / 2;
            next.set(px, rowTop, px + pw, rowBottom);
            pagePrev.set(0, 0, 0, 0);
            pageNext.set(0, 0, 0, 0);
        }

        // 卡区从标题下面起、到按钮上面止，整组在池子里垂直居中：池子比内容高时多出来的是留白。
        int poolTop = titleBar.bottom + POOL_TOP_GAP;
        int poolBottom = next.top - POOL_BOTTOM_GAP;
        int avail = Math.max(0, poolBottom - poolTop);
        pool.set(contentLeft, poolTop, contentRight, poolTop + avail);
        int cardH = CARD_H;
        int gap = CARD_GAP;
        if (shown > 0 && shown * cardH + (shown - 1) * gap > avail) {
            // 极端比例叠上厚安全区时池子装不下这一页的整卡。压卡不压按钮——按钮是唯一的出口，
            // 压掉的那几像素由说明带承担（说明是次要信息，出口不是）。收完间距还要**封顶在
            // CARD_H**：卡被挤完间距后反而比标准卡更高，那是"卡片封顶、富余量当留白"的反面。
            gap = 0;
            cardH = Math.min(CARD_H, avail / shown);
        }
        int stackH = shown > 0 ? shown * cardH + (shown - 1) * gap : 0;
        int top = poolTop + (avail - stackH) / 2;
        for (int i = 0; i < shown; i++) {
            int y = top + i * (cardH + gap);
            cards[i].set(contentLeft, y, contentRight, y + cardH);
        }
    }

    public int primaryWidth() {
        return Math.round(contentWidth * PRIMARY_W_RATIO);
    }

    // ---- 卡内三条带的顶边（纯算术，ShopScreen 与测试共用同一份算法）--------------------------

    public static int row1Top(RectI card) {
        return card.top + CARD_PAD;
    }

    public static int row2Top(RectI card) {
        return row1Top(card) + ROW1_H + ROW_GAP;
    }

    public static int row3Top(RectI card) {
        return row2Top(card) + ROW2_H + ROW_GAP;
    }

    /** 图标右缘，也是两行文字的左边界（名字与核心数字对齐在同一条竖线上）。 */
    public static int textLeft(RectI card) {
        return card.left + CARD_PAD + ICON + 5;
    }

    /** 卡内可用文字宽（说明行用它判断能否整句放下）。 */
    public static int textWidth(RectI card) {
        return card.right - CARD_PAD - textLeft(card);
    }
}
