package com.flexiatom.pixelraider.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import com.flexiatom.pixelraider.core.RectI;
import com.flexiatom.pixelraider.game.Balance;
import com.flexiatom.pixelraider.game.GrowthTree;
import com.flexiatom.pixelraider.gfx.BitmapFont;
import com.flexiatom.pixelraider.gfx.GlowAtlas;
import com.flexiatom.pixelraider.gfx.Ink;
import com.flexiatom.pixelraider.gfx.SpriteFactory;
import com.flexiatom.pixelraider.gfx.SpriteSheets;
import com.flexiatom.pixelraider.gfx.TextCache;
import com.flexiatom.pixelraider.plat.Screen;

/**
 * 成长页绘制（规格 §五 的局外六项；消费入口见 {@code ShopRun} 类头那句「在主菜单的『成长』页上点」）。
 *
 * <p>**面板只决定"点到哪一行"**，扣芯片与实际生效全在 {@code Game}——与 {@link ShopScreen} 同一套
 * 分工。所以报出去的动作是 {@link #ACTION_BUY} 加**项 id**（成长树的 id 是稳定的，不像商店那样
 * 每次开架重排槽位）。
 *
 * <p>这一页拿的是 {@link GrowthTree} 的**引用**而不是副本，理由和商店快照一样：买完之后钱包与
 * 等级都变了，面板要立刻反映"这一行现在点不动了、下一档多少钱"。传副本就得在两处维护"买完之后"。
 *
 * <p>行下标即项 id（{@code 0..Balance.Growth.ITEMS-1}），所以 {@link #L_ITEM} 与 {@link #ICON_CARD}
 * 的下标必须逐格对齐 {@code Balance.Growth.ATTACK/RATE/SHIELD/MOBILE/CRIT/BOMB}。这条耦合由
 * {@code GrowthScreenTest} 钉长度；**每级效果**却刻意不走数组而是按具名常量分支——数组会跟着
 * 常量表一起错位，分支不会。
 */
public final class GrowthScreen {

    public static final int ACTION_NONE = 0;
    public static final int ACTION_BACK = 1;
    /** 提交动作 = {@code ACTION_BUY + 项 id}。 */
    public static final int ACTION_BUY = 2;

    private static final String L_TITLE = "成长";
    private static final String L_CHIP = "芯片";
    private static final String L_INVESTED = "已投入";
    // 下面四枚对测试可见：它们各自占死一个 GrowthLayout 的列宽，"字加了、列没加"只能靠断言报出来。
    static final String L_BACK = "返回菜单";
    static final String L_MAXED = "已满";
    /** 汉字单位不能进点阵通道（越界字符按空格走位），见 {@code ShopScreen.drawCore} 同一件事。 */
    static final String L_UNIT_POINT = "点";
    static final String L_UNIT_COUNT = "枚";

    /** 六项名字，逐字对齐 {@code Balance.Growth} 的六个常量（口径抄自 {@code Balance} 的规格注释）。 */
    static final String[] L_ITEM = {"攻击", "射速", "护盾", "机动", "暴击", "炸弹"};
    /**
     * 图标复用商店卡的精灵表：成长树没有自建精灵，而"攻击↔火力卡、射速↔扳机卡"本来就是同一件事
     * 的局外/局内两张皮——同一枚图标正好把这层关系画出来。补给卡的图标是 7×7 网格，
     * 缩到 18 不是整数倍，那笔偏离 {@code SpriteSheetsTest} 已经钉着，这里不另立口径。
     */
    static final int[] ICON_CARD = {
            Balance.ShopCard.FIREPOWER, Balance.ShopCard.TRIGGER, Balance.ShopCard.SHIELD,
            Balance.ShopCard.THRUSTS, Balance.ShopCard.PRECISION, Balance.ShopCard.SALVO,
    };

    private final DrawKit kit;
    private final HudText hud = new HudText(32);
    private final RectI ra = new RectI();
    private final RectI rb = new RectI();
    private final RectI cell = new RectI();
    /** 命中区：六行 + 唯一出口。下标 {@code 0..ROWS-1} = 项 id，最后一格 = 返回。 */
    private final RectI[] hit = boxes(GrowthLayout.ROWS + 1);
    private final PressSelector press = new PressSelector();
    /** 开页时那一瞬的树。**引用** {@code Game} 持有的实例；没 open 过就是这块初值（一张全 0 的树）。 */
    private GrowthTree view = new GrowthTree();
    private int pageTop;
    private int logicH = Screen.BATTLE_H;

