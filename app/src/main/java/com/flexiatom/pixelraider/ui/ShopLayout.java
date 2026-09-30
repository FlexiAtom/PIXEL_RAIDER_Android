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
import com.flexiatom.pixelraider.plat.Screen;

/**
 * 升级商店面板的全部**可测数字**（规格 §升级商店：每波三选一、图标+名字+核心数字+tip；
 * 卡的总数不在这里写死，读 {@code Balance.Shop.CARDS}）。
 *
 * <p>与暂停面板的分工：那块是"激战里拉开的数据板"，八张卡要在 320~560 的每种逻辑高上分账，
 * 所以它铺满整屏；这块只有三张卡，卡的**内容高度是固定的**（图标行 + 数字行 + 说明行），
 * 长高不带来任何信息。所以它是**底部弹层**：面板高度等于内容高度，多出来的高度还给战场
 * （玩家站在商店前面得能看见自己面对的是什么），只有内容装进不去时才退回贴满可用区。
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
    /** 一次上架几张。数值由 {@code ShopLayoutTest} 钉住等于 {@code Balance.Shop.OFFER}。 */
    public static final int CARDS = 3;
    public static final int CARD_GAP = 4;

    /** 标题在上边框内的呼吸位。 */
    public static final int TITLE_TOP_PAD = 2;
    /** 标题与卡区、卡区与出口按钮之间的固定间隙。 */
    public static final int POOL_TOP_GAP = 6, POOL_BOTTOM_GAP = 8;

    /** 唯一出口：进入下一波。买与不买都是"离开商店"，所以它既是主操作也是关闭动作。 */
    public static final int ROW_PRIMARY_H = 22;
    public static final float PRIMARY_W_RATIO = 0.76f;

    public static final int PANEL_LIFT = 28;

    /** 卡区理想高：三张整卡 + 两个间距。 */
    public static final int STACK_H = CARDS * CARD_H + (CARDS - 1) * CARD_GAP;

    /**
     * 弹层的理想高度——上下边框之间**内容自己需要的**高度，一段都不含"剩余"。
     *
     * <p>由常量派生而不是写死：任何一处内容尺寸改动（卡变高、标题换行）都会自动带着弹层走，
     * 不会出现"内容 262、板子还按 260 画"这种把说明带裁掉的错。
     */
    public static final int SHEET_H = BORDER + TITLE_TOP_PAD + TITLE_H + POOL_TOP_GAP
            + STACK_H + POOL_BOTTOM_GAP + ROW_PRIMARY_H + PAD + BORDER;

    public final RectI panel = new RectI();
    public final RectI titleBar = new RectI();
    public final RectI[] cards = {new RectI(), new RectI(), new RectI()};
    public final RectI next = new RectI();

    public int contentLeft, contentRight, contentWidth;

    /**
     * @param logicH     当前逻辑画布高
     * @param safeTop    顶部安全区内缩（逻辑 px）——商店常在矮屏刘海机上拉开，不能压进挖孔带
     * @param safeBottom 底部安全区内缩
     */
    public void layout(int logicH, int safeTop, int safeBottom) {
        int h = logicH <= 0 ? Screen.BATTLE_H : logicH;
        int bottom = h - safeBottom - PANEL_GAP;
        int usable = Math.max(0, bottom - safeTop - PANEL_GAP);
        // 底边贴可用区下沿、顶边由内容决定。装不下时才长到贴满可用区，由下面的压卡分支收账。
        panel.set(LEFT, bottom - Math.min(SHEET_H, usable), RIGHT, bottom);
        contentLeft = LEFT + BORDER + PAD;
        contentRight = RIGHT - BORDER - PAD;
        contentWidth = contentRight - contentLeft;

        int titleTop = panel.top + BORDER + TITLE_TOP_PAD;
        titleBar.set(contentLeft, titleTop, contentRight, titleTop + TITLE_H);

        int rowBottom = panel.bottom - BORDER - PAD;
        int pw = primaryWidth();
        int px = contentLeft + (contentWidth - pw) / 2;
        next.set(px, rowBottom - ROW_PRIMARY_H, px + pw, rowBottom);

        // 卡区从标题下面起、到按钮上面止，整组在池子里垂直居中：池子比内容高时多出来的是留白。
        int poolTop = titleBar.bottom + POOL_TOP_GAP;
        int poolBottom = next.top - POOL_BOTTOM_GAP;
        int avail = Math.max(0, poolBottom - poolTop);
        int cardH = CARD_H;
        int gap = CARD_GAP;
        if (CARDS * cardH + (CARDS - 1) * gap > avail) {
            // 极端比例叠上厚安全区时池子装不下三张整卡。压卡不压按钮——按钮是唯一的出口，
            // 压掉的那几像素由说明带承担（说明是次要信息，出口不是）。收完间距还要**封顶在
            // CARD_H**：卡被挤完间距后反而比标准卡更高，那是"卡片封顶、富余量当留白"的反面。
            gap = 0;
            cardH = Math.min(CARD_H, avail / CARDS);
        }
        int stackH = CARDS * cardH + (CARDS - 1) * gap;
        int top = poolTop + (avail - stackH) / 2;
        for (int i = 0; i < CARDS; i++) {
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
