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

import android.graphics.Bitmap;
import android.graphics.Canvas;

import com.flexiatom.pixelraider.core.RectI;
import com.flexiatom.pixelraider.game.Rating;
import com.flexiatom.pixelraider.game.ResultSheet;
import com.flexiatom.pixelraider.gfx.BitmapFont;
import com.flexiatom.pixelraider.gfx.GlowAtlas;
import com.flexiatom.pixelraider.gfx.Ink;
import com.flexiatom.pixelraider.gfx.SpriteFactory;
import com.flexiatom.pixelraider.gfx.TextCache;
import com.flexiatom.pixelraider.plat.Screen;

/**
 * 结算页绘制（规格 §四 结算页那一段）。
 *
 * Canvas 进来时已经被平移到战斗区原点（与 HUD 同一套坐标系），所以这里全部用
 * {@link ResultLayout} 的战斗区相对坐标；命中框由 {@link #layout} 换算成屏幕坐标。
 *
 * 绘制端**不自己比时间**：每段问 {@link RevealScript} 要一个 0..1 推进度，为 0 就整段跳过。
 * 淡入口径（烘焙色固定、透明度只走 Paint alpha）住在 {@link DrawKit} 一处，这里只管排布。
 */
public final class ResultScreen {

    public static final int ACTION_NONE = 0;
    public static final int ACTION_RETRY = 1;
    public static final int ACTION_MENU = 2;
    private static final int[] BTN_ACTION = {ACTION_RETRY, ACTION_MENU};

    private static final String L_TITLE = "战绩归零";
    private static final String L_SCORE = "分数";
    private static final String L_NEW_RECORD = "新纪录";
    private static final String L_TO_RECORD = "距纪录";
    private static final String L_FIRST_RUN = "首次出击";
    private static final String L_LAST = "上局";
    private static final String L_RANK = "综合评级";
    private static final String L_RETRY = "重试";
    private static final String L_MENU = "返回菜单";
    /**
     * 下标对齐 {@link Rating} 的五维。**包私有**是给 {@code ResultScreenTest} 看的：行数对不上就是
     * 运行期每帧一次越界异常，而那条数组只有这里读得到。别在别处引用。
     */
    static final String[] L_DIM = {"生存", "击杀", "效率", "超载", "风格"};
    /** 数据卡五行，下标对齐 {@link RevealScript#CARD_ROWS}。同上，只为钉行数而露出来。 */
    static final String[] L_CARD = {"击杀", "命中率", "最高连击", "到达波次", "存活时长"};

    private static final int TITLE_SLIDE = 40;
    private static final int CARD_SLIDE = 24;
    private static final int BIG = 2;              // 分数与等级：点阵放大两倍
    /** 容器底的浓度上限：再高就把战场压没了，"记得自己死在哪"是规格 §四 的要求。 */
    private static final int SHEET_ALPHA = 210;
    private static final int MID_X = (ResultLayout.LEFT + ResultLayout.RIGHT) / 2;

    private final DrawKit kit;
    private final HudText hud = new HudText(64);
    private final RectI ra = new RectI();
    private final RectI rb = new RectI();
    private final RectI rc = new RectI();
    private final RectI retryDraw = new RectI();
    private final RectI menuDraw = new RectI();
    private final RectI retryHit = new RectI();
    private final RectI menuHit = new RectI();
    /** 两枚按钮的**外扩命中框**（画布坐标），下标对齐 {@link #BTN_ACTION}；同时也是按压区。 */
    private final RectI[] btnHit = {retryHit, menuHit};
    /** 遮罩要铺满画布，得知道这一页钉在哪、画布有多高。 */
    private int pageTop;
    private int logicH = Screen.BATTLE_H;
    private final PressSelector press = new PressSelector();

    public ResultScreen(BitmapFont font, TextCache text, GlowAtlas glow) {
        kit = new DrawKit(font, text, glow);
    }