    public GrowthScreen(BitmapFont font, TextCache text, GlowAtlas glow) {
        kit = new DrawKit(font, text, glow);
    }

    private static RectI[] boxes(int n) {
        RectI[] r = new RectI[n];
        for (int i = 0; i < n; i++) r[i] = new RectI();
        return r;
    }

    public int pageHeight() {
        return GrowthLayout.PAGE_H;
    }

    /** 由 {@code Game} 在开页时调一次；之后面板与那棵树共用一份状态，购买无需重开。 */
    public void open(GrowthTree tree) {
        view = tree == null ? new GrowthTree() : tree;
        press.clear();
    }

    /**
     * 命中框换算成屏幕坐标（与结算页同一套约定：绘制用页内相对坐标、命中用屏幕坐标）。
     *
     * <p>外扩之后**行与行必然互压**：行距 30，而 48dp 在这台机上折到 34~45 逻辑像素
     * （{@code Screen.minTouchLogic}）。这不是错误而是那两条标准凑出来的几何——所以判定权
     * 交给"两遍 pass + 固定顺序"，见 {@link #under}。
     */
    public void layout(int minTouchLogic, int pageTop, int logicH) {
        this.pageTop = pageTop;
        this.logicH = logicH;
        for (int i = 0; i < GrowthLayout.ROWS; i++) {
            GrowthLayout.rowRect(i, ra);
            Widgets.hitRect(ra, minTouchLogic, hit[i]);
        }
        GrowthLayout.backRect(ra);
        Widgets.hitRect(ra, minTouchLogic, hit[GrowthLayout.ROWS]);
        for (int i = 0; i < hit.length; i++) hit[i].offsetInPlace(0, pageTop);
    }

    public void pressDown(int x, int y, float elapsed, int pointerId) {
        press.pressDown(under(x, y, elapsed), pointerId);
    }

    public void pressDrag(int x, int y, float elapsed, int pointerId) {
        press.dragTo(under(x, y, elapsed), pointerId);
    }

    /** @return {@link #ACTION_NONE}、{@link #ACTION_BACK} 或 {@code ACTION_BUY + 项 id} */
    public int pressUp(int x, int y, float elapsed, int pointerId) {
        int i = press.releaseTo(under(x, y, elapsed), pointerId);
        return i == PressSelector.NONE ? ACTION_NONE
                : i == GrowthLayout.ROWS ? ACTION_BACK : ACTION_BUY + i;
    }

    public void clearPress() {
        press.clear();
    }

    public int pressedRow() {
        return press.pressed();
    }

    /**
     * 手指下面第几格；没有则 {@link PressSelector#NONE}。
     *
     * <p>买不起与已满的行**不注册命中**：点一行灰的"没反应"就是真的没反应（规格点名的 bug 类）。
     * 判据与 {@link #drawRows} 用的是同一个 {@link #buyable}，所以"看着能点"与"点了会扣芯片"
     * 不可能分裂——这条等价关系是本类存在的核心理由，其余都是排布。
     *
     * <p>两遍 pass 的顺序在第二遍**翻转给出口**：贴着按钮上沿的那几像素同时落在"最后一行的外扩框"
     * 与"返回按钮的外扩框"里，判给行等于一次意外消费（花掉 11 芯片），判给返回只是关一扇窗。
     * 代价对称得一边倒，所以外扩那一遍出口先认。
     */
    private int under(int x, int y, float elapsed) {
        for (int pass = 0; pass < 2; pass++) {
            for (int k = 0; k < hit.length; k++) {
                int i = pass == 0 ? k : (k == 0 ? GrowthLayout.ROWS : k - 1);
                if (i < GrowthLayout.ROWS) {
                    if (!buyable(i)) continue;
                    if (PanelMotion.buttonProgress(i, elapsed) < 1f) continue;
                } else if (PanelMotion.enterProgress(elapsed) < 1f) {
                    continue;
                }
                // 第一遍比绘制框（页内坐标，所以手指也要减掉居原点），第二遍比外扩框（已是屏幕坐标）
                int ty = pass == 0 ? y - pageTop : y;
                if (boxOf(i, pass).contains(x, ty)) return i;
            }
        }
        return PressSelector.NONE;
    }

