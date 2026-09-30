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
import com.flexiatom.pixelraider.game.Balance;
import com.flexiatom.pixelraider.game.ShopRules;
import com.flexiatom.pixelraider.game.ShopRun;
import com.flexiatom.pixelraider.gfx.BitmapFont;
import com.flexiatom.pixelraider.gfx.GlowAtlas;
import com.flexiatom.pixelraider.gfx.Ink;
import com.flexiatom.pixelraider.gfx.SpriteFactory;
import com.flexiatom.pixelraider.gfx.SpriteSheets;
import com.flexiatom.pixelraider.gfx.TextCache;
import com.flexiatom.pixelraider.plat.Screen;

/**
 * 升级商店面板（规格 §升级商店）。
 *
 * <p>整卡即按钮——MD3 的卡片本来就可点；再往卡里塞一枚"购买"小按钮会把 212 逻辑像素
 * 宽的卡面切成两半，那句 tip 就得折行。价格写在名字行右端：它既是标签，也是可点性的承诺。
 *
 * <p>每一页摆几张、要翻几页全由 {@link ShopLayout} 按可用高倒推，这里只问"这一页第几格是什么"。
 * 翻页是面板自己的状态：它不动金币、不动等级、也不惊动 {@code Game}，所以它**不是**一种动作码
 * （{@link #pressUp} 翻完页直接报 {@link #ACTION_NONE}）。
 *
 * <p>面板只决定"点到哪一格"，扣币与实际生效（回血、给炸弹、转好超载、抬上限）全在 {@code Game}：
 * 与 {@link PauseScreen} 同一套分工。所以报出去的动作是 {@link #ACTION_BUY} 加**槽位**而不是卡 id
 * ——卡每次开架都会重排，槽位也只在当前这一页里有效。
 */
public final class ShopScreen {

    public static final int ACTION_NONE = 0;
    public static final int ACTION_NEXT = 1;
    /** 提交动作 = {@code ACTION_BUY + 槽位}（槽位 0..{@link ShopLayout#MAX_CARDS}-1，**当前页内**）。 */
    public static final int ACTION_BUY = 2;

    /** 命中区下标：卡槽之后依次是出口、上一页、下一页。出口沿用旧下标，动作码区间不动。 */
    private static final int IDX_PRIMARY = ShopLayout.MAX_CARDS;
    private static final int IDX_PREV = ShopLayout.MAX_CARDS + 1;
    private static final int IDX_LAST = ShopLayout.MAX_CARDS + 2;

    private static final String L_TITLE = "升级商店";
    private static final String L_COINS = "金币";
    private static final String L_NEXT = "进入下一波";
    /** 暂停店那个入口关掉的只是面板，战场还在原地冻着——所以它不能写着"进入下一波"。 */
    private static final String L_BACK = "返回";
    private static final String L_PAGE_PREV = "上一页";
    private static final String L_PAGE_NEXT = "下一页";
    private static final String L_MAXED = "已满级";
    private static final String L_EMPTY = "货架空了";
    private static final String L_READY = "立即就绪";
    /** 「随机强化」那一档的核心位：它没有数值可画，画的是"抽哪一种"这件事（{@code CORE_CHOICE}）。 */
    private static final String L_CHOICE = "四选一";
    /** 核心数字的单位。汉字不能进点阵通道，见 {@link #drawCore}。 */
    private static final String L_UNIT_POINTS = "点";
    private static final String L_UNIT_COUNT = "枚";
    /** 扳机卡的单位：它 2026-09-26 起卖的是发数，不是射速百分比。 */
    private static final String L_UNIT_SHOTS = "发";
    /** 三个汉字的宽度按 12px 网格算死：为了一句右对齐去问 TextCache 要位图，不值。 */
    private static final int MAXED_W = 3 * Md3.PX_LABEL;

    private final DrawKit kit;
    private final ShopLayout box = new ShopLayout();
    private final HudText hud = new HudText(32);
    private final PressSelector press = new PressSelector();
    /** 命中区：一整架卡 + 出口 + 两枚翻页箭头（箭头不常驻，见 {@link #tappable}）。 */
    private final RectI[] hit = boxes(IDX_LAST + 1);
    private final int[] offer = new int[ShopLayout.MAX_CARDS];
    /**
     * 开架时那一瞬的现状，**引用** {@code Game} 持有的实例（不是副本）。
     *
     * <p>买完之后金币与等级都变了，面板要立刻把新价格和"这张已买不起"反映出来；Game 在每次结算
     * 购买后重填同一个对象即可。没有 {@link #open} 之前它是个字段初值——画出来是一张空架，不崩。
     */
    private ShopRules.Snapshot view = new ShopRules.Snapshot();
    private int offered;
    private int page;
    private int canvasH = Screen.BATTLE_H;
    /** {@link #layout} 收到的那三个尺寸，开架与翻页时要用同一份重算（板子高随这一页的卡数变）。 */
    private int safeTop, safeBottom, minTouch;

