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

import android.graphics.Canvas;

import com.flexiatom.pixelraider.core.RectI;
import com.flexiatom.pixelraider.game.CampaignBriefing;
import com.flexiatom.pixelraider.game.Tier;
import com.flexiatom.pixelraider.game.WaveDirector;
import com.flexiatom.pixelraider.gfx.BitmapFont;
import com.flexiatom.pixelraider.gfx.GlowAtlas;
import com.flexiatom.pixelraider.gfx.Ink;
import com.flexiatom.pixelraider.gfx.TextCache;
import com.flexiatom.pixelraider.plat.Screen;

/**
 * 战役选关页（任务列表 + 简报视口 + 难度分段 + 键行）。池件 {@code campaign-data-tree} 批3-B 的
 * 那一屏；几何在 {@link CampaignLayout}，句子在 {@link CampaignBriefing}，轨的算术在
 * {@link ScrollLayout}——这里只做"把三件摆起来"和"手指下面第几格"。
 *
 * <p>面板只决定"点到哪一格"。开一局、换档、把波号定在第几波全在 {@code Game}（口径同
 * {@link SaveScreen}：报出去的动作只有<b>返回</b>与<b>开始任务</b>两枚，<b>选中的任务与难度不在动作码里</b>，
 * 抬起之后由 {@code Game} 现读 {@link #selectedMission()} 与 {@link #selectedTier()}）。
 * 换任务、换档、滚动都是面板自己的状态，报 {@link #ACTION_NONE}。
 *
 * <p><b>三条命中口径</b>，都是"按下与抬起必须给同一个答案"那一族：
 * <ul>
 *   <li>两枚按钮带<b>不外扩</b>——{@link CampaignLayout#CELL_H} 按绘制尺寸就已够触控下界，
 *       外扩反而会让分段控件三格互吞；</li>
 *   <li>轨的两枚箭头<b>只用绘制框受理</b>（6×9）。外扩到 45 会咬掉任务行第七格与难度第一格，
 *       而它们本来就是那条 45 宽"按着视口区域拖动"（他 2026-10-13 逐字裁的甲）的备选路径——
 *       箭头点了没反应不算丢失出口，因为拖这条路一直在；</li>
 *   <li>任务行照存档页那套两遍 pass：绘制框先判（八行互不重叠），外扩框只收两行之间那两格的缝。</li>
 * </ul>
 *
 * <p><b>简报按 8px 档、其余全按 12px 档</b>（他 2026-10-12 逐字「简报区字要等比缩小」＝换成那张
 * 原生 8px 脸，不是把 12px 缩小）。折行用的尺是 {@link TextCache#widthOf}，它<b>只量不烘</b>：
 * 走 {@code bake} 会让折行探过的每条子串都进槽，把真正上屏的那几行从缓存里挤出去。
 */
public final class CampaignScreen {

    public static final int ACTION_NONE = 0;
    public static final int ACTION_BACK = 1;
    public static final int ACTION_START = 2;

    private static final String L_MISSION = "任务";
    private static final String L_START = "开始任务";
    private static final String L_BACK = "返回";
    /**
     * 档名表直接取存档页那一份（下标 = {@link Tier} 声明序）。再抄一遍"休闲普通终结"就有两处真值，
     * 而这两处会分叉（同一屏里两个名字不同的同一档，是最容易看漏的那种错）。
     */
    private static final String[] L_TIER = SaveScreen.L_TIER;

    /**
     * 命中区下标。**这个顺序就是外扩框互压时的归属**：轨两枚（不参与外扩那遍，见类注释）、
     * 整个视口（拖动的落点，它排在任务行之前，好把行外扩出去的那十几格留在自己这边）、
     * 再八行、再难度三格、最后键行两枚——排在最后的是"这一按就开一局"的那两枚。
     */
    private static final int IDX_UP = 0;
    private static final int IDX_DOWN = 1;
    private static final int IDX_VIEW = 2;
    private static final int IDX_ROWS = 3;
    private static final int IDX_TIER = IDX_ROWS + CampaignLayout.ROWS;
    private static final int IDX_START = IDX_TIER + CampaignLayout.TIER_CELLS;
    private static final int IDX_BACK = IDX_START + 1;
    private static final int BOXES = IDX_BACK + 1;

