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
import com.flexiatom.pixelraider.game.Balance;
import com.flexiatom.pixelraider.game.GrowthTree;
import com.flexiatom.pixelraider.gfx.BitmapFont;
import com.flexiatom.pixelraider.gfx.GlowAtlas;
import com.flexiatom.pixelraider.gfx.Ink;
import com.flexiatom.pixelraider.gfx.TextCache;
import com.flexiatom.pixelraider.plat.Screen;

/**
 * 主菜单页绘制（规格 §四「主次靠样式区分」那一段 + 成长页的宿主）。
 *
 * <p>与 {@link ResultScreen} 同一类坐标系：Canvas 进来时已经被平移到这一页的居原点，
 * 所以这里全用 {@link MenuLayout} 的页内相对坐标；命中框在 {@link #layout} 里换算成屏幕坐标。
 *
 * <p>标题走 ASCII 点阵而不是内嵌中文字体，有两个理由：点阵自带 8×5 的等宽网格，大字不需要
 * 重新烘焙一张 24px 的贴图；而内嵌字集是**按码位 subsets 切出来的**，"像素突击"这四个字里
 * 有没有全要看切表——把标题写成点阵，这条风险就不存在（汉字通道由 {@code EmbeddedFontTest}
 * 逐字面量守，点阵通道只覆盖 32..126，两者互不越界）。
 */
public final class MenuScreen {

    public static final int ACTION_NONE = 0;
    public static final int ACTION_RUN = 1;
    public static final int ACTION_GROWTH = 2;

    private static final int[] BTN_ACTION = {ACTION_RUN, ACTION_GROWTH};
    // 三枚字面量对测试可见：它们与 MenuLayout 的带区是**跨文件耦合**，宽度只对得上与否要能红着脸报出来。
    static final String L_TITLE = "PIXEL RAIDER";
    private static final String L_CHIP = "芯片";
    static final String L_GROWTH = "成长";
    static final String L_RUN = "开始新一局";

    private static final int BIG = 2;
    private static final int MID_X = (MenuLayout.LEFT + MenuLayout.RIGHT) / 2;

    private final DrawKit kit;
    private final HudText hud = new HudText(32);
    private final RectI runDraw = new RectI();
    private final RectI growthDraw = new RectI();
    private final RectI runHit = new RectI();
    private final RectI growthHit = new RectI();
    private final RectI[] btnHit = {runHit, growthHit};
    private final RectI ra = new RectI();
    private int pageTop;
    private int logicH = Screen.BATTLE_H;
    private final PressSelector press = new PressSelector();

    public MenuScreen(BitmapFont font, TextCache text, GlowAtlas glow) {
        kit = new DrawKit(font, text, glow);
    }

    public int pageHeight() {
        return MenuLayout.PAGE_H;
    }

    /** @param pageTop 这一页在逻辑画布上的居中原点；@param logicH 只用来把遮罩铺满整张画布 */
    public void layout(int minTouchLogic, int pageTop, int logicH) {
        this.pageTop = pageTop;
        this.logicH = logicH;
        MenuLayout.buttons(runDraw, growthDraw);
        Widgets.hitRect(runDraw, minTouchLogic, runHit);
        Widgets.hitRect(growthDraw, minTouchLogic, growthHit);
        runHit.offsetInPlace(0, pageTop);
        growthHit.offsetInPlace(0, pageTop);
    }

    public void pressDown(int x, int y, float elapsed, int pointerId) {
        press.pressDown(under(x, y, elapsed), pointerId);
    }

    public void pressDrag(int x, int y, float elapsed, int pointerId) {
        press.dragTo(under(x, y, elapsed), pointerId);
    }

    public int pressUp(int x, int y, float elapsed, int pointerId) {
        int i = press.releaseTo(under(x, y, elapsed), pointerId);
        return i == PressSelector.NONE ? ACTION_NONE : BTN_ACTION[i];
    }

    public void clearPress() {
        press.clear();
    }

