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
import com.flexiatom.pixelraider.game.SaveSlots;
import com.flexiatom.pixelraider.gfx.BitmapFont;
import com.flexiatom.pixelraider.gfx.GlowAtlas;
import com.flexiatom.pixelraider.gfx.Ink;
import com.flexiatom.pixelraider.gfx.TextCache;
import com.flexiatom.pixelraider.plat.Screen;

import java.util.Calendar;

/**
 * 存档页（快照列表 + 写入 / 载入 / 删除）。池件 {@code pause-save-entry} §7 裁的乙形：
 * 暂停页那枚键只开页，**写入键在这座页里**，「已存档」的反馈也因此长在它身上。
 *
 * <p>面板只决定"点到哪一格"，拍快照、落盘、还原、解冻时钟全在 {@code Game}——与
 * {@link GrowthScreen}／{@link DebugScreen} 同一套分工。报出去的动作只有四枚
 * （返回 / 载入 / 存档 / 删除），**选中哪一格不在动作码里**：抬起之后由 {@code Game}
 * 现读 {@link #selectedSlot()}，两处读的是同一个瞬间的状态，不必把槽号编进动作码再拆一次。
 *
 * <p>翻页与选中是面板自己的状态，不动槽位账本也不惊动 {@code Game}，所以它们**不是**动作码
 * （{@link #pressUp} 做完就报 {@link #ACTION_NONE}）——口径照 {@link ShopScreen} 那两枚箭头。
 *
 * <p>它拿的是 {@link SaveSlots} 的**引用**：删一档、覆盖一档之后，列表要立刻变成新的样子，
 * 传副本就得在两处维护"删完之后"。
 */
public final class SaveScreen {

    public static final int ACTION_NONE = 0;
    public static final int ACTION_BACK = 1;
    public static final int ACTION_LOAD = 2;
    public static final int ACTION_SAVE = 3;
    public static final int ACTION_DELETE = 4;

    /** 提示语那一格的三种态：{@link #STATUS_NONE} 时那格画页码。 */
    public static final int STATUS_NONE = 0;
    /** 五十槽全满：他裁的是"拒绝并提示先删或选一份覆盖"，不回绕、不自动淘汰最旧那份。 */
    public static final int STATUS_FULL = 1;
    /** 读回来的字节是空的或坏的（盘上文件与掩码不一致时按掩码报这一句）。 */
    public static final int STATUS_LOAD_FAILED = 2;

    private static final String L_TITLE = "存档";
    private static final String L_USED = "已存";
    static final String L_SLOT = "槽";
    static final String L_WAVE = "波";
    static final String L_SCORE = "分";
    static final String L_EMPTY = "空";
    static final String L_LOAD = "载入";
    static final String L_SAVE = "存档";
    /** 裁定甲的反馈：按下写入之后**这枚键自己的标签**换成它，直到面板关闭。 */
    static final String L_SAVED = "已存档";
    static final String L_DELETE = "删除";
    static final String L_PREV = "上一页";
    static final String L_NEXT = "下一页";
    static final String L_BACK = "返回";
    static final String L_LOADING = "读取中…";
    /** 下标 = {@code SaveSlots.modeOf} 的位值（0 无尽 / 1 战役），由测试钉住与 {@code Mode.campaign} 同序。 */
    static final String[] L_MODE = {"无尽", "战役"};
    /** 下标 = {@code SaveSlots.tierOf} 的位值，即 {@code Tier} 的声明序。 */
    static final String[] L_TIER = {"休闲", "普通", "终结"};
    /**
     * 满槽那句。十三字是**算出来的上界**：meta 那一条左端是「已存 50/50」（右界
     * {@link SaveLayout#META_READOUT_RIGHT} = 67），这一句贴右排在 {@code CONTENT_RIGHT}，
     * 十三字压在 72 起笔，只留五个格的缝；第十四字会顶到占用读数上。裁掉的是「槽」字——
     * 剩下的十三字仍是两条出路（先删 / 选一份覆盖），那是他裁的语义，不许为了排版省掉。
     */
    static final String S_FULL = "已满，先删一份或选一份覆盖";
    static final String S_LOAD_FAILED = "这一份读不出来";