    /** 这一页自己有多高。整页模态按它居中（{@code Screen.Metrics.pageTop(int)}），不再假定 320。 */
    public int pageHeight() {
        return ResultLayout.PAGE_H;
    }

    /**
     * 命中框换算成屏幕坐标（与暂停按钮同一套约定：绘制用相对坐标、命中用屏幕坐标）。
     * <p>{@code pageTop} 是这一页在逻辑画布里的居中原点，{@code logicH} 只用来把遮罩铺满
     * 整张画布——长屏上遮罩只盖住中间那一页，等于在亮着的战场里贴了一块暗板。
     */
    public void layout(int minTouchLogic, int pageTop, int logicH) {
        this.pageTop = pageTop;
        this.logicH = logicH;
        ResultLayout.buttons(retryDraw, menuDraw);
        Widgets.hitRect(retryDraw, minTouchLogic, retryHit);
        Widgets.hitRect(menuDraw, minTouchLogic, menuHit);
        retryHit.offsetInPlace(0, pageTop);
        menuHit.offsetInPlace(0, pageTop);
    }

    /**
     * 按下：只登记按压态。防误触窗口（规格 §四 取消"点任意处重开"）挡在这里——
     * 3.2 秒之内连按压态都不给显，否则按钮还没淡完就亮起来，读起来像"可以点了"。
     */
    public void pressDown(int x, int y, float t, int pointerId) {
        if (!RevealScript.buttonsLive(t)) return;
        press.pressDown(under(x, y), pointerId);
    }

    public void pressDrag(int x, int y, int pointerId) {
        press.dragTo(under(x, y), pointerId);
    }

    /** @return 提交的按钮动作；滑出去取消时是 {@link #ACTION_NONE} */
    public int pressUp(int x, int y, int pointerId) {
        int i = press.releaseTo(under(x, y), pointerId);
        return i == PressSelector.NONE ? ACTION_NONE : BTN_ACTION[i];
    }

    public void clearPress() {
        press.clear();
    }

    /** 手指下面第几枚按钮（外扩命中框，画布坐标）；没有则 {@link PressSelector#NONE}。 */
    private int under(int x, int y) {
        for (int i = 0; i < btnHit.length; i++) {
            if (btnHit[i].contains(x, y)) return i;
        }
        return PressSelector.NONE;
    }

    /** @param t 死亡起算的界面秒数（规格 §五：暂停不该把入场动画冻在半路） */
    public void draw(Canvas c, ResultSheet s, float t) {
        // MASK 的 alpha 位就是遮罩浓度——再 setAlpha(255) 会把它抹成实色，"记得自己死在哪"就没了
        kit.fill.setColor(Ink.MASK);
        // 绘制组已经平移过 pageTop，这里回到画布绝对坐标再减一次：遮罩要盖住整张画布
        kit.rf.set(0, -pageTop, Screen.LOGIC_W, logicH - pageTop);
        c.drawRect(kit.rf, kit.fill);
        kit.fill.setAlpha(255);

        drawTitle(c, t);
        drawDivider(c, t);
        drawScore(c, s, t);
        drawRecord(c, s, t);
        drawRank(c, s, t);
        drawDims(c, s, t);
        drawCards(c, s, t);
        drawCompare(c, s, t);
        drawButtons(c, t);
    }

    private void drawTitle(Canvas c, float t) {
        float p = RevealScript.progressOf(RevealScript.TITLE, t);
        if (p <= 0f) return;
        ResultLayout.titleRect(ra);
        int cy = ra.centerY() - (int) ((1f - Easing.easeOutBack(p)) * TITLE_SLIDE);
        // 规格："发光从 16 降到 5"——砸下来时光晕是散的，落定后只留一圈边
        int extra = Math.round(16f - 11f * p);
        kit.glowAt(c, GlowAtlas.SHEEN, ra.centerX(), cy, ra.height() + extra * 2, Md3.error(),
                Math.round(120 * p));
        kit.bakedCentered(c, L_TITLE, Md3.PX_DISPLAY, Md3.error(),
                ra.centerX(), cy, Math.round(255 * p));
    }