    /** 列表与轨这一族同一级（0.20 秒落定），两枚按钮带晚一级（0.28 秒，正好是面板落定那一瞬）。 */
    private static final int LEVEL_ROWS = 1;
    private static final int LEVEL_BUTTONS = 2;

    private final DrawKit kit;
    private final TextCache text;
    private final HudText hud = new HudText(8);
    private final RectI ra = new RectI();
    private final RectI rb = new RectI();
    private final RectI[] hit = boxes(BOXES);
    private final PressSelector press = new PressSelector();
    /**
     * 折行的尺：8px 档的像素宽。一支对象，不为每次重排分配。
     *
     * <p>写成匿名类而不是 lambda：{@code text} 是本类的 final 字段、要到构造器里才赋上，javac 对
     * <b>字段初始化式里的 lambda</b> 直接报"可能尚未初始化变量 text"（同一份引用写在匿名类的方法体里
     * 不报——它绑的是 {@code this.text}，要到 {@code of()} 真被调用时才解，那时构造早已完成）。
     */
    private final TextWrap.Width ruler = new TextWrap.Width() {
        @Override
        public int of(String s) {
            return text.widthOf(s, BriefingLayout.GLYPH_H);
        }
    };

    /** 选中的任务号（1 起，与 {@link CampaignBriefing} 同一口径；面板内部从不存 0 基的下标）。 */
    private int mission = 1;
    /** 选中的难度档号（＝ {@link Tier} 声明序，默认 {@code NORMAL} 那一档＝今天的基线）。 */
    private int tier = Tier.ordinalOf(Tier.NORMAL);
    /** 上屏行序列与内容高：换任务才重算，不每帧折行。 */
    private String[] lines = new String[0];
    private int contentH;
    private int offset;
    /** 正在拖动视口的那根手指（{@link PressSelector#NONE} = 没人拖）。 */
    private int dragPointer = PressSelector.NONE;
    private int dragStartY;
    private int dragStartOffset;
    private int pageTop;
    private int logicH = Screen.BATTLE_H;

    public CampaignScreen(BitmapFont font, TextCache text, GlowAtlas glow) {
        kit = new DrawKit(font, text, glow);
        this.text = text;
        reline();      // 未 open 也要有一版可画的行（构造期就折好，第一帧不空手）
    }

    private static RectI[] boxes(int n) {
        RectI[] r = new RectI[n];
        for (int i = 0; i < n; i++) r[i] = new RectI();
        return r;
    }

    public int pageHeight() {
        return CampaignLayout.PAGE_H;
    }

    /**
     * 开页：选中回到「任务1 ＋ 普通」，滚动回到顶。
     *
     * <p>默认值是<b>有意</b>的：任务 1 的起始波就是第 1 波、普通档全是 1.00 乘子，于是一进来直接按
     * 「开始任务」与今天主菜单那枚键的结果<b>逐字节相同</b>。这一屏新增的是"可以改"，不是"必须选"。
     */
    public void open() {
        mission = 1;
        tier = Tier.ordinalOf(Tier.NORMAL);
        offset = 0;
        dragPointer = PressSelector.NONE;
        press.clear();
        reline();
    }

    public int selectedMission() {
        return mission;
    }

    public Tier selectedTier() {
        return Tier.at(tier);
    }

    public int pressedIndex() {
        return press.pressed();
    }

    /** 简报当前滚到第几像素（测试与以后的"记忆滚动位"那格都读它，绘制端不另存一份）。 */
    public int scrollOffset() {
        return offset;
    }