    /** 命中区下标：先八行（页内），再翻页行三格，再动作行三格——**这个顺序就是互压时的归属**。 */
    private static final int IDX_BACK = SaveLayout.ROWS_PER_PAGE;
    private static final int IDX_PREV = IDX_BACK + 1;
    private static final int IDX_NEXT = IDX_BACK + 2;
    private static final int IDX_LOAD = IDX_BACK + 3;
    private static final int IDX_SAVE = IDX_BACK + 4;
    private static final int IDX_DELETE = IDX_BACK + 5;
    private static final int BOXES = IDX_BACK + 6;

    /** 三枚动作与翻页行共用一级，八行共用一级。 */
    private static final int LEVEL_ROWS = 1;
    /**
     * 按钮行的级联落点：0.08·2 + 0.12 = 0.28 秒，正是面板落定（{@code enterProgress} 归一）那一瞬——
     * 按钮不会在板子还在淡的时候先亮起来。行只等一级（0.20 秒）：点一行只是选中，没有不可逆后果。
     */
    private static final int LEVEL_BUTTONS = 2;

    private final DrawKit kit;
    /** 时间戳每帧都要格式化，而 {@code Calendar} 是对象——复用一支，理由同 {@link #hud}。 */
    private final Calendar clock = Calendar.getInstance();
    private final HudText hud = new HudText(32);
    private final RectI ra = new RectI();
    private final RectI rb = new RectI();
    private final RectI[] hit = boxes(BOXES);
    private final PressSelector press = new PressSelector();

    /** 槽位账本的**引用**；没 open 过就是这块初值（一张空账），画出来是 50 个空格，不崩。 */
    private SaveSlots slots = new SaveSlots();
    private int page;
    private int selection = SaveSlots.NONE;
    private int status = STATUS_NONE;
    private boolean savedLatched;
    private boolean loading;
    private int pageTop;
    private int logicH = Screen.BATTLE_H;

    public SaveScreen(BitmapFont font, TextCache text, GlowAtlas glow) {
        kit = new DrawKit(font, text, glow);
    }

    private static RectI[] boxes(int n) {
        RectI[] r = new RectI[n];
        for (int i = 0; i < n; i++) r[i] = new RectI();
        return r;
    }

    public int pageHeight() {
        return SaveLayout.PAGE_H;
    }

    /** 开页：页号、选中、提示、「已存档」全部归零——上一次开页的反馈不许带进这一次。 */
    public void open(SaveSlots slots) {
        this.slots = slots == null ? new SaveSlots() : slots;
        page = 0;
        selection = SaveSlots.NONE;
        status = STATUS_NONE;
        savedLatched = false;
        loading = false;
        press.clear();
    }

    /**
     * 由 {@code Game} 每帧现传"读档还在飞"——那一格是页面"读取中…"的真源。
     *
     * <p>它**不碰提示语**：读档结束不等于"那一句已经被看过"，而 {@code Game} 每帧都调这里，
     * 顺手清 status 会把满槽那句擦掉——玩家还没来得及读，动作按钮就重新亮成"可以再存一次"。
     * 提示语只在开页、换页、换选中或下一次写入成功时收掉（见 {@link #turnPage}、{@link #choose}、
     * {@link #markSaved}）。
     */
    public void setLoading(boolean loading) {
        this.loading = loading;
    }

    /** 写入成功：这枚键的标签换成「已存档」，直到下次开页。 */
    public void markSaved() {
        savedLatched = true;
        status = STATUS_NONE;
    }

    /** 写入被拒（满槽）或读档失败：提示语进 meta 那一格。 */
    public void showStatus(int status) {
        this.status = status;
    }

    public int status() {
        return status;
    }

    public int page() {
        return page;
    }

    public boolean isSavedLatched() {
        return savedLatched;
    }

    /** 选中的槽（全架下标），没选中是 {@link SaveSlots#NONE}。{@code Game} 在抬起后现读它。 */
    public int selectedSlot() {
        return selection;
    }

    public int pressedIndex() {
        return press.pressed();
    }

