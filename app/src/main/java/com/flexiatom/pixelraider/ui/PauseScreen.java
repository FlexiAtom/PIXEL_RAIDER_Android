package com.flexiatom.pixelraider.ui;

import android.graphics.Canvas;

import com.flexiatom.pixelraider.core.RectI;
import com.flexiatom.pixelraider.game.RunStats;
import com.flexiatom.pixelraider.game.TrendSampler;
import com.flexiatom.pixelraider.gfx.BitmapFont;
import com.flexiatom.pixelraider.gfx.GlowAtlas;
import com.flexiatom.pixelraider.gfx.Ink;
import com.flexiatom.pixelraider.gfx.TextCache;
import com.flexiatom.pixelraider.plat.Screen;

/**
 * 暂停战术面板（规格 §四 暂停菜单）。
 *
 * 坐标系：**逻辑画布**（0..logicH），不像结算页那样平移到战斗区——它是"从座舱里拉下来的数据板"，
 * 要在 320~560 的每种逻辑高上把图表与八张卡分账分得开。入场的位移由 {@link PauseLayout#liftFor}
 * 给一个值，绘制与命中读同一个，淡入途中才不会点偏。
 *
 * 出口动作只以 {@code ACTION_*} 的形式报给 {@code Game}：这里决定"点到哪"，那里决定"发生什么"。
 */
public final class PauseScreen {

    public static final int ACTION_NONE = 0;
    public static final int ACTION_RESUME = 1;
    public static final int ACTION_SETTINGS = 2;
    public static final int ACTION_RESTART = 3;
    public static final int ACTION_MENU = 4;
    /** 换 tab 在面板内部就地生效，从不报给调用方，所以这里**没有** ACTION_TAB。 */

    /**
     * 成就属 S5、设置页属 S4-c 第三段。没落地之前画成**禁用态且不注册命中**：
     * "点了没反应"是规格点名的 bug 类，而"看着能点其实不能点"比"看着就不能点"更糟。
     * 落地一个就把它改成 true——面板上的外观与命中都从这一个布尔派生，不会脱节；
     * 但动作本身还要 {@code Game.onPanelPointerUp} 加分支，那里才是"发生什么"的一侧。
     */
    static final boolean ACHIEVEMENTS_LIVE = false;
    static final boolean SETTINGS_LIVE = false;
    static final boolean MENU_LIVE = true;      // 2026-09-29 授权摘灰：宿主 openMenu 已在，动作分支同批接上

    private static final String L_TITLE = "作战暂停";
    private static final String L_SAMPLING = "采样中…";
    private static final String L_WAVE = "波次";
    private static final String L_RESUME = "继续作战";
    private static final String L_ACHIEVEMENTS = "成就";
    private static final String L_SETTINGS = "设置";
    private static final String L_RESTART = "重开本局";
    private static final String L_MENU = "返回主菜单";
    /**
     * 八个卡片标签，下标对齐 {@link PauseLayout#CARDS} 与 {@link #cardValue} 的分支。**包私有**
     * 只为让测试钉住行数：对不上的话每次绘制都会越界，而暂停面板正好是激战里会被反复拉开的那块。
     * 波次不在此列——它已经在标题栏上（同一屏出现两次就是浪费一行）。
     */
    static final String[] L_CARD =
            {"分数", "击杀", "命中率", "最高连击", "存活时长", "超载", "芯片", "金币"};
    /** 三个指标 tab，下标对齐 {@link TrendSampler} 的 TAB_*。同上。 */
    static final String[] L_TAB = {"击杀/秒", "得分/秒", "生命"};

    private static final int DASH_LEN = 14;        // 主操作顶边的流光长度
    private static final float DASH_SPEED = 60f;   // px/s
    private static final int CORNER_ARM = 6;       // 四角卡榫的边长