    public void layout(int minTouchLogic, int pageTop, int logicH) {
        this.pageTop = pageTop;
        this.logicH = logicH <= 0 ? Screen.BATTLE_H : logicH;
        railRect(IDX_UP, ra);
        ra.offsetInPlace(0, pageTop);
        hit[IDX_UP].copyFrom(ra);                       // 箭头不外扩（理由见类注释）
        railRect(IDX_DOWN, ra);
        ra.offsetInPlace(0, pageTop);
        hit[IDX_DOWN].copyFrom(ra);
        CampaignLayout.briefRect(ra);
        ra.offsetInPlace(0, pageTop);
        hit[IDX_VIEW].copyFrom(ra);                     // 视口本身就够大，不外扩也过下界
        for (int i = 0; i < CampaignLayout.ROWS; i++) {
            CampaignLayout.rowRect(i, ra);
            Widgets.hitRect(ra, minTouchLogic, hit[IDX_ROWS + i]);
            hit[IDX_ROWS + i].offsetInPlace(0, pageTop);
        }
        for (int i = 0; i < CampaignLayout.TIER_CELLS; i++) {
            CampaignLayout.tierCell(i, ra);
            Widgets.hitRect(ra, minTouchLogic, hit[IDX_TIER + i]);
            hit[IDX_TIER + i].offsetInPlace(0, pageTop);
        }
        for (int i = 0; i < CampaignLayout.KEY_CELLS; i++) {
            CampaignLayout.keyCell(i, ra);
            Widgets.hitRect(ra, minTouchLogic, hit[IDX_START + i]);
            hit[IDX_START + i].offsetInPlace(0, pageTop);
        }
    }

    public void pressDown(int x, int y, float elapsed, int pointerId) {
        int i = under(x, y, elapsed);
        if (i == IDX_VIEW) {
            // 按着视口区域拖动（他 10-13 逐字裁的甲）。第二根手指也落在文字区时**不抢拖动**，
            // 只把可能挂在别的格上的按压态松开——多指在这一屏本就是噪声。
            if (dragPointer == PressSelector.NONE) {
                dragPointer = pointerId;
                dragStartY = y;
                dragStartOffset = offset;
            }
            press.clear();
            return;
        }
        dragPointer = PressSelector.NONE;
        press.pressDown(i, pointerId);
    }

    public void pressDrag(int x, int y, float elapsed, int pointerId) {
        if (pointerId == dragPointer) {
            offset = ScrollLayout.offsetForDrag(CampaignLayout.VIEWPORT_H, contentH,
                    dragStartOffset, y - dragStartY);
            return;
        }
        press.dragTo(under(x, y, elapsed), pointerId);
    }

    /** @return 三枚 {@code ACTION_*} 之一；换任务、换档、滚动步近在内部做完，报 {@link #ACTION_NONE} */
    public int pressUp(int x, int y, float elapsed, int pointerId) {
        if (pointerId == dragPointer) {
            dragPointer = PressSelector.NONE;
            return ACTION_NONE;         // 拖完就是拖完，不该顺手把这一格也算成一次点击
        }
        int i = press.releaseTo(under(x, y, elapsed), pointerId);
        // if 链而不是 switch：IDX_* 建在 CampaignLayout.ROWS 上，switch 的 case 标签不收非常量表达式
        if (i == IDX_BACK) return ACTION_BACK;
        if (i == IDX_START) return ACTION_START;
        if (i == IDX_UP) {
            stepScroll(-1);
            return ACTION_NONE;
        }
        if (i == IDX_DOWN) {
            stepScroll(1);
            return ACTION_NONE;
        }
        if (i >= IDX_ROWS && i < IDX_TIER) {
            chooseMission(i - IDX_ROWS + 1);
            return ACTION_NONE;
        }
        if (i >= IDX_TIER && i < IDX_START) {
            chooseTier(i - IDX_TIER);
        }
        return ACTION_NONE;
    }

    public void clearPress() {
        press.clear();
        dragPointer = PressSelector.NONE;
    }

    /**
     * 换任务：简报从头读起。滚动位<b>不</b>跟着任务保留——上一份简报滚到第 8 行，换一份稿从第 8 行
     * 开始看等于把新稿的开头藏起来，而开头那句正是「第X幕————任务N————军事行动」。
     */
    private void chooseMission(int m) {
        if (m == mission) return;
        mission = m;
        offset = 0;
        reline();
    }