    public void layout(int minTouchLogic, int pageTop, int logicH) {
        this.pageTop = pageTop;
        this.logicH = logicH <= 0 ? Screen.BATTLE_H : logicH;
        for (int i = 0; i < SaveLayout.ROWS_PER_PAGE; i++) {
            SaveLayout.rowRect(i, ra);
            Widgets.hitRect(ra, minTouchLogic, hit[i]);
        }
        SaveLayout.navCell(1, ra);
        Widgets.hitRect(ra, minTouchLogic, hit[IDX_BACK]);
        SaveLayout.navCell(0, ra);
        Widgets.hitRect(ra, minTouchLogic, hit[IDX_PREV]);
        SaveLayout.navCell(2, ra);
        Widgets.hitRect(ra, minTouchLogic, hit[IDX_NEXT]);
        SaveLayout.actionCell(0, ra);
        Widgets.hitRect(ra, minTouchLogic, hit[IDX_LOAD]);
        SaveLayout.actionCell(1, ra);
        Widgets.hitRect(ra, minTouchLogic, hit[IDX_SAVE]);
        SaveLayout.actionCell(2, ra);
        Widgets.hitRect(ra, minTouchLogic, hit[IDX_DELETE]);
        for (int i = 0; i < hit.length; i++) hit[i].offsetInPlace(0, pageTop);
    }

    public void pressDown(int x, int y, float elapsed, int pointerId) {
        press.pressDown(under(x, y, elapsed), pointerId);
    }

    public void pressDrag(int x, int y, float elapsed, int pointerId) {
        press.dragTo(under(x, y, elapsed), pointerId);
    }

    /** @return 五枚 {@code ACTION_*} 之一；翻页与选中在内部做完，报 {@link #ACTION_NONE} */
    public int pressUp(int x, int y, float elapsed, int pointerId) {
        int i = press.releaseTo(under(x, y, elapsed), pointerId);
        // if 链而不是 switch：IDX_* 建在 ROWS_PER_PAGE 上，而那一枚是**反解**出来的方法调用
        // （{@code SaveLayout.rowsFitting}），不是编译期常量，case 标签不收。
        if (i == IDX_BACK) return ACTION_BACK;
        if (i == IDX_PREV) {
            turnPage(-1);
            return ACTION_NONE;
        }
        if (i == IDX_NEXT) {
            turnPage(1);
            return ACTION_NONE;
        }
        if (i == IDX_LOAD) return ACTION_LOAD;
        if (i == IDX_SAVE) return ACTION_SAVE;
        if (i == IDX_DELETE) return ACTION_DELETE;
        if (i >= 0 && i < SaveLayout.ROWS_PER_PAGE) choose(pageStart() + i);
        return ACTION_NONE;
    }

    public void clearPress() {
        press.clear();
    }

    /** 翻一页：选中跟着页走没意义（选的是槽，不是行），所以翻页不清选中，但清掉上一句提示。 */
    private void turnPage(int delta) {
        int p = SaveLayout.clampPage(page + delta);
        if (p == page) return;
        page = p;
        status = STATUS_NONE;
    }

    /** 点同一行是取消选中：不选中就是"写进第一个空槽"，那条路得留在玩家手边。 */
    private void choose(int slot) {
        selection = selection == slot ? SaveSlots.NONE : slot;
        status = STATUS_NONE;
    }

    private int pageStart() {
        return SaveLayout.pageStart(page);
    }

    private int shown() {
        return SaveLayout.shownOn(page);
    }