    public ShopScreen(BitmapFont font, TextCache text, GlowAtlas glow) {
        kit = new DrawKit(font, text, glow);
    }

    private static RectI[] boxes(int n) {
        RectI[] r = new RectI[n];
        for (int i = 0; i < n; i++) r[i] = new RectI();
        return r;
    }

    /** 由 {@code Game} 在开架时调一次（{@link ShopRules#selectOffer} 的结果 + 现状快照）。 */
    public void open(int[] ids, int count, ShopRules.Snapshot snapshot) {
        offered = Math.max(0, Math.min(count, offer.length));
        System.arraycopy(ids, 0, offer, 0, offered);
        view = snapshot == null ? new ShopRules.Snapshot() : snapshot;
        page = 0;
        press.clear();
        applyLayout();
    }

    public int offeredCount() {
        return offered;
    }

    /** @return 这一页第 {@code slot} 格卖的那张卡，空位 -1 */
    public int offerAt(int slot) {
        int i = box.pageStart + slot;
        return slot < 0 || i < 0 || i >= offered ? -1 : offer[i];
    }

    public void layout(int logicH, int safeTop, int safeBottom, int minTouchLogic) {
        canvasH = logicH <= 0 ? Screen.BATTLE_H : logicH;
        this.safeTop = safeTop;
        this.safeBottom = safeBottom;
        this.minTouch = minTouchLogic;
        applyLayout();
    }

    /**
     * 重算板子与命中区。
     *
     * <p>为什么不能只在几何投递时算一次：面板高度等于**这一页**的内容高，于是开架（0 张→3 张→
     * 暂停店 6 张）与翻页都会改它。只按 {@code installGeometry} 那一次算的话，暂停店第二页会照着
     * 第一页的矩形命中——玩家点的是空处。
     */
    private void applyLayout() {
        box.layout(canvasH, safeTop, safeBottom, offered, page);
        for (int i = 0; i < ShopLayout.MAX_CARDS; i++) {
            Widgets.hitRect(box.cards[i], minTouch, hit[i]);
        }
        Widgets.hitRect(box.next, minTouch, hit[IDX_PRIMARY]);
        Widgets.hitRect(box.pagePrev, minTouch, hit[IDX_PREV]);
        Widgets.hitRect(box.pageNext, minTouch, hit[IDX_LAST]);
    }

    public void pressDown(int x, int y, float elapsed, int pointerId) {
        press.pressDown(under(x, y - lift(elapsed), elapsed), pointerId);
    }

    public void pressDrag(int x, int y, float elapsed, int pointerId) {
        press.dragTo(under(x, y - lift(elapsed), elapsed), pointerId);
    }

    /** @return {@link #ACTION_NONE}、{@link #ACTION_NEXT} 或 {@code ACTION_BUY + 槽位} */
    public int pressUp(int x, int y, float elapsed, int pointerId) {
        int i = press.releaseTo(under(x, y - lift(elapsed), elapsed), pointerId);
        if (i == PressSelector.NONE) return ACTION_NONE;
        if (i == IDX_PRIMARY) return ACTION_NEXT;
        if (i >= IDX_PREV) {
            turnPage(i == IDX_PREV ? -1 : 1);
            return ACTION_NONE;
        }
        return ACTION_BUY + i;
    }

    /** 翻一页，然后立刻把矩形换掉——抬起之后同一根手指还可能继续按在新一页的卡上。 */
    private void turnPage(int delta) {
        int p = box.page + delta;
        if (p < 0 || p >= box.pages) return;
        page = p;
        applyLayout();
    }

    public void clearPress() {
        press.clear();
    }

    /**
     * 当前被按住的格子下标（0..{@link ShopLayout#MAX_CARDS}-1 = 槽位，往后是出口与两枚箭头），
     * 没有则 -1。测试与外部的按压态都读它。
     */
    public int pressedSlot() {
        return press.pressed();
    }