    /** 换档只改档号：内容高与滚动都不动。 */
    private void chooseTier(int t) {
        if (t < 0 || t >= L_TIER.length) return;
        tier = t;
    }

    private void stepScroll(int dir) {
        offset = ScrollLayout.clampOffset(offset + dir * BriefingLayout.LINE_STEP,
                CampaignLayout.VIEWPORT_H, contentH);
    }

    /** 内容层 → 上屏行 → 内容高。换任务与开页各一次，不在每帧折行。 */
    private void reline() {
        lines = BriefingLayout.lines(CampaignBriefing.lines(mission), ruler);
        contentH = lines.length * BriefingLayout.LINE_STEP;
        offset = ScrollLayout.clampOffset(offset, CampaignLayout.VIEWPORT_H, contentH);
    }

    /** 轨两枚箭头的绘制框（视口内相对坐标换到页内相对：加一次 {@link CampaignLayout#BRIEF_TOP}）。 */
    private void railRect(int i, RectI out) {
        if (i == IDX_UP) {
            ScrollLayout.arrowUp(BriefingLayout.TRACK_LEFT, CampaignLayout.VIEWPORT_H, out);
        } else {
            ScrollLayout.arrowDown(BriefingLayout.TRACK_LEFT, CampaignLayout.VIEWPORT_H, out);
        }
        out.offsetInPlace(0, CampaignLayout.BRIEF_TOP);
    }