    /**
     * 手指下面第几格；未命中 {@link PressSelector#NONE}。
     *
     * <p>两遍 pass（先绘制框再外扩框）与 {@link PauseScreen} 同理：行高 24、间距 2，外扩到 48dp
     * 之后行与行必然互压，归属由"绘制框不重叠"定；互压那一带落在**绘制框之外**的部分才交给外扩框，
     * 而外扩框之间的先后由 {@link #hit} 的**数组顺序**定——行先、返回次之、翻页、最后动作行。
     * 排在后面的是"点了会改数据"的那几格：同一批像素判给行只是选中（可逆、无后果），判给删除
     * 就是少一份档，代价不对称，所以外扩那一遍删除最后一个认。
     *
     * <p>每格还要等自己**淡完**才可点（与商店/调试同一规矩）：入场途中点一行等于把选中悄悄换掉，
     * 再按一次存档就覆盖了玩家本来没打算动的槽。
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
        if (pass == 1) return hit[i];
        if (i < SaveLayout.ROWS_PER_PAGE) SaveLayout.rowRect(i, ra);
        else if (i < IDX_LOAD) SaveLayout.navCell(i == IDX_BACK ? 1 : i == IDX_PREV ? 0 : 2, ra);
        else SaveLayout.actionCell(i - IDX_LOAD, ra);
        return ra;
    }

    /** 这一格现在该不该受理：内容上有效（这一页摆得下 / 选中能用 / 翻得动）而且已经淡完。 */
    private boolean tappable(int i, float elapsed) {
        boolean rows = i < SaveLayout.ROWS_PER_PAGE;
        if (rows && i >= shown()) return false;
        if (PanelMotion.buttonProgress(rows ? LEVEL_ROWS : LEVEL_BUTTONS, elapsed) < 1f) {
            return false;
        }
        if (rows) return true;
        if (i == IDX_BACK) return true;                  // 唯一的出口，永远留着（否则读档失败时会被关在这一页）
        if (i == IDX_PREV) return page > 0;
        if (i == IDX_NEXT) return page < SaveLayout.PAGES - 1;
        if (loading) return false;                       // 一份在飞时不再发起第二份
        if (i == IDX_SAVE) return true;
        return selection != SaveSlots.NONE && slots.isOccupied(selection);
    }

    // ---- 绘制 -----------------------------------------------------------------------------

    /** @param elapsed 开页起的界面秒数（入场与级联都以它为原点，口径同 {@link GrowthScreen}） */
    public void draw(Canvas c, float elapsed) {
        float p = PanelMotion.enterProgress(elapsed);
        kit.fill.setColor(Ink.MASK);
        kit.rf.set(0, -pageTop, Screen.LOGIC_W, logicH - pageTop);
        c.drawRect(kit.rf, kit.fill);
        kit.fill.setAlpha(255);

        drawTitle(c, p);
        drawMeta(c, p);
        float kRow = PanelMotion.buttonProgress(LEVEL_ROWS, elapsed);
        int start = pageStart();
        int rows = shown();
        for (int i = 0; i < rows; i++) {
            drawRow(c, i, start + i, kRow);
        }
        float kBtn = PanelMotion.buttonProgress(LEVEL_BUTTONS, elapsed);
        SaveLayout.navCell(0, ra);
        drawFlatButton(c, ra, L_PREV, kBtn, page > 0, press.pressed() == IDX_PREV, false);
        drawBackButton(c, kBtn, press.pressed() == IDX_BACK);
        SaveLayout.navCell(2, ra);
        drawFlatButton(c, ra, L_NEXT, kBtn, page < SaveLayout.PAGES - 1,
                press.pressed() == IDX_NEXT, false);
        boolean live = selectionLive();
        SaveLayout.actionCell(0, ra);
        drawFlatButton(c, ra, L_LOAD, kBtn, live, press.pressed() == IDX_LOAD, false);
        SaveLayout.actionCell(1, ra);
        drawFlatButton(c, ra, savedLatched ? L_SAVED : L_SAVE, kBtn, !loading,
                press.pressed() == IDX_SAVE, false);
        SaveLayout.actionCell(2, ra);
        drawFlatButton(c, ra, L_DELETE, kBtn, live, press.pressed() == IDX_DELETE, true);
    }

    /** 载入与删除都只对**有档**的选中格有效，两者共用一条判据，只差在按钮画成灰不灰。 */
    private boolean selectionLive() {
        return !loading && selection != SaveSlots.NONE && slots.isOccupied(selection);
    }

    private void drawTitle(Canvas c, float p) {
        SaveLayout.titleRect(ra);
        kit.baked(c, L_TITLE, Md3.PX_TITLE, Md3.onSurface(), ra.left, ra.centerY(),
                Math.round(255f * p), true);
    }