    private RectI boxOf(int i, int pass) {
        if (pass == 1) return hit[i];
        if (i < GrowthLayout.ROWS) GrowthLayout.rowRect(i, ra);
        else GrowthLayout.backRect(ra);
        return ra;
    }

    private boolean buyable(int i) {
        int cost = view.nextCost(i);
        return cost != GrowthTree.PRICE_MAXED && view.wallet() >= cost;
    }

    // ---- 绘制 -----------------------------------------------------------------------------

    /** @param elapsed 开页起的界面秒数（入场与级联都以它为原点，口径同 {@link ShopScreen}） */
    public void draw(Canvas c, float elapsed) {
        float p = PanelMotion.enterProgress(elapsed);
        kit.fill.setColor(Ink.MASK);
        kit.rf.set(0, -pageTop, Screen.LOGIC_W, logicH - pageTop);
        c.drawRect(kit.rf, kit.fill);
        kit.fill.setAlpha(255);

        drawTitle(c, p);
        drawMeta(c, p);
        drawRows(c, elapsed);
        drawBack(c, p, press.pressed() == GrowthLayout.ROWS);
    }

    private void drawTitle(Canvas c, float p) {
        GrowthLayout.titleRect(ra);
        kit.baked(c, L_TITLE, Md3.PX_TITLE, Md3.onSurface(), ra.left, ra.centerY(),
                Math.round(255f * p), true);
    }

    /** 钱包是这一页唯一的约束条件，所以它既在行上（每次扣减）也在这条线上（总账）。 */
    private void drawMeta(Canvas c, float p) {
        GrowthLayout.metaRect(ra);
        int cy = ra.centerY();
        int alpha = Math.round(255f * p);
        hud.reset().num(view.totalLevels()).chr('/')
                .num(Balance.Growth.ITEMS * Balance.Growth.MAX_LEVEL);
        kit.labelNumber(c, hud, L_INVESTED, (GrowthLayout.LEFT + Screen.LOGIC_W / 2) / 2, cy,
                Md3.onSurfaceVariant(), Md3.onSurface(), alpha, 1);
        hud.reset().num(view.wallet());
        kit.labelNumberRight(c, hud, L_CHIP, GrowthLayout.RIGHT, cy,
                Md3.onSurfaceVariant(), Md3.primary(), alpha, 1);
    }

    private void drawRows(Canvas c, float elapsed) {
        for (int i = 0; i < GrowthLayout.ROWS; i++) {
            drawRow(c, i, PanelMotion.buttonProgress(i, elapsed));
        }
    }

    /** @param k 该行的级联淡入度，0 时整行不画 */
    private void drawRow(Canvas c, int i, float k) {
        if (k <= 0f) return;
        GrowthLayout.rowRect(i, rb);
        int alpha = Math.round(255f * k);
        kit.roundRect(c, rb, Md3.R_MEDIUM, Md3.surfaceContainerHighest(), alpha);
        if (press.pressed() == i) {
            kit.stateLayer(c, rb, Md3.R_MEDIUM, Md3.onSurface(), Md3.STATE_PRESSED_ALPHA);
        }
        // 容器照旧、内容走 MD3 的禁用档：一行"现在点它什么都不会发生"的清单要看起来就不能点。
        int ink = buyable(i) ? alpha : alpha * Md3.DISABLED_ALPHA_PERMILLE / 1000;

        Bitmap icon = SpriteFactory.icon(SpriteSheets.shopIconSheet(ICON_CARD[i]),
                SpriteSheets.shopIconId(ICON_CARD[i]), Md3.primary(),
                GrowthLayout.ICON, GrowthLayout.ICON);
        // 借 DrawKit 自己那支像素画笔改 alpha：SpriteFactory.PIXEL 是全进程共用的，动它会连带
        // 同一帧后面的精灵一起变暗（商店卡那边同一个理由，见 ShopScreen.drawCard）。
        kit.ink.setAlpha(ink);
        SpriteFactory.draw(c, icon, GrowthLayout.iconLeft(rb), GrowthLayout.iconTop(rb),
                GrowthLayout.ICON, GrowthLayout.ICON, kit.ink);
        kit.ink.setAlpha(255);

        kit.baked(c, L_ITEM[i], Md3.PX_LABEL, Md3.onSurface(), GrowthLayout.nameLeft(rb),
                rb.centerY(), ink, true);
        drawEffect(c, i, rb, ink);
        drawDots(c, i, rb, alpha);
        drawCost(c, i, rb, ink);
    }