    private void drawDivider(Canvas c, float t) {
        float p = RevealScript.progressOf(RevealScript.DIVIDER, t);
        if (p <= 0f) return;
        int half = Math.round((ResultLayout.RIGHT - ResultLayout.LEFT) / 2f
                * Easing.easeOutCubic(p));
        kit.rect(c, MID_X - half, ResultLayout.DIVIDER_Y, MID_X + half, ResultLayout.DIVIDER_Y + 1,
                Md3.outlineVariant(), Math.round(255 * p));
    }

    private void drawScore(Canvas c, ResultSheet s, float t) {
        float p = RevealScript.progressOf(RevealScript.SCORE, t);
        if (p <= 0f) return;
        ResultLayout.scoreRect(ra);
        hud.reset().num(s.current.score);
        int dw = BitmapFont.textWidth(hud.buffer(), hud.length(), BIG);
        Bitmap label = kit.text.bake(L_SCORE, Md3.PX_LABEL, Md3.onSurfaceVariant());
        int left = ra.centerX() - (label.getWidth() + DrawKit.GAP + dw) / 2;
        kit.ink.setAlpha(Math.round(255 * p));
        SpriteFactory.draw(c, label, left, ra.centerY() - label.getHeight() / 2,
                label.getWidth(), label.getHeight(), kit.ink);
        kit.ink.setColor(Md3.onSurface());
        kit.font.drawScaled(c, hud.buffer(), hud.length(), left + label.getWidth() + DrawKit.GAP,
                ra.centerY() - BitmapFont.GLYPH_H * BIG / 2, BIG, kit.ink);
        kit.ink.setAlpha(255);
    }

    /** 纪录判定：破纪录说"新纪录"，没破说还差多少——"0.9 倍纪录"这种话玩家读不出来。 */
    private void drawRecord(Canvas c, ResultSheet s, float t) {
        float p = RevealScript.progressOf(RevealScript.RECORD, t);
        if (p <= 0f) return;
        ResultLayout.recordRect(ra);
        if (s.newBest) {
            kit.glowAt(c, GlowAtlas.SPARK, ra.centerX(), ra.centerY(), 22, Md3.primary(),
                    Math.round(140 * p));
            kit.bakedCentered(c, L_NEW_RECORD, Md3.PX_LABEL, Md3.primary(),
                    ra.centerX(), ra.centerY(), Math.round(255 * p));
            return;
        }
        if (!s.bestBefore.present()) {
            kit.bakedCentered(c, L_FIRST_RUN, Md3.PX_LABEL, Md3.onSurfaceVariant(),
                    ra.centerX(), ra.centerY(), Math.round(255 * p));
            return;
        }
        hud.reset().num(Math.max(0L, s.bestBefore.score - s.current.score));
        kit.labelNumber(c, hud, L_TO_RECORD, MID_X, ra.centerY(), Md3.onSurfaceVariant(),
                Md3.onSurfaceVariant(), Math.round(255 * p), 1);
    }

    private void drawRank(Canvas c, ResultSheet s, float t) {
        float p = RevealScript.progressOf(RevealScript.OVERALL, t);
        if (p <= 0f) return;
        ResultLayout.rankRect(ra);
        int color = rankColor(s.letterIndex);
        kit.glowAt(c, GlowAtlas.HALO, MID_X, ra.centerY(),
                BitmapFont.GLYPH_H * BIG + 12, color, Math.round(150 * p));
        kit.baked(c, L_RANK, Md3.PX_LABEL, Md3.onSurfaceVariant(), ResultLayout.LEFT,
                ra.centerY(), Math.round(255 * p), true);
        hud.reset().chr(Rating.letterOf(s.letterIndex));
        int w = BitmapFont.textWidth(hud.buffer(), 1, BIG);
        kit.ink.setColor(color);
        kit.ink.setAlpha(Math.round(255 * p));
        kit.font.drawScaled(c, hud.buffer(), 1, MID_X - w / 2,
                ra.centerY() - BitmapFont.GLYPH_H * BIG / 2, BIG, kit.ink);
        kit.ink.setAlpha(255);
    }