    /** 左端占用读数，右端一句提示（没有提示就退成页码）。 */
    private void drawMeta(Canvas c, float p) {
        SaveLayout.metaRect(ra);
        int cy = ra.centerY();
        int alpha = Math.round(255f * p);
        kit.baked(c, L_USED, Md3.PX_LABEL, Md3.onSurfaceVariant(), ra.left, cy, alpha, true);
        hud.reset().num(slots.count()).chr('/').num(SaveLayout.SLOTS);
        kit.number(c, hud, ra.left + 2 * Md3.PX_LABEL + DrawKit.GAP, cy, 1, Md3.onSurface(), alpha);

        String s = statusLine();
        if (s != null) {
            int color = status == STATUS_LOAD_FAILED ? Md3.error() : Md3.onSurfaceVariant();
            kit.baked(c, s, Md3.PX_LABEL, color, ra.right - s.length() * Md3.PX_LABEL, cy,
                    alpha, true);
            return;
        }
        hud.reset().num(page + 1).chr('/').num(SaveLayout.PAGES);
        kit.numberRight(c, hud, ra.right, cy, 1, Md3.onSurfaceVariant(), alpha);
    }

    private String statusLine() {
        if (loading) return L_LOADING;
        if (status == STATUS_FULL) return S_FULL;
        if (status == STATUS_LOAD_FAILED) return S_LOAD_FAILED;
        return null;
    }

    private void drawRow(Canvas c, int i, int slot, float k) {
        if (k <= 0f) return;
        SaveLayout.rowRect(i, rb);
        int alpha = Math.round(255f * k);
        boolean picked = slot == selection;
        boolean filled = slots.isOccupied(slot);
        int bg = picked ? Md3.primaryContainer()
                : (filled ? Md3.surfaceContainerHighest() : Md3.surfaceContainerLow());
        kit.roundRect(c, rb, Md3.R_MEDIUM, bg, alpha);
        if (press.pressed() == i) {
            kit.stateLayer(c, rb, Md3.R_MEDIUM, Md3.onSurface(), Md3.STATE_PRESSED_ALPHA);
        }
        // 标签与数值靠色阶分（onSurfaceVariant / onSurface），不靠强调色：这一屏有五十字，
        // 每一格都刷青就等于没有重点（口径同 PauseScreen.drawFlatButton 那句「一屏一个强调色」）。
        // 选中由容器色阶表达，所以选中行两组都走 onPrimaryContainer。
        int label = picked ? Md3.onPrimaryContainer() : Md3.onSurfaceVariant();
        int value = picked ? Md3.onPrimaryContainer() : Md3.onSurface();
        // 空格整行走 MD3 的禁用档，只**缩 alpha**（颜色一动就污染 ARGB 的 alpha 通道，
        // 而烘焙色是 cache key，见 DrawKit 类头）：底色已经把"有没有档"分开了，这两级字只是确认。
        int faded = filled ? alpha : alpha * Md3.DISABLED_ALPHA_PERMILLE / 1000;
        int cy1 = SaveLayout.line1CenterY(rb);

        kit.baked(c, L_SLOT, Md3.PX_LABEL, label, SaveLayout.slotLeft(rb), cy1, faded, true);
        hud.reset().pad(slot + 1, 2);
        kit.number(c, hud, SaveLayout.slotLeft(rb) + Md3.PX_LABEL + DrawKit.GAP, cy1, 1,
                value, faded);

        if (!filled) {
            kit.baked(c, L_EMPTY, Md3.PX_LABEL, label, SaveLayout.modeLeft(rb), cy1,
                    faded, true);
            return;
        }
        long meta = slots.meta(slot);
        int mode = SaveSlots.modeOf(meta);
        kit.baked(c, mode == 0 ? L_MODE[0] : L_MODE[1], Md3.PX_LABEL, value,
                SaveLayout.modeLeft(rb), cy1, alpha, true);
        int tier = SaveSlots.tierOf(meta);
        if (tier >= 0 && tier < L_TIER.length) {
            kit.baked(c, L_TIER[tier], Md3.PX_LABEL, value, SaveLayout.tierLeft(rb), cy1,
                    alpha, true);
        }
        kit.baked(c, L_WAVE, Md3.PX_LABEL, label, SaveLayout.waveLeft(rb), cy1, alpha, true);
        hud.reset().pad(SaveSlots.waveOf(meta), 2);
        kit.number(c, hud, SaveLayout.waveLeft(rb) + Md3.PX_LABEL + DrawKit.GAP, cy1, 1,
                value, alpha);
        hud.reset().num(SaveSlots.scoreOf(meta));
        kit.labelNumberRight(c, hud, L_SCORE, SaveLayout.textRight(rb), cy1,
                label, value, alpha, 1);

        formatStamp(slots.stamp(slot), hud, clock);
        kit.numberRight(c, hud, SaveLayout.textRight(rb), SaveLayout.line2CenterY(rb), 1,
                label, alpha);
    }