    /**
     * 手指下面第几枚按钮。这里**不做** {@link ShopScreen} 那套"先绘制框再外扩框"的两遍 pass：
     * 两枚按钮各 108 宽、横向的外扩是空转，纵向之外又没有任何别的目标可抢，一遍外扩框就够
     * （与 {@link ResultScreen} 同一情形——那里也只有单遍）。
     *
     * <p>每枚要等自己**淡完**才可点：入口淡入途中就注册命中，等于在玩家看清"这里有按钮"之前
     * 就把「开始新一局」那一下作废掉本局的动作交了出去。
     */
    private int under(int x, int y, float elapsed) {
        if (PanelMotion.enterProgress(elapsed) < 1f) return PressSelector.NONE;
        for (int i = 0; i < btnHit.length; i++) {
            if (PanelMotion.buttonProgress(i, elapsed) < 1f) continue;
            if (btnHit[i].contains(x, y)) return i;
        }
        return PressSelector.NONE;
    }

    /** @param tree 只为读两个数：钱包余额与已投入等级。这一页不写任何状态。 */
    public void draw(Canvas c, GrowthTree tree, float elapsed) {
        float p = PanelMotion.enterProgress(elapsed);
        kit.fill.setColor(Ink.MASK);
        kit.rf.set(0, -pageTop, Screen.LOGIC_W, logicH - pageTop);
        c.drawRect(kit.rf, kit.fill);
        kit.fill.setAlpha(255);

        drawTitle(c, p);
        drawMeta(c, tree, p);
        drawButtons(c, elapsed);
    }

    private void drawTitle(Canvas c, float p) {
        MenuLayout.titleRect(ra);
        int alpha = Math.round(255f * p);
        MenuLayout.titleGlowRect(ra);
        kit.glowAt(c, GlowAtlas.SHEEN, MID_X, ra.centerY(), ra.height() + 8,
                Md3.primary(), Math.round(90f * p));
        MenuLayout.titleRect(ra);
        hud.reset().text(L_TITLE);
        int w = BitmapFont.textWidth(hud.buffer(), hud.length(), BIG);
        kit.number(c, hud, MID_X - w / 2, ra.centerY(), BIG, Md3.onSurface(), alpha);
    }

    private void drawMeta(Canvas c, GrowthTree tree, float p) {
        MenuLayout.metaRect(ra);
        int cy = ra.centerY();
        int alpha = Math.round(255f * p);
        hud.reset().num(tree.wallet());
        kit.labelNumber(c, hud, L_CHIP, (MenuLayout.LEFT + MID_X) / 2, cy,
                Md3.onSurfaceVariant(), Md3.primary(), alpha, 1);
        hud.reset().num(tree.totalLevels()).chr('/').num(Balance.Growth.ITEMS * Balance.Growth.MAX_LEVEL);
        kit.labelNumberRight(c, hud, L_GROWTH, MenuLayout.RIGHT, cy,
                Md3.onSurfaceVariant(), Md3.onSurface(), alpha, 1);
    }

    /** 按钮只淡入，不做循环动画（与结算页同一条规矩：一直闪的按钮会在余光里抢注意力）。 */
    private void drawButtons(Canvas c, float elapsed) {
        int on = press.pressed();
        float k0 = PanelMotion.buttonProgress(0, elapsed);
        if (k0 > 0f) {
            int alpha = Math.round(255f * k0);
            kit.roundRect(c, runDraw, Md3.R_SMALL, Md3.primary(), alpha);
            if (on == 0) {
                kit.stateLayer(c, runDraw, Md3.R_SMALL, Md3.onPrimary(), Md3.STATE_PRESSED_ALPHA);
            }
            kit.bakedCentered(c, L_RUN, Md3.PX_BODY, Md3.onPrimary(),
                    runDraw.centerX(), runDraw.centerY(), alpha);
        }
        float k1 = PanelMotion.buttonProgress(1, elapsed);
        if (k1 <= 0f) return;
        int alpha = Math.round(255f * k1);
        if (on == 1) {
            kit.stateLayer(c, growthDraw, Md3.R_EXTRA_SMALL, Md3.primary(), Md3.STATE_PRESSED_ALPHA);
        }
        kit.bakedCentered(c, L_GROWTH, Md3.PX_BODY, Md3.primary(),
                growthDraw.centerX(), growthDraw.centerY(), alpha);
    }
}