    /**
     * 手指下面第几格；未命中 {@link PressSelector#NONE}。两遍 pass 的理由与口径同 {@link SaveScreen#under}
     * （先各格自己的绘制框，再按数组顺序收外扩框），这一屏多出来的一条是<b>箭头不参与第二遍</b>。
     */
    private int under(int x, int y, float elapsed) {
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < hit.length; i++) {
                if (!tappable(i, elapsed)) continue;
                int ty = pass == 0 ? y - pageTop : y;
                if (boxOf(i, pass).contains(x, ty)) return i;
            }
        }
        return PressSelector.NONE;
    }

    private RectI boxOf(int i, int pass) {
        if (pass == 1) {
            if (i == IDX_UP || i == IDX_DOWN) {
                ra.set(0, 0, 0, 0);             // 零面积：contains 恒假（箭头不外扩，见类注释）
                return ra;
            }
            return hit[i];
        }
        if (i == IDX_UP || i == IDX_DOWN) {
            railRect(i, ra);
            return ra;
        }
        if (i == IDX_VIEW) {
            CampaignLayout.briefRect(ra);
            return ra;
        }
        if (i < IDX_TIER) {
            CampaignLayout.rowRect(i - IDX_ROWS, ra);
            return ra;
        }
        if (i < IDX_START) {
            CampaignLayout.tierCell(i - IDX_TIER, ra);
            return ra;
        }
        CampaignLayout.keyCell(i - IDX_START, ra);
        return ra;
    }

    /** 这一格现在受理不：入场淡完，而且（对轨那一族）真的还有内容可滚。 */
    private boolean tappable(int i, float elapsed) {
        boolean rail = i <= IDX_VIEW;
        if (rail && ScrollLayout.maxOffset(CampaignLayout.VIEWPORT_H, contentH) == 0) {
            // 没有溢出就没有可滚量：拖与步近都不改变画面（轨照旧<b>画</b>着——他裁的常驻），
            // 但不受理，免得留两枚"点了没反应"的箭头（一颗点不动的按钮比没有按钮更糟）。
            return false;
        }
        return PanelMotion.buttonProgress(rail ? LEVEL_ROWS : LEVEL_BUTTONS, elapsed) >= 1f;
    }

    // ---- 绘制 -----------------------------------------------------------------------------

    /** @param elapsed 开页起的界面秒数（入场与级联都以它为原点，口径同 {@link SaveScreen#draw}） */
    public void draw(Canvas c, float elapsed) {
        float p = PanelMotion.enterProgress(elapsed);
        kit.fill.setColor(Ink.MASK);
        kit.rf.set(0, -pageTop, Screen.LOGIC_W, logicH - pageTop);
        c.drawRect(kit.rf, kit.fill);
        kit.fill.setAlpha(255);

        float kRows = PanelMotion.buttonProgress(LEVEL_ROWS, elapsed);
        for (int i = 0; i < CampaignLayout.ROWS; i++) {
            drawRow(c, i + 1, kRows);
        }
        drawBrief(c, kRows);
        float kBtn = PanelMotion.buttonProgress(LEVEL_BUTTONS, elapsed);
        drawTier(c, kBtn);
        CampaignLayout.keyCell(0, ra);
        drawStartKey(c, kBtn, press.pressed() == IDX_START);
        CampaignLayout.keyCell(1, ra);
        drawFlatButton(c, ra, L_BACK, kBtn, press.pressed() == IDX_BACK);
    }

    private void drawRow(Canvas c, int m, float k) {
        if (k <= 0f) return;
        CampaignLayout.rowRect(m - 1, ra);
        int alpha = Math.round(255f * k);
        boolean picked = m == mission;
        kit.roundRect(c, ra, Md3.R_MEDIUM,
                picked ? Md3.primaryContainer() : Md3.surfaceContainerLow(), alpha);
        if (press.pressed() == IDX_ROWS + m - 1) {
            kit.stateLayer(c, ra, Md3.R_MEDIUM, Md3.onSurface(), Md3.STATE_PRESSED_ALPHA);
        }
        int cy = CampaignLayout.lineCenterY(ra);
        int label = picked ? Md3.onPrimaryContainer() : Md3.onSurfaceVariant();
        int value = picked ? Md3.onPrimaryContainer() : Md3.onSurface();
        kit.baked(c, L_MISSION, Md3.PX_LABEL, label, CampaignLayout.labelLeft(ra), cy, alpha, true);
        hud.reset().pad(m, 2);
        kit.number(c, hud, CampaignLayout.labelLeft(ra) + 2 * Md3.PX_LABEL + DrawKit.GAP, cy,
                1, value, alpha);
        kit.baked(c, WaveDirector.zoneName(m - 1), Md3.PX_LABEL, value,
                CampaignLayout.zoneLeft(ra), cy, alpha, true);
    }

    /** 简报容器 + 裁剪后的 8px 正文 + 常驻轨。 */
    private void drawBrief(Canvas c, float k) {
        if (k <= 0f) return;
        int alpha = Math.round(255f * k);
        CampaignLayout.briefRect(ra);
        kit.roundRect(c, ra, Md3.R_MEDIUM, Md3.surfaceContainerLowest(), alpha);
        int vTop = ra.top;

        int clip = c.save();
        c.clipRect(ra.left, ra.top, BriefingLayout.TRACK_LEFT, ra.bottom);
        int from = BriefingLayout.visibleFrom(offset);
        int to = BriefingLayout.visibleTo(offset, CampaignLayout.VIEWPORT_H, lines.length);
        for (int i = from; i < to; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;         // 空行只付行距，不落笔
            int top = vTop + BriefingLayout.lineTop(i, offset);
            kit.baked(c, line, BriefingLayout.GLYPH_H, Md3.onSurface(),
                    BriefingLayout.TEXT_LEFT, top + BriefingLayout.LINE_STEP / 2, alpha, true);
        }
        c.restoreToCount(clip);

        ScrollLayout.thumbRect(BriefingLayout.TRACK_LEFT, CampaignLayout.VIEWPORT_H,
                contentH, offset, rb);
        rb.offsetInPlace(0, vTop);
        kit.rect(c, rb.left, rb.top, rb.right, rb.bottom, Md3.outline(), alpha);
        ScrollLayout.arrowUp(BriefingLayout.TRACK_LEFT, CampaignLayout.VIEWPORT_H, rb);
        rb.offsetInPlace(0, vTop);
        drawChevron(c, rb.left, rb.top, true, Md3.onSurfaceVariant(), alpha);
        ScrollLayout.arrowDown(BriefingLayout.TRACK_LEFT, CampaignLayout.VIEWPORT_H, rb);
        rb.offsetInPlace(0, vTop);
        drawChevron(c, rb.left, rb.top, false, Md3.onSurfaceVariant(), alpha);
    }

    /**
     * 三阶梯形箭头（轨只有 6 格宽，画不出斜线）。这与"面板圆角不削阶梯"那条决策不冲突：
     * 那里否掉的是<b>把矢量圆角退化成阶梯</b>，这里画的是 6 格尺寸的记号——放大三倍之后它仍然是一个三角形。
     */
    private void drawChevron(Canvas c, int left, int top, boolean up, int color, int alpha) {
        for (int k = 0; k < 3; k++) {
            int w = up ? 2 + 2 * k : 6 - 2 * k;
            int x = left + (ScrollLayout.TRACK_W - w) / 2;
            int y = top + 2 + k;
            kit.rect(c, x, y, x + w, y + 1, color, alpha);
        }
    }

    /** 分段选择器：整条一个容器，选中段填高一档色阶（手法逐字照 {@link PauseScreen} 的那三格 tab）。 */
    private void drawTier(Canvas c, float k) {
        if (k <= 0f) return;
        int alpha = Math.round(255f * k);
        CampaignLayout.tierCell(0, ra);
        CampaignLayout.tierCell(CampaignLayout.TIER_CELLS - 1, rb);
        kit.roundRect(c, ra.left, ra.top, rb.right, rb.bottom, Md3.R_MEDIUM,
                Md3.surfaceContainerLow(), alpha);
        for (int i = 0; i < CampaignLayout.TIER_CELLS; i++) {
            CampaignLayout.tierCell(i, rb);
            boolean on = i == tier;
            if (on) {
                kit.roundRect(c, rb.left - 1, rb.top - 1, rb.right + 1, rb.bottom + 1,
                        Md3.R_EXTRA_SMALL, Md3.secondaryContainer(), alpha);
            }
            if (press.pressed() == IDX_TIER + i) {
                kit.stateLayer(c, rb, Md3.R_EXTRA_SMALL, Md3.onSecondaryContainer(),
                        Md3.STATE_PRESSED_ALPHA);
            }
            kit.bakedCentered(c, L_TIER[i], Md3.PX_LABEL,
                    on ? Md3.onSecondaryContainer() : Md3.onSurfaceVariant(),
                    rb.centerX(), rb.centerY(), alpha);
        }
    }

    /** 主操作＝开始任务（实心）。这一屏的"提交"是它，不是返回。 */
    private void drawStartKey(Canvas c, float k, boolean pressed) {
        if (k <= 0f) return;
        int alpha = Math.round(255f * k);
        kit.roundRect(c, ra, Md3.R_SMALL, Md3.primary(), alpha);
        if (pressed) {
            kit.stateLayer(c, ra, Md3.R_SMALL, Md3.onPrimary(), Md3.STATE_PRESSED_ALPHA);
        }
        kit.bakedCentered(c, L_START, Md3.PX_BODY, Md3.onPrimary(), ra.centerX(), ra.centerY(),
                alpha);
    }

    /** 次级键＝tonal 容器（与存档页那两枚同档）。 */
    private void drawFlatButton(Canvas c, RectI r, String label, float k, boolean pressed) {
        if (k <= 0f) return;
        int alpha = Math.round(255f * k);
        kit.roundRect(c, r, Md3.R_SMALL, Md3.secondaryContainer(), alpha);
        if (pressed) {
            kit.stateLayer(c, r, Md3.R_SMALL, Md3.onSecondaryContainer(),
                    Md3.STATE_PRESSED_ALPHA);
        }
        kit.bakedCentered(c, label, Md3.PX_BODY, Md3.onSecondaryContainer(), r.centerX(),
                r.centerY(), alpha);
    }
}