    /**
     * 字母档压成三档：<b>达标线以上强调、B 中性、C/D 压平</b>。
     *
     * 原来是 S 绿 / A 橙 / B 白 / C·D 灰四档——那是战场色板的语法（每种敌人一个颜色）。MD3 里
     * 颜色只承担语义角色，而这一屏的语义只有一条："这次算不算达标"。等级本身已经由字母给出，
     * 颜色再分四档是给同一个事实配两份真值，还会让人去猜"橙色是什么意思"。**这是一处有意的信息
     * 减量**（A 与 S 不再可比），代价写在池提案里。
     */
    private static int rankColor(int letterIndex) {
        if (letterIndex >= 3) return Md3.primary();
        return letterIndex >= 2 ? Md3.onSurface() : Md3.onSurfaceVariant();
    }

    private void drawDims(Canvas c, ResultSheet s, float t) {
        float p = RevealScript.progressOf(RevealScript.RADAR, t);
        if (p <= 0f) return;
        drawSheet(c, ResultLayout.RADAR_TOP, ResultLayout.RADAR_BOTTOM, p);
        int worst = s.worstDim();
        float grow = Easing.easeOutCubic(p);
        for (int i = 0; i < Rating.COUNT; i++) {
            ResultLayout.radarRowRect(i, ra, rb, rc);
            drawBar(c, L_DIM[i], s.dims[i] * grow, hud.reset().percent(s.dims[i]),
                    rb, ra, rc, i == worst);
        }
    }

    private void drawCards(Canvas c, ResultSheet s, float t) {
        drawSheet(c, ResultLayout.CARDS_TOP, ResultLayout.CARDS_BOTTOM,
                RevealScript.cardProgressOf(0, t));
        for (int row = 0; row < RevealScript.CARD_ROWS; row++) {
            float p = RevealScript.cardProgressOf(row, t);
            if (p <= 0f) continue;
            ResultLayout.cardRowRect(row, ra);
            int dx = -(int) ((1f - Easing.easeOutCubic(p)) * CARD_SLIDE);
            int alpha = Math.round(255 * p);
            Bitmap label = kit.text.bake(L_CARD[row], Md3.PX_LABEL, Md3.onSurfaceVariant());
            kit.ink.setAlpha(alpha);
            SpriteFactory.draw(c, label, ra.left + dx, ra.centerY() - label.getHeight() / 2,
                    label.getWidth(), label.getHeight(), kit.ink);
            cardValue(s, row, hud.reset());
            int w = BitmapFont.textWidth(hud.buffer(), hud.length());
            kit.ink.setColor(Md3.onSurface());
            kit.font.draw(c, hud.buffer(), hud.length(), ra.right - w + dx,
                    ra.centerY() - BitmapFont.GLYPH_H / 2, kit.ink);
            kit.ink.setAlpha(255);
        }
    }

    /** 第 row 行读数；下标顺序必须与 {@link #L_CARD} 一致。 */
    private HudText cardValue(ResultSheet s, int row, HudText out) {
        switch (row) {
            case 0: return out.num(s.current.kills);
            case 1: return out.percent(s.current.accuracy01());
            case 2: return out.num(s.current.peakCombo);
            case 3: return out.num(s.current.waveReached);
            default: return out.clock(s.current.seconds);
        }
    }

    private void drawCompare(Canvas c, ResultSheet s, float t) {
        float p = RevealScript.progressOf(RevealScript.COMPARE, t);
        if (p <= 0f) return;
        ResultLayout.compareRect(ra);
        int alpha = Math.round(255 * p);
        if (!s.lastBefore.present()) {
            kit.bakedCentered(c, L_FIRST_RUN, Md3.PX_LABEL, Md3.onSurfaceVariant(),
                    ra.centerX(), ra.centerY(), alpha);
            return;
        }
        long delta = s.current.score - s.lastBefore.score;
        hud.reset();
        if (delta >= 0) hud.chr('+');
        hud.num(delta);
        kit.labelNumber(c, hud, L_LAST, MID_X, ra.centerY(), Md3.onSurfaceVariant(),
                delta >= 0 ? Md3.primary() : Md3.error(), alpha, 1);
    }

