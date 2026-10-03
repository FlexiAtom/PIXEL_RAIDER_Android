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
import com.flexiatom.pixelraider.gfx.BitmapFont;
import com.flexiatom.pixelraider.gfx.GlowAtlas;
import com.flexiatom.pixelraider.gfx.Ink;
import com.flexiatom.pixelraider.gfx.TextCache;
import com.flexiatom.pixelraider.plat.Screen;

/**
 * 调试页（仅 debug 构建可达，从暂停页那枚「调试」入口进）。规格外的一块内部工具板。
 *
 * <p>它是**纯视图**：开关的真值住在 {@code Game.debugPulseDogfight}，绘制时由调用方把当前状态
 * {@code pulseDogfight} 传进来，面板只负责"点到哪一格"并报出动作码；翻转标志、清模态、解冻时钟、
 * 调 {@code debugForceVictory}/{@code debugEndRun} 全在 {@code Game}——与 {@link PauseScreen}
 * "这里决定点到哪、那里决定发生什么"同一套分工。
 *
 * <p>版面是底部弹层（见 {@link DebugLayout}）：叠在暂停页之上，关掉它战场照旧冻着，与商店同款。
 *
 * <p>四格动作：{@link #ACTION_TOGGLE}（脉冲用格斗弹，就地翻转）、{@link #ACTION_VICTORY}（强制通关）、
 * {@link #ACTION_SETTLE}（立即结算）、{@link #ACTION_BACK}（回暂停页）。后两枚会作废/终结本局，
 * 但**不做二次确认**——能走进这块面板的前提已经是"这是一个在调试的构建"，再叠一层弹窗只是把
 * 取证的手从三个动作拖成五个。
 */
public final class DebugScreen {

    public static final int ACTION_NONE = 0;
    public static final int ACTION_TOGGLE = 1;
    public static final int ACTION_VICTORY = 2;
    public static final int ACTION_SETTLE = 3;
    public static final int ACTION_BACK = 4;

    /** 命中区下标：0 开关 / 1 强制通关 / 2 立即结算 / 3 返回。 */
    private static final int IDX_TOGGLE = 0;
    private static final int IDX_VICTORY = 1;
    private static final int IDX_SETTLE = 2;
    private static final int IDX_BACK = 3;

    /** 每格属于第几级（级联按**行**延后：开关→两枚作弊键→返回），与 {@code PauseScreen} 的分行同口径。 */
    private static final int[] ROW_LEVEL = {0, 1, 1, 2};

    private static final String L_TITLE = "调试";
    private static final String L_TOGGLE = "脉冲用格斗弹";
    private static final String L_ON = "开";
    private static final String L_OFF = "关";
    private static final String L_VICTORY = "强制通关";
    private static final String L_SETTLE = "立即结算";
    private static final String L_BACK = "返回暂停";
    /** 开关行内的左右内缩；汉字单位（开/关）贴右沿时按它留出呼吸位。 */
    private static final int ROW_PAD = 8;

    private final DrawKit kit;
    private final DebugLayout box = new DebugLayout();
    private final PressSelector press = new PressSelector();
    private final RectI[] hit = {new RectI(), new RectI(), new RectI(), new RectI()};
    private int canvasH = Screen.BATTLE_H;
    private int safeTop, safeBottom, minTouch;

    public DebugScreen(BitmapFont font, TextCache text, GlowAtlas glow) {
        kit = new DrawKit(font, text, glow);
    }

    /** 开架：复位按压态。开关状态不存这里——每次 {@link #draw} 由调用方现传，避免两处真值。 */
    public void open() {
        press.clear();
        applyLayout();
    }

    public void layout(int logicH, int safeTop, int safeBottom, int minTouchLogic) {
        canvasH = logicH <= 0 ? Screen.BATTLE_H : logicH;
        this.safeTop = safeTop;
        this.safeBottom = safeBottom;
        this.minTouch = minTouchLogic;
        applyLayout();
    }

    private void applyLayout() {
        box.layout(canvasH, safeTop, safeBottom);
        Widgets.hitRect(box.toggle, minTouch, hit[IDX_TOGGLE]);
        Widgets.hitRect(box.victory, minTouch, hit[IDX_VICTORY]);
        Widgets.hitRect(box.settle, minTouch, hit[IDX_SETTLE]);
        Widgets.hitRect(box.back, minTouch, hit[IDX_BACK]);
    }

    public void pressDown(int x, int y, float elapsed, int pointerId) {
        press.pressDown(under(x, y - lift(elapsed), elapsed), pointerId);
    }

    public void pressDrag(int x, int y, float elapsed, int pointerId) {
        press.dragTo(under(x, y - lift(elapsed), elapsed), pointerId);
    }

    /** @return 五枚 {@code ACTION_*} 之一 */
    public int pressUp(int x, int y, float elapsed, int pointerId) {
        int i = press.releaseTo(under(x, y - lift(elapsed), elapsed), pointerId);
        if (i == PressSelector.NONE) return ACTION_NONE;
        if (i == IDX_TOGGLE) return ACTION_TOGGLE;
        if (i == IDX_VICTORY) return ACTION_VICTORY;
        if (i == IDX_SETTLE) return ACTION_SETTLE;
        return ACTION_BACK;
    }

    public void clearPress() {
        press.clear();
    }

    /** 当前被按住的格下标（0..3），没有则 -1。测试与外部按压态读它。 */
    public int pressedRow() {
        return press.pressed();
    }