    private void drawBackButton(Canvas c, float k, boolean pressed) {
        if (k <= 0f) return;
        SaveLayout.navCell(1, ra);
        int alpha = Math.round(255f * k);
        kit.roundRect(c, ra, Md3.R_SMALL, Md3.primary(), alpha);
        if (pressed) {
            kit.stateLayer(c, ra, Md3.R_SMALL, Md3.onPrimary(), Md3.STATE_PRESSED_ALPHA);
        }
        kit.bakedCentered(c, L_BACK, Md3.PX_BODY, Md3.onPrimary(), ra.centerX(), ra.centerY(),
                alpha);
    }

    /**
     * 次级键 = MD3 的 tonal 容器（与 {@code DebugScreen} 那两枚作弊键同一档），返回才是实心主操作：
     * 这一页的"安全退路"是唯一的出口，位阶给最高的那一档。灰掉的格子只降文字透明度，
     * 底色照旧——底一淡整枚按钮像被移出版面，玩家会找不到它。
     */
    private void drawFlatButton(Canvas c, RectI r, String label, float k, boolean live,
                                boolean pressed, boolean destructive) {
        if (k <= 0f) return;
        int alpha = Math.round(255f * k);
        kit.roundRect(c, r, Md3.R_SMALL, Md3.secondaryContainer(), alpha);
        if (pressed && live) {
            kit.stateLayer(c, r, Md3.R_SMALL, Md3.onSecondaryContainer(),
                    Md3.STATE_PRESSED_ALPHA);
        }
        int base = destructive ? Md3.error() : Md3.onSecondaryContainer();
        int ink = live ? alpha : alpha * Md3.DISABLED_ALPHA_PERMILLE / 1000;
        kit.bakedCentered(c, label, Md3.PX_BODY, base, r.centerX(), r.centerY(), ink);
    }

    /**
     * epoch 毫秒 → 「MM-dd HH:mm」十一位点阵。没档（stamp 为 0）画一个 ASCII「-」：
     * 点阵里它在位，汉字通道里没有"空"这个字形可借用。
     *
     * <p>走 {@link Calendar} 而不是 {@code SimpleDateFormat}：后者每调一次分配一个格式化器，
     * 而这行每帧都要重排八次。{@code sink} 由调用方复用（测试也传同一支，反算期望值时用的是
     * 同一个时区，断言因此与时区无关）。
     */
    public static void formatStamp(long epochMillis, HudText out, Calendar sink) {
        out.reset();
        if (epochMillis <= 0L) {
            out.chr('-');
            return;
        }
        sink.clear();
        sink.setTimeInMillis(epochMillis);
        two(out, sink.get(Calendar.MONTH) + 1);
        out.chr('-');
        two(out, sink.get(Calendar.DAY_OF_MONTH));
        out.chr(' ');
        two(out, sink.get(Calendar.HOUR_OF_DAY));
        out.chr(':');
        two(out, sink.get(Calendar.MINUTE));
    }

    private static void two(HudText out, int v) {
        int x = v < 0 ? 0 : (v > 99 ? 99 : v);
        out.chr((char) ('0' + x / 10)).chr((char) ('0' + x % 10));
    }
}