    /**
     * 命中的第几格；未命中 {@link PressSelector#NONE}。
     *
     * <p>买不起的卡**不注册命中**：点一张灰卡"没反应"是真的没反应，那比看着它灰着更糟
     * （规格点名的"点了没反应"bug 类）。出口不看余额，永远可点；箭头只看翻不翻得动。
     *
     * <p>每一格还要等自己**淡完**才可点（与 {@link PauseScreen} 同一条规矩）：入场 0.28 秒里
     * 第三张卡只淡了两成，这时候点它等于买走一张玩家还没看清的卡；出口更糟——它在 0.02 秒
     * 就有 6% 的不透明度，手快的人会在看见商店之前就把这一波的商店关掉了。
     *
     * <p>两遍 pass（先绘制框再外扩框）与 {@link PauseScreen} 同理：卡 60 高、间距 4，
     * 外扩到 48dp 之后互相压，只认外扩框会把上一张的边让给下一张。
     */
    private int under(int x, int y, float elapsed) {
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < hit.length; i++) {
                if (!tappable(i, elapsed)) continue;
                RectI r = pass == 0 ? drawn(i) : hit[i];
                if (r.contains(x, y)) return i;
            }
        }
        return PressSelector.NONE;
    }

    /** 这一格现在该不该受理：内容上有效（买得起 / 翻得动）而且已经淡完。 */
    private boolean tappable(int i, float elapsed) {
        if (i < ShopLayout.MAX_CARDS) {
            return i < box.shown && purchasable(i) && PanelMotion.buttonProgress(i, elapsed) >= 1f;
        }
        if (PanelMotion.enterProgress(elapsed) < 1f) return false;
        if (i == IDX_PREV) return box.page > 0;
        if (i == IDX_LAST) return box.page < box.pages - 1;
        return true;
    }

    private RectI drawn(int i) {
        if (i == IDX_PRIMARY) return box.next;
        if (i == IDX_PREV) return box.pagePrev;
        if (i == IDX_LAST) return box.pageNext;
        return box.cards[i];
    }

    private boolean purchasable(int slot) {
        int id = offerAt(slot);
        return id >= 0 && ShopRules.purchasable(Balance.shopCards[id], view);
    }

    private static int lift(float elapsed) {
        return PanelMotion.liftFor(PanelMotion.enterProgress(elapsed), ShopLayout.PANEL_LIFT);
    }

    // ---- 绘制 -----------------------------------------------------------------------------

    /** @param run 只为读各卡当前等级（角标）；乘子在这里没有任何用处 */
    public void draw(Canvas c, ShopRun run, float elapsed) {
        float p = PanelMotion.enterProgress(elapsed);
        kit.rect(c, 0, 0, Screen.LOGIC_W, canvasH, Ink.BG_DEEP,
                Math.round(255f * PanelMotion.maskAlphaAt(p)));
        int save = c.save();
        c.translate(0f, lift(elapsed));
        drawFrame(c, p);
        drawTitle(c, p);
        drawCards(c, run, elapsed);
        drawPageArrows(c, p);
        drawNext(c, p, press.pressed() == IDX_PRIMARY);
        c.restoreToCount(save);
    }

    /** 容器靠色阶差表达层级（MD3 的 elevation 语法：不加阴影也不加描边）。 */
    private void drawFrame(Canvas c, float p) {
        kit.roundRect(c, box.panel, Md3.R_LARGE, Md3.surfaceContainer(), Math.round(255f * p));
    }

    private void drawTitle(Canvas c, float p) {
        int alpha = Math.round(255f * p);
        int cy = box.titleBar.centerY();
        kit.baked(c, L_TITLE, Md3.PX_TITLE, Md3.onSurface(), box.titleBar.left, cy, alpha, true);
        hud.reset().num(view.coins);
        kit.labelNumberRight(c, hud, L_COINS, box.titleBar.right, cy,
                Md3.onSurfaceVariant(), Md3.primary(), alpha, 1);
    }

    /** 每张卡延后一级淡入：一屏卡同时"啪"地出现，视线一张也抓不住。 */
    private void drawCards(Canvas c, ShopRun run, float elapsed) {
        if (offered == 0) {
            kit.bakedCentered(c, L_EMPTY, Md3.PX_LABEL, Md3.onSurfaceVariant(),
                    box.pool.centerX(), box.pool.centerY(), Math.round(255f * p(elapsed)));
            return;
        }
        for (int i = 0; i < box.shown; i++) {
            drawCard(c, i, run, PanelMotion.buttonProgress(i, elapsed));
        }
    }

    private static float p(float elapsed) {
        return PanelMotion.enterProgress(elapsed);
    }

    /** @param k 该格自己的淡入度（级联进度），0 时整张不画 */
    private void drawCard(Canvas c, int slot, ShopRun run, float k) {
        if (k <= 0f) return;
        int id = offerAt(slot);
        Balance.ShopCard card = Balance.shopCards[id];
        RectI r = box.cards[slot];
        int alpha = Math.round(255f * k);
        kit.roundRect(c, r, Md3.R_MEDIUM, Md3.surfaceContainerHighest(), alpha);
        if (press.pressed() == slot) {
            kit.stateLayer(c, r, Md3.R_MEDIUM, Md3.onSurface(), Md3.STATE_PRESSED_ALPHA);
        }
        // 容器照旧、内容走 MD3 的禁用档：一张"现在点它什么都不会发生"的卡要**看起来**就不能点。
        int ink = purchasable(slot) ? alpha : alpha * Md3.DISABLED_ALPHA_PERMILLE / 1000;

        int band1 = ShopLayout.row1Top(r);
        int band2 = ShopLayout.row2Top(r);
        int band3 = ShopLayout.row3Top(r);
        int textLeft = ShopLayout.textLeft(r);
        int cy1 = band1 + ShopLayout.ROW1_H / 2;
        int cy2 = band2 + ShopLayout.ROW2_H / 2;
        int right = r.right - ShopLayout.CARD_PAD;

        Bitmap icon = SpriteFactory.icon(SpriteSheets.shopIconSheet(id),
                SpriteSheets.shopIconId(id), Md3.primary(), ShopLayout.ICON, ShopLayout.ICON);
        // 图标要有 38% 的禁用态，而 SpriteFactory.PIXEL 是全进程共用的画笔——借 DrawKit 自己那支
        // 像素画笔（三禁已在构造里配好），用完复位，否则同一帧后面的精灵一起变暗。
        kit.ink.setAlpha(ink);
        SpriteFactory.draw(c, icon, r.left + ShopLayout.CARD_PAD, band1,
                ShopLayout.ICON, ShopLayout.ICON, kit.ink);
        kit.ink.setAlpha(255);
        kit.baked(c, card.name, Md3.PX_LABEL, Md3.onSurface(), textLeft, cy1, ink, true);
        drawPrice(c, card, right, cy1, ink);
        drawCore(c, id, textLeft, cy2, ink);
        drawLevel(c, id, run, right, cy2, ink);
        kit.baked(c, card.tip, Md3.PX_LABEL, Md3.onSurfaceVariant(), textLeft,
                band3 + ShopLayout.ROW3_H / 2, ink, true);
    }

    /** 名字行右端：能花就写价格，满级写"已满级"；淡成禁用由调用方算好的 {@code alpha} 表达。 */
    private void drawPrice(Canvas c, Balance.ShopCard card, int rightEdge, int cy, int alpha) {
        int price = ShopRules.nextPrice(card, view);
        if (price == Balance.Shop.PRICE_MAXED) {
            kit.bakedCentered(c, L_MAXED, Md3.PX_LABEL, Md3.onSurfaceVariant(),
                    rightEdge - MAXED_W / 2, cy, alpha);
            return;
        }
        hud.reset().num(price);
        kit.labelNumberRight(c, hud, L_COINS, rightEdge, cy,
                Md3.onSurfaceVariant(), Md3.primary(), alpha, 1);
    }

    /**
     * 核心数字（卡面的决策主信息）。数值与单位都从 {@link ShopRules} 现读，与结算端同源，
     * 所以调 {@code Balance.shop} 之后卡面必然跟着变——不会写出"卡面 +2%、实际结算 +3%"。
     *
     * <p>数字走点阵、单位汉字走内嵌字体，两条通道**不能混在一串里**：点阵只覆盖 32..126，
     * 越界字符按空格走位——写 {@code "+50点"} 会画成"+50 "，那一个字的宽度空着，玩家读到的是
     * "这张卡给了 50 个什么"。所以单位单独烤一张 12px 的字图贴在数字后面。
     */
    private void drawCore(Canvas c, int id, int x, int cy, int alpha) {
        float v = ShopRules.coreValue(id);
        String unit = null;
        switch (ShopRules.coreKind(id)) {
            case ShopRules.CORE_PERCENT:
                hud.reset().chr('+').fixed(v, ShopRules.coreDecimals(id)).chr('%');
                break;
            case ShopRules.CORE_POINTS:
                hud.reset().chr('+').num(Math.round(v));
                unit = L_UNIT_POINTS;
                break;
            case ShopRules.CORE_COUNT:
                hud.reset().chr('+').num(Math.round(v));
                unit = L_UNIT_COUNT;
                break;
            case ShopRules.CORE_SHOTS:
                hud.reset().chr('+').num(Math.round(v));
                unit = L_UNIT_SHOTS;
                break;
            case ShopRules.CORE_CHOICE:
                kit.baked(c, L_CHOICE, Md3.PX_LABEL, Md3.primary(), x, cy, alpha, true);
                return;
            default:
                kit.baked(c, L_READY, Md3.PX_LABEL, Md3.primary(), x, cy, alpha, true);
                return;
        }
        kit.number(c, hud, x, cy, 2, Md3.primary(), alpha);
        if (unit != null) {
            kit.baked(c, unit, Md3.PX_LABEL, Md3.primary(),
                    x + BitmapFont.textWidth(hud.buffer(), hud.length(), 2) + DrawKit.GAP,
                    cy, alpha, true);
        }
    }

    /**
     * 等级角标「Lv n」——**不带分母**。
     *
     * <p>为什么去掉「/max」：2026-09-26 火力与扳机取消满级之后，{@code maxLevelOf} 对它们返回
     * {@code UNLIMITED}，带分母的写法要么印出「Lv 3/-1」这种废话，要么逼这张卡永远不上角标。
     * 玩家真正要读的是"我这局在这张上压了几级"，分母从来不是决策信息。
     *
     * <p>该不该画由 {@link ShopRules#showsLevel} 判（判据是"收益随不随累计等级变多"），
     * 不在这里读 {@code UNLIMITED} 那个哨兵：它现在同时承载"一次性补给卡"和"无上限养成卡"
     * 两种语义，照旧判法会把两种卡一起划掉。
     */
    private void drawLevel(Canvas c, int id, ShopRun run, int rightEdge, int cy, int alpha) {
        if (!ShopRules.showsLevel(id)) return;
        hud.reset().text("Lv").chr(' ').num(run.levelOf(id));
        kit.numberRight(c, hud, rightEdge, cy, 1, Md3.onSurfaceVariant(), alpha);
    }

    /**
     * 翻页箭头：只在真翻了页的货架上出现，样式一律比出口低一档（等宽不等色）。
     *
     * <p>第一页的「上一页」与末页的「下一页」画出来但不受理（{@link #tappable}），
     * 而不是让箭头忽左忽右地消失——箭头位置一动，玩家刚建立起来的"中间那枚才是走人"就散了。
     */
    private void drawPageArrows(Canvas c, float p) {
        if (box.pages <= 1) return;
        int alpha = Math.round(255f * p);
        drawPageArrow(c, box.pagePrev, L_PAGE_PREV, box.page > 0,
                press.pressed() == IDX_PREV, alpha);
        drawPageArrow(c, box.pageNext, L_PAGE_NEXT, box.page < box.pages - 1,
                press.pressed() == IDX_LAST, alpha);
    }

    private void drawPageArrow(Canvas c, RectI r, String label, boolean enabled,
                               boolean pressed, int alpha) {
        kit.roundRect(c, r, Md3.R_SMALL, Md3.surfaceContainerHighest(), alpha);
        if (pressed) {
            kit.stateLayer(c, r, Md3.R_SMALL, Md3.onSurface(), Md3.STATE_PRESSED_ALPHA);
        }
        int ink = enabled ? alpha : alpha * Md3.DISABLED_ALPHA_PERMILLE / 1000;
        kit.bakedCentered(c, label, Md3.PX_BODY, Md3.onSurfaceVariant(),
                r.centerX(), r.centerY(), ink);
    }

    /** 出口那句字由入口决定：同一枚按钮在两家店里承诺的是两件不同的事，写同一句就是撒谎。 */
    private String exitLabel() {
        return view.entry == ShopRules.ENTRY_PAUSE ? L_BACK : L_NEXT;
    }

    private void drawNext(Canvas c, float p, boolean pressed) {
        RectI r = box.next;
        int alpha = Math.round(255f * p);
        kit.roundRect(c, r, Md3.R_SMALL, Md3.primary(), alpha);
        if (pressed) {
            kit.stateLayer(c, r, Md3.R_SMALL, Md3.onPrimary(), Md3.STATE_PRESSED_ALPHA);
        }
        kit.bakedCentered(c, exitLabel(), Md3.PX_BODY, Md3.onPrimary(),
                r.centerX(), r.centerY(), alpha);
    }
}