    /**
     * 命中的第几格；未命中 {@link PressSelector#NONE}。
     *
     * <p>每格要等自己**淡完**才可点（与 {@link ShopScreen}/{@link PauseScreen} 同一条规矩）：入场途中
     * 误触「立即结算」会当场作废本局，比少响应那 0.28 秒贵得多。两遍 pass（先绘制框再外扩框）同理——
     * 四行外扩到 48dp 后互相压，先认绘制框能把行间的 padding 抢占地挡在外面。
     */
    private int under(int x, int y, float elapsed) {
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < hit.length; i++) {
                if (PanelMotion.buttonProgress(ROW_LEVEL[i], elapsed) < 1f) continue;
                RectI r = pass == 0 ? drawn(i) : hit[i];
                if (r.contains(x, y)) return i;
            }
        }
        return PressSelector.NONE;
    }

    private RectI drawn(int i) {
        switch (i) {
            case IDX_TOGGLE: return box.toggle;
            case IDX_VICTORY: return box.victory;
            case IDX_SETTLE: return box.settle;
            default: return box.back;
        }
    }

    private static int lift(float elapsed) {
        return PanelMotion.liftFor(PanelMotion.enterProgress(elapsed), DebugLayout.PANEL_LIFT);
    }

    // ---- 绘制 -----------------------------------------------------------------------------

    /** @param pulseDogfight 开关当前状态（{@code Game.debugPulseDogfight}），面板只照画，不改它 */
    public void draw(Canvas c, boolean pulseDogfight, float elapsed) {
        float p = PanelMotion.enterProgress(elapsed);
        kit.rect(c, 0, 0, Screen.LOGIC_W, canvasH, Ink.BG_DEEP,
                Math.round(255f * PanelMotion.maskAlphaAt(p)));
        int save = c.save();
        c.translate(0f, lift(elapsed));
        drawFrame(c, p);
        drawTitle(c, p);
        drawToggle(c, pulseDogfight, PanelMotion.buttonProgress(0, elapsed));
        drawFlatButton(c, box.victory, L_VICTORY, PanelMotion.buttonProgress(1, elapsed),
                press.pressed() == IDX_VICTORY);
        drawFlatButton(c, box.settle, L_SETTLE, PanelMotion.buttonProgress(1, elapsed),
                press.pressed() == IDX_SETTLE);
        drawBack(c, PanelMotion.buttonProgress(2, elapsed), press.pressed() == IDX_BACK);
        c.restoreToCount(save);
    }

    /** 容器靠色阶差表达层级（MD3 的 elevation 语法：不加阴影也不加描边）。 */
    private void drawFrame(Canvas c, float p) {
        kit.roundRect(c, box.panel, Md3.R_LARGE, Md3.surfaceContainer(), Math.round(255f * p));
    }

    private void drawTitle(Canvas c, float p) {
        kit.baked(c, L_TITLE, Md3.PX_TITLE, Md3.onSurface(), box.titleBar.left,
                box.titleBar.centerY(), Math.round(255f * p), true);
    }

    /**
     * 开关行：整行即按钮，左标签右状态。态用**颜色**表达（MD3 的语义色口径）——开＝{@code primary}，
     * 关＝{@code onSurfaceVariant}，而不是靠一个会跳位的滑钮；这一屏的字都要横平竖直地对得齐。
     */
    private void drawToggle(Canvas c, boolean on, float k) {
        if (k <= 0f) return;
        RectI r = box.toggle;
        int alpha = Math.round(255f * k);
        kit.roundRect(c, r, Md3.R_MEDIUM, Md3.surfaceContainerHighest(), alpha);
        if (press.pressed() == IDX_TOGGLE) {
            kit.stateLayer(c, r, Md3.R_MEDIUM, Md3.onSurface(), Md3.STATE_PRESSED_ALPHA);
        }
        int cy = r.centerY();
        kit.baked(c, L_TOGGLE, Md3.PX_LABEL, Md3.onSurface(), r.left + ROW_PAD, cy, alpha, true);
        int stateInk = on ? Md3.primary() : Md3.onSurfaceVariant();
        kit.bakedCentered(c, on ? L_ON : L_OFF, Md3.PX_LABEL, stateInk,
                r.right - ROW_PAD - Md3.PX_LABEL / 2, cy, alpha);
    }

    /**
     * 次级作弊键 = MD3 的 tonal 容器（{@code secondaryContainer}）：比返回那枚实心键低一级，
     * 但仍是"点它有件事发生"的形状——两块纯文字并排会读成标签而不是按钮。
     */
    private void drawFlatButton(Canvas c, RectI r, String label, float k, boolean pressed) {
        if (k <= 0f) return;
        int alpha = Math.round(255f * k);
        kit.roundRect(c, r, Md3.R_SMALL, Md3.secondaryContainer(), alpha);
        if (pressed) {
            kit.stateLayer(c, r, Md3.R_SMALL, Md3.onSecondaryContainer(), Md3.STATE_PRESSED_ALPHA);
        }
        kit.bakedCentered(c, label, Md3.PX_BODY, Md3.onSecondaryContainer(),
                r.centerX(), r.centerY(), alpha);
    }

    /** 返回＝这一页唯一的实心主操作：安全退路给最高的位阶，与商店/暂停页的出口同一条语法。 */
    private void drawBack(Canvas c, float k, boolean pressed) {
        if (k <= 0f) return;
        RectI r = box.back;
        int alpha = Math.round(255f * k);
        kit.roundRect(c, r, Md3.R_SMALL, Md3.primary(), alpha);
        if (pressed) {
            kit.stateLayer(c, r, Md3.R_SMALL, Md3.onPrimary(), Md3.STATE_PRESSED_ALPHA);
        }
        kit.bakedCentered(c, L_BACK, Md3.PX_BODY, Md3.onPrimary(),
                r.centerX(), r.centerY(), alpha);
    }
}
