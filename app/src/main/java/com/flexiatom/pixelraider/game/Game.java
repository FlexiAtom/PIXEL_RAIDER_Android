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
package com.flexiatom.pixelraider.game;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.KeyEvent;

import com.flexiatom.pixelraider.core.FrameProbe;
import com.flexiatom.pixelraider.core.GameThread;
import com.flexiatom.pixelraider.core.InputRouter;
import com.flexiatom.pixelraider.core.ModalStack;
import com.flexiatom.pixelraider.core.RectI;
import com.flexiatom.pixelraider.core.Scheduler;
import com.flexiatom.pixelraider.core.Time;
import com.flexiatom.pixelraider.gfx.BitmapFont;
import com.flexiatom.pixelraider.gfx.Background;
import com.flexiatom.pixelraider.gfx.FxRegistry;
import com.flexiatom.pixelraider.gfx.Ink;
import com.flexiatom.pixelraider.gfx.GlowAtlas;
import com.flexiatom.pixelraider.gfx.OffscreenCache;
import com.flexiatom.pixelraider.gfx.ParticlePool;
import com.flexiatom.pixelraider.gfx.ParticlePool.Particle;
import com.flexiatom.pixelraider.gfx.SpriteFactory;
import com.flexiatom.pixelraider.gfx.SpriteSheets;
import com.flexiatom.pixelraider.gfx.TextCache;
import com.flexiatom.pixelraider.plat.KeyValue;
import com.flexiatom.pixelraider.plat.QualityProfile;
import com.flexiatom.pixelraider.plat.Screen;
import com.flexiatom.pixelraider.ui.GrowthScreen;
import com.flexiatom.pixelraider.ui.HudLayout;
import com.flexiatom.pixelraider.ui.HudText;
import com.flexiatom.pixelraider.ui.MenuScreen;
import com.flexiatom.pixelraider.ui.PauseScreen;
import com.flexiatom.pixelraider.ui.RevealScript;
import com.flexiatom.pixelraider.ui.ResultScreen;
import com.flexiatom.pixelraider.ui.ShopScreen;
import com.flexiatom.pixelraider.ui.Widgets;

/**
 * 战场世界（S3 起）：波次 → 敌人 → 弹幕 → 碰撞 → 掉落 → 得分，全部接进固定步长循环。
 *
 * 本类**不含玩法数值与规则算术**：那些都在 {@code game} 的纯类里（{@link Balance} 供数、
 * {@link WaveFlow}/{@link EnemyBehavior}/{@link BossBehavior}/{@link DamageRules} 算规则），
 * 这里只做三件事——按帧驱动它们、把结果画出来、把输入翻译成意图。
 * 这么分不是为了好看：规格 §九.20 要求"能被断言的算法必须能在 JVM 里跑"，
 * 一旦规则写进这个 import 了 android.graphics 的类，它们就永久地测不了了。
 *
 * 两个时钟分开走（规格 §五 hit-stop）：{@link #world} 走的是被定格吃掉后的世界秒数，
 * 震屏/闪光/粒子走满 {@code dt}。
 */
public final class Game implements GameThread.Host {

    /** 触觉回调。放在 game 侧由 plat 实现，避免本类 import android 的震动服务。 */
    public interface Haptics {
        void heavyClick();

        void tick();
    }

    private static final int MARGIN = 16;
    /** 中文 UI 字号：8px 是可读下限（规格 §三），再小就得靠抗锯齿烘焙救。 */
    /** 战场上的中文读数（波次名、Boss 名、状态名）走内嵌像素中文，只能是 12 的整数倍。 */
    private static final int UI_PX = 12;
    /** 行1 内部的「标签—数字」间距与「项—项」间距（都是手调的视觉值，不是规格数）。 */
    private static final int NUM_GAP = 2;
    private static final int ITEM_GAP = 8;
    /**
     * 暂停图标 7×7 网格放大到 2 倍。只允许整数倍（规格 §三：非整数倍缩放会让像素宽窄不均），
     * 而 7 的下一档正好铺满行1 的 14 高度。
     */
    private static final int PAUSE_ICON_W = 14;
    private static final int PAUSE_ICON_H = 14;
    private static final String L_HP = "生命";
    private static final String L_SHIELD = "护盾";
    private static final String L_OVERLOAD = "过载";
    private static final String L_ENEMY = "敌人";
    private static final String L_BOSS = "首领";
    private static final String L_WAVE = "波次";
    private static final String L_CHIP = "芯片";
    private static final String L_COIN = "金币";
    private static final String L_SCORE = "分数";
    private static final String L_PHASE2 = "二阶段";

    private final Time time = new Time();
    private final InputRouter input = new InputRouter();
    private final ModalStack modals = new ModalStack();
    private final Scheduler scheduler = new Scheduler();
    private final BitmapFont font = new BitmapFont();
    private final Background background = new Background();
    private final GlowAtlas glow = new GlowAtlas();
    private final FxRegistry fx = new FxRegistry();
    private final ParticleLayer particleLayer = new ParticleLayer();
    private ParticlePool particles = new ParticlePool(256);

    // ---- 玩法（纯类 + 池）--------------------------------------------------------------

    private final WaveDirector director = new WaveDirector();
    private final Rng rng = new Rng(0x9E3779B9L);
    private final WaveFlow wave = new WaveFlow(director, rng);
    private final StatusLayers status = new StatusLayers();
    private final PlayerState player = new PlayerState();
    private final ComboMeter combo = new ComboMeter();
    private final OverloadMeter overload = new OverloadMeter();
    private final HitStop hitStop = new HitStop();
    private Enemies enemies = new Enemies(Balance.wave.maxAlive + 2);
    private final BulletPool shots = new BulletPool(Balance.bullet.capacity);
    private final BulletPool hostile = new BulletPool(Balance.bullet.enemyCapacity);
    /** 弹链：把一代 N 发弹丸排成"固定时隙 × 若干列"，布局只在买卡/换枪/有效冷却变时重算。 */
    private final BulletChain chain = new BulletChain();
    /** 帧内复用的开火模板——弹链跨帧排放，所以它必须是字段而不是 {@code playerFire} 的局部量。 */
    private final WeaponFire.Template shotTpl = new WeaponFire.Template();
    /**
     * 导弹仿真池。**不与 {@link #shots} 共用**：普通弹一进命中循环就 {@code killAt}（打中即消失），
     * 而仿真体要活过引信、再锁、战斗部三个阶段（{@link Missiles} 类头三条理由）。
     */
    private final Missiles missiles = new Missiles(Balance.missile.capacity);
    /**
     * 战斗部池（连续杆亮线 + 它破碎后的碎片）。第二个实参**只为断言** {@code Warhead.contact}
     * 那个位图装得下敌槽号，所以它必须与 {@link #enemies} 同源，不许写第二份字面量。
     */
    private Warheads warheads = new Warheads(Balance.missile.warheadCapacity, enemies.capacity());
    /** 两段视野 + 牛顿导引；内部那张 occupancy 位图按敌人容量构造，容量对不上会当场抛。 */
    private final MissileBehavior missileBehavior = new MissileBehavior(enemies.capacity());
    /**
     * **格斗弹那一流**的弹体池。与 {@link #missiles} 分家是他裁的（「弹体池建议分家」＋「跨池不排他
     * 是工程设计」，L37356），分家的**全部理由**是两条锁互不相干：普通弹的一锁/二锁各自占位，
     * 格斗弹的锁另算 ⇒ 一只敌机可以同时挂三份锁。两个池 ⇒ 两份 {@code occupiedStamp}（各在自己的
     * {@link MissileBehavior} 实例里），"普通弹锁着的敌机格斗弹照样能锁"因此是结构事实，不是特判。
     */
    private final Missiles dogfightMissiles = new Missiles(Balance.missile.dogfightCapacity);
    /** 格斗流的导引身份：读 {@code Balance.missile} 的 {@code dogfight*} 那一组自带档。 */
    private final MissileBehavior dogfightBehavior =
            new MissileBehavior(enemies.capacity(), MissileBehavior.STREAM_DOGFIGHT);
    /** 格斗弹的出膛模板：与 {@link #shotTpl} **分开**，因为那一份的 crit 是逐发 roll 的。 */
    private final WeaponFire.Template dogTpl = new WeaponFire.Template();
    /** 已处理过的淘汰次数：{@link Missiles#evicted} 是池外的**唯一**副本，读晚一次就可能被覆盖。 */
    private long evictSeen;
    /** 同上，但记的是**格斗池**那一份副本（两池各有一只 evicted，共用一个基准会漏动画）。 */
    private long dogEvictSeen;
    private Drops drops = new Drops(48);
    private SpatialGrid grid;

    /** 帧内复用的暂存：每帧零分配的代价就是这些数组必须提前 new 好。 */
    private final int[] collected = new int[Drops.KIND_COUNT];
    private final int[] candidates = new int[Balance.wave.maxAlive + 4];
    private final int[] activeKinds = new int[1];
    private final float[] shotVX = new float[BossBehavior.MAX_SHOTS];
    private final float[] shotVY = new float[BossBehavior.MAX_SHOTS];
    /** 帧内复用：{@link WarheadRules#rodEnds} 的输出（亮线两端点）。 */
    private final float[] rodEnds = new float[WarheadRules.ROD_ENDS_FLOATS];

    private float world;                 // 世界时钟（hit-stop 期间比 time.game() 慢）
    private long score;
    private int chips;
    private int coins;
    /** 金币倍率的小数余量，见 {@link #applyCollected}。 */
    private float coinCarry;
    private int bombs = Balance.player.startBombs;
    private boolean runOver;

    private float shake;
    private float shakeX, shakeY;
    private float hurtFlash;
    private float sweepFlash;
    /** 表现时钟：震屏噪声读它，不读 {@link #world}（定格期间 world 不走）。 */
    private float fxClock;

    private Haptics haptics;

    private final Paint fill = new Paint();
    private final Paint stroke = new Paint();
    private final Paint text = new Paint();
    private final RectF rf = new RectF();
    private static final Path WEDGE = new Path();
    private static final Paint WEDGE_PAINT = new Paint();
    static {
        WEDGE_PAINT.setAntiAlias(false);
        WEDGE_PAINT.setFilterBitmap(false);
    }
    private final RectI pauseDraw = new RectI();
    private final RectI pauseHit = new RectI();
    /** 炸弹按钮：绘制框在 HUD 相对坐标，命中框另加 hudTop（与暂停按钮同一套换算）。 */
    private final RectI bombDraw = new RectI();
    private final RectI bombHit = new RectI();
    /** 切枪键：与炸弹键同一套换算（绘制框 HUD 相对、命中框加 hudTop），也摆在同一条底线。 */
    private final RectI switchDraw = new RectI();
    private final RectI switchHit = new RectI();
    private final InputRouter.Event ev = new InputRouter.Event();
    private final int[] firedCommands = new int[4];

    /** 中文标签烘焙缓存（规格 §三：运行期只做一次 drawBitmap）。 */
    private final TextCache uiText = new TextCache();
    /**
     * 帧内复用的格式化缓冲：HUD 数字每帧重排，用 String 拼接就等于每帧造两个对象。
     * 只有一块——一次 {@code reset()} 格式化必须紧跟着绘制，先攒两个读数再画会互相盖掉。
     */
    private final HudText hud = new HudText(96);
    /** 帧内分段计时（诊断用）。渲染线程拿它量 LOCK/POST，本类量逻辑与各绘制段。 */
    private final FrameProbe probe = new FrameProbe();
    private final RectI rectA = new RectI();
    private final RectI rectB = new RectI();
    private final RectI rectC = new RectI();

    /** 结算页（绘制 + 命中框）。放在 uiText/font/glow 之后：字段初始化按声明顺序，早一行就是 null。 */
    private final ResultScreen result = new ResultScreen(font, uiText, glow);
    /** 暂停战术面板。同上，构造依赖 font/uiText/glow 三块已经 new 好。 */
    private final PauseScreen pause = new PauseScreen(font, uiText, glow);
    /** 升级商店面板。同上。 */
    private final ShopScreen shop = new ShopScreen(font, uiText, glow);
    /** 主菜单页（成长页的宿主）。同上。 */
    private final MenuScreen menu = new MenuScreen(font, uiText, glow);
    /** 成长页：局外成长树的**消费面板**——{@code GrowthTree} 今天终于有人读它了。同上。 */
    private final GrowthScreen growthPage = new GrowthScreen(font, uiText, glow);
    /** 局内成长：商店卡买到的等级与五个乘子（局外那套在 {@link #growth}，两套互不覆盖）。 */
    private final ShopRun shopRun = new ShopRun();
    /**
     * 商店的现状快照。**Game 拥有并在每次开架与购买后重填**，面板拿引用读——买完一张卡之后
     * 金币与等级都变了，货架上的价格要立刻跟着变，传副本就得在两处维护"买完之后"。
     */
    private final ShopRules.Snapshot shopSnap = new ShopRules.Snapshot();
    private final float[] shopScore = new float[Balance.Shop.CARDS];
    private final int[] shopOrder = new int[Balance.Shop.CARDS];
    private final int[] shopOffer = new int[Balance.Shop.CARDS];
    /** 进入商店的界面时刻（面板的入场与级联都以它为原点）。 */
    private float shopAtUi;
    /** 上一帧的波次相位：只用来看"刚刚清完一波"这一条边沿。 */
    private int lastFlowPhase = WaveFlow.PREP;
    /** 这条边沿的商店还没开出去（栈顶被别的面板占着），等它空出来补开。 */
    private boolean shopPending;
    /** 本局原始计数（结算页的输入）。 */
    private final RunStats stats = new RunStats();
    /** 暂停面板折线图的数据源：按世界秒累计，暂停期间不推进。 */
    private final TrendSampler trend = new TrendSampler();
    private final RunRecords records = new RunRecords();
    /** 局外成长树：数值乘在这里、芯片也存在这里（规格 §五 六项 ×5 级）。 */
    private final GrowthTree growth = new GrowthTree();
    private final ResultSheet sheet = new ResultSheet();
    /** 落盘出口。渲染线程拿不到 Context，由 plat 在启动时注入（规格 §八 的边界）。 */
    private KeyValue store = KeyValue.EMPTY;
    /** 一局结束（死亡或通关）那一刻的界面时间：结算页的分阶段揭示以它为原点。 */
    private float resultAtUi;
    /** 拉开暂停那一刻的界面时间：入场动画与"淡完才可点"以它为原点（规格 §五 uiTime）。 */
    private float pauseAtUi;
    /** 主菜单与成长页的同一件事：每页自己的入场原点。 */
    private float menuAtUi;
    private float growthAtUi;
    /**
     * 最近一次关掉面板的界面时刻。只为一个判据服务：刚抬起的那一下不算战场手势，
     * 见 {@link #justClosedModal()}。-1 = 这一局还没关过任何面板。
     */
    private float modalClosedAtUi = -1f;
    /** 系统双击判定窗的量级（Android 默认 viewConfiguration.doubleTapTimeout ≈ 300ms）。 */
    private static final float DOUBLE_TAP_SLOP_SEC = 0.35f;
    /** 重开转场：0.45s 全屏淡入（规格 §四）。> 0 表示还在淡。 */
    private float restartFade;
    /** 血/盾条的拖尾残影：掉血量就是那段残影的长度（规格 §四）。 */
    private float hpTrail;
    private float shieldTrail;

    private Screen.Metrics metrics = defaultMetrics();
    /** 几何是否已经就绪过一次：只有那一次才开新局（见 {@link #onSurfaceReady}）。 */
    private boolean runBooted;
    private int hitBoxLogic;

    private int movePointerId = -1;
    private float anchorX, anchorY;
    /**
     * 手指位移的欠账（规格 §二 的相对位移拖动）。见 {@link DragDebt}——限速在这里，
     * 所以机动类乘子对触屏真的有效。
     */
    private final DragDebt drag = new DragDebt();
    private float keyVx, keyVy;
    private int qualityTier = QualityProfile.QUALITY_MID;

    /** 特效注册表里的粒子层：层级是数据，不是 render() 里的调用先后。 */
    private final class ParticleLayer implements FxRegistry.Layer {
        @Override public int order() { return FxRegistry.ORDER_PARTICLES; }
        @Override public boolean isEmpty() { return particles.activeCount() == 0; }
        @Override public void draw(Canvas canvas) { drawParticles(canvas); }
    }

