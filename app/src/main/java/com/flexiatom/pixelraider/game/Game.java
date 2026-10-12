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
import com.flexiatom.pixelraider.plat.AsyncSaveSink;
import com.flexiatom.pixelraider.plat.KeyValue;
import com.flexiatom.pixelraider.plat.QualityProfile;
import com.flexiatom.pixelraider.plat.Screen;
import com.flexiatom.pixelraider.ui.CampaignScreen;
import com.flexiatom.pixelraider.ui.DebugScreen;
import com.flexiatom.pixelraider.ui.GrowthScreen;
import com.flexiatom.pixelraider.ui.HudLayout;
import com.flexiatom.pixelraider.ui.HudText;
import com.flexiatom.pixelraider.ui.MenuScreen;
import com.flexiatom.pixelraider.ui.PauseScreen;
import com.flexiatom.pixelraider.ui.RadarLayout;
import com.flexiatom.pixelraider.ui.RevealScript;
import com.flexiatom.pixelraider.ui.ResultScreen;
import com.flexiatom.pixelraider.ui.SaveScreen;
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

    /**
     * 音频回调。和 {@link Haptics} 同一档理由：住在 game 侧由 plat 实现，本类因此不 import
     * android 的媒体栈（规格 §八 的逻辑核／平台层解耦）。
     *
     * <p>三个方法各自守一条硬要求（规格 §六/§七，2026-10-05 裁定读成"行为"而非 API 名）：
     * {@link #beginFrame()} 归零**每帧新增配额**——{@code step()} 一帧可能跑 0..N 次，
     * 配额挂在帧上而不是步上；{@link #play(int)} 收 {@link AudioCues} 的事件 id，播放面自己
     * 决定它去哪条总线；{@link #setBgm(int)} 换轨即从头循环，**没有交叉淡入**（§七 :294 已作废）。
     */
    public interface Audio {
        void beginFrame();

        void play(int cue);

        void setBgm(int track);

        /** 未接线时的出口，形状同 {@code KeyValue.EMPTY}：二十来个站点因此都不用 null 判。 */
        Audio NONE = new Audio() {
            @Override public void beginFrame() {
            }

            @Override public void play(int cue) {
            }

            @Override public void setBgm(int track) {
            }
        };
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
     * 光束池（第三条流）。**不与 {@link #shots} 共用**，理由与 {@link #missiles} 那两条同形且更硬：
     * 光束没有速度、没有寿命积分、命中即结完（{@link Beams} 类头三条差别）。把它塞进弹池，
     * 就得给那张 14 字段的结构加"不可回收、不积分"两种例外——为一个分支污染整张表。
     * <p>⚠ 它是**画面**的容器：伤害在 {@link #collide} 里结，结完那一格亮着继续存在只是因为
     * 玩家要看得见"我打的是束不是弹"。
     */
    private final Beams beams = new Beams(Balance.beam.capacity);
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
    /** 芯片的同型余量（[规格 §8.4]：0.85／0.75 那两档不吃这层就会在 int 截断里被吞掉，早期局能吞整 1–2 枚）。 */
    private float chipCarry;
    /**
     * 本局的模式（[规格 §5.5] 分叉清单的真源），档在它里面（{@link Mode#tier}）。写点只有
     * {@link #applyMode} 一处，由整局重置调用。
     *
     * <p>这一份＋{@code WaveDirector}／{@code WaveFlow} 里那两份"转授"的副本是 §8.5 定的形状：波次那几格
     * 经 director 读、批次与终局经 wave 读，其余四格直读这一枚。三份引用在 {@link #applyMode} 里一起换，
     * 所以"读到两份不同的档/模式"没有窗口。volatile 的理由见 {@code WaveDirector.mode}（整局重置有一条
     * 链来自 UI 线程）。
     */
    private volatile Mode mode = Mode.CAMPAIGN;
    private int bombs = Balance.player.startBombs;
    private boolean runOver;

    private float shake;
    private float shakeX, shakeY;
    private float hurtFlash;
    private float sweepFlash;
    /** 表现时钟：震屏噪声读它，不读 {@link #world}（定格期间 world 不走）。 */
    private float fxClock;

    private Haptics haptics;
    /** 播放面。默认 {@link Audio#NONE}，所以 26 个站点都不用判空（形状同 {@code KeyValue.EMPTY}）。 */
    private Audio audio = Audio.NONE;

    private final Paint fill = new Paint();
    private final Paint stroke = new Paint();
    private final Paint text = new Paint();
    private final RectF rf = new RectF();
    /** 四个角的**方向**（{@link #drawRadar} 用它把一圈 L 拆成四次循环，而不是在绘制热路径上 new 数组）。 */
    private static final int[] BRACKET_X = {-1, 1, -1, 1};
    private static final int[] BRACKET_Y = {-1, -1, 1, 1};
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
    /** 体系键：换算与摆放同前两颗，在三兄弟的最左手边（几何与判序见 {@code HudLayout.schoolRect}）。 */
    private final RectI schoolDraw = new RectI();
    private final RectI schoolHit = new RectI();
    /** 雷达屏：换算同前三枚键。它**不是按钮**，命中框只用来判"这一跳落在屏内还是屏外"。 */
    private final RectI radarDraw = new RectI();
    private final RectI radarHit = new RectI();
    /** 点选时的草稿三张：两张本地坐标 ＋ 一张对应 obj 槽号（为什么必须有第三张见 {@link #pickRadarTarget}）。 */
    private float[] radarXs = new float[0];
    private float[] radarYs = new float[0];
    private int[] radarSlots = new int[0];
    /**
     * 玩家指定的那一只（敌 obj 槽号），{@link MissileBehavior#NO_DESIGNATED} = 没指定。
     *
     * <p>它与 {@link #designatedBorn} 是**一对**：{@code Enemies} 回收槽位，光存槽号会在上一只刚死、
     * 下一只接手同一槽位时把指定安静地跳到玩家从没点过的敌机上。完整有效性判据三条，
     * 抄 {@link Enemies#isLive} 那条注释的口径（isLive ∧ born 对得上 ∧ hp &gt; 0）。
     */
    private int designatedSlot = MissileBehavior.NO_DESIGNATED;
    private long designatedBorn;
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
    /** 调试页（仅 debug 构建可达）。同上。 */
    private final DebugScreen debug = new DebugScreen(font, uiText, glow);
    /**
     * 存档页（50 槽的列表页，从暂停页那枚「存档」进）。同上。
     * <b>它不持有任何槽位数据</b>——画两形读的是 {@link #saveSlots} 那本账的引用（{@code open} 时递进去）。
     */
    private final SaveScreen savePage = new SaveScreen(font, uiText, glow);
    /**
     * 战役选关页（八条任务 + 简报 + 难度 + 键行，从主菜单进）。同上。
     * <b>它不碰规则层</b>：选中的任务号与档号存在页里，开局那一步由 {@link #resetRun(Mode, int)} 现读。
     */
    private final CampaignScreen campaignPage = new CampaignScreen(font, uiText, glow);
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
    /** 进入调试页的界面时刻（面板入场与级联以它为原点，与商店同款）。 */
    private float debugAtUi;
    /** 进入存档页的界面时刻。同上（整页待遇，入场原点与 {@code growthAtUi} 那一族同一个角色）。 */
    private float saveAtUi;
    /** 进入战役选关页的界面时刻。同上（整页待遇的入场原点，与 {@code saveAtUi} 同一角色）。 */
    private float campaignAtUi;
    /**
     * 是否 debug 构建（FLAG_DEBUGGABLE）。由 plat 在启动时注入，决定暂停页那枚「调试」入口画不画——
     * 正式包不注册它的命中也不淡入，与 {@code PauseScreen} 里"未落地的出口整条剔除"同一条口径。
     */
    private boolean debugBuild;
    /**
     * 调试开关：允许非制导武器（脉冲）也发射格斗弹。目视验证用——格斗弹平时只在制导弹上触发，
     * 换弹型成本高，这个开关把那一步绕过去。默认 false，只在本进程内翻转，绝不落盘。
     */
    private boolean debugPulseDogfight;
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
    /**
     * 存档槽位账本（哪些槽有档＋列表页那几枚标量）。它是"这一槽有没有档"的<b>唯一真源</b>：
     * 存档页画两形读这里，不去问介质（问就是渲染线程 {@code File.exists()}）。
     */
    private final SaveSlots saveSlots = new SaveSlots();
    /**
     * 存档字节的线程层出口，与 {@link #store} 同一条注入边界、由 plat 在启动时给。
     * <b>可以为 null</b>——没注入就是"这台机器还没给存档通路"，那两格入口按不可用画
     * （不静默降级成"存进内存、下次开机就没了"，那是 {@code KeyValue.EMPTY} 那条口径明确否掉的假象）。
     */
    private AsyncSaveSink saveSink;
    /**
     * 正在等的读档槽号，-1 = 没在等。它同时是"在飞的那一次"的闸门：{@code AsyncSaveSink} 的交回形状
     * 是一枚引用槽，第二次投递会盖掉第一份的结果，而盖掉之后没人知道那一行点的是哪个槽。
     */
    private int loadingSlot = -1;
    /** 上一次读档的失败边沿。存档页读走一次就清（{@link #takeLoadFailure()}），不留在页面上当常驻状态。 */
    private boolean loadFailed;
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

    /** 注入播放面。传 null 等于退回静音出口，不是"关掉音频"——真正的开关是 {@code setMuted}。 */
    public void setAudio(Audio a) {
        audio = a == null ? Audio.NONE : a;
    }

    /**
     * 注入落盘出口并在同一时刻读回持久状态。只在启动时（surface 建立之前）由 plat 调一次——
     * 晚调等于渲染线程可能已经在读一份没 load 过的纪录表。
     */
    public void attachStore(KeyValue kv) {
        store = kv == null ? KeyValue.EMPTY : kv;
        records.load(store);
        growth.readFrom(store);
        saveSlots.readFrom(store);
    }

    /**
     * 注入存档字节的线程层出口（{@code plat/AsyncSaveSink}）。与 {@link #attachStore} 同一条边界、
     * 同一次时机：surface 建立之前。传 null 等于宣告这台机器没有存档通路——那两格入口按不可用画，
     * 不降级成"写进内存、下次开机就没了"。
     */
    public void attachSave(AsyncSaveSink sink) {
        saveSink = sink;
    }

    /** 槽位账本（只读用途给界面；写只走 {@link SaveSlots#occupy} 与 {@link SaveSlots#release}）。 */
    public SaveSlots saveSlots() {
        return saveSlots;
    }

    /** 存档通路是否可用。null 出口不是错误状态，是"没注入"，界面据此画不可用那一形。 */
    public boolean saveAvailable() {
        return saveSink != null;
    }

    // ---- 局内快照（批2-b：序列化 + 两段式交接的渲染线程这一头）-------------------------------

    /**
     * 拍下<b>当前这一局</b>的仿真状态，返回一份可以直接交给
     * {@link com.flexiatom.pixelraider.plat.AsyncSaveSink#submitWrite} 的字节。
     *
     * <p><b>它不碰 IO，一帧内做完</b>（[规格 §八] 那条红线只管"等盘"）。整个方法是读字段 + 写数组，
     * 量级实测 45–106 KB（池件 §8.11），比一帧的绘制段便宜；而按下「存档」之后玩家本来就要等页面
     * 回话，那一次点击换一整局的确定性是这笔交易的原价。
     *
     * <p><b>调用前提：{@code time.isPaused()} 为真。</b>这不是礼貌而是正确性——本方法逐槽读一只敌人的
     * 二十几个字段，仿真还在推进时读到的就是"上半帧的血量配下半帧的坐标"。真闸取 isPaused 而不是
     * "栈顶 == 存档页"（池件 §7 第 2 条：任何一扇模态页开着都冻住了世界，判栈顶会漏掉暂停页那种
     * 同样安全的时机）。
     *
     * <p>段序与"什么不在字节里"记在 {@link Snapshot} 的类注释，本方法是那份清单的唯一实现处。
     */
    public byte[] captureRun() {
        // 64 KB 的起点按 §8.11 那个区间给：给小了只是多搬一次（Buf 自己倍增），给大了白占内存。
        Buf w = new Buf(64 << 10);
        Snapshot.putHeader(w);
        int top = metrics.battleTop();
        int band = metrics.battleHeight();
        // 八座池：顺序＝本类的字段声明序（敌人/玩家弹/敌弹/普通导弹/格斗导弹/战斗部/光束/掉落）
        enemies.writeSnapshot(w, top, band);
        shots.writeSnapshot(w, top, band);
        hostile.writeSnapshot(w, top, band);
        missiles.writeSnapshot(w, top, band);
        dogfightMissiles.writeSnapshot(w, top, band);
        warheads.writeSnapshot(w, top, band);
        beams.writeSnapshot(w, top, band);
        drops.writeSnapshot(w, top, band);
        // 九个状态对象 + 随机源
        player.writeSnapshot(w, top, band);
        status.writeSnapshot(w);
        combo.writeSnapshot(w);
        overload.writeSnapshot(w);
        hitStop.writeSnapshot(w);
        wave.writeSnapshot(w);
        shopRun.writeSnapshot(w);
        stats.writeSnapshot(w);
        trend.writeSnapshot(w);
        rng.writeSnapshot(w);
        writeRunScalars(w);
        return w.toByteArray();
    }

    /**
     * 把一份字节套回本局。<b>要么整套上，要么一套都不上</b>——这条保证是这里存在的唯一理由：
     * {@code captureRun()} 先抓一份"退回点"，中途任何一格验不过就按它整套重放，于是失败的那次读档
     * 既不会留下半套状态，也不会毁掉玩家正在打的这一局。
     *
     * <p><b>模式不在字节里</b>（真源是槽位账本那枚 packed meta，见 {@link Snapshot} 第 1 条），所以
     * 读档入口的次序是"先 {@link #applyMode} 再套字节"，这件事由 {@link #applyPendingLoad()} 负责，
     * 不在本方法里读账本——本方法只管字节。
     *
     * @return false = 这份字节不合法（{@link Buf.Invalid}），本局已退回套它之前的状态
     */
    public boolean applyRun(byte[] data) {
        if (data == null) return false;
        final byte[] undo = captureRun();
        try {
            readAll(data);
        } catch (Buf.Invalid bad) {
            try {
                readAll(undo);
            } catch (Buf.Invalid impossible) {
                // 自己刚写的字节被自己的读端拒了＝编解码不对称（写与读的段序分叉了）。那是码错，
                // 不是玩家的档坏； swallow 它会留下一局混合状态且没有任何一行说它发生过。
                impossible.addSuppressed(bad);
                throw impossible;
            }
            return false;
        }
        afterApply();
        return true;
    }

    /**
     * 按下「存档」：拍字节 → 改槽位账本 → 把字节交给 IO 线程。
     *
     * <p>三步的次序有条件。<b>字节先产出来</b>：只有它存在了才配登记一个槽（反过来是先承诺后落盘，
     * 掩码会说"有档"而盘上什么都没有）。<b>账本在提交之前就改对</b>：列表页画"有档/空格"读的是掩码
     * 而不是介质（那正是 {@link SaveSlots} 存在的理由），慢一帧就是"点了没反应"。IO 排在该槽后面，
     * {@code submitWrite} 之后那份字节归 IO 线程，本类再也不碰它。
     *
     * @param slot        目标槽；{@link SaveSlots#NONE} = 自己找第一个空槽
     * @param epochMillis 存档时刻。<b>由调用方给</b>——本类一次挂钟读取都没有（那条纪律的原文在
     *                    {@link SaveSlots#occupy}；要时刻的人在场外，见批2-c 的接线账）
     * @return 落成的槽号；{@link SaveSlots#NONE} = 通路没注入、槽号非法，或<b>50 槽已满</b>
     *         （满槽是拒绝，不自动淘汰最旧那份——腾位是玩家的显式动作）
     */
    public int saveRun(int slot, long epochMillis) {
        if (saveSink == null) return SaveSlots.NONE;
        int target = slot == SaveSlots.NONE ? saveSlots.allocateFree() : slot;
        if (target < 0 || target >= SaveSlots.CAPACITY) return SaveSlots.NONE;
        byte[] data = captureRun();
        int waveNo = wave.wave();
        saveSlots.occupy(target,
                SaveSlots.packMeta(mode, mode.chapterForWave(waveNo), waveNo, (int) score),
                epochMillis);
        saveSlots.commit(store);
        saveSink.submitWrite(target, data);
        return target;
    }

    /**
     * 删掉一份档：改账本 → 落账 → 把删除交给 IO 线程。三件的次序与 {@link #saveRun} 同一条理由——
     * "哪些槽有档"的真源是掩码，列表页画它而不是画盘，所以账本先对，玩家抬手那一刻就看到这一行变空。
     *
     * <p>没注入通路 / 槽号非法 / 那一格本来就空，三种都<b>静默不做</b>：存档页的命中端
     * （{@code SaveScreen.tappable}）本来就不受理这三形，走到这里等于有人绕过了面板。
     * 与写侧同样<b>不撤回</b>：IO 那边失败时掩码说"没了"而盘上还留着字节，代价见池件 §8 第 5 条那条挂账。
     */
    public void deleteRun(int slot) {
        if (saveSink == null) return;
        if (slot < 0 || slot >= SaveSlots.CAPACITY) return;
        if (!saveSlots.isOccupied(slot)) return;
        saveSlots.release(slot);
        saveSlots.commit(store);
        saveSink.submitDelete(slot);
    }

    /**
     * 按下存档页某一行：投递一次读意图，<b>不等它</b>。结果落在 {@code AsyncSaveSink} 那枚引用槽里，
     * 由 {@link #applyPendingLoad()} 在安全点取回。
     *
     * <p>同一时刻只允许一份在飞：那枚槽只有一个格，第二次投递会把第一份的字节盖掉，而槽号配对
     * 随之消失——被盖掉的玩家那一次点击就成了"点了没反应"。挡住比调和便宜。
     */
    public void requestLoad(int slot) {
        if (saveSink == null || loadingSlot >= 0) return;
        if (slot < 0 || slot >= SaveSlots.CAPACITY) return;
        loadingSlot = slot;
        saveSink.requestRead(slot);
    }

    /** 有没有一份档正在等回来（存档页那一行"读取中…"的读数）。 */
    public boolean loadInFlight() {
        return loadingSlot >= 0;
    }

    /** 读走"上一次读档失败"这一次边沿：页面提示完就清，不留成常驻状态。 */
    public boolean takeLoadFailure() {
        boolean failed = loadFailed;
        loadFailed = false;
        return failed;
    }

    /**
     * 反向两段式的后半：{@code frame} 在安全点抽一次。
     *
     * <p><b>位置只有一处：{@code consumeInput()} 之后、{@code time.advance()} 之前。</b>那里的仿真刚收完
     * 输入、还没推进任何一步，套一套新状态不会踩在任何一次判定中间；放进那个补帧 for 里是错的
     * （一步一半）。
     *
     * <p><b>只在 {@code time.isPaused()} 为真时动手</b>。读的结果要是回来得比页面关闭还晚，那一格就
     * 继续留在槽里等下一次冻住——半路把一套状态盖到正在打的局上，玩家会看见自己的船瞬移而没有任何
     * 一次点击解释它。这里不取走就等于不产生失败：槽还在，{@link #loadInFlight()} 照实说"在等"。
     *
     * @return true = 取回了一份<b>并且整套上了</b>。调用方（{@link #frame}）因此知道该收页——
     *         页面上那一行"读取中…"只有本类能回答"它结束了且成功了"，让面板自己猜时刻就是假进度。
     *         失败与"这一帧没动手"都返回 false，两者由 {@link #takeLoadFailure()} 那枚边沿区分。
     */
    private boolean applyPendingLoad() {
        if (loadingSlot < 0 || !time.isPaused()) return false;
        AsyncSaveSink.Read read = saveSink.pollRead();
        if (read == null) return false;
        loadingSlot = -1;
        long meta = saveSlots.meta(read.slot);
        // 模式不进字节（真源是账本 meta 的 mode/tier 两位；章号由还原出来的波号推出），所以它得在
        // 套字节之外单独换一次。⚠ 它**不在 undo 那份字节里**，于是失败时也必须自己退回——否则"读档没成功"
        // 的局会留下一件没人按过的改动：hp、敌数、掉落乘子从此按别人那一档的规则跑。
        // 介质读回 null（那一槽其实没字节）也走同一条失败路：页面报"这一份读不出来"。
        Mode previous = mode;
        Tier tier = Tier.at(SaveSlots.tierOf(meta));
        applyMode(SaveSlots.modeOf(meta) == 1 ? Mode.campaign(tier) : Mode.endless(tier));
        if (applyRun(read.data)) {
            loadFailed = false;
            return true;
        }
        applyMode(previous);
        loadFailed = true;
        return false;
    }

    /** 读端总装：段序与 {@link #captureRun()} 逐项镜像，最后断言<b>一口不剩</b>。 */
    private void readAll(byte[] data) {
        Buf.Reader r = new Buf.Reader(data);
        Snapshot.readHeader(r);
        int top = metrics.battleTop();
        int band = metrics.battleHeight();
        enemies.restoreSnapshot(r, top, band);
        shots.restoreSnapshot(r, top, band);
        hostile.restoreSnapshot(r, top, band);
        missiles.restoreSnapshot(r, top, band);
        dogfightMissiles.restoreSnapshot(r, top, band);
        warheads.restoreSnapshot(r, top, band);
        beams.restoreSnapshot(r, top, band);
        drops.restoreSnapshot(r, top, band);
        player.restoreSnapshot(r, top, band);
        status.restoreSnapshot(r);
        combo.restoreSnapshot(r);
        overload.restoreSnapshot(r);
        hitStop.restoreSnapshot(r);
        wave.restoreSnapshot(r);
        shopRun.restoreSnapshot(r);
        stats.restoreSnapshot(r);
        trend.restoreSnapshot(r);
        rng.restoreSnapshot(r);
        readRunScalars(r);
        if (r.remaining() != 0) {
            throw new Buf.Invalid("snapshot leaves " + r.remaining()
                    + " bytes unread - write and read segments no longer mirror each other");
        }
    }

    /** {@code Game} 自己那一段：只有标量。模式与章号不在这里（{@link Snapshot} 类注释的不存清单）。 */
    private void writeRunScalars(Buf w) {
        w.writeFloat(world);
        w.writeLong(score);
        w.writeInt(chips);
        w.writeInt(coins);
        w.writeFloat(coinCarry);
        w.writeFloat(chipCarry);
        w.writeInt(bombs);
        w.writeBoolean(runOver);
        w.writeInt(designatedSlot);
        w.writeLong(designatedBorn);
        w.writeInt(lastFlowPhase);
        w.writeBoolean(shopPending);
    }

    /** 先全部验完再一次性赋值（与各个 {@code restoreSnapshot} 同一条纪律：不被拒时不留半套）。 */
    private void readRunScalars(Buf.Reader r) {
        float worldSecs = r.readFloat();
        long sc = r.readLong();
        int ch = r.readInt();
        int cn = r.readInt();
        float cc = r.readFloat();
        float chc = r.readFloat();
        int bm = r.readInt();
        boolean over = r.readBoolean();
        int dSlot = r.readInt();
        long dBorn = r.readLong();
        int flowPhase = r.readInt();
        boolean pending = r.readBoolean();

        if (!Float.isFinite(worldSecs) || worldSecs < 0f) throw new Buf.Invalid("world " + worldSecs);
        if (sc < 0L) throw new Buf.Invalid("score " + sc);
        if (ch < 0 || cn < 0 || bm < 0) throw new Buf.Invalid("negative chips/coins/bombs");
        // 两枚余量的定义就是"取整之后剩下的那点"（applyCollected 每次减去取出的整数枚），所以恒在
        // [0,1)。越界不是玩家数据歪，是布局错位——夹住它等于把一次错位读成"金币少了几枚"那种查不出来的小偏差。
        if (!(cc >= 0f && cc < 1f) || !(chc >= 0f && chc < 1f)) {
            throw new Buf.Invalid("carry out of range: coin " + cc + " chip " + chc);
        }
        if (dSlot != MissileBehavior.NO_DESIGNATED && (dSlot < 0 || dSlot >= enemies.capacity())) {
            throw new Buf.Invalid("designated slot " + dSlot + " out of " + enemies.capacity());
        }
        if (flowPhase < WaveFlow.PREP || flowPhase > WaveFlow.VICTORY) {
            throw new Buf.Invalid("lastFlowPhase " + flowPhase);
        }

        world = worldSecs;
        score = sc;
        chips = ch;
        coins = cn;
        coinCarry = cc;
        chipCarry = chc;
        bombs = bm;
        runOver = over;
        designatedSlot = dSlot;
        // 没指定时那一枚 born 一律归零：写侧可能带着上一次指定的残值（designateSlot 只改槽号那一支
        // 会顺手写 born，清空那一支不一定写过 born），留着它就是留一个"看着像身份"的数。
        designatedBorn = dSlot == MissileBehavior.NO_DESIGNATED ? 0L : dBorn;
        lastFlowPhase = flowPhase;
        shopPending = pending;
    }

    /**
     * 套完字节之后要把<b>不在字节里的那几格</b>重新对齐，四件事各有一条不做的后果：
     *
     * <ul>
     *   <li>{@code chain.clear()}：弹链里还排着上一段那一次扣扳机没吐完的几发。不丢就会在还原后的
     *       位置上补射出一次"我从没按过的第二发"。</li>
     *   <li>震屏／受伤闪白／扫描闪白归零：它们是某一<em>瞬间</em>的冲击，跨一次存档没有意义。留着
     *       等于读档后画面自己抖一下。</li>
     *   <li>血/盾残影重播种：残影长度＝"上一条读数减这一条"，而上一条属于段前的玩家。不重播种，
     *       读档第一眼会看见一条从别的血量拖过来的长尾。</li>
     *   <li>走位锚点与欠账重对齐到还原后的位置：锚点是<b>手指</b>位置的参考系，不重对齐的话玩家按住
     *       的手指会在下一帧把船从新位置拖回旧位置（那正好抵消掉这次还原）。</li>
     * </ul>
     *
     * <p>手指本身不断（{@code movePointerId} 留着）：读档时玩家大概率还按着，抬起才算结束。
     */
    private void afterApply() {
        chain.clear();
        shake = 0f;
        hurtFlash = 0f;
        sweepFlash = 0f;
        hpTrail = player.hpRatio();
        shieldTrail = player.shieldRatio();
        for (int i = 0; i < collected.length; i++) collected[i] = 0;
        anchorX = player.x;
        anchorY = player.y;
        drag.clear();
    }

    /**
     * 是否 debug 构建（FLAG_DEBUGGABLE），由 plat 在启动时注入。唯一用途是暂停页那枚「调试」入口：
     * 正式包里它既不画也不受理。值在这里落地，几何投递时随 {@link PauseScreen#layout} 传给面板
     * 决定第二行的格数（见 {@link #debugBuild}）。
     */
    public void setDebugBuild(boolean debugBuild) {
        this.debugBuild = debugBuild;
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
        HudLayout.schoolRect(m.battleHeight(), schoolDraw);
        Widgets.hitRect(schoolDraw, hitBoxLogic, schoolHit);
        schoolHit.offsetInPlace(0, m.hudTop());
        // 雷达屏**不做 48dp 外扩**（与上面三枚键的唯一差别）：它的命中框要是比绘制框大，
        // "落在框内就不登记走位锚点"那条就会咬掉战场顶边外面一圈本来该用来走位的落点。
        // 绘制框本身是 {@code HudLayout.RADAR_SIDE} = 48 逻辑格，够点。
        HudLayout.radarRect(radarDraw);
        radarHit.set(radarDraw.left, radarDraw.top, radarDraw.right, radarDraw.bottom);
        radarHit.offsetInPlace(0, m.hudTop());
        result.layout(hitBoxLogic, m.pageTop(result.pageHeight()), m.logicH);
        // 四张固定页各自报整页高，居中原点由 pageTop 给（口径同结算页，不跟着战场长高）
        menu.layout(hitBoxLogic, m.pageTop(menu.pageHeight()), m.logicH);
        growthPage.layout(hitBoxLogic, m.pageTop(growthPage.pageHeight()), m.logicH);
        savePage.layout(hitBoxLogic, m.pageTop(savePage.pageHeight()), m.logicH);
        campaignPage.layout(hitBoxLogic, m.pageTop(campaignPage.pageHeight()), m.logicH);
        // 面板走整张逻辑画布坐标（要在 320~560 上分账图表与卡片），另需安全区让它从挖孔带让开
        layoutPausePanel();
        shop.layout(m.logicH, m.safeTop, m.safeBottom, hitBoxLogic);
        debug.layout(m.logicH, m.safeTop, m.safeBottom, hitBoxLogic);
        particles = new ParticlePool(QualityProfile.particleBudget(qualityTier));
        background.rebuild(Screen.LOGIC_W, m.logicH, qualityTier, 1);
        // 网格尺寸跟着画布走：高度是随屏幕比例算出来的，不重建就会在底部漏掉一整排
        // 覆盖半径读的是 SpatialGrid 的那个常量——战斗部覆盖不变量的单测同读一处，
        // 测试里再写一遍 40 就立了第二个真源。
        grid = new SpatialGrid(Screen.LOGIC_W, m.logicH, enemies.capacity(),
                SpatialGrid.DEFAULT_MAX_CENTER_DISTANCE);
    }

    /**
     * 暂停面板的版面，两处调用点共用这一份实参：{@link #installGeometry}（尺寸或安全区变了）与
     * {@link #openPause}（**模式变了**）。后者是必须的一条：第三行摆两格还是三格取决于
     * {@code mode.campaign}，而换一局（{@code resetRun}）不重投 surface，只靠装几何那一处就会让
     * 无尽局拿着上一局战役局的版面画出一枚点不动的「存档」。
     */
    private void layoutPausePanel() {
        pause.layout(metrics.logicH, metrics.safeTop, metrics.safeBottom, hitBoxLogic, debugBuild,
                mode.campaign && saveAvailable());
    }

    /**
     * surface 就绪附带的那一局，开完**停在主菜单上**：冷启动直进波次 1 算缺口
     * （2026-09-29 用户裁定）。菜单整页盖住战场，所以这里把模拟冻住——不冻的话菜单背后那一局
     * 照样在打波次、照样能死，然后结算页会盖到菜单上面去。解冻只有 {@code resetRun} 一条路
     * （「开始新一局」走它），与暂停页那条定格态同一口径。
     */
    private void bootRun() {
        resetRun(Mode.CAMPAIGN);
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
        // 配额挂在**帧**上而不是步上：下面那个 for 一帧能跑 0..N 步，在步里归零等于
        // "补帧的那几步各自有 10 声额度"，一次卡顿会放出几十声。
        audio.beginFrame();
        // 输入**每帧一次、必须在 advance 之前**：Time 一被 setPaused(true) 就返回 0 步，
        // 挪进步骤循环等于按下暂停那一刻起再也收不到任何事件——面板上的"继续作战"永远点不动。
        consumeInput();
        // 读档的交回点。它必须紧贴 consumeInput 之后、advance 之前，且只在冻住的世界里动手，
        // 两条理由都写在 applyPendingLoad 上。
        if (applyPendingLoad()) {
            onLoadLanded();
        } else if (takeLoadFailure()) {
            // 边沿读走即清：这一句只报一次，之后留在页上会被下一次动作误读成"还没失败"
            savePage.showStatus(SaveScreen.STATUS_LOAD_FAILED);
        }
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
        audio.setBgm(bgmTrackNow());
        render(canvas);
    }

    /**
     * 这一帧该放哪条 BGM。每帧重算、不追边沿：三条轨的归属完全由"栈顶是哪一屏 + 正在打的
     * 是不是 Boss 波"派生，而"同轨不动"那一条判断只有一份、住在播放面那边。
     *
     * <p>暂停/商店/调试**不改轨**（它们是"打到一半"）：换轨在听觉里就是"这一局结束了"的信号，
     * 按一次暂停就听见一次终局是假话。主菜单、战役选关页、成长页、图鉴、设置、结算页归到同一条 MENU
     * 轨——那六屏都是"没在打"。选关页不必像存档页那样查栈：它只有一个宿主（从主菜单进），今天没有
     * "打到一半去选关"这条路。
     *
     * <p>存档页是这一族里唯一**宿主不固定**的一屏（暂停页与主菜单两处入口共用），所以它跟着栈里
     * 有没有菜单来定轨，而不是跟着自己：从菜单进来是"找一份档续玩"＝没在打；从暂停页进来还是
     * 打到一半。这一格不落，玩家在主菜单底下按「存档」会听见战斗乐——那正是上面那句假话的形状。
     */
    private int bgmTrackNow() {
        int top = modals.peek();
        if (top == ModalStack.MENU || top == ModalStack.GROWTH || top == ModalStack.RESULT
                || top == ModalStack.CODEX || top == ModalStack.SETTINGS
                || top == ModalStack.CAMPAIGN) {
            return AudioCues.BGM_MENU;
        }
        if (top == ModalStack.SAVE && modals.contains(ModalStack.MENU)) {
            return AudioCues.BGM_MENU;
        }
        return wave.currentWaveIsBoss() ? AudioCues.BGM_BOSS : AudioCues.BGM_PLAY;
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
            // 主菜单是这条链的根（结算页 → 主菜单 → 成长页／存档页），它下面没有"上一级"可回，
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
        if (top == ModalStack.DEBUG) {
            // 调试页叠在暂停页之上：返回键只把它掀回暂停页，时钟照旧冻着（同 closeDebug 的动作）
            closeDebug();
            return;
        }
        if (top == ModalStack.SAVE) {
            // 同调试页：掀回**宿主那一屏**（暂停页或主菜单）。存档页没有"回到战场"这枚出口，返回键若是走
            // 通用 pop 就会连 PAUSE 一起弹掉而**不解冻**（那条尾巴只认 pop 出来的是不是 PAUSE），
            // 玩家会被关在一块看不见的面板外。菜单宿主那一侧走的是"回到菜单"，同样不该解冻。
            closeSave();
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
        layoutPausePanel();   // 每次拉开重算一遍第三行：这一局是战役还是无尽，只在开面板这一刻一定是现值
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
            // 间歇 → 下一波出怪那条边沿就是"新一波开始了"：商店关掉之后玩家听见的第一声。
            // 第一波不走这里（{@code resetRun} 把基准钉在 PREP，开局那一声归 RUN_START）。
            if (lastFlowPhase == WaveFlow.INTERMISSION) audio.play(AudioCues.WAVE_START);
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

    // ---- 调试页（仅 debug 构建） --------------------------------------------------------------

    /**
     * 开调试页：叠在暂停页之上，与 {@code openShop(ENTRY_PAUSE)} 同一形态——时钟本就冻着，
     * 这里再冻一次是幂等。关掉它走 {@link #closeDebug} 只 pop 一层，玩家回到**同一块**暂停面板。
     */
    private void openDebug() {
        debug.open();
        modals.push(ModalStack.DEBUG);
        time.setPaused(true);
        dropMoveInput();
        debugAtUi = time.ui();
    }

    /** 掀回暂停页：只把 DEBUG 出栈，时钟不动（理由同 {@code closeShop} 的 {@code ENTRY_PAUSE} 分支）。 */
    private void closeDebug() {
        if (modals.peek() != ModalStack.DEBUG) return;
        modals.pop();
    }

    /**
     * 调试页报上来的动作。**状态真值全在 {@code Game}，面板只是视图**：
     * <ul>
     *   <li>{@code ACTION_TOGGLE}：就地翻转 {@link #debugPulseDogfight}——绘制时又传回面板，画成新的态。</li>
     *   <li>{@code ACTION_BACK}：{@link #closeDebug} 回暂停页，战场照旧定格。</li>
     *   <li>{@code ACTION_VICTORY}/{@code ACTION_SETTLE}：先清模态、解冻时钟，再调那两条真链
     *       （{@link #debugForceVictory}/{@link #debugEndRun}）。它们闸门只认栈顶 {@code NONE}——
     *       此刻 DEBUG 压着 PAUSE，不清干净就会被闸门挡回、什么都不发生。清完走的是与 F8/F9 直按
     *       完全同一条路径，取到的证才与真机一致。</li>
     * </ul>
     */
    private void onDebugPointerUp(int action) {
        if (action == DebugScreen.ACTION_TOGGLE) {
            debugPulseDogfight = !debugPulseDogfight;
            return;
        }
        if (action == DebugScreen.ACTION_BACK) {
            closeDebug();
            return;
        }
        if (action == DebugScreen.ACTION_VICTORY || action == DebugScreen.ACTION_SETTLE) {
            modals.clear();
            time.setPaused(false);
            markModalClosed();
            if (action == DebugScreen.ACTION_VICTORY) debugForceVictory();
            else debugEndRun();
        }
    }

    // ---- 存档页 -----------------------------------------------------------------------------

    /**
     * 开存档页：**两处入口共用**——暂停页那枚键与主菜单那枚（2026-10-11 他补的
     * 「补进主菜单，本件内做掉」）。叠在打开它的那一屏之上，与 {@link #openDebug} 同一形态——
     * 时钟本就冻着，这里再冻一次是幂等；从菜单进来栈是 {@code MENU → SAVE}，返回键掀回菜单。
     *
     * <p>{@code open} 递进去的是 {@link #saveSlots} 的<b>引用</b>不是副本：列表页画"哪一格有档"读的就是
     * 那本账，所以存完一份之后下一次绘制立刻见到新槽，不需要谁去通知页面（{@code shopSnap} 同一手法）。
     *
     * @param writable 宿主有没有"正在打的这一局"可拍：暂停页 true，主菜单 false。false 时页内那枚
     *                 写入键**不摆**（{@code SaveLayout.actionCell}），菜单侧因此只有载入与删除两格。
     */
    private void openSave(boolean writable) {
        savePage.open(saveSlots, writable);
        modals.push(ModalStack.SAVE);
        time.setPaused(true);
        dropMoveInput();
        saveAtUi = time.ui();
    }

    /** 掀回宿主那一屏：只把 SAVE 出栈，时钟不动（理由同 {@link #closeDebug}）。 */
    private void closeSave() {
        if (modals.peek() != ModalStack.SAVE) return;
        modals.pop();
    }

    /**
     * 存档页报上来的动作。<b>页只决定"哪一格"，改数据的三件都住在本类</b>：
     * <ul>
     *   <li>{@code ACTION_BACK}：{@link #closeSave} 掀回宿主那一屏（暂停页或主菜单），战场照旧定格。</li>
     *   <li>{@code ACTION_SAVE}：拍当前这一局。<b>挂钟时刻由这一侧递进去</b>——
     *       {@link #saveRun} 一次时钟读取都没有（那条纪律的原文在它的 {@code @param} 上）。
     *       成了就把那枚键的标签亮成「已存档」，五十格全满就把"先删或选一份覆盖"顶到 meta 那一格。</li>
     *   <li>{@code ACTION_LOAD}：投递一次读意图，<b>不等它</b>；回来的那一帧由 {@link #frame} 收页。</li>
     *   <li>{@code ACTION_DELETE}：删掉选中那一份。</li>
     * </ul>
     * 翻页与选中在 {@code SaveScreen.pressUp} 内部就消化完了（报 {@code ACTION_NONE}），所以这里看不见它们。
     */
    private void onSavePointerUp(int action) {
        if (action == SaveScreen.ACTION_BACK) {
            closeSave();
            return;
        }
        if (action == SaveScreen.ACTION_SAVE) {
            int landed = saveRun(savePage.selectedSlot(), System.currentTimeMillis());
            if (landed == SaveSlots.NONE) savePage.showStatus(SaveScreen.STATUS_FULL);
            else savePage.markSaved();
            return;
        }
        if (action == SaveScreen.ACTION_LOAD) {
            requestLoad(savePage.selectedSlot());
            return;
        }
        if (action == SaveScreen.ACTION_DELETE) {
            deleteRun(savePage.selectedSlot());
        }
    }

    /**
     * 一份档真的套上了之后收页：<b>整摞掀掉并解冻</b>，而不是掀回暂停页。
     *
     * <p>理由不是省一次点击，是"载入"这一动作的回话只有战场本身给得出——波次、分数、血盾、敌数全换了，
     * 玩家抬头就看见。停在暂停面板上等于拿一块板子回答"成功了吗"，答的还是别人那块板的读数。
     *
     * <p>{@link #markModalClosed} 那一条不能省：{@code modals.clear()} 之后栈顶是 {@code NONE}，
     * 而抬起「载入」的那根手指下一击紧跟着就来——战场手势那道闸门只看时刻不看来源
     * （理由写在 {@link #battlefieldGestureAllowed} 上），不补这一记就会被同一根手指多触发一次过载。
     */
    private void onLoadLanded() {
        modals.clear();
        time.setPaused(false);
        markModalClosed();
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
        audio.play(AudioCues.BUY);
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
        if (top == ModalStack.DEBUG) {
            debug.pressDown(x, y, time.ui() - debugAtUi, id);
            return;
        }
        if (top == ModalStack.SAVE) {
            savePage.pressDown(x, y, time.ui() - saveAtUi, id);
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
        if (top == ModalStack.CAMPAIGN) {
            campaignPage.pressDown(x, y, time.ui() - campaignAtUi, id);
            return;
        }
        if (pauseHit.contains(x, y)) {
            openPause();
            return;
        }
        // 底部那一排今天是**三枚**：〔体系〕〔模式(切枪)〕〔炸弹〕。判序 = 误判代价的排序，见 HudLayout.schoolRect：
        // 体系 ↔ 模式 走错了都只多按一次；而任何一枚走到炸弹那侧，代价是一件存了整波的手牌。所以炸弹**最后**判。
        // 两枚换枪键各自配一道"按了有没有得换"的条件（与炸弹那条"没存货就不注册命中"同一条口径 ——
        // 一颗点不动的按钮比没有按钮更糟）：体系键要**有第二个体系可去**，模式键要**本体系还有第二把**。
        // 手持激光或导弹时模式键不出现（那两个体系各只有一把枪），买了任一解锁卡之前体系键不出现。
        if (shopRun.schoolKeyLive(player.weaponId) && schoolHit.contains(x, y)) {
            selectWeapon(shopRun.nextSchoolWeapon(player.weaponId, player.lastGunOfSchool));
            return;
        }
        if (shopRun.modeKeyLive(player.weaponId) && switchHit.contains(x, y)) {
            selectWeapon(shopRun.nextWeaponInSchool(player.weaponId));
            return;
        }
        // 没有存货就不注册命中：一颗点不动的按钮比没有按钮更糟（规格点名的"点了没反应"bug 类）
        if (bombs > 0 && bombHit.contains(x, y)) {
            detonateBomb();
            return;
        }
        // 雷达屏排在走位锚点**之前**，且**不论点没点着敌点都吞掉这一跳**：他那条逐字是「按下即选、
        // 落在框内就不登记走位锚点」。吞掉的理由不是排序，是这一跳的意图已经算数了——点空 = 取消指定
        // （见 {@link #pickRadarTarget}），要是它再顺手把锚点登记到屏幕顶上，玩家一取消指定机体就跟着
        // 往上跳一格，两条意图互相踩。
        if (shopRun.radarOn() && radarHit.contains(x, y)) {
            pickRadarTarget(x - radarHit.left, y - (metrics.hudTop() + HudLayout.RADAR_TOP));
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
        } else if (top == ModalStack.DEBUG) {
            onDebugPointerUp(debug.pressUp(x, y, time.ui() - debugAtUi, id));
        } else if (top == ModalStack.SAVE) {
            onSavePointerUp(savePage.pressUp(x, y, time.ui() - saveAtUi, id));
        } else if (top == ModalStack.MENU) {
            onMenuPointerUp(menu.pressUp(x, y, time.ui() - menuAtUi, id));
        } else if (top == ModalStack.GROWTH) {
            onGrowthPointerUp(growthPage.pressUp(x, y, time.ui() - growthAtUi, id));
        } else if (top == ModalStack.CAMPAIGN) {
            onCampaignPointerUp(campaignPage.pressUp(x, y, time.ui() - campaignAtUi, id));
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
        } else if (action == PauseScreen.ACTION_DEBUG) {
            openDebug();   // 仅 debug 构建才会报出这个码（面板里那格 isLive 才受理）
        } else if (action == PauseScreen.ACTION_SAVE) {
            openSave(true);   // 同上，判据多一条：非战役局那一格压根不存在（§5.5 已裁）
        } else if (action == PauseScreen.ACTION_RESTART) {
            // 出口一级化（规格 §四）：重开直接生效，没有二次确认弹窗
            restartFade = 1f;
            resetRun(Mode.CAMPAIGN);
        } else if (action == PauseScreen.ACTION_MENU) openMenu();   // 复用结算页那条链。这里**故意不解冻**时钟：菜单页没有"回到这一局"的出口，出去只有 resetRun（它自己解冻），所以留在定格态 = "这一局已被放弃"的唯一真值；ui 时钟照走，菜单动画不受影响
    }

    /** 结算页的两个按钮（规格 §四 取消"点任意处重开"，防误触窗口挡在 {@code pressDown} 那侧）。 */
    private void onResultPointerUp(int action) {
        if (action == ResultScreen.ACTION_RETRY) {
            restartFade = 1f;
            resetRun(Mode.CAMPAIGN);
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

    /** 主菜单的三枚出口：进选关页挑一局、去成长页花芯片、开存档页续玩**哪**一份（他 10-11 自纠后用的就是"哪"）。 */
    private void onMenuPointerUp(int action) {
        if (action == MenuScreen.ACTION_RUN) {
            openCampaign();     // 战役选关页（批3-B）：这一枚键从今天起**不再直接开局**，理由见 openCampaign
        } else if (action == MenuScreen.ACTION_GROWTH) {
            openGrowth();
        } else if (action == MenuScreen.ACTION_SAVE) {
            // false：菜单底下没有"正在打的这一局"可拍（这一局已被放弃，理由见 openMenu），所以页内只有载入与删除
            openSave(false);
        }
    }

    /**
     * 开战役选关页：压在菜单之上，与 {@link #openGrowth} 同一形态（时钟本就冻着，这里再冻一次是幂等；
     * 返回键掀回菜单）。
     *
     * <p><b>主菜单那枚「开始新一局」的落点由"直接开局"改成了"进这一页"</b>——这不是把那条链换掉，
     * 是把它往后挪了一格：页的默认选中的正是「任务 1 ＋ 普通」，而那两者的开局波号与乘子逐字节等于
     * 今天{@link #resetRun(Mode) resetRun(Mode.CAMPAIGN)}的结果（{@code CampaignScreen.open} 的注释
     * 钉的就是这条）。所以老路径没丢，只是多了一屏可以按「返回」退出去。
     */
    private void openCampaign() {
        modals.push(ModalStack.CAMPAIGN);
        campaignPage.open();
        dropMoveInput();
        time.setPaused(true);
        campaignAtUi = time.ui();
    }

    /**
     * 选关页报上来的两枚出口。<b>页只管"选中的是哪一局"，改现场的全在本类</b>（口径同
     * {@link #onSavePointerUp}）：返回掀回菜单，开始任务才落进 {@link #resetRun(Mode, int)}。
     */
    private void onCampaignPointerUp(int action) {
        if (action == CampaignScreen.ACTION_BACK) {
            modals.pop();
            return;
        }
        if (action == CampaignScreen.ACTION_START) {
            restartFade = 1f;
            // 现读，不从动作码里带：面板换任务与换档都报 ACTION_NONE，选中值只有它自己知道
            resetRun(Mode.campaign(campaignPage.selectedTier()), campaignPage.selectedMission());
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
        if (top == ModalStack.DEBUG) {
            debug.pressDrag(x, y, time.ui() - debugAtUi, id);
            return;
        }
        if (top == ModalStack.SAVE) {
            savePage.pressDrag(x, y, time.ui() - saveAtUi, id);
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
        if (top == ModalStack.CAMPAIGN) {
            campaignPage.pressDrag(x, y, time.ui() - campaignAtUi, id);
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
     * <p>这一处同时是**持有量的闸门**：键盘 1..6 与底部两枚换枪键都汇到这里，所以"没买过的枪按下去
     * 会不会响"只有一条答案。⚠ 它今天才开始拦键盘——原先六把枪全都能按，而其中两把从来没在
     * 商店里卖过（{@code ShopRun.weaponUnlocked}），于是键盘是唯一的获取途径、触屏压根没有。
     * 拦完之后键盘与触屏读的是同一个持有集合：调试要先看到导弹，就去商店买那一张「基础导弹」，
     * 而不是在这里给键盘留一条绕过闸门的近路（留了就有两个真源，而"哪把枪能按"正是这次出问题的地方）。
     *
     * <p>玩家侧的知情渠道今天有两条，且**都是真的**：① 没买的那两把**进不了循环**，所以底部那两枚键
     * 在持有量不足时整块不出现（判据 {@code ShopRun.schoolKeyLive} / {@code modeKeyLive}，见
     * {@code onPointerDown}）——键的出现本身就在报"你手里有几个体系、这个体系里有几把"；
     * ② 按了一把买不到的（键盘那条路）响一声 {@code DENIED}。⚠ 原先这段写的是"面板把没持有的
     * 那几格灰掉"，**那句没有对应实现**（{@code weaponUnlocked} 的读者只有这道闸门、{@code resetRun}
     * 的钳位与测试，商店面板一个都不读）——2026-10-07 拆两级时按现状改口，别按下不表留给下一个人当真。
     */
    private void selectWeapon(int id) {
        if (id < 0 || id >= Balance.weapons.length || id == player.weaponId) return;
        if (!shopRun.weaponUnlocked(id)) {
            // 按了一把没买过的枪：这一声是这个闸门的**可听出口**，也是键盘那条路唯一的出口——
            // 1..6 是按 id 直指的，体系键那套"没得换就不出现"的可见性救不了它。
            audio.play(AudioCues.DENIED);
            return;
        }
        player.weaponId = id;
        // 体系记忆：这一把就是"该体系里上一次手持的那把"。写在这道唯一的入口上，键盘与触屏
        // 两条路才不会各记各的（判据同一条：只要武器换成了手里这一把，就盖一次）。
        player.lastGunOfSchool[Balance.weapons[id].school] = id;
        audio.play(AudioCues.WEAPON_SWITCH);
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
        // [规格 §8.2] 小怪 hp 的档位乘子只走这一格（§8.5 那条"Boss 豁免"就靠它成立：initBoss 写的是
        // 另一条独立数值，读的是 bossHpFor 那条 1.45^n，不经过这里）。普通档 1.00f 乘法精确 ⇒ 与今天逐字节同值。
        // [规格 §5.5] 无尽侧还有第二轴 {@code hpGrowth}（战役侧恒 1）。两轴相乘在这里是安全的，因为
        // "战役不吃增长、无尽恒跑普通档"这条不变式使任一模式下都只有一枚非 1——它由 ModeTest 钉住。
        e.hp = e.maxHp = Math.round(spec.hp * mode.tier.hpMul * mode.hpGrowth(wave.wave(), kind));
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
        audio.play(AudioCues.BOSS_SPAWN);
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
        audio.play(AudioCues.ENEMY_FIRE);
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
        audio.play(AudioCues.ENEMY_FIRE);
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
     * <p>{@code made} 是 **shots／missiles／beams 三条分支的合计**（{@link BulletChain#step} 的口径），
     * 所以这一处记账覆盖全部实发。只记 shots 侧的话 {@code shotsHit} 会大过 {@code shotsFired}，
     * 而 {@code RunStats.accuracy()} 只防除零不防反向、{@code Rating.clamp01} 与 {@code RunSummary}
     * 再钳两次 ⇒ 结算页安静地显示"命中率 100%"，全链路没有任何东西会红。
     */
    private void stepChain(float dt) {
        int made = chain.step(dt, player.x, noseY(), shots, missiles, beams);
        // 记账挪到**实际出膛**处：计划发数与真的打进池里的发数从此不可能对不上。
        if (made > 0) {
            stats.addShots(made);
            // 一帧一声，不按发出几声响几次：散射一档一帧能排五发，逐发响等于把开火听成一串炸豆。
            audio.play(fireCueOf(chain.generationWeaponId()));
        }
        stepEvictions();
    }

    /**
     * 枪种 → 开火声。读的是**代内冻结**的武器号（{@link BulletChain#generationWeaponId}），
     * 不是 {@code player.weapon()}：链横跨多帧，换枪那一帧会把新枪的声音安到上一代的弹上。
     */
    private static int fireCueOf(int weaponId) {
        return switch (weaponId) {
            case Balance.Weapon.LASER -> AudioCues.FIRE_LASER;
            case Balance.Weapon.SHOT -> AudioCues.FIRE_SPREAD;
            case Balance.Weapon.MISSILE -> AudioCues.MISSILE_LAUNCH;
            case Balance.Weapon.ARC -> AudioCues.FIRE_ARC;
            case Balance.Weapon.BOOMER -> AudioCues.FIRE_SPIN;
            default -> AudioCues.FIRE_PULSE;
        };
    }

    /**
     * 满池淘汰：被踢的那一枚**只播自毁动画**——不展开战斗部、不结算伤害、不计命中（R22 裁的
     * "静默"静的是**结算**这一半，不是画面）。于是"永不拒发"与"没有弹会无声消失"同时成立：
     * 按下去那一下永远会出弹，被踢的那枚在屏幕上留下一团**哑火**小烟（{@link #spawnMisfire}），
     * 读得出"它是没了、不是打完了"。
     * <p>⚠ 与 R13 **刻意分路**：空视场自爆走 {@link #detonateMissile} 照常展开并结算（那半是弹自己
     * 打完了这一发），这里不借那条路——合回一个调用就等于把 R22 的裁定吃掉。
     * <p>⚠ 必须紧接 {@code chain.step}：{@link Missiles#evicted} 是**池外的唯一副本**，同一次排放里
     * 再淘汰一枚就把它覆盖；那条路走不到（{@code capacity} 1.7× 余量 + {@code MissilesTest} 钉着）。
     */
    private void stepEvictions() {
        long total = missiles.evictTotal();
        if (total == evictSeen) return;
        evictSeen = total;
        spawnMisfire(missiles.evicted.x, missiles.evicted.y);
    }

    /**
     * 导弹这一帧要走的全部四步：先校验雷达屏上的指定，再两条流各积分一次，最后格斗流补一次发射。
     *
     * <p>{@link #stepDesignation} 排最前是它必须读本步的**新**敌表（敌人在上一步刚被打死、
     * 槽位刚被复用），又要在 {@code beginFrame} 之前——否则弹拿着一个上一帧的死槽号装订。
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
        stepDesignation();
        stepPool(missiles, missileBehavior, dt, designatedSlot);
        // 格斗流**永远收 NO_DESIGNATED**（他 2026-10-03 逐字「目标给普通导弹用，格斗弹不用」）：
        // 这道闸写在调用处而不是行为类里加一条按流别的分支，是因为两流的飞行层本来同形——
        // 让"格斗弹看不见指定"成为一次传参，共用代码里就不会多出一个人人都要绕的 if。
        stepPool(dogfightMissiles, dogfightBehavior, dt, MissileBehavior.NO_DESIGNATED);
        // 发射排在**本流全部 advance 之后**：tryLaunch 读的占用戳要包含"这一帧刚锁上的那些"，
        // 早一步就会朝同一只敌机在同一帧里出第二枚（判序写在 MissileBehavior#tryLaunch 的方法头）。
        if (shopRun.dogfightOn() && (player.weapon().school == Balance.Weapon.SCHOOL_MISSILE
                || debugPulseDogfight)) fireDogfight();
    }

    /**
     * 「死了即清空」（他 2026-10-03 逐字）。每帧一次、O(1)，不做跨帧残留也不做队列：
     * 指定是一块屏上的状态，屏上的点没了它就该跟着没。
     *
     * <p>判据三条，一条不多一条不少（口径抄 {@link Enemies#isLive} 那条注释自己写的完整式子）：
     * ① 还在活跃表里 —— 槽位会被回收，光 {@code hp > 0} 会把"接手这一格的下一只"读成"指定还有效"；
     * ② {@code born} 对得上 —— 这一条才是"还是我点的那一只"的唯一署名；
     * ③ 血没归零 —— 摘表延后到 {@link #reapDead}（那是刻意的，见那里），所以归零的敌在本步仍在表里。
     *
     * <p>⚠ {@link MissileBehavior#advance} 里读指定时**还会再判一次** ①③。这不是重复：那里判的是
     * "本步刚出膛的这一刻它还能不能用"，而 {@code stepEnemies} 与弹道积分之间隔着一次
     * {@link #spawnQueued}，任何一道闸改了敌表都得让下游自己确认，不能让上游的承诺跨越三步还有效。
     */
    private void stepDesignation() {
        if (designatedSlot == MissileBehavior.NO_DESIGNATED) return;
        Enemies.Enemy e = enemies.objAt(designatedSlot);
        if (!enemies.isLive(designatedSlot) || e == null || e.born != designatedBorn || e.hp <= 0) {
            designatedSlot = MissileBehavior.NO_DESIGNATED;
        }
    }

    /**
     * 雷达屏上的一点 → 指定谁。坐标是**雷达本地**的（原点 = 方块左上角，换算见 {@link #onPointerDown}）。
     *
     * <p>取容差内最近的那只（{@link RadarLayout#nearestOf}），一个都没进容差 ⇒ **取消指定**：
     * 他给的口径是「死了即清空」，"点空了也清空"是同一条意图的另一半——屏上是玩家在下指令，
     * 替他挑一只他没说过的属于越权。
     */
    private void pickRadarTarget(float lx, float ly) {
        int n = enemies.activeCount();
        if (radarXs.length < n) {
            radarXs = new float[n];
            radarYs = new float[n];
            radarSlots = new int[n];
        }
        int slots = 0;
        for (int i = 0; i < n; i++) {
            Enemies.Enemy e = enemies.activeAt(i);
            if (e == null || e.hp <= 0) continue;      // 血已归零但还没摘表的那几只不该可点
            radarXs[slots] = radarLocalX(e);
            radarYs[slots] = radarLocalY(e);
            // ⚠ 第三张草稿是**必须**的：上面那个 continue 让"可点的那几只"的下标与活跃表下标脱钩，
            // nearestOf 返回的是**草稿数组**的下标，直接拿去 slotOfActive 会点到隔壁那只。
            radarSlots[slots] = enemies.slotOfActive(i);
            slots++;
        }
        int hit = RadarLayout.nearestOf(radarXs, radarYs, slots, lx, ly, Balance.radar.hitTolerance);
        if (hit < 0) {
            designatedSlot = MissileBehavior.NO_DESIGNATED;
            return;
        }
        designatedSlot = radarSlots[hit];
        Enemies.Enemy picked = enemies.objAt(designatedSlot);
        designatedBorn = picked == null ? 0L : picked.born;
        // 指到才响：那一格 `hit < 0` 的出口是"清了指定"，给声音等于告诉玩家"点中了什么"。
        audio.play(AudioCues.RADAR_CUE);
        if (haptics != null) haptics.tick();
    }

    /**
     * 战场 → 雷达本地的横换算。**绘制端与点选端共用这两条**：各写一遍的话，两者一旦漂了
     * 就是"点明明在那个点上、按下去却清了空"，那种 bug 只在真机上露头、单测钉不住。
     */
    private float radarLocalX(Enemies.Enemy e) {
        float side = HudLayout.RADAR_SIDE;
        return RadarLayout.clampToSquare(RadarLayout.toLocalX(e.x, Screen.LOGIC_W, side), side);
    }

    /** 纵的那条：原点减 {@code battleTop}（战场从 HUD 之下起算，而雷达量的是整块战场）。 */
    private float radarLocalY(Enemies.Enemy e) {
        float side = HudLayout.RADAR_SIDE;
        return RadarLayout.clampToSquare(
                RadarLayout.toLocalY(e.y - metrics.battleTop(), metrics.battleHeight(), side), side);
    }

    /**
     * 一流弹的相位积分 + 三路判据。**两条流各调一次**，方法头那条判序对两流同形（差异全部
     * 由 {@link MissileBehavior} 实例的 stream 在 {@code beginFrame} 里解析成有效值，这里不读流别）。
     * 唯一的例外是 {@code designated}：它由**调用处**给（格斗流恒给"没人被指定"），本方法自己不分岔。
     *
     * <p>索敌与引信都**不碰 {@link SpatialGrid}**：网格的覆盖半径只有 {@code ring×CELL = 48px}，
     * 撑不到导引头的射程，用它只会得到"视野内的一小撮"⇒ 静默漏锁。这里扫的是活跃敌表
     * （≤ {@code Balance.wave.maxAlive} = 26），既正确又比建表+查询便宜。网格只留给
     * {@link #stepWarheads} 那条**近距**接触采样。
     */
    private void stepPool(Missiles pool, MissileBehavior behavior, float dt, int designated) {
        Balance.Missile spec = Balance.missile;
        behavior.beginFrame(pool, enemies, spec, shopRun, designated);
        // 弹体撞击档的**伤害源身份**：两流的分别住在池上（见 {@link MissileBehavior#STREAM_DOGFIGHT}
        // 那条"为什么不挂布尔到弹身"的理由），所以这里按"这一池是哪一流"解析一次，不按枚解析。
        // 它今天还不改变任何数字（场上没有带子弹护甲的目标），但身份必须在结算点就拿得到，
        // 否则将来格斗弹打敌弹那一格要回头补第二套判据。
        final int bodySource = behavior.stream() == MissileBehavior.STREAM_DOGFIGHT
                ? DamageRules.SRC_DOGFIGHT_BODY : DamageRules.SRC_MISSILE_BODY;
        for (int i = pool.activeCount() - 1; i >= 0; i--) {
            Missiles.Missile m = pool.activeAt(i);
            int flags = behavior.advance(m, spec, dt, enemies,
                    Screen.LOGIC_W, metrics.logicH, MARGIN);
            // 只认第一次开眼：FLAG_RELOCKED 是"换了一只"，同一枚弹连响两声会听成两枚。
            if ((flags & MissileBehavior.FLAG_ACQUIRED) != 0) audio.play(AudioCues.LOCK_ON);
            // 三路同归一条引爆（方案里编号 R13）：出处 = 记录 L14773 answers-OPT，
            // UTC 2026-09-25T09:29:00.463Z = 本地 2026-09-25 17:29:00，选项标签「自爆也展开，与撞敌同路」
            // ⇒ 选这一档是他的决定，但这句标签是我的措辞。三路合流后差别只在**记账**——撞敌/引信算这一发
            // 命中，空视场自爆不算；这条记账口径是我定的，他没谈过命中怎么算。
            if ((flags & MissileBehavior.FLAG_DETONATE) != 0) {
                detonateMissile(m, true, behavior.fusedSlot(), bodySource);
            } else if ((flags & MissileBehavior.FLAG_SELF_DESTRUCT) != 0) {
                // 自爆这一路必然没撞到人（引信那一句排在它前面，撞到了就走上面那条），所以槽号给 -1
                detonateMissile(m, false, -1, bodySource);
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
        int slot = dogfightBehavior.tryLaunch(player.x, noseY(), enemies);
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
        audio.play(AudioCues.MISSILE_DOGFIGHT);
        stepDogEvictions();
    }

    /** 格斗池的满池淘汰：与 {@link #stepEvictions} 同一条 R22 口径（只播动画、不结算），也同一团哑火。 */
    private void stepDogEvictions() {
        long total = dogfightMissiles.evictTotal();
        if (total == dogEvictSeen) return;
        dogEvictSeen = total;
        spawnMisfire(dogfightMissiles.evicted.x, dogfightMissiles.evicted.y);
    }

    /**
     * 机头（= 出膛点）的 y。**弹链排放与格斗弹发射共用这一个读数**：画面上"弹从哪儿出来"必须
     * 是同一个点，抄两份的话其中一份改了另一份不会跟着改，玩家看到的是同一把枪的两个枪口。
     */
    private float noseY() {
        return player.y - 8f;
    }

    /**
     * 引爆这一发。三条出口由 {@link WarheadRules#detonationRoute} 判（判据不在这里，理由见那里）。
     *
     * <p>亮线形状与伤害档位出自记录 L14750 feedback(status=rejected)（真人自敲），
     * UTC 2026-09-25T09:19:51.727Z = 本地 2026-09-25 17:19:51，他的字「命中接近后展开连续杆战斗部
     * （在这里是垂直于导弹的一条亮线）」「接触到亮线的扣完整伤害，接触到破碎碎片的扣四分之一
     * （向下取整，最小为1）」⇒ 杆与碎片各一档是他的措辞。
     *
     * <p><b>连续杆现在是买来的</b>（他 2026-10-02 逐字裁「新增『连续杆』卡，未买靠撞击」）。这条改动
     * 作废的是我旧注释里那句"弹体本身不造成伤害"——那句本来就是<b>我</b>把他那两档措辞补成的互斥口径
     * （他没谈过弹体）。现在没买卡时弹体确实造成伤害，判据钉在 {@link WarheadRules} 那张真值表里。
     *
     * <p>杆与撞击**共用同一个伤害数值**（都是 {@link DamageRules#resolve} 读 {@code m.damage}，
     * 各自走 {@code SRC_ROD}／{@code SRC_MISSILE_BODY} 那一档）：
     * 卡买的是"这一发炸开成一条线、顺带碎片"，不是"这一发更疼"。所以单只敌人吃到的总伤害不因买卡而变，
     * 变的是**一次能打几只**。
     *
     * <p>⚠ 满池淘汰**不在这条路上**（R22：只播动画、不结算，见 {@link #stepEvictions}）；空视场自爆
     * 走这一条但不算命中（那是我的记账口径，他没谈过命中怎么算）。池满时 {@code spawnRod} 返回 null
     * ⇒ 少一根杆，玩家看不出来，但 {@link Warheads#refuseTotal()} 会记账（"看不出来的截断"和
     * "静默的截断"不是一回事）。
     */
    private void detonateMissile(Missiles.Missile m, boolean countsAsHit, int fusedSlot,
                                 int bodySource) {
        if (countsAsHit) stats.addHit();
        int route = WarheadRules.detonationRoute(shopRun.rodOn(), fusedSlot);
        if (route == WarheadRules.ROUTE_ROD) {
            WarheadRules.spawnRod(warheads, m, Balance.missile);
        } else if (route == WarheadRules.ROUTE_IMPACT) {
            settleImpact(m, fusedSlot, bodySource);
        }
        spawnBurst(m.x, m.y, 12, m.color);
        shake = Math.max(shake, 0.45f);
        hitStop.arm(HitStop.secondsFor(0.2f));
        if (haptics != null) haptics.tick();
    }

    /**
     * 撞击档结算：把这一发的伤害打在**引信碰到的那一只**身上。
     *
     * <p>只打一只、不做半径采样，两条理由：① 槽号是现成的（{@link MissileBehavior#fusedSlot()}），
     * 而这里**没有网格可查**——{@code rebuildGrid} 排在 {@code collide} 里、晚于本步
     * （{@code stepMissiles} 在它之前），拿帧首快照去圈"弹体半径内的敌人"会漏掉本步刚挪进来的那只；
     * ② 卡面说的是"没买杆只撞单体"，圈出一片就是另一句假话。
     *
     * <p>三条动作照 {@link #settleContact} 那套抄：判 null、判 {@code hp <= 0}（同一步里可能已经
     * 被别的结算打死，摘表要到 {@link #reapDead}）、打死者走 {@link #killEnemy}（不接就无分数无掉落）。
     *
     * <p>{@code bodySource} 是"这一发是哪一流的弹体"（{@link DamageRules#SRC_MISSILE_BODY} 或
     * {@link DamageRules#SRC_DOGFIGHT_BODY}），由 {@link #stepPool} 按池给出——不在这里猜，
     * 弹身上没有流别字段，那是 {@link MissileBehavior} 明确不要的东西。
     */
    private void settleImpact(Missiles.Missile m, int slot, int bodySource) {
        Enemies.Enemy e = enemies.objAt(slot);
        if (e == null || e.hp <= 0) return;
        e.hp -= DamageRules.resolve(bodySource, DamageRules.ARMOR_REGULAR, m.damage);
        e.hitFlash = 0.09f;
        audio.play(AudioCues.ENEMY_HIT);
        if (e.hp <= 0) killEnemy(e, m.crit);
    }

    private void stepBullets(float dt) {
        shots.stepAndCompact(dt, Screen.LOGIC_W, metrics.logicH, MARGIN);
        hostile.stepAndCompact(dt, Screen.LOGIC_W, metrics.logicH, MARGIN);
        // 光束不积分——它没有"往哪儿走"可算，只剩亮度衰减。排在 collide **之前**：
        // 本步新出膛的那一束先衰减一格，随后在同一步里结算（{@link #playerBeamsAgainstEnemies}
        // 的闸门是 {@code settled} 而不是 {@code life > 0}，所以这一格衰减不会让它跳过结算）。
        beams.advance(dt);
    }

    private void collide(float dt) {
        rebuildGrid();
        stepWarheads(dt);
        playerShotsAgainstEnemies();
        playerBeamsAgainstEnemies();
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
     * <p>伤害读 {@link Warheads.Warhead#damage} 这个**已经取整的 int**：杆在 {@code WarheadRules.spawnRod}
     * 里走过 {@link DamageRules#resolve} 的 {@code SRC_ROD} 那一档，碎片值由
     * {@code WarheadRules.shardDamage} 从杆值派生（{@code SRC_SHARD} 档），
     * 这里不再取第二次整——那是第三个真源。
     *
     * <p>⚠ 代价要说清楚：战斗部的**档位定在生成那一刻**，所以"这一根杆打在带护甲的东西上"这件事
     * 在这里问不出来（目标此刻还不存在，一个 tick 里它可能碰到好几只）。今天没有带护甲的目标，
     * 这句话不成立也不影响任何数字；等 #67「对弹机制」让战斗部也能打敌弹时，护甲要么在这条链上
     * 重新解析，要么由那张表把杆与碎片一并裁成打不穿——两条都比现在抄一份强。
     */
    private void settleContact(Warheads.Warhead w) {
        int mask = WarheadRules.takeContact(w);
        // 一根杆一个 tick 一声，不按"这一 tick 咬住几只"响几只：同时咬四只会放四声同样的撕开，
        // 听着像炸豆。碎片不响——它是一根杆的余势，六片各叫一声等于把一次解体放成六次。
        if (mask != 0 && w.kind == Warheads.Warhead.KIND_ROD) audio.play(AudioCues.ROD_TEAR);
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
            audio.play(AudioCues.ENEMY_HIT);
            if (hit.hp <= 0) killEnemy(hit, b.crit);
            shots.killAt(i);
        }
    }

    /**
     * 光束的结算：一道束 = 一次出膛、**沿途每架各结一次**、**一生只结这一次**。
     *
     * <p>排在 {@link #rebuildGrid} 之后是硬的（同 {@link #settleImpact} 那条教训）：出膛那一刻
     * 网格还是上一帧的快照，用它在 {@code playerFire} 里打，会漏掉本步刚挪进来的那一只。
     * 而"瞬时"这件事不靠这一步实现——靠的是**结一次就不再结**（{@link Beams.Beam#settled}）：
     * 束亮着的后面几格只是画面，不再产生任何伤害。
     *
     * <p>⚠ 不走网格。网格的覆盖环是按**点**查询的尺寸出来的（{@link SpatialGrid#DEFAULT_MAX_CENTER_DISTANCE}
     * = 40，见那个常量的自述），而一束光的纵向跨度是整条战场（300 上下），拿它查一次等于漏查；
     * 换成一串采样点又要去重。这里两侧的量都不大——同屏最多两道（{@code Balance.beam.lifeSec} 那条算式），
     * 敌人上界 {@code Balance.wave.maxAlive} + 2，全表扫一遍约 28 次比较，
     * 而且**只在出膛那一格做**（其余各格直接跳过 settled 闸门）。网格在这条路上省不出可见的收益，
     * 却会引进"覆盖界要跟着带变"的那笔账（{@code WarheadRules.sampleContact} 正挂着它）。
     *
     * <p>记账口径：**一束最多记一个 hit**。穿三架也只记一发命中、出膛记一发（{@code chain.step}
     * 那侧的 {@code addShots}）。少这一条约束，{@code shotsHit} 就会大过 {@code shotsFired}，
     * 被 {@code Rating.clamp01} 静默钳成"命中率 100%"——那条坑与它的后果都写在
     * {@link BulletChain#step} 的方法头；导弹侧同一口径（{@link #detonateMissile} 的
     * {@code countsAsHit} 是一枚一次）。
     *
     * <p>声音**一束一声**不按"穿了几架"响几声，与 {@code stepChain}／{@code settleContact}
     * 那两处同一条理由：同时咬四只会放四声同样的 ENEMY_HIT，听着像炸豆。火花则是逐架的
     * （画面上确实打了四下）。
     */
    private void playerBeamsAgainstEnemies() {
        float top = metrics.battleTop();
        for (int i = 0; i < beams.activeCount(); i++) {
            Beams.Beam b = beams.at(i);
            if (b.settled) continue;
            b.settled = true;                     // 没打着也算结过：下一格不再结
            boolean any = false;
            int dmg = BeamRules.damageOf(b);
            for (int j = 0; j < enemies.activeCount(); j++) {
                Enemies.Enemy e = enemies.activeAt(j);
                if (e.hp <= 0) continue;          // 判活用 hp<=0，与弹-敌那条同一个理由
                if (!BeamRules.hits(b.x, top, b.y, b.width / 2f, Balance.beam.hitPad,
                        e.x, e.y, e.radius)) continue;
                any = true;
                e.hp -= dmg;
                e.hitFlash = 0.09f;
                spawnSpark(e.x, e.y, b.color, b.crit);
                if (e.hp <= 0) killEnemy(e, b.crit);   // 打死者必须走 killEnemy，否则无分数无掉落
            }
            if (!any) continue;
            stats.addHit();
            audio.play(AudioCues.ENEMY_HIT);
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
            // 基准点数是出膛时写死的整数（12/20），过这一档结算今天等于原样，但"打折"这一层
            // 从此只有 resolve 一个出口——将来玩家侧若挂上护甲，也在这同一格里读。
            hurtPlayer(DamageRules.resolve(DamageRules.SRC_ENEMY_BULLET,
                    DamageRules.ARMOR_REGULAR, b.damage), false);
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
            audio.play(AudioCues.BOSS_KILL);
            if (haptics != null) haptics.heavyClick();
        } else {
            shake = Math.max(shake, 0.28f);
            hitStop.arm(HitStop.secondsFor(0.35f));
            audio.play(AudioCues.KILL);
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
            // [规格 §8.4] 难度掉落乘子挂在**入账口**、不挂在概率侧。三条芯片源（概率掉／Boss 必掉／
            // 每波保底）在这一格汇成一条，所以一处就全覆盖，也不必跟幸运卡抢 clamp01 的上界。
            // 带余量的累加与金币同型：0.85／0.75 那两档若在 int 上直接截断，就是规格判过两次的
            // "卡面写了收益、结算里没有"第三次复发——挂在这里还顺带让 HUD 与暂停页那个芯片数是真的。
            chipCarry += collected[Drops.CHIP] * mode.tier.dropMul;
            int wholeChips = (int) chipCarry;
            chips += wholeChips;
            chipCarry -= wholeChips;
            collected[Drops.CHIP] = 0;
        }
        if (collected[Drops.COIN] > 0) {
            // 倍率走**带余量**的累加：一次捡 1 枚 × 1.25 直接取整等于什么都没加，
            // 那正是规格点名的"卡面写了收益、结算里没有"。余数留着下一波一起结。
            // [规格 §8.4] 难度的 dropMul 与卡片的 coinMul 在这一格相乘——两者都是入账侧的乘子。
            coinCarry += collected[Drops.COIN] * shopRun.coinMul() * mode.tier.dropMul;
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
            // 一帧捡到多枚也只响一声（与触觉同一个口径）：那是"这一帧收到了"的事件，不是逐枚计数。
            audio.play(AudioCues.POWERUP);
            if (haptics != null) haptics.heavyClick();
        }
    }

    /** 唯一的受伤出口：状态层乘算在这里进，规格 §五 点名的"图标亮着但不生效"就断在这。 */
    private void hurtPlayer(int raw, boolean heavy) {
        // [规格 §8.2] 档位的 takenMul 与状态层那枚**在这一格相乘**：这里是全仓唯一的受伤出口，
        // 四路上游（敌弹／小怪接触／Boss 接触／自爆）都汇到这一行，乘在别处就会漏掉一条。
        int flags = player.hurt(raw, status.takenMul() * mode.tier.takenMul);
        if (flags == PlayerState.FLAG_NONE) return;
        hurtFlash = 1f;
        shake = Math.max(shake, heavy ? 0.9f : 0.6f);
        hitStop.arm(HitStop.secondsFor(heavy ? 0.8f : 0.4f));
        spawnBurst(player.x, player.y, heavy ? 20 : 10,
                (flags & PlayerState.FLAG_SHIELD_BROKEN) != 0 ? Ink.SHIELD : Ink.WARN);
        if ((flags & PlayerState.FLAG_SHIELD_BROKEN) != 0) audio.play(AudioCues.SHIELD_BREAK);
        if (haptics != null) haptics.heavyClick();
        if ((flags & PlayerState.FLAG_DIED) != 0) onPlayerDied();
    }

    private void onPlayerDied() {
        runOver = true;
        shake = 1f;
        sweepFlash = 0.6f;
        spawnBurst(player.x, player.y, 48, Ink.PROBE);
        audio.play(AudioCues.PLAYER_DEATH);
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
        // 横扫只放一声：逐只补 ENEMY_HIT 会把 26 声同一个音挤进同一帧，听着像炸豆而不是"清屏"。
        audio.play(AudioCues.OVERLOAD);
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
            e.hp -= DamageRules.bombDamage(e.maxHp);
            e.hitFlash = 0.12f;
            if (e.hp <= 0) killEnemy(e, false);
        }
        sweepFlash = 1f;
        shake = 1f;
        hitStop.arm(HitStop.secondsFor(0.9f));
        audio.play(AudioCues.ITEM_BOMB);
        if (haptics != null) haptics.heavyClick();
    }

    /**
     * 换模式的**唯一**写点：本类的四格（hp／受伤／金币／芯片）直读 {@link #mode}，波次那三格
     * （数量／精英下限／出怪间隔）由 {@code WaveDirector} 转授，批次与局末出口由 {@code WaveFlow} 转授。
     * 三份引用在这一格里一起换，所以"读到两套规则"没有窗口——这也是 §8.5 那句"每格仍只有唯一一个
     * 读取点"能立住的前提。
     */
    private void applyMode(Mode mode) {
        this.mode = mode;
        director.setMode(mode);
        wave.setMode(mode);
    }

    /** 从第一章起的一整局重置——冷启动、暂停页重开、结算页重试这三枚老调用点用的都是这一枚。 */
    public void resetRun(Mode mode) {
        resetRun(mode, 1);
    }

    /**
     * 整局重置（死亡后重开、冷启动、以及从选关页开的那一局）。
     *
     * <p>模式与档在**这一格**落定——[规格 §8.5] 那句"档跟着一整局走"落到代码上就是"只有这里能换"：
     * 中途换会把已刷出的怪和接下来要刷的怪切成两套规则，HUD 那条"敌人"进度条的分母当场与队列不符。
     * {@code mode} 的三个调用点传 {@link Mode#CAMPAIGN}，选关页那一个传
     * {@link Mode#campaign(Tier)}——今天只有这一格能把档换掉（无尽侧的分叉已在逻辑核落地并有测试，
     * 它的<b>入口</b>仍未接，见 {@code Mode} 类注释那条"为什么默认是战役"）。
     *
     * <p>{@code mission} 是<b>起点</b>章号（1 起，与选关页那八条任务同一口径），它只改{@link WaveFlow#beginAtChapter}
     * 的那一步：局末出口照旧恒在 {@code maxWave}。这一批刻意只动起点——把"打完本章即通关"顺手落进这里，
     * 今天那条真机验过的"40 波通关"基线会当场变成"5 波通关"，而那一格是他还没裁的（池件
     * {@code campaign-data-tree} §2 的第 2 条待测）。
     */
    public void resetRun(Mode mode, int mission) {
        applyMode(mode);
        enemies.clear();
        // 指定挂在**敌池槽号**上，池子清空后那个 (槽号, born) 对就成了悬空引用。每帧
        // {@link #stepDesignation} 那三条判据本来也会把它清掉，但那是"下一帧才发现"的清理；
        // 新一局的雷达屏必须从"没人指定"开始，不能靠一条兜底判定去赢得开局第一帧。
        designatedSlot = MissileBehavior.NO_DESIGNATED;
        shots.clear();
        hostile.clear();
        // 链只停止排放，**布局缓存留着**：它由武器号/弹丸数/周期三个数决定，而下局的
        // shopRun.reset() 与第一次开火都会让 matches() 自己判出来。把缓存也抹掉反而会让
        // "布局是不是清了"变成一个要额外验证的状态。
        chain.clear();
        // 四张新池同样在这里归零。⚠ `clear()` 的形状是"只置游标与槽位表"，这一点是硬的：
        // 本方法有一条调用链跑在 **UI 线程**上（MainActivity:138 → GameSurfaceView:44 → 本方法），
        // 与渲染线程并发。所以撕裂读的最坏后果必须是"多丢几枚导弹"，不能是"读到半个相位状态机"。
        missiles.clear();
        dogfightMissiles.clear();
        warheads.clear();
        beams.clear();
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
        chipCarry = 0f;
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
        // 体系记忆随持有量一起清零：上一局买断过的激光不该成为这一局体系键的落点（理由见
        // PlayerState.lastGunOfSchool，那条 bug 与上一句钳 weaponId 是同一个形状的反面）。
        player.resetSchoolMemory();
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
        wave.beginAtChapter(mission);
        // 开新局的声音挂在**这一格**而不是那三个调用点（重开/重试/菜单开局）：它们是同一个事件的
        // 三个入口，写三处就有第四处漏掉的那天。冷启动那一次多半听不见——播放面还没 ready。
        audio.play(AudioCues.RUN_START);
    }

    // ---- 特效 -------------------------------------------------------------------------------

    /**
     * 哑火：满池淘汰那一枚的动画（他 2026-10-02 逐字裁「分开：淘汰改『哑火』小烟」）。
     *
     * <p>它存在的唯一理由是**与引爆分得开**：淘汰的弹**没有炸**（不展开战斗部、不结算，R22），
     * 而旧写法直接借了 {@link #spawnBurst} 的 12 粒 ＋ 弹体本色 ＋ 一粒白心，与真炸开同形同色，
     * 于是玩家在屏幕上读成"它打中了"。这里三处都反着来：粒子少（5）、色固定为暗灰蓝
     * （不借弹色，两条流的哑火因此同形）、只用方粒不借 KIND_TRAIL 那种拖尾。**颜色与粒数是我定的**，
     * 他给的只有「哑火」「小烟」这两个词。
     */
    private void spawnMisfire(float x, float y) {
        // 出声挂在这一格里，两条淘汰池因此共用同一个上限：每帧每池最多一声（配额 10 的小份额）。
        // 它排在粒子判满之前是有意的——"这一枚被踢了"这件事已经发生，烟挤掉了不该连声音一起吞。
        audio.play(AudioCues.MISFIRE);
        for (int i = 0; i < 5; i++) {
            Particle p = particles.spawn();
            if (p == null) return;
            float a = i * 1.26f;
            p.x = x; p.y = y;
            p.px = x; p.py = y;                   // 与当前位置同步，第一帧才不会拉出长尾
            p.vx = (float) Math.cos(a) * 22f;
            p.vy = (float) Math.sin(a) * 22f - 8f; // 那一下上飘：读成冒烟，不是炸开
            p.gravity = -6f;
            p.drag = 2.4f;
            p.size = 1;
            p.maxLife = p.life = 0.3f + (i % 3) * 0.1f;
            p.color = Ink.DIM;
            p.kind = Particle.KIND_SQUARE;
        }
    }

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
        drawBeams(c);
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
        // 五张**整页**模态（结算 / 主菜单 / 成长 / 存档 / 选关）共用一套待遇：自己报整页高、在画布里居中、
        // 不跟着战场长高，并且把 HUD 与波次横幅一起让位。这个布尔是那条待遇的**唯一**判据——
        // 分两处各列一遍模态号的写法，迟早会有一处忘记加新页（表现为"菜单底下亮着血条"）。
        boolean pageModal = modal == ModalStack.RESULT
                || modal == ModalStack.MENU || modal == ModalStack.GROWTH || modal == ModalStack.SAVE
                || modal == ModalStack.CAMPAIGN;
        probe.begin(FrameProbe.MODAL);
        if (pageModal) {
            int pageSave = c.save();
            if (modal == ModalStack.MENU) {
                c.translate(0f, metrics.pageTop(menu.pageHeight()));
                menu.draw(c, growth, time.ui() - menuAtUi);
            } else if (modal == ModalStack.GROWTH) {
                c.translate(0f, metrics.pageTop(growthPage.pageHeight()));
                growthPage.draw(c, time.ui() - growthAtUi);
            } else if (modal == ModalStack.SAVE) {
                // "读取中…"那一形的真源是 Game 那枚在飞槽号，面板自己不猜（池件 §9 第 8 条第⑤项）
                savePage.setLoading(loadInFlight());
                c.translate(0f, metrics.pageTop(savePage.pageHeight()));
                savePage.draw(c, time.ui() - saveAtUi);
            } else if (modal == ModalStack.CAMPAIGN) {
                c.translate(0f, metrics.pageTop(campaignPage.pageHeight()));
                campaignPage.draw(c, time.ui() - campaignAtUi);
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
            // 紧跟行2：它占的就是行2 右侧那段空地（几何见 HudLayout.RADAR_*）
            drawRadar(c);
            drawBombButton(c);
            drawSwitchButton(c);
            drawSchoolButton(c);
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
        if (modal == ModalStack.DEBUG) {
            debug.draw(c, debugPulseDogfight, time.ui() - debugAtUi);
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
     * 光束：**一条从机头拉到战场顶边的竖条**，亮度按剩余生命衰减。它不是一条会走的弹，
     * 屏幕上它就是"这一把正在打的东西"的全部署名——他 2026-10-07 那句「甚至是打的子弹而不是激光」
     * 要修的正是这一格。
     *
     * <p>两个端点各有出处：
     * <ul>
     *   <li><b>上端取 {@code metrics.battleTop()}</b>，与 {@link #playerBeamsAgainstEnemies}
     *       传给 {@link BeamRules#hits} 的是**同一个读数**。分开的后果是本仓最常见的那一类错：
     *       画到战场顶、却把还没进场的敌人也算打中（"打得比画得长"）。</li>
     *   <li><b>下端取 {@code Beams.Beam.y}</b> = 出膛那一刻的机头 y，**不是**当前机头 y。
     *       束亮着的那几格机头可能已经横移过去，束跟着走就变成"光柱拖着飞机跑"——
     *       它记录的是那一次出膛，不是一根拴在机头上的棍子。</li>
     * </ul>
     *
     * <p>画法是 {@code drawRect} 而不是 {@code drawLine}：与 {@link #drawShots} 同一条口径
     * （整数逻辑网格上对齐，粗细由 {@code Beam.width} 说了算）。走 line 就要动 {@code stroke}
     * 的粗细与抗锯齿，而那支 Paint 与 HUD 共用——改完不还原会把下一行边框变成三像素亮条
     * （{@link #drawWarheads} 那条 ⚠ 说的就是这件事）。
     *
     * <p>白芯：**恒有一格**，暴击时加宽到 2。理由不是好看——纯色的 2 像素条在 12px 像素字体那套
     * 视觉语言里读起来仍是"一根细线"，中间亮、两边淡才读得出"这是光束、它烫"。
     */
    private void drawBeams(Canvas c) {
        float top = metrics.battleTop();
        for (int i = 0; i < beams.activeCount(); i++) {
            Beams.Beam b = beams.at(i);
            if (b.life <= 0f) continue;           // 灭了的槽位还在环形窗口里，只是不再画
            int a = Math.round(255f * (b.life / Balance.beam.lifeSec));
            fill.setColor(b.color);
            fill.setAlpha(a);
            rf.set(b.x - b.width / 2f, top, b.x + b.width / 2f, b.y);
            c.drawRect(rf, fill);
            float core = b.crit ? 1f : 0.5f;      // 半宽 ⇒ 1px 与 2px
            fill.setColor(0xFFFFFFFF);
            rf.set(b.x - core, top, b.x + core, b.y);
            c.drawRect(rf, fill);
        }
        fill.setAlpha(255);
    }

    /**
     * 战斗部：连续杆画成**一条垂直弹轴的亮线**，碎片画成小方块。     *
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
     * **模式键**（族内换枪）：框里印**当前这一把**的名字，点一下换到同体系里的下一把**买过的**。
     *
     * <p>画"手里是什么"而不是"下一次换成什么"：这一格同时是场上那条弹道的署名——玩家低头确认
     * 自己正在打什么的那一眼，比预知下一把有用（而且下一把要等他真按了才算数）。
     *
     * <p>名字着色用武器自己的 {@code color}（{@code Balance.Weapon} 那一格），于是键上的字与
     * 飞出去的弹是同一个颜色：形状先于颜色，但颜色在这里是**对应关系**的免费提示，
     * 不必再画一个图标去说"这是枪"。字面量就是武器表的 name，不新增文案 ⇒ 没有第二个真源。
     *
     * <p>手持激光或导弹时**整块不画**（判据与命中那条同源，见 {@code ShopRun.modeKeyLive}）：
     * 那两个体系各只有一把枪，这一枚按下去无事可换。
     */
    private void drawSwitchButton(Canvas c) {
        if (!shopRun.modeKeyLive(player.weaponId)) return;
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
     * **体系键**：框里印**当前这个体系**的名字（{@code Balance.Weapon.SCHOOL_NAMES}），点一下跳到
     * 下一个买得起的体系。与模式键的分工就是他那句逐字的两半「体系键（子弹／激光／导弹）＋
     * 模式键（族内换枪）」——一枚管"哪一族"，一枚管"这一族的哪一把"。
     *
     * <p>用**中性色**而不是武器自己的颜色：模式键那格的颜色是"场上飞着什么"的署名，两枚键同色会
     * 把它们读成同一个东西的两半。这里要报的是结构（手里有几个体系），不是弹道。
     *
     * <p>只有一个体系可去时**整块不画**（判据 {@code ShopRun.schoolKeyLive}，与命中那条同源）：
     * 出厂四把全在子弹族，没买那两张解锁卡之前，这一枚按下去无事可换。
     */
    private void drawSchoolButton(Canvas c) {
        if (!shopRun.schoolKeyLive(player.weaponId)) return;
        fill.setColor(Ink.EDGE);
        rf.set(schoolDraw.left, schoolDraw.top, schoolDraw.right, schoolDraw.bottom);
        c.drawRect(rf, fill);
        String name = Balance.Weapon.SCHOOL_NAMES[player.weapon().school];
        Bitmap label = uiText.bake(name, UI_PX, Ink.TEXT);
        text.setColor(Ink.TEXT);
        SpriteFactory.draw(c, label, schoolDraw.centerX() - label.getWidth() / 2,
                schoolDraw.centerY() - label.getHeight() / 2,
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

    /**
     * 雷达屏（他 2026-10-03 逐字「在生命、护盾、过载和敌人那些条的右侧，放雷达屏幕（半透明绿色正方形，
     * 带有网格…）」＋「敌点建议用黄色」）。
     *
     * <p>整块画在 HUD 的 {@code hudTop} 平移组里 ⇒ 这里全用 **HUD 相对坐标**，与
     * {@link HudLayout#radarRect} 同一套口径（命中端在 {@code installGeometry} 里换一次算）。
     * 底色**必须半透明**：HUD 是盖在战场上的读数层（{@code battleTop} 与 {@code hudTop} 是同一个数），
     * 一块不透明的屏会在地面上开个洞。
     *
     * <p>形状分工三条各说一件事，混用就读出歧义：底色＋网格＝"这是一块屏"（不是第五条状态条），
     * 黄点＝"场上有谁"，白括号＝"玩家点了谁"。选中态特意**不靠颜色**承担——敌点已经是黄的了。
     *
     * <p>没买「雷达锁定」时这一整块不画，命中端也不受理：两端共用 {@link ShopRun#radarOn()}
     * 那**一个**谓词，不许各读一次等级。
     */
    private void drawRadar(Canvas c) {
        if (!shopRun.radarOn()) return;
        Balance.Radar r = Balance.radar;
        final int left = HudLayout.RADAR_LEFT;
        final int top = HudLayout.RADAR_TOP;
        final int side = HudLayout.RADAR_SIDE;
        fill.setColor(r.baseColor);
        rf.set(left, top, left + side, top + side);
        c.drawRect(rf, fill);
        int cells = Math.max(1, r.gridCells);
        fill.setColor(r.gridColor);
        for (int i = 1; i < cells; i++) {
            int gx = left + side * i / cells;
            rf.set(gx, top + 1, gx + 1, top + side - 1);
            c.drawRect(rf, fill);
            int gy = top + side * i / cells;
            rf.set(left + 1, gy, left + side - 1, gy + 1);
            c.drawRect(rf, fill);
        }
        // 边框也走 fill：描边矩形在非抗锯齿下会压在半格上，与上面那套落格口径对不齐。
        fill.setColor(r.borderColor);
        rf.set(left, top, left + side, top + 1);
        c.drawRect(rf, fill);
        rf.set(left, top + side - 1, left + side, top + side);
        c.drawRect(rf, fill);
        rf.set(left, top + 1, left + 1, top + side - 1);
        c.drawRect(rf, fill);
        rf.set(left + side - 1, top + 1, left + side, top + side - 1);
        c.drawRect(rf, fill);

        int dot = Math.max(1, Math.round(r.dotRadius));
        fill.setColor(r.enemyColor);
        for (int i = 0; i < enemies.activeCount(); i++) {
            Enemies.Enemy e = enemies.activeAt(i);
            if (e == null || e.hp <= 0) continue;      // 与点选端同一条筛选：画得出就该点得着
            int px = left + (int) radarLocalX(e);
            int py = top + (int) radarLocalY(e);
            rf.set(px - dot, py - dot, px + dot, py + dot);
            c.drawRect(rf, fill);
        }

        // 自机：三行 1/3/5 的方格堆成朝上的实心三角。不用 Path——斜边在非抗锯齿画布上糊成一团灰，
        // 而这块屏只有 48 格，一团体灰就自机自己。
        fill.setColor(r.shipColor);
        int sx = left + (int) RadarLayout.clampToSquare(
                RadarLayout.toLocalX(player.x, Screen.LOGIC_W, side), side);
        int sy = top + (int) RadarLayout.clampToSquare(
                RadarLayout.toLocalY(player.y - metrics.battleTop(), metrics.battleHeight(), side), side);
        for (int row = 0; row < 3; row++) {
            rf.set(sx - row, sy - 1 + row, sx + row + 1, sy + row);
            c.drawRect(rf, fill);
        }

        // 指定态（唯一一个"读玩家输入"的形状，所以最后画，压在点上）
        if (designatedSlot == MissileBehavior.NO_DESIGNATED) return;
        Enemies.Enemy d = enemies.objAt(designatedSlot);
        if (d == null || d.hp <= 0) return;            // 与 {@link #stepDesignation} 同一帧生效，画空槽没意义
        int cx = left + (int) radarLocalX(d);
        int cy = top + (int) radarLocalY(d);
        int bl = Math.max(2, Math.round(r.bracketLen));
        int off = dot + 1;                             // 让开本体，括号不糊在点上
        for (int i = 0; i < 4; i++) {
            int dx = BRACKET_X[i];
            int dy = BRACKET_Y[i];
            int cornerX = cx + dx * off;
            int cornerY = cy + dy * off;
            rf.set(Math.min(cornerX, cornerX + dx * bl), cornerY,
                    Math.max(cornerX, cornerX + dx * bl) + 1, cornerY + 1);
            c.drawRect(rf, fill);
            rf.set(cornerX, Math.min(cornerY, cornerY + dy * bl), cornerX + 1,
                    Math.max(cornerY, cornerY + dy * bl) + 1);
            c.drawRect(rf, fill);
        }
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