    private final DrawKit kit;
    private final PauseLayout box = new PauseLayout();
    private final HudText hud = new HudText(32);
    private final RectI[] tabHit = {new RectI(), new RectI(), new RectI()};
    /** 五枚出口的**外扩命中框**（48dp 标准），下标对齐 {@link #EXIT_ACTION}。同时是按压区的判定框。 */
    private final RectI[] exitHit = {new RectI(), new RectI(), new RectI(), new RectI(), new RectI()};
    private final PressSelector press = new PressSelector();
    /** 包内可见只为测试（{@code PauseScreenTest} 钉它与 {@link #EXIT_LIVE} 的下标对应）；对外仍然只报动作码。 */
    static final int[] EXIT_ACTION =
            {ACTION_RESUME, ACTION_NONE, ACTION_SETTINGS, ACTION_RESTART, ACTION_MENU};
    /** 每枚出口属于第几级（成就与设置同属第二级，规格 §四 的级联顺序）。 */
    private static final int[] EXIT_LEVEL = {0, 1, 1, 2, 2};
    /**
     * 未落地的出口在这里就整条剔除（既不响应也不淡入）。成就属 S5、设置属 S4-c 第三段、
     * 主菜单属 S4-c 后段——落地一个就把对应布尔翻成 true，并给 {@link #EXIT_ACTION} 那格真动作。
     * 这最后一句是契约，不是建议：{@code PauseScreenTest} 现在钉着"亮着的出口必须带真动作"。
     */
    static final boolean[] EXIT_LIVE =
            {true, ACHIEVEMENTS_LIVE, SETTINGS_LIVE, true, MENU_LIVE};
    private int canvasH = Screen.BATTLE_H;
    private int tab = TrendSampler.TAB_KILLS;

    public PauseScreen(BitmapFont font, TextCache text, GlowAtlas glow) {
        kit = new DrawKit(font, text, glow);
    }

    public int tab() {
        return tab;
    }

    public void layout(int logicH, int safeTop, int safeBottom, int minTouchLogic) {
        canvasH = logicH <= 0 ? Screen.BATTLE_H : logicH;
        box.layout(canvasH, safeTop, safeBottom);
        for (int i = 0; i < tabHit.length; i++) {
            Widgets.hitRect(box.tabs[i], minTouchLogic, tabHit[i]);
        }
        for (int i = 0; i < exitHit.length; i++) {
            Widgets.hitRect(drawn(i), minTouchLogic, exitHit[i]);
        }
    }

    /**
     * 按下。面板**吃掉**落在它上面的每一击（绝不让点击漏到走位上去），所以命中与否不需要报给调用方。
     *
     * <p>只有换 tab 在这里就地生效——它改的是"看哪条曲线"，没有可撤销的后果，按下就该有反馈。
     * 五枚出口**只登记按压态、不执行**：动作留到 {@link #pressUp}，滑出去就取消
     * （"重开本局"作废本局，不可逆）。
     *
     * @param elapsed 进入暂停以来的界面秒数——位移与级联都从它算，不缓存上一帧的值
     */
    public void pressDown(int x, int y, float elapsed, int pointerId) {
        float p = PanelMotion.enterProgress(elapsed);
        int yy = y - PanelMotion.liftFor(p, PauseLayout.PANEL_LIFT);
        for (int i = 0; i < tabHit.length; i++) {
            if (tabHit[i].contains(x, yy)) {
                tab = i;
                press.pressDown(PressSelector.NONE, pointerId);   // 换页时把上一枚的高亮松开
                return;
            }
        }
        press.pressDown(exitUnder(x, yy, elapsed), pointerId);
    }

    /** 面板开着期间的移动只改按压态；不是面板管的手指一律忽略（那条路径在 {@code Game} 里另有判定）。 */
    public void pressDrag(int x, int y, float elapsed, int pointerId) {
        float p = PanelMotion.enterProgress(elapsed);
        press.dragTo(exitUnder(x, y - PanelMotion.liftFor(p, PauseLayout.PANEL_LIFT), elapsed), pointerId);
    }

    /** @return 提交的出口动作，或 {@link #ACTION_NONE}（含滑出去取消） */
    public int pressUp(int x, int y, float elapsed, int pointerId) {
        float p = PanelMotion.enterProgress(elapsed);
        int i = press.releaseTo(exitUnder(x, y - PanelMotion.liftFor(p, PauseLayout.PANEL_LIFT), elapsed), pointerId);
        return i == PressSelector.NONE ? ACTION_NONE : EXIT_ACTION[i];
    }

    public void clearPress() {
        press.clear();
    }

    /** 当前被按住的出口下标（{@link #EXIT_ACTION} 那一组）；没有则 -1。 */
    public int pressedExit() {
        return press.pressed();
    }

    /**
     * 第 i 枚出口的**绘制框**——直接引用 {@link #box} 里的实例，不复制，免得两份真值。
     * 公开只为测试：按压态画在哪个框上、重叠断言量哪几个矩形，都得到这儿取。
     */
    public RectI drawn(int i) {
        switch (i) {
            case 0: return box.resume;
            case 1: return box.achievements;
            case 2: return box.settings;
            case 3: return box.restart;
            default: return box.menu;
        }
    }