    public Game() {
        fx.register(particleLayer);
        // 规格 §零：所有精灵 Paint 不带 ANTI_ALIAS、不带 FILTER_BITMAP、不抖动
        fill.setAntiAlias(false);
        fill.setFilterBitmap(false);
        fill.setDither(false);
        stroke.setAntiAlias(false);
        stroke.setFilterBitmap(false);
        stroke.setDither(false);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1f);
        text.setAntiAlias(false);
        text.setFilterBitmap(false);
        text.setDither(false);
    }

    public void setHaptics(Haptics h) {
        haptics = h;
    }

    /**
     * 注入落盘出口并在同一时刻读回持久状态。只在启动时（surface 建立之前）由 plat 调一次——
     * 晚调等于渲染线程可能已经在读一份没 load 过的纪录表。
     */
    public void attachStore(KeyValue kv) {
        store = kv == null ? KeyValue.EMPTY : kv;
        records.load(store);
        growth.readFrom(store);
    }

    /** 成长树读数给界面（主菜单/暂停面板）用，写只走 {@link GrowthTree#buyTreeUpgrade}。 */
    public GrowthTree growth() {
        return growth;
    }

    private static Screen.Metrics defaultMetrics() {
        return Screen.compute(Screen.LOGIC_W, Screen.LOGIC_H_MIN, 0, 0, 0, 0,
                Screen.SCALE_MIN);
    }

    public Time time() {
        return time;
    }

    /** 交给渲染线程量 LOCK/POST；与 {@link #frame} 里的段共用同一张表。 */
    public FrameProbe probe() {
        return probe;
    }

    public InputRouter input() {
        return input;
    }

    public ModalStack modals() {
        return modals;
    }

    public Scheduler scheduler() {
        return scheduler;
    }

    /** 命中区边长（逻辑像素）：48dp 在当前机型上的换算结果，随 surfaceChanged 重算。 */
    public int hitBoxLogic() {
        return hitBoxLogic;
    }

    public void onSurfaceReady(Screen.Metrics m, float density, int qualityTier) {
        boolean first = !runBooted;
        runBooted = true;
        installGeometry(m, density, qualityTier);
        // 一次性初始化，不是每次投递：见 resetRun 的 javadoc。缺这道守卫时，任何一次 insets/layout
        // 重投递都会把玩家正在打的这一局清零。
        if (first) bootRun();
    }

    /**
     * 只装几何——出生点与各池一律不碰。
     *
     * <p>这一支每次投递都跑，包括"尺寸真的变了"的那次（临时导航栏会把 {@code safeBottom} 改掉）。
     * 出生点不在这里：{@code respawn} 会瞬移 + 回满血盾 + 给无敌，那是"开新局"的动作，
     * 不是"窗口变了"的动作；几何变了人会不会掉出战场由每帧 {@code clampToBattle} 兜回。
     */
    private void installGeometry(Screen.Metrics m, float density, int qualityTier) {
        metrics = m;
        input.setMetrics(m);
        this.qualityTier = qualityTier;
        hitBoxLogic = Widgets.minTouchLogic(m.pxPerLogicX, density);
        HudLayout.pauseRect(pauseDraw);
        Widgets.hitRect(pauseDraw, hitBoxLogic, pauseHit);
        // 绘制框在 HUD 相对坐标（render 里整块按 hudTop 平移），命中框在逻辑屏幕坐标——
        // 两套坐标系只在这里换算一次，谁忘加 hudTop 都会当场点不中暂停。
        pauseHit.offsetInPlace(0, m.hudTop());
        HudLayout.bombRect(m.battleHeight(), bombDraw);
        Widgets.hitRect(bombDraw, hitBoxLogic, bombHit);
        bombHit.offsetInPlace(0, m.hudTop());
        HudLayout.switchRect(m.battleHeight(), switchDraw);
        Widgets.hitRect(switchDraw, hitBoxLogic, switchHit);
        switchHit.offsetInPlace(0, m.hudTop());
        result.layout(hitBoxLogic, m.pageTop(result.pageHeight()), m.logicH);
        // 三张固定页各自报整页高，居中原点由 pageTop 给（口径同结算页，不跟着战场长高）
        menu.layout(hitBoxLogic, m.pageTop(menu.pageHeight()), m.logicH);
        growthPage.layout(hitBoxLogic, m.pageTop(growthPage.pageHeight()), m.logicH);
        // 面板走整张逻辑画布坐标（要在 320~560 上分账图表与卡片），另需安全区让它从挖孔带让开
        pause.layout(m.logicH, m.safeTop, m.safeBottom, hitBoxLogic);
        shop.layout(m.logicH, m.safeTop, m.safeBottom, hitBoxLogic);
        particles = new ParticlePool(QualityProfile.particleBudget(qualityTier));
        background.rebuild(Screen.LOGIC_W, m.logicH, qualityTier, 1);
        // 网格尺寸跟着画布走：高度是随屏幕比例算出来的，不重建就会在底部漏掉一整排
        // 覆盖半径读的是 SpatialGrid 的那个常量——战斗部覆盖不变量的单测同读一处，
        // 测试里再写一遍 40 就立了第二个真源。
        grid = new SpatialGrid(Screen.LOGIC_W, m.logicH, enemies.capacity(),
                SpatialGrid.DEFAULT_MAX_CENTER_DISTANCE);
    }

    /**
     * surface 就绪附带的那一局，开完**停在主菜单上**：冷启动直进波次 1 算缺口
     * （2026-09-29 用户裁定）。菜单整页盖住战场，所以这里把模拟冻住——不冻的话菜单背后那一局
     * 照样在打波次、照样能死，然后结算页会盖到菜单上面去。解冻只有 {@code resetRun} 一条路
     * （「开始新一局」走它），与暂停页那条定格态同一口径。
     */
    private void bootRun() {
        resetRun();
        time.setPaused(true);
        openMenu();
    }

    public int qualityTier() { return qualityTier; }

    public ParticlePool particles() { return particles; }

    /** 画质变档：释放全部可重建离屏缓存，按新档重建（规格 §六）。 */
    public void onQualityChanged(int newTier) {
        if (newTier == qualityTier) return;
        qualityTier = newTier;
        OffscreenCache.releaseAll();
        background.release();
        particles = new ParticlePool(QualityProfile.particleBudget(newTier));
        background.rebuild(Screen.LOGIC_W, metrics.logicH, newTier, 1);
    }

    /**
     * 渲染线程启动时补回 {@link #releaseResources()} 交还的背景烘焙层。
     *
     * <p>字体、光晕图集、文本缓存都是按需重建的（回收之后下一次取用自己会长回来），唯独
     * {@link Background} 不是：它整块画不画只看 {@code w}，而 {@code release()} 把 {@code w} 打回
     * -1，于是"中途退出再回来"会留下一个纯黑的战场，面板与 HUD 照画不误（2026-09-25 用户报）。
     *
     * <p>{@code metrics} 可能还没到——{@code surfaceCreated} 会早于 MainActivity 的
     * {@code applyMetrics}，那一次重建由 {@link #onSurfaceReady} 负责，这里不必抢。
     */
    @Override
    public void acquireResources() {
        if (metrics != null) {
            background.rebuild(Screen.LOGIC_W, metrics.logicH, qualityTier, 1);
        }
    }

    @Override
    public void releaseResources() {
        background.release();
        glow.recycle();
        font.recycle();
        uiText.clear();
        OffscreenCache.releaseAll();
    }

    @Override
    public void frame(float deltaSeconds, Canvas canvas) {
        // 输入**每帧一次、必须在 advance 之前**：Time 一被 setPaused(true) 就返回 0 步，
        // 挪进步骤循环等于按下暂停那一刻起再也收不到任何事件——面板上的"继续作战"永远点不动。
        consumeInput();
        int steps = time.advance(deltaSeconds);
        probe.begin(FrameProbe.STEP);
        for (int i = 0; i < steps; i++) {
            step(Time.STEP);
            // 某一步自己把面板打开了（清完一波→商店、阵亡→结算）就到此为止。不 break 的话
            // 补帧剩下的那一两个 1/60 秒仍会在已经冻住的画面背后跑一程：玩家在商店里看到的
            // 战场不是按下最后一枪那一刻的，而是又往后挪了两帧的。
            if (time.isPaused()) break;
        }
        int fired = scheduler.fireDue(time.game(), firedCommands, firedCommands.length);
        for (int i = 0; i < fired; i++) {
            onScheduled(firedCommands[i]);
        }
        probe.end(FrameProbe.STEP);
        render(canvas);
    }

    // ---- 输入 -------------------------------------------------------------------------------

    private void consumeInput() {
        int guard = 0;
        while (guard < 64 && input.poll(ev)) {
            guard++;
            switch (ev.kind) {
                case InputRouter.EV_POINTER_DOWN:
                    onPointerDown(ev.pointerId, ev.x, ev.y);
                    break;
                case InputRouter.EV_POINTER_MOVE:
                    onPointerMove(ev.pointerId, ev.x, ev.y);
                    break;
                case InputRouter.EV_POINTER_UP:
                    onPointerUp(ev.pointerId, ev.x, ev.y);
                    break;
                case InputRouter.EV_DOUBLE_TAP:
                    // [规格] 双击释放超载扫描（键盘是 X 键）。
                    // 走 battlefieldGestureAllowed 而不是只看栈顶：见 markModalClosed 那段——
                    // 连点出口的两下，第二下的按下到达时面板已经关完了。
                    if (battlefieldGestureAllowed()) triggerOverload();
                    break;
                case InputRouter.EV_KEY_DOWN:
                    onKeyDown(ev.extra);
                    break;
                case InputRouter.EV_KEY_UP:
                    onKeyUp(ev.extra);
                    break;
                case InputRouter.EV_BACK:
                    onBack();
                    break;
                case InputRouter.EV_PAUSE:
                    // 规格 §零：onPause 自动进入暂停菜单。只 setPaused(true) 是不够的——
                    // 那等于把时钟冻在没有面板的地方，回前台后没有任何出口能解冻。
                    openPause();
                    break;
                case InputRouter.EV_RESUME:
                    time.forceNextFrameToOneStep();
                    break;
                case InputRouter.EV_DEBUG_KILL:
                    debugEndRun();
                    break;
                case InputRouter.EV_DEBUG_VICTORY:
                    debugForceVictory();
                    break;
                default:
                    break;
            }
        }
    }

    private void onBack() {
        int top = modals.peek();
        if (top == ModalStack.RESULT) {
            // 结算页没有"返回上一级"：栈里只有它自己，返回键什么都不做。
            // 规格 §四 特意取消"点任意处重开"，理由同样适用于误按返回。
            return;
        }
        if (top == ModalStack.MENU) {
            // 主菜单是这条链的根（结算页 → 主菜单 → 成长页），它下面没有"上一级"可回，
            // 返回键什么都不做。成长页不留在这条判断里：它走下面的通用 pop，退回主菜单。
            return;
        }
        if (top == ModalStack.NONE) {
            openPause();
            return;
        }
        if (top == ModalStack.SHOP) {
            // 返回键 = 走那枚唯一的出口（这一波静场结束）。掉到下面的通用 pop 会把时钟留在冻住的状态
            closeShop();
            return;
        }
        if (modals.pop() == ModalStack.PAUSE) {
            time.setPaused(false);
            markModalClosed();
        }
    }

    /** 开面板：把走位的按下态一并丢掉，否则解暂停后会凭空朝某个方向滑一下。 */
    private void openPause() {
        // 只问模态，不问 runOver：runOver 现在是"模拟闸门"，界面侧的单一真值住在栈顶
        if (modals.peek() != ModalStack.NONE) return;
        modals.push(ModalStack.PAUSE);
        time.setPaused(true);
        dropMoveInput();
        pauseAtUi = time.ui();
        // 上一轮的按压态不能带进来：抬起事件一旦被环满丢掉，按钮就会永远亮着
        pause.clearPress();
    }

    private void closePause() {
        if (modals.peek() != ModalStack.PAUSE) return;
        modals.pop();
        time.setPaused(false);
        markModalClosed();
    }

    /**
     * 记一下"面板刚刚不在场"。
     *
     * <p>存在的理由只有一条：{@code onDoubleTap} 是在**第二下的按下**那一刻报上来的，
     * 而第一下的抬起早就把面板关掉了。于是"连点出口两下"到达 {@code triggerOverload} 时
     * 栈顶已经是 NONE，模态闸门拦不住——玩家只点了一下按钮，30 秒充能却没了。
     */
    private void markModalClosed() {
        modalClosedAtUi = time.ui();
    }

    /** 双击这类战场手势：栈顶要空，而且离上一次关面板得超过一个双击判定窗。 */
    private boolean battlefieldGestureAllowed() {
        if (modals.peek() != ModalStack.NONE) return false;
        return time.ui() - modalClosedAtUi > DOUBLE_TAP_SLOP_SEC;
    }

    // ---- 升级商店 -------------------------------------------------------------------------

    /**
     * 认"刚刚清完一波"这条边沿，开一次商店（规格 §零：波次 → 清场 → 升级商店 → 下一波）。
     *
     * <p>为什么在 Game 这边看边沿、不给 {@link WaveFlow} 加回调：那块状态机不该知道界面存在，
     * 而这条边沿每局只出现一次（INTERMISSION 里它不会再翻）。
     *
     * <p>挡了要挂起、不能丢：边沿每局只出现一次，"这一波栈顶有人"就把商店整块跳过，
     * 真机上看起来就跟"商店根本没实现"一模一样（2026-09-24 用户报的正是这个现象）。
     * 结算页那一档不必特殊处理：这段在 {@code if (!runOver)} 里面，人死了这一局也就到此为止。
     */
    private void maybeOpenShop() {
        int ph = wave.phase();
        if (ph != WaveFlow.INTERMISSION) {
            lastFlowPhase = ph;
            shopPending = false;
            return;
        }
        if (lastFlowPhase != WaveFlow.INTERMISSION) {
            lastFlowPhase = ph;
            shopPending = true;
        }
        if (shopPending && modals.peek() == ModalStack.NONE) {
            shopPending = false;
            openShop(ShopRules.ENTRY_WAVE);
        }
    }

    private void openShop(int entry) {
        fillShopSnapshot();
        // 入口在开架这一瞬冻结进快照：货架摆什么、打不打折、关店干什么，三件事都由它派生
        shopSnap.entry = entry;
        int n = ShopRules.selectOffer(shopSnap, rng, shopScore, shopOrder, shopOffer);
        shop.open(shopOffer, n, shopSnap);
        modals.push(ModalStack.SHOP);
        time.setPaused(true);
        dropMoveInput();
        shopAtUi = time.ui();
    }

    /**
     * 关店。**做什么由入口决定，不由调用点决定**（{@code ShopRules.ENTRY_*}）。
     *
     * <p>回合店：这一波的静场到此结束，直接进下一波的 PREP（横幅照给，出怪不提前）。
     * 商店是静场：回来时战场也该是静的——留着那批在清场瞬间冻住的敌弹，等于让玩家在购买界面
     * 停留半分钟之后落地即死。掉落物不清，那是玩家打出来的钱。
     *
     * <p>暂停店：只是把面板掀回暂停页。栈里 SHOP 下面压着 PAUSE，走位与射击的闸门看栈顶，
     * 时钟本来就是冻着的，所以这里**只 pop**——清场会把玩家暂停前面对的弹幕抹掉，
     * {@code finishIntermission()} 会在间奏里凭空推进波次，两条都是拿回合店的语义去套暂停店。
     */
    private void closeShop() {
        if (modals.peek() != ModalStack.SHOP) return;
        modals.pop();
        if (shopSnap.entry == ShopRules.ENTRY_PAUSE) return;
        hostile.clear();
        wave.finishIntermission();
        time.setPaused(false);
        markModalClosed();
    }

    /** 商店报上来的动作：买卡，或结束这一波。 */
    private void onShopPointerUp(int action) {
        if (action == ShopScreen.ACTION_NEXT) {
            closeShop();
            return;
        }
        if (action >= ShopScreen.ACTION_BUY) {
            buyShopCard(action - ShopScreen.ACTION_BUY);
        }
    }

    /**
     * 买一张卡。**顺序即正确性**：先确认这张卡真的能升、再扣钱、最后才把效果打到现场上。
     *
     * <p>反过来会出现"钱扣了卡没升"；先把效果打完再判余额则会出现"白嫖一次满血"。
     * 判据复用 {@link ShopRules#purchasable}——面板的命中端读的是同一个函数，
     * 所以"灰着的卡点不动"与"点不动的卡扣不到钱"不可能分裂。
     */
    private void buyShopCard(int slot) {
        int id = shop.offerAt(slot);
        if (id < 0 || id >= Balance.shopCards.length) return;
        fillShopSnapshot();
        Balance.ShopCard card = Balance.shopCards[id];
        if (!ShopRules.purchasable(card, shopSnap)) return;
        int price = ShopRules.nextPrice(card, shopSnap);
        if (!shopRun.buyUpgrade(id)) return;
        coins -= price;
        applyShopUpgrade(id);
        fillShopSnapshot();
    }

    /**
     * 把刚买的那一级打到现场。
     *
     * <p>只有会改**别的容器**的卡需要这一步（血、盾、炸弹、超载、状态层）；五个乘子是 {@link #shopRun}
     * 现算的，读取点自己会看到新等级，这里不需要做任何事。
     */
    private void applyShopUpgrade(int id) {
        switch (id) {
            case Balance.ShopCard.HULL:
                // 抬上限的同时把新增那一截补满（raiseCaps 只在真的抬高时才补，见 PlayerState）
                player.raiseCaps(player.maxHp + Balance.shop.hullPerLevel, player.maxShield);
                break;
            case Balance.ShopCard.REPAIR:
                player.healHp(Balance.shop.repairAmount, Balance.shield.fromHpOverfullSmall);
                break;
            case Balance.ShopCard.SHIELD:
                // 走溢出通道：卡面已经写了 +50，这里按剩余空间打折就是"显示与实际不一致"。
                // 读的还是 Balance.shield.fromUpgrade 那一格——和 ShopRules.coreValue 同源。
                player.addShieldBeyondCap(Balance.shield.fromUpgrade);
                break;
            case Balance.ShopCard.SALVO:
                bombs += Balance.shop.salvoBombs;
                break;
            case Balance.ShopCard.SURGE:
                overload.forceReady();
                break;
            case Balance.ShopCard.RANDOM:
                // 商店侧只抽**四种增益**（0..SWIFT），掉落侧抽**八种**（0..PICKUP_COUNT，含减益）：
                // 两个抽样集是两条独立决定的产物，不是这里漏了一半（掉落侧存废仍未裁）。
                // activate 的语义逐字是"新的顶掉旧的"，所以手上有狂热时抽到幸运是**净亏**——
                // 他给的知情渠道就是卡面 tip 那半句（「至于买了之后顶掉，一行提示即可」）。
                status.activate(rng.nextInt(StatusLayers.SWIFT + 1));
                break;
            default:
                break;
        }
    }

    /** 快照的每一项都是"这张卡现在点它会不会有事发生"的判据，全部现读现场。 */
    private void fillShopSnapshot() {
        shopSnap.level = shopRun.levels();
        shopSnap.wave = wave.wave();
        shopSnap.hp = player.hp;
        shopSnap.maxHp = player.maxHp;
        shopSnap.shield = player.shield;
        shopSnap.maxShield = player.maxShield;
        shopSnap.overloadReady = overload.ready();
        shopSnap.bombs = bombs;
        shopSnap.coins = coins;
    }

    private void onPointerDown(int id, int x, int y) {
        int top = modals.peek();
        if (top == ModalStack.RESULT) {
            result.pressDown(x, y, time.ui() - resultAtUi, id);
            return;
        }
        if (top == ModalStack.PAUSE) {
            pause.pressDown(x, y, time.ui() - pauseAtUi, id);
            return;
        }
        if (top == ModalStack.SHOP) {
            shop.pressDown(x, y, time.ui() - shopAtUi, id);
            return;
        }
        if (top == ModalStack.MENU) {
            menu.pressDown(x, y, time.ui() - menuAtUi, id);
            return;
        }
        if (top == ModalStack.GROWTH) {
            growthPage.pressDown(x, y, time.ui() - growthAtUi, id);
            return;
        }
        if (pauseHit.contains(x, y)) {
            openPause();
            return;
        }
        // 切枪键排在炸弹**之前**判：两枚键的命中框都由 hitRect 按中心外扩到 48dp 下限，
        // 低分辨率屏（外扩量 ≥ 42）上中间那 8 格净空会被咬掉，此时必须让误判落在换枪那一侧——
        // 它只多一次震动，而反过来是把一件存了整波的炸弹点没了。判据与几何都在 HudLayout.switchRect。
        // 另一处不对称是顺带说清的：这一枚不像炸弹那样配"没存货就不注册命中"的条件——这一圈上
        // 永远还有下一把（四把默认枪不挂闸门，那条循环钉在 ShopRulesTest 的"每按一次必换"）。
        if (switchHit.contains(x, y)) {
            selectWeapon(shopRun.nextUnlockedWeapon(player.weaponId));
            return;
        }
        // 没有存货就不注册命中：一颗点不动的按钮比没有按钮更糟（规格点名的"点了没反应"bug 类）
        if (bombs > 0 && bombHit.contains(x, y)) {
            detonateBomb();
            return;
        }
        if (movePointerId == -1) {
            movePointerId = id;
            anchorX = x;
            anchorY = y;
        }
    }

    /**
     * 抬手才执行动作（MD3 的按下→抬起语义，也顺手补上了旧版缺的一件事实："按下去、发现按错、
     * 把手指滑开"以前是撤不掉的）。面板开着时每一击都在这里被消化，绝不漏到走位上去。
     */
    private void onPointerUp(int id, int x, int y) {
        int top = modals.peek();
        if (top == ModalStack.PAUSE) {
            onPanelPointerUp(pause.pressUp(x, y, time.ui() - pauseAtUi, id));
        } else if (top == ModalStack.SHOP) {
            onShopPointerUp(shop.pressUp(x, y, time.ui() - shopAtUi, id));
        } else if (top == ModalStack.MENU) {
            onMenuPointerUp(menu.pressUp(x, y, time.ui() - menuAtUi, id));
        } else if (top == ModalStack.GROWTH) {
            onGrowthPointerUp(growthPage.pressUp(x, y, time.ui() - growthAtUi, id));
        } else if (top == ModalStack.RESULT) {
            onResultPointerUp(result.pressUp(x, y, id));
        }
        if (id == movePointerId) movePointerId = -1;
    }

    /**
     * 暂停面板报上来的出口动作：只有"继续作战""升级商店""重开本局""返回主菜单"四个出口现在真的活着——
     * 未落地的出口在 {@code PauseScreen.EXIT_LIVE} 里就已被剔除，压根报不出动作码。
     *
     * <p>商店那一枚走 {@link #openShop}(ENTRY_PAUSE)：SHOP 压在 PAUSE 之上，关店只 pop 一层，
     * 玩家回到的是**同一块**暂停面板、同一个定格时钟（{@code pauseAtUi} 不动，面板重新画出来时
     * 入场进度早已是 1，不会再落一次）。
     */
    private void onPanelPointerUp(int action) {
        if (action == PauseScreen.ACTION_RESUME) {
            closePause();
        } else if (action == PauseScreen.ACTION_SHOP) {
            openShop(ShopRules.ENTRY_PAUSE);
        } else if (action == PauseScreen.ACTION_RESTART) {
            // 出口一级化（规格 §四）：重开直接生效，没有二次确认弹窗
            restartFade = 1f;
            resetRun();
        } else if (action == PauseScreen.ACTION_MENU) openMenu();   // 复用结算页那条链。这里**故意不解冻**时钟：菜单页没有"回到这一局"的出口，出去只有 resetRun（它自己解冻），所以留在定格态 = "这一局已被放弃"的唯一真值；ui 时钟照走，菜单动画不受影响
    }

    /** 结算页的两个按钮（规格 §四 取消"点任意处重开"，防误触窗口挡在 {@code pressDown} 那侧）。 */
    private void onResultPointerUp(int action) {
        if (action == ResultScreen.ACTION_RETRY) {
            restartFade = 1f;
            resetRun();
        } else if (action == ResultScreen.ACTION_MENU) {
            openMenu();
        }
    }

    /**
     * 让位给主菜单。三个调用点各自负责时钟，这里**一律不碰** {@code time.setPaused}：暂停页那条
     * 已由 {@link #openPause} 冻上；结算页那条的闸门是 {@code runOver}；冷启动那条自己冻
     * （见 {@link #bootRun}）。在这里再做一遍，等于给"从菜单退回这一局"留一条谁都没打算走的后门。
     *
     * <p>结算页要**让位**而不是留在菜单底下：它是这一串的根，留在下面会让返回键语义变成
     * "从主菜单退回结算页"——那一屏是给死人看的，不该还有一步可以退回去。
     */
    private void openMenu() {
        modals.pop();
        modals.push(ModalStack.MENU);
        dropMoveInput();
        menuAtUi = time.ui();
        menu.clearPress();
    }

    /** 主菜单的两个出口：开新局，或去成长页花芯片。 */
    private void onMenuPointerUp(int action) {
        if (action == MenuScreen.ACTION_RUN) {
            restartFade = 1f;
            resetRun();               // 里面那句 popUntil(NONE) 顺手把这一页收掉
        } else if (action == MenuScreen.ACTION_GROWTH) {
            openGrowth();
        }
    }

    /** 成长页压在菜单页之上：pop 回菜单。时钟不动，理由同 {@link #openMenu}。 */
    private void openGrowth() {
        modals.push(ModalStack.GROWTH);
        growthPage.open(growth);
        dropMoveInput();
        growthAtUi = time.ui();
    }

    /**
     * 成长页报上来的动作：**买**。三种拒绝（越界、满级、余额不足）全在
     * {@link GrowthTree#buyTreeUpgrade} 里，而且都不动状态；面板那侧灰掉不注册命中的判据
     * 读的是同一组数，所以"点得动的行"与"扣得到钱的行"不可能分裂。
     *
     * <p>落盘紧跟在成功之后：一次购买是一笔交易，不是一次可以丢的广播（{@code GrowthTree.save}
     * 那句 flush 就是为这条写的）。失败**不写**——没有变化要落地，多一次磁盘写只多一次抖动。
     */
    private void onGrowthPointerUp(int action) {
        if (action == GrowthScreen.ACTION_BACK) {
            modals.pop();
            return;
        }
        if (action >= GrowthScreen.ACTION_BUY
                && growth.buyTreeUpgrade(action - GrowthScreen.ACTION_BUY)) {
            growth.save(store);
        }
    }

    private void onPointerMove(int id, int x, int y) {
        // 面板开着时手指滑动只改按压态，绝不改到战场
        int top = modals.peek();
        if (top == ModalStack.PAUSE) {
            pause.pressDrag(x, y, time.ui() - pauseAtUi, id);
            return;
        }
        if (top == ModalStack.SHOP) {
            shop.pressDrag(x, y, time.ui() - shopAtUi, id);
            return;
        }
        if (top == ModalStack.MENU) {
            menu.pressDrag(x, y, time.ui() - menuAtUi, id);
            return;
        }
        if (top == ModalStack.GROWTH) {
            growthPage.pressDrag(x, y, time.ui() - growthAtUi, id);
            return;
        }
        if (top == ModalStack.RESULT) {
            result.pressDrag(x, y, id);
            return;
        }
        if (id != movePointerId) return;
        // 只记账，不搬运：见 DragDebt。锚点照旧跟着手指走，战机什么时候到位由限速决定。
        drag.add(x - anchorX, y - anchorY);
        anchorX = x;
        anchorY = y;
    }

    private void onKeyDown(int code) {
        if (code == KeyEvent.KEYCODE_ESCAPE) {
            onBack();          // 面板开着的时候也必须收得到返回键，所以挡在模态判定之前
            return;
        }
        if (modals.peek() != ModalStack.NONE) return;   // 面板开着时键盘不动战场
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_A:
                keyVx = -1f;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_D:
                keyVx = 1f;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_W:
                keyVy = -1f;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_S:
                keyVy = 1f;
                break;
            case KeyEvent.KEYCODE_X:
                triggerOverload();
                break;
            case KeyEvent.KEYCODE_1:
            case KeyEvent.KEYCODE_2:
            case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_4:
            case KeyEvent.KEYCODE_5:
            case KeyEvent.KEYCODE_6:
                selectWeapon(code - KeyEvent.KEYCODE_1);
                break;
            default:
                break;
        }
    }

    private void onKeyUp(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_A:
                if (keyVx < 0) keyVx = 0f;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_D:
                if (keyVx > 0) keyVx = 0f;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_W:
                if (keyVy < 0) keyVy = 0f;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_S:
                if (keyVy > 0) keyVy = 0f;
                break;
            default:
                break;
        }
    }

    /**
     * 换枪不清掉已经在飞的弹：玩家的主动投资不该被自己的一键取消。
     *
     * <p>这一处同时是**持有量的闸门**：键盘 1..6 与底部切枪键都汇到这里，所以"没买过的枪按下去
     * 会不会响"只有一条答案。⚠ 它今天才开始拦键盘——原先六把枪全都能按，而其中两把从来没在
     * 商店里卖过（{@code ShopRun.weaponUnlocked}），于是键盘是唯一的获取途径、触屏压根没有。
     * 拦完之后键盘与触屏读的是同一个持有集合：调试要先看到导弹，就去商店买那一张「基础导弹」，
     * 而不是在这里给键盘留一条绕过闸门的近路（留了就有两个真源，而"哪把枪能按"正是这次出问题的地方）。
     */
    private void selectWeapon(int id) {
        if (id < 0 || id >= Balance.weapons.length || id == player.weaponId) return;
        if (!shopRun.weaponUnlocked(id)) return;
        player.weaponId = id;
        if (haptics != null) haptics.tick();
    }

    private void onScheduled(int commandId) {
        // 延迟特效通道：S3 还没有排期特效，命令照常走完 fireDue
    }

    // ---- 每帧 -------------------------------------------------------------------------------

    private void step(float dt) {
        // worldSecondsFor 自己会把定格额度扣掉，不要再 step() 一次——那等于两倍的衰减
        float wdt = hitStop.worldSecondsFor(dt);
        decayEffects(dt);                 // 震屏/闪光走**满 dt**，规格点名的两个时钟分离
        if (!runOver) {
            player.stepTimers(wdt);
            status.setEnvironment(StatusLayers.envForZone(wave.zoneIndex()));
            status.step(wdt);
            overload.step(wdt);
            movePlayer(wdt);
            spawnQueued(wdt);
            stepEnemies(wdt);
            // ⚠ 夹在 stepEnemies 与 stepChain 之间，两条都是硬的：它要读本步的**新**敌位
            // （否则引信与视场对着一帧前的敌人），又不能推进本步刚出膛的那一枚（链在它之后）。
            stepMissiles(wdt);
            // 链必须在 playerFire 之前推进：布局的一列容量 C 是 `floor(周期/间隔 + 一格余量)` 取整来的，
            // 于是排放窗口**最多可以比周期长出去一格的一小部分**（1e-4 个间隔，远小于一格弹链）。
            // 这一格恰好落在"下一代就绪"的同一步上——先推进链，最后那个时隙先出膛，随后
            // beginGeneration 才重置游标；反过来写就会把整代里最靠后的那个时隙**静默吃掉**
            // （横向优先之后它是一整排并列的列，最多 COLUMN_CAP 发，比旧律的单发重得多）。
            stepChain(wdt);
            playerFire();
            stepBullets(wdt);
            collide(wdt);
            stepDrops(wdt);
            reapDead();
            world += wdt;
            stats.worldSeconds = world;                 // 同上：面板上的"存活时长"要活读
            // 趋势采样吃**世界步长**：暂停与定格都不算进折线（规格 §四"每 1 秒一帧"是战场秒）
            trend.tick(wdt, (int) stats.kills, score, player.hpRatio());
            maybeOpenShop();
            // 通关出口放在这一段**最后**：波次状态机自己不知道界面存在，VICTORY 是谁把它翻译成
            // 结算页的，得有个唯一的地方——就是这里，与死亡共用 {@link #settleRun}。
            // 放在末尾还有一条实际好处：同一步里商店那道边沿先走过（末波 closeWave 直接进
            // VICTORY、不进 INTERMISSION，所以它本来也不会开），终态不会跟面板抢栈。
            if (wave.victory()) onVictory();
        }
        particles.stepAndCompact(dt);
        particles.cullOutOfBounds(Screen.LOGIC_W, metrics.logicH, 8);
        background.step(dt);
    }

    private void decayEffects(float dt) {
        // 残影走满 dt（与震屏同一个道理）：定格期间它若冻住，玩家恰好在看的那段掉血不缩
        hpTrail = HudLayout.trailNext(hpTrail, player.hpRatio(), dt, Balance.player.trailPerSec);
        shieldTrail = HudLayout.trailNext(shieldTrail, player.shieldRatio(), dt,
                Balance.player.trailPerSec);
        if (shake > 0f) {
            shake -= dt * 3.2f;
            if (shake < 0f) shake = 0f;
        }
        if (hurtFlash > 0f) {
            hurtFlash -= dt * 3.6f;
            if (hurtFlash < 0f) hurtFlash = 0f;
        }
        if (sweepFlash > 0f) {
            sweepFlash -= dt / Balance.overload.sweepSec;
            if (sweepFlash < 0f) sweepFlash = 0f;
        }
        if (restartFade > 0f) {
            restartFade -= dt / RevealScript.RESTART_FADE_SEC;
            if (restartFade < 0f) restartFade = 0f;
        }
        // 振幅在衰减里算：定格期间震屏照样往回收
        fxClock += dt;
        float a = shake * shake * 9f;
        shakeX = ((fxClock * 977f) % 2f < 1f ? -a : a);
        shakeY = ((fxClock * 613f) % 2f < 1f ? -a : a);
    }

    private void movePlayer(float dt) {
        // 成长树与状态层**叠乘**（规格 §五）：局内 buff 不该把局外机动抹平，反之也一样
        float speed = Balance.player.moveSpeed * status.speedMul()
                * growth.speedMul() * shopRun.speedMul();
        if (keyVx != 0f || keyVy != 0f) {
            float k = Balance.player.keyMoveSpeed / Balance.player.moveSpeed;
            player.x += keyVx * speed * k * dt;
            player.y += keyVy * speed * k * dt;
        }
        stepDrag(dt, speed);
        player.clampToBattle(Screen.LOGIC_W, metrics.battleTop(), metrics.battleHeight());
    }

    /** 消费手指欠账：限速与额度都在 {@link DragDebt} 里，这里只把放行量加到机体上。 */
    private void stepDrag(float dt, float speed) {
        drag.consume(dt, speed, Balance.player.dragCatchupSec);
        player.x += drag.appliedX;
        player.y += drag.appliedY;
    }

    /**
     * 丢掉走位输入：按下态、按键方向，以及**手指位移的欠账**。
     *
     * <p>欠账必须一起丢：世界时钟冻住之后它还挂在账上，解暂停的第一帧战机就会凭空窜一段。
     */
    private void dropMoveInput() {
        movePointerId = -1;
        keyVx = 0f;
        keyVy = 0f;
        drag.clear();
    }

    /** 波次队列 → 实体池。池满时把这一次退回队列，不丢怪。 */
    private void spawnQueued(float dt) {
        int kind = wave.pollSpawn(dt, enemies.aliveCount());
        if (kind == WaveFlow.NONE) return;
        Enemies.Enemy e = enemies.spawn();
        if (e == null) {
            wave.returnSpawn();
            return;
        }
        int top = metrics.battleTop();
        if (kind == WaveFlow.BOSS) {
            initBoss(e, top);
            return;
        }
        Balance.Enemy spec = Balance.enemies[kind];
        e.kind = kind;
        e.boss = false;
        e.hp = e.maxHp = spec.hp;
        e.score = spec.score;
        e.radius = spec.hitRadius();
        e.x = rng.range(14f, Screen.LOGIC_W - 14f);
        e.y = top - 10f - rng.next01() * 8f;       // 从顶边外飞进来，别凭空出现
        e.anchorX = e.x;
        e.vy = spec.speed;
        e.fireTimer = spec.fireGap * 0.6f + rng.range(0f, spec.fireGap * 0.8f);
    }

    private void initBoss(Enemies.Enemy e, int battleTop) {
        Balance.Boss spec = Balance.bosses[wave.currentBossIndex()];
        int hp = wave.currentBossHp();
        e.kind = spec.id;
        e.boss = true;
        e.phase2 = false;
        e.phase2AtRatio = spec.phase2AtRatio;
        e.hp = e.maxHp = hp;
        e.score = spec.score;
        e.radius = spec.hitRadius();
        e.x = e.anchorX = Screen.LOGIC_W / 2f;
        e.y = battleTop - 20f;
        e.locked = false;
        e.fireTimer = spec.fireGap;
        if (haptics != null) haptics.heavyClick();
    }

    private void stepEnemies(float dt) {
        int top = metrics.battleTop();
        for (int i = enemies.activeCount() - 1; i >= 0; i--) {
            Enemies.Enemy e = enemies.activeAt(i);
            if (e.boss) {
                Balance.Boss spec = Balance.bosses[e.kind];
                int flags = BossBehavior.advance(e, spec, dt, player.x, player.y,
                        Screen.LOGIC_W, top);
                if ((flags & BossBehavior.FLAG_PHASE2) != 0) onBossPhase2(e, spec);
                if ((flags & BossBehavior.FLAG_FIRED) != 0) fireBossVolley(e, spec);
            } else {
                Balance.Enemy spec = Balance.enemies[e.kind];
                int flags = EnemyBehavior.advance(e, spec, dt, player.x, player.y,
                        Screen.LOGIC_W, top);
                if ((flags & EnemyBehavior.FLAG_FIRED) != 0) fireEnemyShot(e, spec);
                if ((flags & EnemyBehavior.FLAG_EXPLODE) != 0) detonate(e, spec);
            }
        }
    }

    private void fireEnemyShot(Enemies.Enemy e, Balance.Enemy spec) {
        BulletPool.Bullet b = hostile.spawnEvictingOldest(false);
        if (b == null) return;                     // 弹幕池满：少一发新弹，不踢已在途的弹
        float dx = player.x - e.x, dy = player.y - e.y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        float sp = spec.bulletSpeed;
        if (len < 0.001f) {
            b.vx = 0f; b.vy = sp;
        } else {
            b.vx = dx / len * sp;
            b.vy = dy / len * sp;
        }
        b.x = e.x; b.y = e.y;
        b.px = e.px; b.py = e.py;
        b.damage = DamageRules.bulletDamage(false);
        b.size = Balance.bullet.hostileSize;
        b.color = spec.color;
        b.hostile = true;
        // 只给 maxLife：子弹的 life 是**计时器**（0 起、往上加，见 BulletPool.stepAndCompact），
        // 和粒子的 life（满值起、往下减）是两个约定。这里写成 life = maxLife 的话
        // 敌弹在出膛的下一帧就被判过期，屏幕上永远看不到一发。
        b.maxLife = Balance.bullet.hostileLife;
        b.crit = false;
    }

    private void fireBossVolley(Enemies.Enemy e, Balance.Boss spec) {
        int n = BossBehavior.shots(e, spec, player.x, player.y, shotVX, shotVY);
        for (int i = 0; i < n; i++) {
            BulletPool.Bullet b = hostile.spawnEvictingOldest(false);
            if (b == null) return;
            float sp = spec.bulletSpeed;
            b.x = e.x; b.y = e.y + e.radius * 0.4f;
            b.px = b.x; b.py = b.y;
            b.vx = shotVX[i] * sp;
            b.vy = shotVY[i] * sp;
            b.damage = DamageRules.bulletDamage(true);
            b.size = Balance.bullet.bossSize;
            b.color = spec.color;
            b.hostile = true;
            b.maxLife = Balance.bullet.bossLife;
            b.crit = false;
        }
        if (haptics != null) haptics.tick();
    }

    private void onBossPhase2(Enemies.Enemy e, Balance.Boss spec) {
        // 进二阶段是一次"它变了"的信息，给足反馈：短定格 + 震屏 + 亮一下
        hitStop.arm(HitStop.secondsFor(0.9f));
        shake = 1f;
        sweepFlash = 0.4f;
        spawnBurst(e.x, e.y, 26, spec.color);
        if (haptics != null) haptics.heavyClick();
    }

    /** 自爆怪贴脸引爆：炸玩家，也把自己算死（同归于尽才是这怪的定位）。 */
    private void detonate(Enemies.Enemy e, Balance.Enemy spec) {
        spawnBurst(e.x, e.y, 22, spec.color);
        shake = Math.max(shake, 0.7f);
        hurtPlayer(DamageRules.contactDamageAt(spec, player.maxHp, true), true);
        e.hp = 0;
        killEnemy(e, false);
    }

    /**
     * 射速乘数：状态层与局外成长树**两者叠乘**（规格 §五）。canFire 与 consumeShot 必须读到同一个数。
     *
     * <p>局内商店卡**不在这里**：扳机卡 2026-09-26 起卖的是发数不是射速（见
     * {@link ShopRun#pelletBonus}），理由是固定步长把周期量化成 {@code ceil(cd/STEP)·STEP}，
     * 相邻几个乘子档会压出同一个实测周期——那条路线上玩家的钱会买到"什么都没变"。
     */
    private float rateMulNow() {
        return status.rateMul() * growth.rateMul();
    }

    private void playerFire() {
        float rateMul = rateMulNow();
        if (!player.canFire(rateMul)) return;
        Balance.Weapon w = player.weapon();
        // 周期取**有效**冷却（fireGap ÷ 乘子），不是基础 fireGap：乘子越大 C 越小、列数越多，
        // 弹链因此变宽而不是同列两发糊成一条实线。cooldownFor 是"有效周期"的唯一定义点，
        // 这里复用它而不是自己除一次，免得钳 rateMul≤0 的那段逻辑出现第二个副本。
        float period = player.cooldownFor(rateMul);
        // 扳机卡的等级在这里变成**发数**：pelletBonus 是"等级 → 发数"的唯一读数点，激光那把
        // 由它自己返回 0（不在这里再加一个 weaponId 分支，否则例外就有两个真源）。
        int pellets = Math.max(1, w.pellets) + shopRun.pelletBonus(w.id);
        // 三触发器（买卡 ⇒ pellets 变 / 换枪 ⇒ w 变 / 射速状态变 ⇒ period 变）合成这一条比较：
        // 三个数完全决定几何，所以不变就一个除法都不做。
        if (!chain.matches(w, pellets, period)) chain.layout(w, pellets, period);
        // 攻击这一路两笔分开算：局外成长树是**乘子**（满级 1.15），局内火力卡是**每发固定加点**
        // （每级 +1）。不写成两个乘子相乘，是因为按发取整的伤害吃不动百分比——见 WeaponFire.fillTemplate。
        // 暴击的百分点两边同单位，所以是相加而不是相乘。
        WeaponFire.fillTemplate(shotTpl, w, 1f, growth.attackMul(), shopRun.damageBonus(),
                rng.next01(), growth.critBonusPercent() + shopRun.critBonus());
        chain.beginGeneration(shotTpl);
        player.consumeShot(rateMul);
        muzzle(player.x, player.y - 10f, w.color);
    }

    /**
     * 排放弹链。**每帧都走**（哪怕这一帧根本没满足开火条件——上一代的剩余时隙还在途上），
     * 且必须排在 {@link #playerFire} **之前**：排放窗口可以因取整余量比周期长出不到一格，
     * 那最后一步正好与"下一代就绪"撞在一起，先推进链才不会被 {@code beginGeneration} 抹掉末尾那一档。
     * 出膛点每帧重读机头，所以边移动边开火时链是弯的。
     *
     * <p>{@code made} 是 **shots 与 missiles 两条分支的合计**（{@link BulletChain#step} 的口径），
     * 所以这一处记账覆盖全部实发。只记 shots 侧的话 {@code shotsHit} 会大过 {@code shotsFired}，
     * 而 {@code RunStats.accuracy()} 只防除零不防反向、{@code Rating.clamp01} 与 {@code RunSummary}
     * 再钳两次 ⇒ 结算页安静地显示"命中率 100%"，全链路没有任何东西会红。
     */
    private void stepChain(float dt) {
        int made = chain.step(dt, player.x, noseY(), shots, missiles);
        // 记账挪到**实际出膛**处：计划发数与真的打进池里的发数从此不可能对不上。
        if (made > 0) stats.addShots(made);
        stepEvictions();
    }

    /**
     * 满池淘汰：被踢的那一枚**只播自毁动画**——不展开战斗部、不结算伤害、不计命中（R22 裁的
     * "静默"静的是**结算**这一半，不是画面）。于是"永不拒发"与"没有弹会无声消失"同时成立：
     * 按下去那一下永远会出弹，被踢的那枚在屏幕上留下一团粒子，读得出"它是没了、不是打完了"。
     * <p>⚠ 与 R13 **刻意分路**：空视场自爆走 {@link #detonateMissile} 照常展开并结算（那半是弹自己
     * 打完了这一发），这里不借那条路——合回一个调用就等于把 R22 的裁定吃掉。
     * <p>⚠ 必须紧接 {@code chain.step}：{@link Missiles#evicted} 是**池外的唯一副本**，同一次排放里
     * 再淘汰一枚就把它覆盖；那条路走不到（{@code capacity} 1.7× 余量 + {@code MissilesTest} 钉着）。
     */
    private void stepEvictions() {
        long total = missiles.evictTotal();
        if (total == evictSeen) return;
        evictSeen = total;
        spawnBurst(missiles.evicted.x, missiles.evicted.y, 12, missiles.evicted.color);
    }

    /**
     * 导弹这一帧要走的全部三步：两条流各积分一次，然后格斗流补一次发射。
     *
     * <p>两条流**共用这一个入口**是因为它们的差别全在 {@link MissileBehavior} 实例的身份里
     * （{@code stream} → {@code beginFrame} 解析有效值），不在调度顺序里。⚠ 先后次序本身**没有**
     * 跨流影响——两份占用位图各是各的，这正是「跨池不排他是工程设计」（L37356）的结构含义。
     * 真正写死的是每条流**内部**的 {@code beginFrame → advance → 发射}：发射早于 advance 就看不到
     * 本帧刚落下的锁，晚于 beginFrame 但早于 advance 也一样（判序见 {@link MissileBehavior#tryLaunch}）。
     *
     * <p>⚠ 池子**各自 clear**（见 {@code resetRun}）、淘汰动画**各自一份基准**（{@link #stepEvictions}
     * 与 {@link #stepDogEvictions}）：{@link Missiles#evicted} 是每个池自己的那一份唯一副本。
     */
    private void stepMissiles(float dt) {
        stepPool(missiles, missileBehavior, dt);
        stepPool(dogfightMissiles, dogfightBehavior, dt);
        // 发射排在**本流全部 advance 之后**：tryLaunch 读的占用戳要包含"这一帧刚锁上的那些"，
        // 早一步就会朝同一只敌机在同一帧里出第二枚（判序写在 MissileBehavior#tryLaunch 的方法头）。
        if (shopRun.dogfightOn() && player.weapon().guided) fireDogfight();
    }

    /**
     * 一流弹的相位积分 + 三路判据。**两条流各调一次**，方法头那条判序对两流同形（差异全部
     * 由 {@link MissileBehavior} 实例的 stream 在 {@code beginFrame} 里解析成有效值，这里不读流别）。
     *
     * <p>索敌与引信都**不碰 {@link SpatialGrid}**：网格的覆盖半径只有 {@code ring×CELL = 48px}，
     * 撑不到导引头的射程，用它只会得到"视野内的一小撮"⇒ 静默漏锁。这里扫的是活跃敌表
     * （≤ {@code Balance.wave.maxAlive} = 26），既正确又比建表+查询便宜。网格只留给
     * {@link #stepWarheads} 那条**近距**接触采样。
     */
    private void stepPool(Missiles pool, MissileBehavior behavior, float dt) {
        Balance.Missile spec = Balance.missile;
        behavior.beginFrame(pool, enemies, spec, shopRun);
        for (int i = pool.activeCount() - 1; i >= 0; i--) {
            Missiles.Missile m = pool.activeAt(i);
            int flags = behavior.advance(m, spec, dt, enemies,
                    Screen.LOGIC_W, metrics.logicH, MARGIN);
            // 三路同归一条引爆（方案里编号 R13）：出处 = 记录 L14773 answers-OPT，
            // UTC 2026-09-25T09:29:00.463Z = 本地 2026-09-25 17:29:00，选项标签「自爆也展开，与撞敌同路」
            // ⇒ 选这一档是他的决定，但这句标签是我的措辞。三路合流后差别只在**记账**——撞敌/引信算这一发
            // 命中，空视场自爆不算；这条记账口径是我定的，他没谈过命中怎么算。
            if ((flags & MissileBehavior.FLAG_DETONATE) != 0) {
                detonateMissile(m, true);
            } else if ((flags & MissileBehavior.FLAG_SELF_DESTRUCT) != 0) {
                detonateMissile(m, false);
            }
            if (MissileBehavior.isRetired(flags)) pool.killAt(i);
        }
    }

    /**
     * 格斗弹的发射端：**战机火控**每帧问一次"机头前那条扇形里还有没有没人锁的敌机"，有就出一枚。
     * 它不在弹链里（{@code BulletChain} 不认识敌人，也不该认识），所以出膛、记账、淘汰动画
     * 这三件事在这里各自补一遍——漏掉记账就是命中率虚高，见下面那三条 {@code stats} 的注释。
     *
     * <p>每帧**至多一枚**（不是"扇形里有几只就出几枚"）：判据本身一敌一锁，出第二枚要等第一枚
     * 的锁在本帧的 advance 里落进占用戳；同一帧连出会把刚选出的那只重复选给自己。
     *
     * <p>⚠ 出膛后**必须**同帧 {@link MissileBehavior#armDogfight}：那枚弹带着 RADAR_PENDING 进
     * {@code advance} 会当场抛（"生来带锁"是设定，不是概率，见那里的注释）。
     */
    private void fireDogfight() {
        Balance.Missile spec = Balance.missile;
        int slot = dogfightBehavior.tryLaunch(player.x, noseY(), enemies, spec);
        if (slot < 0) return;                       // 扇形里没有可锁的 ⇒ 不发（不是"随便发一枚"）
        Balance.Weapon w = player.weapon();
        // 伤害档沿用**当前那把制导枪**：他的原话只说"自带高过载和舵效"，说的是导引参数，
        // 没有第二套伤害档。这里重填一份模板而不是复用 shotTpl：那一份的 crit 是逐发 roll 的，
        // 借用会让格斗弹和上一轮主炮共享同一个暴击判定。
        WeaponFire.fillTemplate(dogTpl, w, 1f, growth.attackMul(), shopRun.damageBonus(),
                rng.next01(), growth.critBonusPercent() + shopRun.critBonus());
        Missiles.Missile m = WeaponFire.fireMissile(dogfightMissiles, dogTpl, player.x, noseY(), 0f);
        dogfightBehavior.armDogfight(m, enemies, slot);
        // 记账：**出膛一笔**，与 chain.step 那条同一个口径（少这一笔 addHit 就大过 addShots，
        // 结算页会安静显示命中率 100%，全链路不红）。
        stats.addShots(1);
        stepDogEvictions();
    }

    /** 格斗池的满池淘汰：与 {@link #stepEvictions} 同一条 R22 口径（只播动画、不结算）。 */
    private void stepDogEvictions() {
        long total = dogfightMissiles.evictTotal();
        if (total == dogEvictSeen) return;
        dogEvictSeen = total;
        spawnBurst(dogfightMissiles.evicted.x, dogfightMissiles.evicted.y,
                12, dogfightMissiles.evicted.color);
    }

    /**
     * 机头（= 出膛点）的 y。**弹链排放与格斗弹发射共用这一个读数**：画面上"弹从哪儿出来"必须
     * 是同一个点，抄两份的话其中一份改了另一份不会跟着改，玩家看到的是同一把枪的两个枪口。
     */
    private float noseY() {
        return player.y - 8f;
    }

    /**
     * 展开战斗部：生出一根垂直弹轴的连续杆亮线，附带一次视觉反馈。弹体本身**不造成伤害**——
     * 伤害只从杆与碎片走。亮线形状与伤害档位出自记录 L14750 feedback(status=rejected)（真人自敲），
     * UTC 2026-09-25T09:19:51.727Z = 本地 2026-09-25 17:19:51，他的字「命中接近后展开连续杆战斗部
     * （在这里是垂直于导弹的一条亮线）」「接触到亮线的扣完整伤害，接触到破碎碎片的扣四分之一
     * （向下取整，最小为1）」⇒ 杆与碎片各一档是他的措辞；"弹体本身不造成伤害" 是我把它补成
     * 了互斥口径，属我的推导。**空视场**自爆也走这一条展开（方案里编号 R13，同 L14773：那句标签是
     * 我的措辞，选它的是他的）；⚠ 满池淘汰**不在这条路上**，它只播动画、不结算（R22，见 {@link #stepEvictions}）。
     *
     * <p>池满时 {@code spawnRod} 返回 null ⇒ 少一根杆，玩家看不出来，但
     * {@link Warheads#refuseTotal()} 会记账（"看不出来的截断"和"静默的截断"不是一回事）。
     */
    private void detonateMissile(Missiles.Missile m, boolean countsAsHit) {
        if (countsAsHit) stats.addHit();
        WarheadRules.spawnRod(warheads, m, Balance.missile);
        spawnBurst(m.x, m.y, 12, m.color);
        shake = Math.max(shake, 0.45f);
        hitStop.arm(HitStop.secondsFor(0.2f));
        if (haptics != null) haptics.tick();
    }

    private void stepBullets(float dt) {
        shots.stepAndCompact(dt, Screen.LOGIC_W, metrics.logicH, MARGIN);
        hostile.stepAndCompact(dt, Screen.LOGIC_W, metrics.logicH, MARGIN);
    }

    private void collide(float dt) {
        rebuildGrid();
        stepWarheads(dt);
        playerShotsAgainstEnemies();
        hostileShotsAgainstPlayer();
        enemiesAgainstPlayer();
    }

    /**
     * 战斗部逐格积分 ＋ **每步扫掠带接触采样** ＋ 每 tick 计费。排在 {@code rebuildGrid} 之后是硬前提：
     * {@link WarheadRules#sampleContact} 靠网格取候选。
     *
     * <p>⚠ 采样与计费**刻意分开**（{@code ticksDue} 与 {@code sampleContact} 是两个节拍）。
     * 结算节拍 0.08s，而小怪沿杆方向穿过亮线只要 9px ÷ 180px/s = 0.05s ⇒ 只在 tick 那一刻测，
     * 随机相位下有 37.5% 的概率"整段穿过去而两个瞬间都不在接触区"——画面上就是"该中没中"。
     * 每步采的形状是**带**而不是瞬间（{@link WarheadRules#sweptBandHitsCircle}）：位移在设计上是
     * 连续的，判据不能比画面更稀。⚠ 带是**梯形**（步初半长 → 步末半长），所以 {@code dt} 必须传进
     * {@link WarheadRules#sampleContact}——他 2026-09-30 逐字判过：「杆没长满时按满长扫与视觉效果
     * 不符，属于代码与设计不符」。
     *
     * <p>⚠ 本方法在 JVM 侧**结构性零覆盖**（{@code Game} 是渲染类，静态块 new Paint ⇒ 直调必
     * {@code ExceptionInInitializerError}）。判据全在 {@link WarheadRules}，这里只留"按纯函数
     * 的结果执行动作"；留在这一侧的三条必需动作只能靠审查与真机看分数：见 {@link #settleContact}。
     */
    private void stepWarheads(float dt) {
        Balance.Missile spec = Balance.missile;
        for (int i = warheads.activeCount() - 1; i >= 0; i--) {
            Warheads.Warhead w = warheads.activeAt(i);
            WarheadRules.advance(w, dt, spec);
            if (WarheadRules.expired(w)) {
                // 先摘表再破碎：反过来写 killAt 的末位交换会把新生的一片搬到当前下标，白少走一帧。
                warheads.killAt(i);
                if (w.kind == Warheads.Warhead.KIND_ROD) WarheadRules.spawnShards(warheads, w, spec);
                continue;
            }
            WarheadRules.sampleContact(w, enemies, grid, candidates, spec, dt);
            for (int t = WarheadRules.ticksDue(w, dt, spec.tickSec); t > 0; t--) settleContact(w);
        }
    }

    /**
     * 一次 tick 的结算：把这格累计到的接触位图逐位扣一次伤害，然后清零（下一次 tick 只结
     * 这之间新碰到的）。三条动作成对出现，漏哪半句都错：
     *
     * <ol>
     *   <li>逐候选 {@code hp <= 0 → continue}。网格是**帧首快照**，而 {@link #killEnemy} 不摘表
     *       （摘表延后到 {@link #reapDead}）⇒ 同一步里"血已归零但还在候选里"的敌人必然出现；
     *       {@code SpatialGrid.query} 明写它只负责不漏，过滤是调用方的义务。</li>
     *   <li>打死者**必须**走 {@code killEnemy}。不接 ⇒ 由 {@code cullDeadAndOffscreen} 静默摘表
     *       ⇒ 无分数、无掉落、无连击。</li>
     *   <li>{@code killEnemy} **不幂等**（kills++ / score / rollDrops）⇒ 缺了 ① 就是双份记账：
     *       一次引爆六片碎片从同一点喷出，最坏多记 5 次击杀与 5 份掉落。</li>
     * </ol>
     *
     * <p>伤害读 {@link Warheads.Warhead#damage} 这个**已经取整的 int**：出膛那刻走过
     * {@code WeaponFire.damageOf(float)}，碎片值由 {@code WarheadRules.shardDamage} 从杆值派生，
     * 这里不再取第二次整——那是第三个真源。
     */
    private void settleContact(Warheads.Warhead w) {
        int mask = WarheadRules.takeContact(w);
        while (mask != 0) {
            int slot = Integer.numberOfTrailingZeros(mask);
            mask &= ~(1 << slot);
            Enemies.Enemy e = enemies.objAt(slot);
            if (e == null || e.hp <= 0) continue;
            e.hp -= w.damage;
            e.hitFlash = 0.09f;
            if (e.hp <= 0) killEnemy(e, w.crit);
        }
    }

    private void rebuildGrid() {
        if (grid == null) return;
        grid.clear();
        for (int i = 0; i < enemies.activeCount(); i++) {
            Enemies.Enemy e = enemies.activeAt(i);
            if (e.hp <= 0) continue;
            grid.insert(enemies.slotOfActive(i), e.x, e.y);
        }
    }

    private void playerShotsAgainstEnemies() {
        if (grid == null) return;
        for (int i = shots.activeCount() - 1; i >= 0; i--) {
            BulletPool.Bullet b = shots.activeAt(i);
            int n = grid.query(b.x, b.y, candidates);
            Enemies.Enemy hit = null;
            float best = Float.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                Enemies.Enemy e = enemies.objAt(candidates[j]);
                if (e == null || e.hp <= 0) continue;         // 判活用 hp<=0，不用 indexOf
                float dx = e.x - b.x, dy = e.y - b.y;
                float rr = e.radius + b.size;
                if (dx * dx + dy * dy > rr * rr) continue;
                float d2 = dx * dx + dy * dy;
                if (d2 < best) {
                    best = d2;
                    hit = e;
                }
            }
            if (hit == null) continue;
            stats.addHit();
            int dmg = WeaponFire.damageOf(b);
            hit.hp -= dmg;
            hit.hitFlash = 0.09f;
            spawnSpark(b.x, b.y, b.color, b.crit);
            if (hit.hp <= 0) killEnemy(hit, b.crit);
            shots.killAt(i);
        }
    }

    private void hostileShotsAgainstPlayer() {
        if (!player.alive || player.invulnerable()) return;
        float rr = player.radius + Balance.bullet.hostileHitPad;
        for (int i = hostile.activeCount() - 1; i >= 0; i--) {
            BulletPool.Bullet b = hostile.activeAt(i);
            float dx = b.x - player.x, dy = b.y - player.y;
            if (dx * dx + dy * dy > rr * rr) continue;
            hostile.killAt(i);
            hurtPlayer((int) b.damage, false);
            if (!player.alive || player.invulnerable()) return;   // 一次受伤只算一发：无敌帧生效了
        }
    }

    private void enemiesAgainstPlayer() {
        if (!player.alive || player.invulnerable()) return;
        int top = metrics.battleTop();
        for (int i = enemies.activeCount() - 1; i >= 0; i--) {
            Enemies.Enemy e = enemies.activeAt(i);
            if (e.hp <= 0 || e.y < top) continue;              // 还没进场的怪不算撞
            float dx = e.x - player.x, dy = e.y - player.y;
            float rr = e.radius + player.radius;
            if (dx * dx + dy * dy > rr * rr) continue;
            if (e.boss) {
                hurtPlayer(DamageRules.bossContactDamage(player.maxHp), true);
            } else {
                Balance.Enemy spec = Balance.enemies[e.kind];
                boolean pointBlank = spec.id == Balance.Enemy.BURSTER;
                hurtPlayer(DamageRules.contactDamageAt(spec, player.maxHp, pointBlank), true);
                e.hp = 0;
                killEnemy(e, false);
            }
            if (!player.alive || player.invulnerable()) return;
        }
    }

    private void reapDead() {
        enemies.cullDeadAndOffscreen(metrics.battleBottom(), MARGIN);
        for (int i = enemies.activeCount() - 1; i >= 0; i--) {
            Enemies.Enemy e = enemies.activeAt(i);
            if (e.boss && e.y > metrics.battleBottom() + MARGIN) enemies.killAt(i);
        }
    }

    // ---- 结算 -------------------------------------------------------------------------------

    private void killEnemy(Enemies.Enemy e, boolean crit) {
        stats.kills++;
        if (crit) stats.critKills++;
        long gained = Math.round(e.score * wave.scoreWaveFactor() * combo.multiplier(world));
        if (crit) gained = Math.round(gained * Balance.score.critKillMul);
        score += gained;
        combo.hit(world);
        // 峰值逐帧记账：暂停面板要在死亡之前就读到它，只在死亡瞬间快照的话面板上永远是 0
        if (combo.peakHits() > stats.peakCombo) stats.peakCombo = combo.peakHits();
        rollDrops(e);
        spawnBurst(e.x, e.y, e.boss ? 40 : 14, e.boss
                ? Balance.bosses[e.kind].color : Balance.enemies[e.kind].color);
        if (e.boss) {
            shake = 1f;
            hitStop.arm(HitStop.secondsFor(1f));
            sweepFlash = 0.5f;
            if (haptics != null) haptics.heavyClick();
        } else {
            shake = Math.max(shake, 0.28f);
            hitStop.arm(HitStop.secondsFor(0.35f));
        }
    }

    private void rollDrops(Enemies.Enemy e) {
        float mul = status.dropMul();
        if (e.boss) {
            for (int i = 0; i < Drops.bossChips(); i++) {
                if (drops.spawn(Drops.CHIP, 1, e.x + (i - 1) * 8f, e.y) != null) {
                    wave.notifyChipDropped();
                }
            }
            drops.spawn(Drops.SHIELD, Balance.shield.fromRepairItem, e.x, e.y + 6f);
            return;
        }
        if (Drops.chipRoll(rng.next01(), mul)
                && drops.spawn(Drops.CHIP, 1, e.x, e.y) != null) {
            wave.notifyChipDropped();
        }
        if (Drops.coinRoll(rng.next01(), mul)) {
            drops.spawn(Drops.COIN, Balance.drop.coinPerKillBase + e.kind, e.x, e.y + 4f);
        }
        if (Drops.powerupRoll(rng.next01(), mul)) {
            drops.spawn(Drops.POWERUP, 0, e.x, e.y + 2f);
        } else if (Drops.hpRoll(rng.next01(), mul)) {
            drops.spawn(Drops.HP, Balance.drop.hpItemHeal, e.x, e.y + 2f);
        } else if (rng.chance(Balance.drop.bombChance * mul)) {
            drops.spawn(Drops.BOMB, 1, e.x, e.y + 2f);
        }
    }

    private void stepDrops(float dt) {
        int bottom = metrics.battleBottom();
        drops.stepAndCollect(dt, Screen.LOGIC_W, bottom, MARGIN,
                player.x, player.y, Balance.drop.pickupRadius,
                Balance.drop.pullRadius * shopRun.magnetMul(), collected);
        applyCollected();
        int n = wave.takeGuaranteeChips();
        for (int i = 0; i < n; i++) {
            drops.spawn(Drops.CHIP, 1, player.x, metrics.battleTop() + 40f + i * 8f);
        }
    }

    private void applyCollected() {
        if (collected[Drops.CHIP] > 0) {
            chips += collected[Drops.CHIP];
            collected[Drops.CHIP] = 0;
        }
        if (collected[Drops.COIN] > 0) {
            // 倍率走**带余量**的累加：一次捡 1 枚 × 1.25 直接取整等于什么都没加，
            // 那正是规格点名的"卡面写了收益、结算里没有"。余数留着下一波一起结。
            coinCarry += collected[Drops.COIN] * shopRun.coinMul();
            int whole = (int) coinCarry;
            coins += whole;
            coinCarry -= whole;
            collected[Drops.COIN] = 0;
        }
        if (collected[Drops.HP] > 0) {
            player.healHp(collected[Drops.HP], Balance.shield.fromHpOverfullSmall);
            collected[Drops.HP] = 0;
        }
        if (collected[Drops.SHIELD] > 0) {
            player.addShield(collected[Drops.SHIELD]);
            collected[Drops.SHIELD] = 0;
        }
        if (collected[Drops.BOMB] > 0) {
            // [规格] 炸弹入栏，由玩家决定何时放——不是捡到就炸
            bombs += collected[Drops.BOMB];
            collected[Drops.BOMB] = 0;
        }
        if (collected[Drops.POWERUP] > 0) {
            for (int i = 0; i < collected[Drops.POWERUP]; i++) {
                status.activate(rng.nextInt(StatusLayers.PICKUP_COUNT));
            }
            stats.powerupsTaken += collected[Drops.POWERUP];
            collected[Drops.POWERUP] = 0;
            if (haptics != null) haptics.heavyClick();
        }
    }

    /** 唯一的受伤出口：状态层乘算在这里进，规格 §五 点名的"图标亮着但不生效"就断在这。 */
    private void hurtPlayer(int raw, boolean heavy) {
        int flags = player.hurt(raw, status.takenMul());
        if (flags == PlayerState.FLAG_NONE) return;
        hurtFlash = 1f;
        shake = Math.max(shake, heavy ? 0.9f : 0.6f);
        hitStop.arm(HitStop.secondsFor(heavy ? 0.8f : 0.4f));
        spawnBurst(player.x, player.y, heavy ? 20 : 10,
                (flags & PlayerState.FLAG_SHIELD_BROKEN) != 0 ? Ink.SHIELD : Ink.WARN);
        if (haptics != null) haptics.heavyClick();
        if ((flags & PlayerState.FLAG_DIED) != 0) onPlayerDied();
    }

    private void onPlayerDied() {
        runOver = true;
        shake = 1f;
        sweepFlash = 0.6f;
        spawnBurst(player.x, player.y, 48, Ink.PROBE);
        if (haptics != null) haptics.heavyClick();
        settleRun(false);
    }

    /**
     * 打完最后一波（#54 的那一支）。**开的是同一条结算链**，只有演出不同：没有机头爆散、
     * 没有震屏与闪白——那些是"你没了"的语言，挂在"打完了"上面会把终态读成事故。
     *
     * <p>芯片结清、落盘、快照三件与死亡**逐字同一段**（{@link #settleRun}），因为它们是"这一局结束"
     * 的后果，不是"死亡"的后果：通关不该比阵亡少拿一份工资，那样打到底反而是惩罚。
     */
    private void onVictory() {
        runOver = true;
        if (haptics != null) haptics.heavyClick();
        settleRun(true);
    }

    /**
     * 一局结束的**唯一**结算出口（死亡与通关共用）。放进共用段不是为了少写六行，是因为这六行里
     * 有一条只在"两条出口都存在"时才会被想起的账：暂停重开什么都不给（留了就等于奖励中途重来），
     * 所以结清必须挂在终态上而不是挂在死亡上。
     */
    private void settleRun(boolean victory) {
        modals.push(ModalStack.RESULT);
        dropMoveInput();
        growth.addWallet(chips);
        chips = 0;
        growth.save(store);
        // 结算快照：峰值与时长在打的时候就已经逐帧记进 stats 了，这里不需要再补一次现场读数
        sheet.fill(stats, score, wave.wave(), wave.wavesCleared(), victory, records, store);
        resultAtUi = time.ui();
        result.clearPress();
    }

    /**
     * 真机取证用（{@code EV_DEBUG_KILL}，只在 debug 包被 {@code MainActivity} 放行）：**复用真死亡那一条链**，
     * 不开第二条结算路径——否则结算页在这里是被"另一个东西"画出来的，取证取到的就不是玩家会看到的那一份。
     *
     * <p>闸门与 {@link #triggerOverload} 同一条：只问栈顶。面板开着时按 F9 不放行，免得造出
     * "结算页压在暂停页下面"这种真实对局里不存在的栈形。
     */
    private void debugEndRun() {
        if (modals.peek() != ModalStack.NONE) return;
        // 数值取大到能盖过护盾与 takenMul，但**不绕过任何一道闸**：无敌期按下去没反应是真链的行为，
        // 绕过它就不是取证了（那是造一条玩家看不到的死法）。
        hurtPlayer(9999, true);
    }

    /**
     * 真机取证用（{@code EV_DEBUG_VICTORY}，F8）：只做两件事——把波号推到终波、把场上敌人清掉。
     * 之后的 {@code SPAWN → CLEAR → closeWave → VICTORY}，以及 {@link #step} 末尾那一行发现通关、
     * {@link #onVictory} 结的账，**全是真的那一条**（道理同 {@link #debugEndRun}：绕过发现路径
     * 取到的证不成立）。闸门也与它同一条：只问栈顶。
     *
     * <p>⚠ 这条键是**我加的**，不是他提的（他给的是"通关结算页不存在需要修"这一句）。加它的理由：
     * 验收要走到 {@code Balance.wave.maxWave} 那一波，真打是十几二十分钟一个手势都不能停。
     * 不要的话删三处即可（本方法 ＋ {@code InputRouter.EV_DEBUG_VICTORY} ＋ {@code MainActivity}
     * 的 F8 分支），产品逻辑一处不动。
     *
     * <p>清场走的是池子的整清、不是逐个击杀：这一局的成绩单因此是"打到终波但路上没杀够"，
     * 版面照常出——要验的是**那一支有没有出口和标记**，不是它的数字好不好看。
     */
    private void debugForceVictory() {
        if (modals.peek() != ModalStack.NONE) return;
        wave.debugReachFinalWave();
        enemies.clear();
    }

    /** 超载扫描：[规格] 冷却制 30 秒，trigger() 拒绝时不放（不给"读条中途白嫖"）。 */
    private void triggerOverload() {
        // 只问模态：结算页与暂停面板都在栈上，runOver 那份双真值就此在输入侧收敛成一条
        if (modals.peek() != ModalStack.NONE || !overload.trigger()) return;
        stats.overloadUses++;
        sweepFlash = 1f;
        shake = Math.max(shake, 0.8f);
        hitStop.arm(HitStop.secondsFor(0.7f));
        int dmg = DamageRules.overloadDamage();
        for (int i = enemies.activeCount() - 1; i >= 0; i--) {
            Enemies.Enemy e = enemies.activeAt(i);
            if (e.hp <= 0) continue;
            e.hp -= dmg;
            e.hitFlash = 0.12f;
            if (e.hp <= 0) killEnemy(e, false);
        }
        hostile.clear();
        if (haptics != null) haptics.heavyClick();
    }

    /**
     * 引爆一枚炸弹：清光敌弹，再按**生命上限**的比例重伤全场（规格 §掉落物"清屏并重伤全场"）。
     *
     * <p>比例而不是固定伤害：杂兵与 Boss 的血量差着数量级，写死一个数只会让一边被秒、另一边
     * 完全没感觉。扣完仍然活着的怪继续被常规弹道磨——炸弹因此不是第二套伤害系统，而是把
     * "眼前这一坨"打成残局的手段。死亡结算走 {@link #killEnemy}，分数、掉落、连击一律照记。
     */
    private void detonateBomb() {
        if (bombs <= 0 || runOver) return;
        bombs--;
        hostile.clear();
        for (int i = enemies.activeCount() - 1; i >= 0; i--) {
            Enemies.Enemy e = enemies.activeAt(i);
            if (e.hp <= 0) continue;
            e.hp -= Math.max(1, Math.round(e.maxHp * Balance.player.bombHpRatio));
            e.hitFlash = 0.12f;
            if (e.hp <= 0) killEnemy(e, false);
        }
        sweepFlash = 1f;
        shake = 1f;
        hitStop.arm(HitStop.secondsFor(0.9f));
        if (haptics != null) haptics.heavyClick();
    }

    /** 整局重置（死亡后重开、以及 surfaceChanged 的一次性初始化）。 */
    public void resetRun() {
        enemies.clear();
        shots.clear();
        hostile.clear();
        // 链只停止排放，**布局缓存留着**：它由武器号/弹丸数/周期三个数决定，而下局的
        // shopRun.reset() 与第一次开火都会让 matches() 自己判出来。把缓存也抹掉反而会让
        // "布局是不是清了"变成一个要额外验证的状态。
        chain.clear();
        // 三张新池同样在这里归零。⚠ `clear()` 的形状是"只置游标与槽位表"，这一点是硬的：
        // 本方法有一条调用链跑在 **UI 线程**上（MainActivity:138 → GameSurfaceView:44 → 本方法），
        // 与渲染线程并发。所以撕裂读的最坏后果必须是"多丢几枚导弹"，不能是"读到半个相位状态机"。
        missiles.clear();
        dogfightMissiles.clear();
        warheads.clear();
        // 清池之后 {@link Missiles#evicted} 里躺的还是上一局那只弹，把比较基准推到当前计数，
        // 免得新一局的某次淘汰在上一局的旧坐标上撒一团自毁粒子（R22 之后它不展开杆，但坐标还是旧的）。
        // ⚠ 两条基准**各归各池**：evicted 是每个池自己的那一份副本，拿普通池的计数去比格斗池的
        // 计数会在新一局第一次淘汰时安静漏掉动画。
        evictSeen = missiles.evictTotal();
        dogEvictSeen = dogfightMissiles.evictTotal();
        drops.clear();
        for (int i = 0; i < collected.length; i++) collected[i] = 0;
        score = 0;
        chips = 0;
        coins = Balance.player.startCoins;
        coinCarry = 0f;
        bombs = Balance.player.startBombs + growth.extraBombs();
        world = 0f;
        runOver = false;
        // 新一局没有模态：结算页与暂停面板都不该跟着活下来
        modals.popUntil(ModalStack.NONE);
        time.setPaused(false);
        markModalClosed();
        dropMoveInput();
        stats.reset();
        trend.reset();
        // 局内成长随这一局一起清掉；快照要重新挂到新数组上（ShopRun 复用同一个数组，这句是防御
        // 未来有人改成重新分配——挂错数组的表现是"买了卡但乘子不动"，最难查的那类）。
        shopRun.reset();
        shopSnap.level = shopRun.levels();
        // 持有量清零之后，手里那把也可能已经不存在了：不钳回"局内一定有的那一把"，下一局开局就在
        // 打这一局没买过的导弹——那是"买了没效果"的反向版本（没买却有），同样没法解释也测不出来。
        if (!shopRun.weaponUnlocked(player.weaponId)) player.weaponId = Balance.Weapon.PULSE;
        lastFlowPhase = WaveFlow.PREP;
        shopPending = false;
        shake = 0f;
        hurtFlash = 0f;
        sweepFlash = 0f;
        combo.reset();
        overload.forceReady();
        status.clearPickups();
        status.setEnvironment(StatusLayers.ENV_NONE);
        player.respawn(Screen.LOGIC_W / 2f, metrics.battleTop() + metrics.battleHeight() * 0.72f);
        // respawn 把上限打回 Balance 原值，所以成长树的护盾必须**在它之后**重施（之前会被抹掉）
        player.raiseCaps(Balance.player.maxHp, Balance.player.maxShield + growth.shieldBonus());
        hpTrail = player.hpRatio();
        shieldTrail = player.shieldRatio();
        player.px = player.x;
        player.py = player.y;
        anchorX = player.x;
        anchorY = player.y;
        wave.reset();
    }

    // ---- 特效 -------------------------------------------------------------------------------

    /** 一次爆炸：全部走 {@link ParticlePool#spawn()}，池满就少几粒，帧内不 new 对象。 */
    private void spawnBurst(float x, float y, int n, int color) {
        for (int i = 0; i < n; i++) {
            Particle p = particles.spawn();
            if (p == null) return;
            float a = i * 0.35f + (i & 1);
            float sp = 40f + (i % 5) * 26f;
            p.x = x; p.y = y;
            p.px = x; p.py = y;                   // 与当前位置同步，第一帧才不会拉出长尾
            p.vx = (float) Math.cos(a) * sp;
            p.vy = (float) Math.sin(a) * sp;
            p.gravity = 26f;
            p.drag = 1.6f;
            p.size = (i % 3) + 1;
            p.maxLife = p.life = 0.35f + (i % 4) * 0.12f;
            p.color = (i % 4 == 0) ? 0xFFFFFFFF : color;
            p.kind = (i % 3 == 0) ? Particle.KIND_TRAIL : Particle.KIND_SQUARE;
        }
    }

    private void spawnSpark(float x, float y, int color, boolean crit) {
        int n = crit ? 5 : 2;
        for (int i = 0; i < n; i++) {
            Particle p = particles.spawn();
            if (p == null) return;
            float a = i * 1.7f;
            p.x = x; p.y = y;
            p.px = x; p.py = y;
            p.vx = (float) Math.cos(a) * 60f;
            p.vy = (float) Math.sin(a) * 60f - 20f;
            p.gravity = 0f;
            p.drag = 3f;
            p.size = 1;
            p.maxLife = p.life = crit ? 0.22f : 0.12f;
            p.color = crit ? 0xFFFFFFFF : color;
            p.kind = Particle.KIND_SQUARE;
        }
    }

    private void muzzle(float x, float y, int color) {
        Particle p = particles.spawn();
        if (p == null) return;
        p.x = x; p.y = y;
        p.px = x; p.py = y;
        p.vx = 0f; p.vy = -30f;
        p.gravity = 0f;
        p.drag = 4f;
        p.size = 2;
        p.maxLife = p.life = 0.08f;
        p.color = color;
        p.kind = Particle.KIND_SQUARE;
    }

    /** 拖尾按速度矢量拉楔形，不是矩形——矩形像"后面贴了张纸"。 */
    private void drawParticles(Canvas c) {
        for (int i = 0; i < particles.activeCount(); i++) {
            Particle p = particles.activeAt(i);
            float k = p.maxLife <= 0f ? 0f : p.life / p.maxLife;
            if (p.kind == Particle.KIND_TRAIL) {
                float vx = p.x - p.px, vy = p.y - p.py;
                float m = (float) Math.sqrt(vx * vx + vy * vy);
                if (m < 0.05f) continue;
                float nx = -vy / m, ny = vx / m;
                float half = p.size * 0.9f;
                WEDGE.rewind();
                WEDGE.moveTo(p.x + nx * half, p.y + ny * half);
                WEDGE.lineTo(p.x - nx * half, p.y - ny * half);
                WEDGE.lineTo(p.x - vx * 3f, p.y - vy * 3f);
                WEDGE.close();
                WEDGE_PAINT.setColor(p.color);
                WEDGE_PAINT.setAlpha(Math.round(180f * k));
                c.drawPath(WEDGE, WEDGE_PAINT);
            } else {
                fill.setColor(p.color);
                fill.setAlpha(Math.round(255f * k));
                int s = Math.max(1, Math.round(p.size));
                rf.set(p.x - s, p.y - s, p.x + s, p.y + s);
                c.drawRect(rf, fill);
                fill.setAlpha(255);
            }
        }
    }

    // ---- 渲染 -------------------------------------------------------------------------------

    private void render(Canvas c) {
        probe.begin(FrameProbe.BG_CLR);
        c.drawColor(Ink.BG_DEEP);
        probe.end(FrameProbe.BG_CLR);
        probe.begin(FrameProbe.BG);
        int save = c.save();
        // 逻辑坐标 → 缓冲像素：整帧一次 scale，之后所有绘制都在 240 x LOGIC_H 里算
        c.scale(metrics.scale, metrics.scale);
        int top = metrics.battleTop();
        int shakeSave = c.save();
        c.translate(shakeX, shakeY);

        background.draw(c, probe);
        drawBattleFrame(c, top);
        probe.end(FrameProbe.BG);
        probe.begin(FrameProbe.ENT);
        drawDrops(c);
        drawEnemies(c);
        drawPlayer(c);
        drawShots(c, hostile, true);
        drawMissiles(c);
        drawWarheads(c);
        drawShots(c, shots, false);
        probe.end(FrameProbe.ENT);
        probe.begin(FrameProbe.FX);
        fx.drawAll(c);
        // 只退震屏这一层平移，**scale 必须留着**：退到 save 之前等于把逻辑坐标退回缓冲像素，
        // 之后所有 HUD 会在 scale=2 的机器上缩成左上角的一块（scale=1 的低端机反而看不出）。
        c.restoreToCount(shakeSave);

        // 以上 UI 与闪白不吃震屏：抖动只作用于世界，不然 HUD 会跟着糊
        drawHurtFlash(c, top);
        drawSweepFlash(c, top);
        probe.end(FrameProbe.FX);
        int modal = modals.peek();
        // 三张**整页**模态（结算 / 主菜单 / 成长）共用一套待遇：自己报整页高、在画布里居中、
        // 不跟着战场长高，并且把 HUD 与波次横幅一起让位。这个布尔是那条待遇的**唯一**判据——
        // 分两处各列一遍模态号的写法，迟早会有一处忘记加新页（表现为"菜单底下亮着血条"）。
        boolean pageModal = modal == ModalStack.RESULT
                || modal == ModalStack.MENU || modal == ModalStack.GROWTH;
        probe.begin(FrameProbe.MODAL);
        if (pageModal) {
            int pageSave = c.save();
            if (modal == ModalStack.MENU) {
                c.translate(0f, metrics.pageTop(menu.pageHeight()));
                menu.draw(c, growth, time.ui() - menuAtUi);
            } else if (modal == ModalStack.GROWTH) {
                c.translate(0f, metrics.pageTop(growthPage.pageHeight()));
                growthPage.draw(c, time.ui() - growthAtUi);
            } else {
                c.translate(0f, metrics.pageTop(result.pageHeight()));
                result.draw(c, sheet, time.ui() - resultAtUi);
            }
            c.restoreToCount(pageSave);
        } else {
            int bannerSave = c.save();
            c.translate(0f, top);
            drawWaveBanner(c);
            c.restoreToCount(bannerSave);
        }
        probe.end(FrameProbe.MODAL);

        probe.begin(FrameProbe.HUD);
        if (!pageModal) {
            // HUD 钉在**屏幕顶 + 安全区**。它和战场原点今天恰好是同一个数（都等于 safeTop），
            // 但走 hudTop() 这个出口：战场是"玩家能飞到的地方"，HUD 是"压在上面的读数"，
            // 两者一旦哪天要分开（比如 HUD 让开 Boss 条），改一处就够。组内一律 0 起相对坐标。
            int hudSave = c.save();
            c.translate(0f, metrics.hudTop());
            drawTopRow(c);
            drawStatusRow(c);
            drawBombButton(c);
            drawSwitchButton(c);
            drawBossBar(c);
            // 暂停面板不接管 HUD：它靠 0.78 遮罩把整套读数压成背景，视线才落在面板的卡上
            if (modal == ModalStack.NONE) drawFpsPanel(c);
            c.restoreToCount(hudSave);
        }
        probe.end(FrameProbe.HUD);
        probe.begin(FrameProbe.TAIL);
        if (modal == ModalStack.PAUSE) {
            // 面板走整张逻辑画布（不在 hudSave 的平移组里），高屏上才谈得上"纵向分账"
            pause.draw(c, score, wave.wave(), chips, coins, stats, trend, time.ui() - pauseAtUi);
        }
        if (modal == ModalStack.SHOP) {
            shop.draw(c, shopRun, time.ui() - shopAtUi);
        }
        drawRestartFade(c);
        probe.end(FrameProbe.TAIL);
        // 帧末退到进 render 之前：Canvas 对象可能被 Surface 复用，变换漏到下一帧就是每帧再乘一次 scale
        c.restoreToCount(save);
    }

    /** 重开转场（规格 §四）：0.45s 全屏淡入，盖的是整块逻辑画布，不只是战斗区。 */
    private void drawRestartFade(Canvas c) {
        if (restartFade <= 0f) return;
        fill.setColor(Ink.BG_DEEP);
        fill.setAlpha(Math.round(230f * restartFade));
        rf.set(0, 0, Screen.LOGIC_W, metrics.logicH);
        c.drawRect(rf, fill);
        fill.setAlpha(255);
    }

    private void drawBattleFrame(Canvas c, int top) {
        stroke.setColor(Ink.EDGE);
        rf.set(0.5f, top + 0.5f, Screen.LOGIC_W - 0.5f, metrics.battleBottom() - 0.5f);
        c.drawRect(rf, stroke);
    }

    private void drawHurtFlash(Canvas c, int top) {
        if (hurtFlash <= 0f) return;
        fill.setColor(Ink.WARN);
        fill.setAlpha(Math.round(70f * hurtFlash));
        rf.set(0, top, Screen.LOGIC_W, metrics.battleBottom());
        c.drawRect(rf, fill);
        fill.setAlpha(255);
    }

    private void drawSweepFlash(Canvas c, int top) {
        if (sweepFlash <= 0f) return;
        fill.setColor(0xFFFFFFFF);
        fill.setAlpha(Math.round(120f * sweepFlash));
        rf.set(0, top, Screen.LOGIC_W, metrics.battleBottom());
        c.drawRect(rf, fill);
        fill.setAlpha(255);
    }

    /** 机体：像素网格生成的精灵；受伤无敌期闪烁（规格 §三：闪烁而不是消失）。 */
    private void drawPlayer(Canvas c) {
        if (!player.alive) return;
        if (player.invulnerable() && ((world * 20f) % 2f) < 1f) return;
        Bitmap ship = SpriteFactory.icon(SpriteSheets.SHIP, SpriteSheets.ID_SHIP, Ink.PROBE, 11, 11);
        // 2026-09-24 决策：机头恒定朝上，不随移动方向转。原来每帧一次 canvas.rotate 去掉，
        // 顺带免掉旋转重采样对像素网格的破坏。
        SpriteFactory.draw(c, ship, (int) (player.x - 5.5f), (int) (player.y - 5.5f), 11, 11,
                SpriteFactory.PIXEL);

        Bitmap atlas = glow.atlasFor(Ink.PROBE);
        int k = GlowAtlas.SPARK;
        SpriteFactory.drawRegion(c, atlas, GlowAtlas.regionX(k), GlowAtlas.regionY(k),
                GlowAtlas.regionW(k), GlowAtlas.regionH(k),
                (int) player.x - 8, (int) player.y - 8, 16, 16, SpriteFactory.GLOW);

        stroke.setColor(overload.ready() ? Ink.OVERLOAD : Ink.TEXT);
        rf.set(player.x - 11f, player.y - 11f, player.x + 11f, player.y + 11f);
        // 超载环读真实充能，不再是装饰性的计时器
        c.drawArc(rf, -90f, 360f * overload.charge01(), false, stroke);
    }

    private void drawEnemies(Canvas c) {
        for (int i = 0; i < enemies.activeCount(); i++) {
            Enemies.Enemy e = enemies.activeAt(i);
            int sheetId;
            Bitmap bmp;
            int size;
            int color;
            if (e.boss) {
                Balance.Boss spec = Balance.bosses[e.kind];
                sheetId = SpriteSheets.bossId(e.kind);
                color = spec.color;
                size = spec.spriteSize;
                bmp = SpriteFactory.icon(SpriteSheets.bossSheet(e.kind), sheetId, color, size, size);
            } else {
                Balance.Enemy spec = Balance.enemies[e.kind];
                sheetId = SpriteSheets.enemyId(e.kind);
                color = spec.color;
                size = spec.spriteSize;
                bmp = SpriteFactory.icon(SpriteSheets.enemySheet(e.kind), sheetId, color, size, size);
            }
            if (e.enter < 1f) {
                fill.setAlpha(Math.round(255f * e.enter));
                SpriteFactory.draw(c, bmp, (int) (e.x - size / 2f), (int) (e.y - size / 2f),
                        size, size, fill);
                fill.setAlpha(255);
            } else {
                SpriteFactory.draw(c, bmp, (int) (e.x - size / 2f), (int) (e.y - size / 2f),
                        size, size, SpriteFactory.PIXEL);
            }
            if (e.hitFlash > 0f) {
                // 受击白闪用一个小方块盖在核心上：再烘一张白色精灵会为每次命中多占一个颜色槽
                int half = Math.max(1, size / 4);
                fill.setColor(0xFFFFFFFF);
                rf.set(e.x - half, e.y - half, e.x + half, e.y + half);
                c.drawRect(rf, fill);
            }
        }
    }

    private void drawShots(Canvas c, BulletPool pool, boolean fromEnemy) {
        for (int i = 0; i < pool.activeCount(); i++) {
            BulletPool.Bullet b = pool.activeAt(i);
            fill.setColor(b.color == 0 ? (fromEnemy ? Ink.WARN : Ink.PROBE) : b.color);
            float w = b.size;
            float h = b.size;
            if (!fromEnemy) {
                h = b.size * Balance.weapons[b.weaponId].drawLenRatioY;
            }
            rf.set(b.x - w / 2f, b.y - h / 2f, b.x + w / 2f, b.y + h / 2f);
            c.drawRect(rf, fill);
            if (b.crit) {
                fill.setColor(0xFFFFFFFF);
                rf.set(b.x - 1f, b.y - 1f, b.x + 1f, b.y + 1f);
                c.drawRect(rf, fill);
            }
        }
        fill.setAlpha(255);
    }

    /**
     * 仿真弹体：方块 + 一格拖尾。方块与 {@link #drawShots} 同口径（尺寸取 {@code size}，
     * **不乘** {@code drawLenRatioY}——那个比例是"弹链时隙"的输入，而导弹走独立仿真池、不参与排放，
     * 套上去会让它在屏幕上比普通弹长一截却没有算术依据）。拖尾读 {@code px/py}，
     * 于是滑行段与点火段在画面上分得开：弹链的"形态先于颜色"这条在这里仍然成立。
     */
    private void drawMissiles(Canvas c) {
        // 两条流**同一条画法**：形状与颜色都取自出膛那一刻的武器模板（格斗弹没有第二套外观，
        // 那是他没说过的东西，不在这儿发明）。真机如果分不清哪个池在飞，再谈加不加标记。
        drawMissilePool(c, missiles);
        drawMissilePool(c, dogfightMissiles);
        fill.setAlpha(255);
    }

    /** 一流弹的绘制。⚠ 与 {@link #stepPool} 一样按池调用，两池共用这一份像素口径。 */
    private void drawMissilePool(Canvas c, Missiles pool) {
        for (int i = 0; i < pool.activeCount(); i++) {
            Missiles.Missile m = pool.activeAt(i);
            stroke.setColor(m.color);
            c.drawLine(m.px, m.py, m.x, m.y, stroke);
            fill.setColor(m.color);
            rf.set(m.x - m.size / 2f, m.y - m.size / 2f, m.x + m.size / 2f, m.y + m.size / 2f);
            c.drawRect(rf, fill);
            if (m.crit) {
                fill.setColor(0xFFFFFFFF);
                rf.set(m.x - 1f, m.y - 1f, m.x + 1f, m.y + 1f);
                c.drawRect(rf, fill);
            }
        }
    }

    /**
     * 战斗部：连续杆画成**一条垂直弹轴的亮线**，碎片画成小方块。
     *
     * <p>端点一律走 {@link WarheadRules#rodEnds}，本类**不持角度**：那四个 float 是命中与绘制
     * 唯一的共同几何，写第二次就会"画得直但打得歪"。速度为零时 {@code rodEnds} 返回 false，
     * 那一格既不画也不算——与 {@code Game.stepWarheads} 里同一个判据。
     *
     * <p>⚠ {@code stroke} 与 HUD 共用一支 Paint，改完粗细与 alpha 必须还原，否则下一行的边框
     * 会跟着变成三像素的亮条（那种错在截图上只是"线粗了点"，很难查回这一行）。
     */
    private void drawWarheads(Canvas c) {
        Balance.Missile spec = Balance.missile;
        for (int i = 0; i < warheads.activeCount(); i++) {
            Warheads.Warhead w = warheads.activeAt(i);
            int a = 255;
            if (w.maxLife > 0f) {
                float k = 1f - w.life / w.maxLife;         // 到期前渐隐，不给"突然消失"
                a = Math.round(255f * (k < 0f ? 0f : k));
            }
            if (w.kind == Warheads.Warhead.KIND_ROD) {
                if (!WarheadRules.rodEnds(w.x, w.y, w.vx, w.vy, WarheadRules.rodHalfLen(w, spec), rodEnds)) continue;
                stroke.setColor(w.color);
                stroke.setAlpha(a);
                stroke.setStrokeWidth(Math.max(1f, w.size));
                c.drawLine(rodEnds[0], rodEnds[1], rodEnds[2], rodEnds[3], stroke);
            } else {
                fill.setColor(w.color);
                fill.setAlpha(a);
                rf.set(w.x - w.size / 2f, w.y - w.size / 2f, w.x + w.size / 2f, w.y + w.size / 2f);
                c.drawRect(rf, fill);
            }
        }
        stroke.setStrokeWidth(1f);
        stroke.setAlpha(255);
        fill.setAlpha(255);
    }

    private void drawDrops(Canvas c) {
        int size = Balance.drop.drawSize;
        for (int i = 0; i < drops.activeCount(); i++) {
            Drops.Item it = drops.activeAt(i);
            Bitmap bmp = SpriteFactory.icon(SpriteSheets.dropSheet(it.kind),
                    SpriteSheets.dropId(it.kind), Drops.colorOf(it.kind), size, size);
            SpriteFactory.draw(c, bmp, (int) (it.x - size / 2f), (int) (it.y - size / 2f),
                    size, size, SpriteFactory.PIXEL);
        }
    }

    /** 行1：波次 | 芯片 | 金币 | 分数，整行右对齐到 208（规格 §四：避开暂停按钮 214~232）。 */
    private void drawTopRow(Canvas c) {
        int mid = (HudLayout.ROW1_TOP + HudLayout.ROW1_BOTTOM) / 2;
        int right = HudLayout.INFO_RIGHT;
        right = drawInfoItem(c, L_SCORE, hud.reset().num(score), right, mid, Ink.TEXT);
        right = drawInfoItem(c, L_COIN, hud.reset().num(coins), right, mid, Ink.OVERLOAD);
        right = drawInfoItem(c, L_CHIP, hud.reset().num(chips), right, mid, Ink.SHIELD);
        drawInfoItem(c, L_WAVE, hud.reset().num(wave.wave()), right, mid, Ink.TEXT);
        fill.setColor(Ink.EDGE);
        rf.set(pauseDraw.left, pauseDraw.top, pauseDraw.right, pauseDraw.bottom);
        c.drawRect(rf, fill);
        // 暂停是图标不是文字（规格 §三：像素规则约束精灵与图标）。画两竖条的点阵字母 "II"
        // 看起来像一串被截断的读数，读不出"这是个按钮"。
        Bitmap bars = SpriteFactory.icon(SpriteSheets.PAUSE_BARS, SpriteSheets.ID_PAUSE,
                Ink.TEXT, PAUSE_ICON_W, PAUSE_ICON_H);
        SpriteFactory.draw(c, bars, pauseDraw.centerX() - PAUSE_ICON_W / 2,
                pauseDraw.centerY() - PAUSE_ICON_H / 2, PAUSE_ICON_W, PAUSE_ICON_H,
                SpriteFactory.PIXEL);
    }

    /**
     * 炸弹按钮：图标 + 存货数（规格 §掉落物"入栏，由玩家决定何时放"）。
     *
     * <p>存货为 0 时整块不画，也不注册命中（见 {@code onPointerDown}）——一个永远点不动的角落
     * 只会让人以为屏幕那块区域坏了。有货才出现，出现的位置固定，视线一次就能建立"右下角=炸弹"。
     */
    private void drawBombButton(Canvas c) {
        if (bombs <= 0) return;
        fill.setColor(Ink.EDGE);
        rf.set(bombDraw.left, bombDraw.top, bombDraw.right, bombDraw.bottom);
        c.drawRect(rf, fill);
        int mid = bombDraw.centerY();
        Bitmap icon = SpriteFactory.icon(SpriteSheets.DROP_BOMB, SpriteSheets.ID_BOMB,
                Drops.colorOf(Drops.BOMB), HudLayout.BOMB_ICON, HudLayout.BOMB_ICON);
        SpriteFactory.draw(c, icon, bombDraw.left + HudLayout.BOMB_PAD,
                mid - HudLayout.BOMB_ICON / 2, HudLayout.BOMB_ICON, HudLayout.BOMB_ICON,
                SpriteFactory.PIXEL);
        HudText num = hud.reset().num(bombs);
        text.setColor(Ink.TEXT);
        font.draw(c, num.buffer(), num.length(),
                bombDraw.left + HudLayout.BOMB_PAD + HudLayout.BOMB_ICON + HudLayout.BOMB_NUM_GAP,
                mid - BitmapFont.GLYPH_H / 2, text);
    }

    /**
     * 切枪键：框里印**当前这一把**的名字，点一下换到下一把**买过的**。
     *
     * <p>画"手里是什么"而不是"下一次换成什么"：这一格同时是场上那条弹道的署名——玩家低头确认
     * 自己正在打什么的那一眼，比预知下一把有用（而且下一把要等他真按了才算数）。
     *
     * <p>名字着色用武器自己的 {@code color}（{@code Balance.Weapon} 那一格），于是键上的字与
     * 飞出去的弹是同一个颜色：形状先于颜色，但颜色在这里是**对应关系**的免费提示，
     * 不必再画一个图标去说"这是枪"。字面量就是武器表的 name，不新增文案 ⇒ 没有第二个真源。
     */
    private void drawSwitchButton(Canvas c) {
        fill.setColor(Ink.EDGE);
        rf.set(switchDraw.left, switchDraw.top, switchDraw.right, switchDraw.bottom);
        c.drawRect(rf, fill);
        Balance.Weapon w = player.weapon();
        Bitmap label = uiText.bake(w.name, UI_PX, w.color);
        text.setColor(w.color);
        SpriteFactory.draw(c, label, switchDraw.centerX() - label.getWidth() / 2,
                switchDraw.centerY() - label.getHeight() / 2,
                label.getWidth(), label.getHeight(), text);
    }

    /**
     * 画一项「中文标签 + ASCII 数字」，从右往左排，返回下一项可用的右边界（已含间距）。
     * 中文走 TextCache（常量串，烘焙一次），数字走 HudText + BitmapFont（帧内零分配）。
     */
    private int drawInfoItem(Canvas c, String label, HudText num, int right, int midY, int color) {
        Bitmap b = uiText.bake(label, UI_PX, color);
        int lw = b.getWidth();
        int dw = BitmapFont.textWidth(num.buffer(), num.length());
        int left = right - (lw + NUM_GAP + dw);
        text.setColor(color);
        SpriteFactory.draw(c, b, left, midY - b.getHeight() / 2, lw, b.getHeight(), text);
        font.draw(c, num.buffer(), num.length(), left + lw + NUM_GAP,
                midY - BitmapFont.GLYPH_H / 2, text);
        return left - ITEM_GAP;
    }

    /** 行2 四条状态条 + 行3 状态效果胶囊（几何全在 {@link HudLayout}，此处只画）。 */
    private void drawStatusRow(Canvas c) {
        float hp = player.hpRatio();
        float sh = player.shieldRatio();
        drawBar(c, 0, true, L_HP, hp, hpTrail, hpColor(hp));
        drawBar(c, 1, false, L_SHIELD, sh, shieldTrail, shieldColor(sh));
        drawBar(c, 2, false, L_OVERLOAD, overload.charge01(), -1f, Ink.OVERLOAD);
        drawEnemyBar(c);
        drawCapsules(c);
    }

    private static int hpColor(float ratio) {
        return ratio > 0.6f ? Ink.HP : (ratio > 0.3f ? Ink.OVERLOAD : Ink.WARN);
    }

    /**
     * 溢出段画不出长度（{@code fillWidth} 钳 0..1，见 {@link Ink#SHIELD_OVER} 那段），所以越限期间
     * 整条换成亮色、掉回上限之内那一帧翻回常色。颜色翻回去就是"多出来那一截用完了"的读数。
     */
    private static int shieldColor(float ratio) {
        return ratio > 1f ? Ink.SHIELD_OVER : Ink.SHIELD;
    }

    /** 敌人条：剩余 = 在场 + 排队，分母是本波总数；Boss 波换成"首领"并读 Boss 血量。 */
    private void drawEnemyBar(Canvas c) {
        Enemies.Enemy boss = aliveBoss();
        if (boss != null) {
            drawBar(c, 3, false, L_BOSS, boss.maxHp <= 0f ? 0f : (float) boss.hp / boss.maxHp,
                    -1f, Balance.bosses[boss.kind].color);
            return;
        }
        float left = enemies.aliveCount() + wave.pendingSpawns();
        drawBar(c, 3, false, L_ENEMY, left / Math.max(1, wave.waveTotal()), -1f, Ink.WARN);
    }

    /** trail &lt; 0 表示这条没有残影（过载/敌人条读的是进度，不是血量）。 */
    private void drawBar(Canvas c, int row, boolean main, String label, float ratio,
                         float trail, int color) {
        float r = HudLayout.clamp01(ratio);
        HudLayout.labelRect(row, rectA);
        Bitmap b = uiText.bake(label, UI_PX, color);
        text.setColor(color);
        SpriteFactory.draw(c, b, rectA.left, rectA.centerY() - b.getHeight() / 2,
                b.getWidth(), b.getHeight(), text);

        HudLayout.barRect(row, main, rectB);
        fill.setColor(Ink.BATTLE);
        rf.set(rectB.left, rectB.top, rectB.right, rectB.bottom);
        c.drawRect(rf, fill);
        int fw = HudLayout.fillWidth(r);
        int tw = trail >= 0f ? HudLayout.trailWidth(r, trail) : 0;
        if (tw > 0) {
            fill.setColor(Ink.EDGE);
            rf.set(rectB.left + fw, rectB.top, rectB.left + fw + tw, rectB.bottom);
            c.drawRect(rf, fill);
        }
        fill.setColor(color);
        rf.set(rectB.left, rectB.top, rectB.left + fw, rectB.bottom);
        c.drawRect(rf, fill);
        if (main) {
            // 体积光：顶端一行提亮、底端一行压暗；末端一枚锐光。通用条没这个待遇（规格 §四）
            fill.setColor(0x66FFFFFF);
            rf.set(rectB.left, rectB.top, rectB.left + fw, rectB.top + 1);
            c.drawRect(rf, fill);
            fill.setColor(0x40000000);
            rf.set(rectB.left, rectB.bottom - 1, rectB.left + fw, rectB.bottom);
            c.drawRect(rf, fill);
            if (fw > 2) {
                drawGlow(c, GlowAtlas.SPARK, rectB.left + fw - 5, rectB.centerY() - 4, 8, color);
            }
        }
        stroke.setColor(Ink.EDGE);
        rf.set(rectB.left, rectB.top, rectB.right, rectB.bottom);
        c.drawRect(rf, stroke);
    }

    /** 行3：色点 → 圆环从 12 点顺时针消退；秒数不显示（余光读得尽环，读不尽字）。 */
    private void drawCapsules(Canvas c) {
        int n = status.activeKinds(activeKinds);
        int slot = 0;
        for (int i = 0; i < n && slot < HudLayout.CAP_MAX; i++) {
            int kind = activeKinds[i];
            int color = StatusLayers.isBuff(kind) ? Ink.PICK : Ink.WARN;
            drawCapsule(c, slot++, StatusLayers.label(kind), color,
                    HudLayout.ringRemaining01(status.remainingOf(kind), Balance.status.pickupSec));
        }
        int env = status.environment();
        if (env != StatusLayers.ENV_NONE && slot < HudLayout.CAP_MAX) {
            // 环境层没有倒计时（离开星区即失效）：满环，不是空环
            drawCapsule(c, slot, StatusLayers.environmentLabel(env), Ink.TEXT, 1f);
        }
    }

    /** slot 0 = 最右，往左排；整组右对齐到 215。 */
    private void drawCapsule(Canvas c, int slot, String label, int color, float remaining01) {
        HudLayout.capsuleRects(slot, rectB, rectC);
        Bitmap b = uiText.bake(label, UI_PX, color);
        text.setColor(color);
        SpriteFactory.draw(c, b, rectC.centerX() - b.getWidth() / 2,
                rectC.centerY() - b.getHeight() / 2, b.getWidth(), b.getHeight(), text);
        drawRing(c, rectB, color, remaining01);
    }

    private void drawRing(Canvas c, RectI box, int color, float remaining01) {
        rf.set(box.left, box.top, box.right, box.bottom);
        // 底轨画整圈再叠剩余段：方形外框会把"环"读成"格子里的弧线"，余光看不出在消退
        stroke.setColor(Ink.EDGE);
        c.drawArc(rf, 0f, 360f, false, stroke);
        stroke.setColor(color);
        c.drawArc(rf, -90f, 360f * HudLayout.clamp01(remaining01), false, stroke);
        int mid = box.centerY();
        fill.setColor(color);
        rf.set(box.centerX() - 1, mid - 1, box.centerX() + 1, mid + 1);
        c.drawRect(rf, fill);
    }

    /** [规格 §四] Boss 血条：整幅宽、二阶段把变奏点标出来——玩家要看得见"还差多少才换弹道"。 */
    private void drawBossBar(Canvas c) {
        Enemies.Enemy boss = aliveBoss();
        if (boss == null) return;
        Balance.Boss spec = Balance.bosses[boss.kind];
        HudLayout.bossBarRect(rectA);
        float r = boss.maxHp <= 0 ? 0f : HudLayout.clamp01((float) boss.hp / boss.maxHp);
        int span = rectA.width() - 1;
        int fw = Math.round(span * r);
        fill.setColor(Ink.BATTLE);
        rf.set(rectA.left, rectA.top, rectA.right, rectA.bottom);
        c.drawRect(rf, fill);
        fill.setColor(spec.color);
        rf.set(rectA.left, rectA.top, rectA.left + fw, rectA.bottom);
        c.drawRect(rf, fill);
        // 变奏点用底色调回一条缝：盖在填充上，也盖在空槽上，两种状态都读得到
        int mark = rectA.left + Math.round(span * HudLayout.clamp01(spec.phase2AtRatio));
        fill.setColor(Ink.BATTLE);
        rf.set(mark, rectA.top, mark + 1, rectA.bottom);
        c.drawRect(rf, fill);
        stroke.setColor(Ink.EDGE);
        rf.set(rectA.left, rectA.top, rectA.right, rectA.bottom);
        c.drawRect(rf, stroke);
        if (fw > 3) {
            drawGlow(c, GlowAtlas.SPARK, rectA.left + fw - 6, rectA.centerY() - 6, 12, spec.color);
        }
        int labelColor = boss.phase2 ? Ink.WARN : Ink.TEXT;
        HudLayout.bossLabelRect(rectB);
        Bitmap nm = uiText.bake(spec.name, UI_PX, labelColor);
        text.setColor(labelColor);
        SpriteFactory.draw(c, nm, rectB.left, rectB.centerY() - nm.getHeight() / 2,
                nm.getWidth(), nm.getHeight(), text);
        if (boss.phase2) {
            Bitmap p2 = uiText.bake(L_PHASE2, UI_PX, Ink.WARN);
            SpriteFactory.draw(c, p2, rectB.left + nm.getWidth() + NUM_GAP,
                    rectB.centerY() - p2.getHeight() / 2, p2.getWidth(), p2.getHeight(), text);
        }
    }

    /** 场上同时只有一个 Boss；首领条与 Boss 血条必须读同一个实例，否则两处血量会打架。 */
    private Enemies.Enemy aliveBoss() {
        for (int i = 0; i < enemies.activeCount(); i++) {
            Enemies.Enemy e = enemies.activeAt(i);
            if (e.boss && e.hp > 0) return e;
        }
        return null;
    }

    /** 辉光走烘焙图集，不走 setShadowLayer（规格 §三：实时模糊会把帧时间翻三倍）。 */
    private void drawGlow(Canvas c, int kind, int x, int y, int size, int color) {
        SpriteFactory.drawRegion(c, glow.atlasFor(color),
                GlowAtlas.regionX(kind), GlowAtlas.regionY(kind),
                GlowAtlas.regionW(kind), GlowAtlas.regionH(kind),
                x, y, size, size, SpriteFactory.GLOW);
    }

    /** 开战横幅：波次/首领 + 星区名。星区名是常量串（WaveDirector 里那张表），不每帧拼。 */
    private void drawWaveBanner(Canvas c) {
        int ph = wave.phase();
        if (ph != WaveFlow.PREP && ph != WaveFlow.INTERMISSION) return;
        boolean bossWave = wave.currentWaveIsBoss();
        int midY = metrics.battleHeight() / 2 - 20;
        drawLabeledCentered(c, bossWave ? L_BOSS : L_WAVE, hud.reset().num(wave.wave()),
                Screen.LOGIC_W / 2, midY, bossWave ? Ink.WARN : Ink.TEXT);
        Bitmap z = uiText.bake(WaveDirector.zoneName(wave.zoneIndex()), UI_PX, Ink.DIM);
        text.setColor(Ink.DIM);
        SpriteFactory.draw(c, z, Screen.LOGIC_W / 2 - z.getWidth() / 2, midY + 12,
                z.getWidth(), z.getHeight(), text);
    }

    /** 标签在左、数字在右，整组按指定中心水平排开。 */
    private void drawLabeledCentered(Canvas c, String label, HudText num, int cx, int midY, int color) {
        Bitmap b = uiText.bake(label, UI_PX, color);
        int dw = BitmapFont.textWidth(num.buffer(), num.length());
        int left = cx - (b.getWidth() + NUM_GAP + dw) / 2;
        text.setColor(color);
        SpriteFactory.draw(c, b, left, midY - b.getHeight() / 2, b.getWidth(), b.getHeight(), text);
        font.draw(c, num.buffer(), num.length(), left + b.getWidth() + NUM_GAP,
                midY - BitmapFont.GLYPH_H / 2, text);
    }

    /**
     * 规格 §四：右上角、贴暂停按钮右边界、两行**带单位**、层级在所有 UI 之上。
     * 单位走 6px 点阵而不是 TextCache——这两个数每帧都变，但单位不变，烘焙反而是浪费。
     */
    private void drawFpsPanel(Canvas c) {
        HudLayout.fpsRect(rectA);
        int fps = Math.round(time.fps());
        text.setColor(fps >= 55 ? Ink.TEXT : Ink.WARN);
        drawRightText(c, hud.reset().num(fps).text(" FPS"), rectA.right, HudLayout.FPS_TOP);
        text.setColor(Ink.DIM);
        drawRightText(c, hud.reset().fixed(time.frameMs(), 1).text(" MS"),
                rectA.right, HudLayout.FPS_TOP + HudLayout.FPS_LINE_H);
        // 调试行贴左上：面板在右上，各占一角互不抢位。受伤系数可大于 1（脆化/虚空），
        // percent 会把它钳成 100%，所以这里读定点小数。
        HudText dbg = hud.reset()
                .text("E").num(enemies.aliveCount()).chr('/').num(wave.pendingSpawns())
                .text("  B").num(shots.activeCount())
                .text("  H").num(hostile.activeCount())
                .text("  P").num(particles.activeCount())
                .text("  D").num(drops.activeCount())
                // 这一行整个走 ASCII 点阵（BitmapFont），非 ASCII 字符在那儿没有字形，
                // 画出来是空格——"×" 就是被 EmbeddedFontTest 从这儿揪出来的。
                .text("  x").fixed(status.takenMul(), 2);
        font.draw(c, dbg.buffer(), dbg.length(), 4, HudLayout.FPS_TOP, text);
        dumpProbeToLogcat();
    }

    /**
     * 帧内分段计时的出口：打到 logcat，一秒一行。
     *
     * <p>不放屏上是因为**放不下**：HUD 从帧数面板到 Boss 名之间只有 14 逻辑像素的缝，
     * 而一次分账要五行——点在点阵字上会互相压字，读不出小数就等于没测。
     * 挂在调试面板这条路径上，也就是只有开了 {@code showFps} 才有这一秒一次的
     * {@code String.format}；稳态渲染路径仍然一次都不分配。
     *
     * <p>它值这份保留：26fps 那次从"整帧 38ms"到"星场 18.7ms 占 49%"只用了一行日志，
     * 而在此之前所有关于"是不是怪太多""是不是 GC"的判断都是猜。
     */
    private int probeTick;

    private void dumpProbeToLogcat() {
        if (++probeTick < 60) return;
        probeTick = 0;
        android.util.Log.i("PRProbe", String.format(java.util.Locale.US,
                "fps=%.0f L%.1f D%.1f P%.1f | step%.1f BG%.1f ENT%.1f FX%.1f HUD%.1f MOD%.1f"
                        + " | clr%.1f glow%.1f neb%.1f plan%.1f star%.1f dust%.1f | buf%dx%d s%d"
                        + " | txt%d m%d e%d",
                time.fps(),
                probe.ms(FrameProbe.LOCK), probe.ms(FrameProbe.DRAW), probe.ms(FrameProbe.POST),
                probe.ms(FrameProbe.STEP), probe.ms(FrameProbe.BG), probe.ms(FrameProbe.ENT),
                probe.ms(FrameProbe.FX), probe.ms(FrameProbe.HUD), probe.ms(FrameProbe.MODAL),
                probe.ms(FrameProbe.BG_CLR), probe.ms(FrameProbe.BG_GLOW), probe.ms(FrameProbe.BG_NEB),
                probe.ms(FrameProbe.BG_PLAN), probe.ms(FrameProbe.BG_STAR), probe.ms(FrameProbe.BG_DUST),
                metrics.bufW, metrics.bufH, metrics.scale,
                uiText.size(), uiText.misses(), uiText.evictions()));
    }

    private void drawRightText(Canvas c, HudText t, int right, int top) {
        int w = BitmapFont.textWidth(t.buffer(), t.length());
        font.draw(c, t.buffer(), t.length(), right - w, top, text);
    }
}