    /** 按钮只淡入，不做循环动画（规格 §四：一直闪的按钮会在余光里抢注意力）。 */
    private void drawButtons(Canvas c, float t) {
        float p = RevealScript.progressOf(RevealScript.BUTTONS, t);
        if (p <= 0f) return;
        int alpha = Math.round(255 * p);
        int on = press.pressed();
        drawFilledButton(c, retryDraw, L_RETRY, alpha, on == 0);
        drawTextButton(c, menuDraw, L_MENU, alpha, on == 1);
    }

    /**
     * filled + text 的配对（MD3 按钮层级）。主次靠"有没有底"区分，不靠大小也不靠描边亮度——
     * 规格 §四 那句"主次靠样式区分"照样成立，只是描边这一维被换成了色块这一维。
     *
     * <p>返回菜单刻意用 text button：它不是破坏性动作（结算页已经死了，没有可失去的东西），
     * 给它 {@code error} 色会读成"删档"。
     */
    private void drawFilledButton(Canvas c, RectI box, String label, int alpha, boolean pressed) {
        kit.roundRect(c, box, Md3.R_SMALL, Md3.primary(), alpha);
        if (pressed) {
            kit.stateLayer(c, box, Md3.R_SMALL, Md3.onPrimary(), Md3.STATE_PRESSED_ALPHA);
        }
        kit.bakedCentered(c, label, Md3.PX_BODY, Md3.onPrimary(), box.centerX(), box.centerY(),
                alpha);
    }

    private void drawTextButton(Canvas c, RectI box, String label, int alpha, boolean pressed) {
        if (pressed) {
            kit.stateLayer(c, box, Md3.R_EXTRA_SMALL, Md3.primary(), Md3.STATE_PRESSED_ALPHA);
        }
        kit.bakedCentered(c, label, Md3.PX_BODY, Md3.primary(),
                box.centerX(), box.centerY(), alpha);
    }

    /**
     * 一组行的容器底（MD3 的 card）。
     *
     * <p>为什么是**半透明**：这一屏要让人记住自己死在哪（规格 §四），战场必须透得出来。
     * 所以这里不改遮罩口径，只给"需要轨道色的那两组"（五维条、数据卡）垫一层容器色阶——
     * 没有它，进度条的轨道色就只能压在弹幕上。alpha 走 {@code Paint}，色本身仍是固定不透明色。
     */
    private void drawSheet(Canvas c, int top, int bottom, float p) {
        if (p <= 0f) return;
        kit.roundRect(c, ResultLayout.LEFT - 4, top - 2, ResultLayout.RIGHT + 4, bottom + 2,
                Md3.R_MEDIUM, Md3.surfaceContainerLow(), Math.round(SHEET_ALPHA * p));
    }

    /** 一条「标签 + 条体 + 读数」；几何来自 {@link ResultLayout}，这里只负责落笔。 */
    private void drawBar(Canvas c, String label, float norm01, HudText value,
                         RectI bar, RectI labelBox, RectI valueBox, boolean isWorst) {
        int color = isWorst ? Md3.error() : Md3.primary();
        kit.baked(c, label, Md3.PX_LABEL, isWorst ? color : Md3.onSurfaceVariant(),
                labelBox.left, labelBox.centerY(), 255, true);
        kit.rect(c, bar.left, bar.top, bar.right, bar.bottom, Md3.surfaceContainerHighest(), 255);
        kit.rect(c, bar.left, bar.top, bar.left + ResultLayout.barFillWidth(norm01), bar.bottom,
                color, 255);
        kit.numberRight(c, value, valueBox.right, valueBox.centerY(), 1,
                isWorst ? color : Md3.onSurface(), 255);
    }
}