    /**
     * 命中的第几枚出口；未命中 -1。
     *
     * <p>每一级要等自己淡完才可点：入场途中误触"重开本局"是不可逆的，比少响应 0.28 秒贵得多。
     * 两遍：先认画出来的按钮，再认外扩框。三行出口挤在 66px 里，外扩到 48dp 之后行与行互压得厉害
     * （实测 40 逻辑像素的 touch floor 下压掉 18 格，而行的间距只有 22 格）——只认外扩框的话，
     * "重开本局"顶边那十几格会被上一行的 padding 抢走。先认绘制框就是把这一刀挡在外面，
     * 前提是绘制框彼此不重叠（{@code PauseScreenTest.drawnExitRectsNeverOverlap} 钉着）。
     */
    private int exitUnder(int x, int y, float elapsed) {
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < EXIT_LIVE.length; i++) {
                if (!EXIT_LIVE[i] || PanelMotion.buttonProgress(EXIT_LEVEL[i], elapsed) < 1f) {
                    continue;
                }
                if ((pass == 0 ? drawn(i) : exitHit[i]).contains(x, y)) {
                    return i;
                }
            }
        }
        return PressSelector.NONE;
    }

    /**
     * @param elapsed 进入暂停以来的界面秒数（面板自己算入场与级联，调用方只给时钟）
     */
    public void draw(Canvas c, long score, int wave, int chips, int coins, RunStats stats,
                     TrendSampler trend, float elapsed) {
        float p = PanelMotion.enterProgress(elapsed);
        kit.rect(c, 0, 0, Screen.LOGIC_W, canvasH, Ink.BG_DEEP,
                Math.round(255f * PanelMotion.maskAlphaAt(p)));
        int lift = PanelMotion.liftFor(p, PauseLayout.PANEL_LIFT);
        int save = c.save();
        c.translate(0f, lift);
        drawFrame(c, p);
        drawTitle(c, wave, p);
        drawTabs(c, p);
        drawChart(c, trend, p);
        drawCards(c, score, chips, coins, stats, p);
        drawExits(c, elapsed);
        c.restoreToCount(save);
    }

    /**
     * 面板本体：一块圆角容器，层级靠**色阶差**表达（MD3 的 elevation 语法：不加阴影也不加描边）。
     * 四角卡榫保留——规格 §四 的"装置边框"是权威，MD3 那一档也没有"角标"的对应物。它们从圆弧
     * 结束处起画，所以始终落在容器内部，不会戳到遮罩上。
     */
    private void drawFrame(Canvas c, float p) {
        int alpha = Math.round(255f * p);
        RectI r = box.panel;
        kit.roundRect(c, r, Md3.R_LARGE, Md3.surfaceContainerHigh(), alpha);
        kit.rect(c, box.titleBar.left, box.titleBar.bottom, box.titleBar.right,
                box.titleBar.bottom + 1, Md3.outlineVariant(), alpha);
        corner(c, r, Md3.R_LARGE, 1, 1, alpha);
        corner(c, r, Md3.R_LARGE, -1, 1, alpha);
        corner(c, r, Md3.R_LARGE, 1, -1, alpha);
        corner(c, r, Md3.R_LARGE, -1, -1, alpha);
    }

    /**
     * 一个角上的两段卡榫线（画成数组循环就会每帧分配两个 int[]，这条路不走）。
     *
     * @param rad 容器圆角——线从这个格数之后起画，避开被削掉的角
     */
    private void corner(Canvas c, RectI r, int rad, int dx, int dy, int alpha) {
        int x = dx > 0 ? r.left : r.right - 1;
        int y = dy > 0 ? r.top : r.bottom - 1;
        kit.line(c, x + dx * rad, y, x + dx * (rad + CORNER_ARM), y, Md3.primary(), alpha);
        kit.line(c, x, y + dy * rad, x, y + dy * (rad + CORNER_ARM), Md3.primary(), alpha);
    }

    private void drawTitle(Canvas c, int wave, float p) {
        int alpha = Math.round(255f * p);
        int cy = box.titleBar.centerY();
        kit.baked(c, L_TITLE, Md3.PX_TITLE, Md3.onSurface(), box.titleBar.left, cy, alpha, true);
        hud.reset().num(wave);
        kit.labelNumberRight(c, hud, L_WAVE, box.titleBar.right, cy,
                Md3.onSurfaceVariant(), Md3.primary(), alpha, 1);
    }

    /**
     * 分段选择器（MD3 的 single-select segmented 语法）：整条一个容器，选中段填一档更高的色阶，
     * 靠色阶差而不是下划线区分状态。段与段之间留一格容器底色当分隔，就不必画分隔线。
     */
    private void drawTabs(Canvas c, float p) {
        int alpha = Math.round(255f * p);
        kit.roundRect(c, box.contentLeft, box.tabs[0].top, box.contentRight, box.tabs[0].bottom,
                Md3.R_MEDIUM, Md3.surfaceContainerLow(), alpha);
        for (int i = 0; i < box.tabs.length; i++) {
            RectI r = box.tabs[i];
            boolean on = i == tab;
            if (on) {
                kit.roundRect(c, r.left - 1, r.top - 1, r.right + 1, r.bottom + 1,
                        Md3.R_EXTRA_SMALL, Md3.secondaryContainer(), alpha);
            }
            kit.bakedCentered(c, L_TAB[i], Md3.PX_LABEL,
                    on ? Md3.onSecondaryContainer() : Md3.onSurfaceVariant(),
                    r.centerX(), r.centerY(), alpha);
        }
    }

    /** 监护仪折线：速率类从 0 起，生命类用固定的 0~1 轴，两者都不是"贴最小值画平"。 */
    private void drawChart(Canvas c, TrendSampler trend, float p) {
        int alpha = Math.round(255f * p);
        RectI r = box.chart;
        kit.roundRect(c, r, Md3.R_MEDIUM, Md3.surfaceContainerLowest(), alpha);
        int midY = (r.top + r.bottom) / 2;
        kit.line(c, r.left + Md3.R_MEDIUM, midY, r.right - 1 - Md3.R_MEDIUM, midY,
                Md3.outlineVariant(), alpha);
        if (!trend.hasSeries()) {
            kit.bakedCentered(c, L_SAMPLING, Md3.PX_LABEL, Md3.onSurfaceVariant(),
                    r.centerX(), midY, alpha);
            return;
        }
        int n = trend.pointCount(tab);
        float lo = 0f;
        float hi = tab == TrendSampler.TAB_HP ? 1f : maxOf(trend, tab, n);
        if (hi <= lo) hi = lo + 1f;                 // 全程为零：贴着底线画一条平线，不放大噪声
        int x0 = r.left + 2;
        int x1 = r.right - 3;
        int yBottom = r.bottom - 2;
        int usableH = Math.max(0, r.height() - 5);
        int px = 0;
        int py = 0;
        for (int i = 0; i < n; i++) {
            int cx = n == 1 ? x1 : x0 + Math.round((x1 - x0) * (float) i / (n - 1));
            float v = trend.valueAt(tab, i);
            int cy = yBottom - Math.round(usableH * (v - lo) / (hi - lo));
            if (i > 0) {
                kit.line(c, px, py, cx, cy, Md3.primary(), alpha);
            }
            px = cx;
            py = cy;
        }
        // 游标辉光是**数据的指针**，不是容器的高程：MD3 禁的是阴影，不是这一处读数辅助。
        kit.glowAt(c, GlowAtlas.SPARK, px, py, 12, Md3.primary(), Math.round(110f * p));
        kit.rect(c, px - 1, py - 1, px + 1, py + 1, Md3.onSurface(), alpha);
    }

    private static float maxOf(TrendSampler trend, int which, int n) {
        float hi = 0f;
        for (int i = 0; i < n; i++) {
            float v = trend.valueAt(which, i);
            if (v > hi) hi = v;
        }
        return hi;
    }

    /** 卡片：色阶差当层级（比面板底更高一档），标签走 {@code onSurfaceVariant}。 */
    private void drawCards(Canvas c, long score, int chips, int coins, RunStats stats, float p) {
        int alpha = Math.round(255f * p);
        for (int i = 0; i < box.cards.length; i++) {
            RectI r = box.cards[i];
            kit.roundRect(c, r, Md3.R_MEDIUM, Md3.surfaceContainerHighest(), alpha);
            kit.baked(c, L_CARD[i], Md3.PX_LABEL, Md3.onSurfaceVariant(),
                    r.left + 3, r.centerY(), alpha, true);
            int color = cardValue(stats, i, chips, coins, score);
            int scale = valueScale(i);
            kit.numberRight(c, hud, r.right - 3, r.centerY(), scale, color, alpha);
        }
    }

    /**
     * 读数写进 hud 并返回该卡的颜色；下标顺序必须与 {@link #L_CARD} 一致。
     *
     * <p>MD3 的立场是**颜色只表达语义角色，不表达"哪个数字更酷"**，所以这一屏只有一个强调色：
     * 能花的钱。原先超载是橙、芯片是绿、金币是黄——那是战场色板的语法，在面板上只会把视线撕散。
     */
    private int cardValue(RunStats stats, int i, int chips, int coins, long score) {
        switch (i) {
            case 0: hud.reset().num(score); return Md3.onSurface();
            case 1: hud.reset().num(stats.kills); return Md3.onSurface();
            case 2: hud.reset().percent(stats.accuracy()); return Md3.onSurface();
            case 3: hud.reset().num(stats.peakCombo); return Md3.onSurface();
            case 4: hud.reset().clock(stats.elapsedWholeSeconds()); return Md3.onSurface();
            case 5: hud.reset().num(stats.overloadUses); return Md3.onSurface();
            case 6: hud.reset().num(chips); return Md3.primary();
            default: hud.reset().num(coins); return Md3.primary();
        }
    }

    /**
     * 数值三级字号：点阵 2×（头部读数）/ 点阵 1×（次级读数）/ 8px 中文标签。
     * 没有第四档，也没有更小的点阵档——精灵按整数倍缩放是规格 §三 的硬线。
     */
    private static int valueScale(int i) {
        return i < 2 ? 2 : 1;
    }

    private void drawExits(Canvas c, float elapsed) {
        int on = press.pressed();
        drawResumeButton(c, PanelMotion.buttonProgress(0, elapsed), elapsed, on == 0);
        drawFlatButton(c, box.achievements, L_ACHIEVEMENTS,
                PanelMotion.buttonProgress(1, elapsed), ACHIEVEMENTS_LIVE, false, on == 1);
        drawFlatButton(c, box.settings, L_SETTINGS,
                PanelMotion.buttonProgress(1, elapsed), SETTINGS_LIVE, false, on == 2);
        drawFlatButton(c, box.restart, L_RESTART,
                PanelMotion.buttonProgress(2, elapsed), true, false, on == 3);
        drawFlatButton(c, box.menu, L_MENU,
                PanelMotion.buttonProgress(2, elapsed), MENU_LIVE, true, on == 4);
    }

    /**
     * 主操作 = MD3 的 filled button：一层 {@code primary} 底 + {@code onPrimary} 字，靠"底与容器
     * 的色阶落差"当吸引力，不靠辉光也不靠渐变（那两样是战场色板的语法，规格 §四 的"独占一行"
     * 说的是位阶，不是发光）。
     *
     * <p>顶边流光保留：它是规格点名的动效，用户裁决"动效以规格为准"。它被夹在两条圆弧之间的
     * 平边段上跑，所以不会淌出圆角。
     */
    private void drawResumeButton(Canvas c, float bp, float elapsed, boolean pressed) {
        if (bp <= 0f) return;
        RectI r = box.resume;
        int alpha = Math.round(255f * bp);
        kit.roundRect(c, r, Md3.R_SMALL, Md3.primary(), alpha);
        if (pressed) {
            kit.stateLayer(c, r, Md3.R_SMALL, Md3.onPrimary(), Md3.STATE_PRESSED_ALPHA);
        }
        int flat = Math.min(r.width() / 2, Md3.R_SMALL);
        int span = r.width();
        int pos = (int) (elapsed * DASH_SPEED) % (span + DASH_LEN * 2) - DASH_LEN;
        int sx = Math.max(r.left + flat, r.left + pos);
        int ex = Math.min(r.right - flat, r.left + pos + DASH_LEN);
        if (ex > sx) {
            kit.line(c, sx, r.top, ex, r.top, Md3.onPrimary(), 255);
        }
        kit.bakedCentered(c, L_RESUME, Md3.PX_BODY, Md3.onPrimary(),
                r.centerX(), r.centerY(), alpha);
    }

    /**
     * 次级出口 = MD3 的 text button：**没有描边**（同级元素之间的区分交给级联时序与位置，
     * 描边只会把一屏画成六个框）。
     *
     * @param live false 时按 MD3 的禁用口径走：38% 的 {@code onSurfaceVariant}，禁用态要**看起来**禁用
     */
    private void drawFlatButton(Canvas c, RectI r, String label, float bp, boolean live,
                                boolean danger, boolean pressed) {
        if (bp <= 0f) return;
        int alpha = Math.round(255f * bp);
        if (!live) {
            alpha = alpha * Md3.DISABLED_ALPHA_PERMILLE / 1000;
        }
        int tint = !live ? Md3.onSurfaceVariant() : (danger ? Md3.error() : Md3.primary());
        // 状态层的色 = onState（这一枚的字色），底是透明的：MD3 换色不换底，只叠一层
        if (pressed) {
            kit.stateLayer(c, r, Md3.R_EXTRA_SMALL, tint, Md3.STATE_PRESSED_ALPHA);
        }
        kit.bakedCentered(c, label, Md3.PX_BODY, tint, r.centerX(), r.centerY(), alpha);
    }
}