    /** 「每级 +N」：数值从 {@code Balance.growth} 现读，与 {@link GrowthTree} 那六个效果函数同一张表。 */
    private void drawEffect(Canvas c, int i, RectI row, int alpha) {
        String unit = effectOf(i);
        int dw = BitmapFont.textWidth(hud.buffer(), hud.length(), 1);
        int uw = unit == null ? 0 : Md3.PX_LABEL + DrawKit.GAP;
        int left = GrowthLayout.effectRight(row) - dw - uw;
        kit.number(c, hud, left, row.centerY(), 1, Md3.onSurfaceVariant(), alpha);
        if (unit != null) {
            kit.baked(c, unit, Md3.PX_LABEL, Md3.onSurfaceVariant(), left + dw + DrawKit.GAP,
                    row.centerY(), alpha, true);
        }
    }

    /**
     * 把「+N」写进 {@code hud}，返回紧跟其后的单位汉字；返回 {@code null} 表示没有单位
     * （百分号是 ASCII，已经在 hud 里跟着点阵走完了）。
     */
    private String effectOf(int i) {
        hud.reset().chr('+');
        switch (i) {
            case Balance.Growth.ATTACK:
                hud.num(Math.round(Balance.growth.attackPerLevel * 100f)).chr('%');
                return null;
            case Balance.Growth.RATE:
                hud.num(Math.round(Balance.growth.ratePerLevel * 100f)).chr('%');
                return null;
            case Balance.Growth.SHIELD:
                hud.num(Balance.growth.shieldPerLevel);
                return L_UNIT_POINT;
            case Balance.Growth.MOBILE:
                hud.num(Math.round(Balance.growth.mobilePerLevel * 100f)).chr('%');
                return null;
            case Balance.Growth.CRIT:
                hud.num(Balance.growth.critPerLevel).chr('%');
                return null;
            case Balance.Growth.BOMB:
                hud.num(Balance.growth.bombPerLevel);
                return L_UNIT_COUNT;
            default:
                // 越界只可能来自 ITEMS 与常量表脱钩（改表没改面板）：宁可空着，也不画一个错的数
                hud.reset();
                return null;
        }
    }

    /** 等级点格：五格一目了然，比「3/5」少一次减法。满不满由颜色说，不靠描边亮度。 */
    private void drawDots(Canvas c, int i, RectI row, int alpha) {
        int lv = view.level(i);
        for (int j = 0; j < GrowthLayout.DOT_MAX; j++) {
            GrowthLayout.dotCellRect(row, j, cell);
            kit.rect(c, cell.left, cell.top, cell.right, cell.bottom,
                    j < lv ? Md3.primary() : Md3.outlineVariant(), alpha);
        }
    }

    /** 能买就写价格，满级写「已满」——与 {@code ShopScreen.drawPrice} 同一句判据。 */
    private void drawCost(Canvas c, int i, RectI row, int alpha) {
        int cost = view.nextCost(i);
        int right = GrowthLayout.costRight(row);
        if (cost == GrowthTree.PRICE_MAXED) {
            kit.bakedCentered(c, L_MAXED, Md3.PX_LABEL, Md3.onSurfaceVariant(),
                    right - 2 * Md3.PX_LABEL / 2, row.centerY(), alpha);
            return;
        }
        hud.reset().num(cost);
        kit.labelNumberRight(c, hud, L_CHIP, right, row.centerY(),
                Md3.onSurfaceVariant(), Md3.primary(), alpha, 1);
    }

    private void drawBack(Canvas c, float p, boolean pressed) {
        GrowthLayout.backRect(ra);
        int alpha = Math.round(255f * p);
        kit.roundRect(c, ra, Md3.R_SMALL, Md3.primary(), alpha);
        if (pressed) {
            kit.stateLayer(c, ra, Md3.R_SMALL, Md3.onPrimary(), Md3.STATE_PRESSED_ALPHA);
        }
        kit.bakedCentered(c, L_BACK, Md3.PX_BODY, Md3.onPrimary(), ra.centerX(), ra.centerY(), alpha);
    }
}
