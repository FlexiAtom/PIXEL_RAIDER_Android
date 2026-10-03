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
package com.flexiatom.pixelraider.plat;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.flexiatom.pixelraider.core.InputRouter;
import com.flexiatom.pixelraider.game.Game;
import com.flexiatom.pixelraider.gfx.TextCache;

/**
 * 单 Activity（规格 §零）。主线程只做两件事：把输入事件投进队列、维护窗口与安全区。
 * 游戏状态一律在渲染线程读写，不加锁（§八）。
 */
public final class MainActivity extends ComponentActivity {

    private final Game game = new Game();

    private FrameLayout root;
    private GameSurfaceView surface;
    private android.view.GestureDetector gestures;
    private Screen.Metrics metrics;

    private int movePointerId = -1;

    private android.media.AudioManager audio;
    private boolean hasAudioFocus;
    private android.media.AudioFocusRequest focusRequest;
    /** 落盘出口要留着，销毁时才能等它把挂起的写做完（规格 §八 的单线程池）。 */
    private AsyncKeyValue store;
    /** 只有 debug 包带作弊键（F9 立即结算），release 包里这个键不拦截、照常交给系统。 */
    private boolean debugKeys;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        debugKeys = (getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        // 同一个闸门喂给 Game：暂停页那枚「调试」入口只在 debug 构建画得出、受理得了（surface 之后才建，这里先落地）
        game.setDebugBuild(debugKeys);
        // 落盘出口必须在渲染线程能碰到 Game 之前挂上：surface 是后面才建的，但一旦建起来就是它先动
        store = new AsyncKeyValue(new SharedPrefs(
                getSharedPreferences("pixel_raider", Context.MODE_PRIVATE)));
        game.attachStore(store);
        // 内嵌像素中文要在第一块贴图烘出来之前装上（BAKE 是静态笔，装晚了一部分文本会用系统字烘）。
        // 和落盘出口同一条边界：渲染线程拿不到 Context，由 plat 在启动时注入。
        // 装失败必须出声：兜底态是"系统字 + 抗锯齿"，画面会整片变样，静默的话只会被当成"字体没生效"。
        if (!TextCache.installTypeface(getAssets())) {
            android.util.Log.w("PixelRaider", "embedded CJK face unavailable: " + TextCache.ASSET
                    + " - UI falls back to the system font (antialiased)");
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // cutout 授权与沉浸式必须在 setContentView 之前：窗口第一次排版时若还没拿到
        // LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS + decorFits=false，decor 会把挖孔那 52px 扣掉，
        // root 首帧就是 1548。那样下面量到的尺寸永远慢一帧，画面顶上就留下一条真黑边。
        allowCutout();
        goImmersive();

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.setFitsSystemWindows(false);
        surface = new GameSurfaceView(this, game);
        root.addView(surface, new FrameLayout.LayoutParams(1, 1));
        setContentView(root);

        installTouchRouting();
        installBackHandler();
        // 触觉只以接口形式进 Game：渲染线程拿不到 Context，也不该拿到（规格 §八 的边界）
        game.setHaptics(new HapticPump(new Vibrate(this)));
        audio = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            applyInsets(WindowInsetsCompat.toWindowInsetsCompat(insets, v));
            return insets;
        });
        // 首帧布局前拿不到尺寸；布局完成后再从根窗口取一次 insets。
        // 守卫必须同时比宽高：纵向适配之后 windowW 永远不变，只比宽度会让 root 从 1548
        // 长回 1600 的那次重排被吞掉，黑边就此固化在顶上。
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (metrics != null && v.getWidth() == metrics.windowW && v.getHeight() == metrics.windowH) return;
            WindowInsetsCompat compat = androidx.core.view.ViewCompat.getRootWindowInsets(v);
            applyInsets(compat != null ? compat : WindowInsetsCompat.CONSUMED);
        });
    }

    /**
     * 挖孔/刘海区允许内容延伸进去，安全区避让交给 insets 计算。
     *
     * <p>cutout 模式是 {@code LayoutParams.layoutInDisplayCutoutMode} 这个**属性字段**，
     * 不是窗口 flag——它的值 SHORT_EDGES=1 和 {@code FLAG_SCALED=0x4000} 只是数字上挨着，
     * 往 {@code addFlags} 里塞 0x4000 会得到一扇被 WMS 按缩放窗口排版的窗，
     * 整窗缩到亚像素矩形里：层在 SurfaceFlinger 侧一切"正常"，屏幕上什么都不剩。
     */
    private void allowCutout() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attr = getWindow().getAttributes();
            attr.layoutInDisplayCutoutMode =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                            ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                            : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attr);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
    }

    private void goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat ctl =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        ctl.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        ctl.hide(WindowInsetsCompat.Type.systemBars());
    }

    /**
     * 规格 §零：WindowInsetsCompat 处理刘海、挖孔、手势条安全区。
     *
     * <p>2026-09-24 适配决策：逻辑画布不再居中留黑边，而是让逻辑高度按屏幕比例生长到刚好
     * 铺满安全区（见 {@link Screen} 顶部注释）。所以这里的矩形只剩"避开安全区"一个职责，
     * 不再承担"消化多余屏幕"。
     */
    private void applyInsets(WindowInsetsCompat compat) {
        int w = root.getWidth();
        int h = root.getHeight();
        if (w <= 0 || h <= 0) return;
        androidx.core.graphics.Insets bars = compat.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());

        int scaleCap = QualityProfile.defaultScaleCap(this);
        metrics = Screen.compute(w, h, bars.left, bars.top, bars.right, bars.bottom, scaleCap);
        // 规格 §六：初始档按机型兜底，低端机默认 scale=1、画质=中，不要一上来按 2 倍缓冲渲染
        surface.applyMetrics(metrics, getResources().getDisplayMetrics().density,
                QualityProfile.initialQuality(this));

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        lp.width = metrics.surfaceW;
        lp.height = metrics.surfaceH;
        lp.leftMargin = metrics.surfaceX;
        lp.topMargin = metrics.surfaceY;
        surface.setLayoutParams(lp);
    }

    // ---- 输入：全部翻译成逻辑坐标后经 InputRouter 交给渲染线程 ----

    private void installTouchRouting() {
        gestures = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener());
        // 规格 §二：双击用 GestureDetector.OnDoubleTapListener，不要用 postDelayed 手搓
        gestures.setOnDoubleTapListener(new android.view.GestureDetector.OnDoubleTapListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                return false;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                game.input().offer(InputRouter.EV_DOUBLE_TAP, -1, e.getX(), e.getY(), 0);
                return true;
            }

            @Override
            public boolean onDoubleTapEvent(MotionEvent e) {
                return false;
            }
        });

        root.setOnTouchListener((v, e) -> {
            handleTouch(e);
            return true;
        });
    }

    private void handleTouch(MotionEvent e) {
        InputRouter router = game.input();
        int action = e.getActionMasked();

        // 原始事件先喂给 GestureDetector（它只读不改，两者共用同一份 MotionEvent）
        gestures.onTouchEvent(e);

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                int i = e.getActionIndex();
                movePointerId = e.getPointerId(i);
                router.offer(InputRouter.EV_POINTER_DOWN, movePointerId, e.getX(i), e.getY(i), 0);
                return;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                // 第二指起一律不作为移动指（规格 §二 / §九.15：忘记 getPointerId 会让移动指瞬移）
                int i = e.getActionIndex();
                int id = e.getPointerId(i);
                router.offer(InputRouter.EV_POINTER_DOWN, id, e.getX(i), e.getY(i), 0);
                return;
            }
            case MotionEvent.ACTION_MOVE: {
                if (movePointerId < 0) return;
                int i = e.findPointerIndex(movePointerId);
                if (i < 0) return;
                // 补齐历史采样点，否则高刷屏上拖动会一顿一顿
                int hist = e.getHistorySize();
                for (int k = 0; k < hist; k++) {
                    router.offer(InputRouter.EV_POINTER_MOVE, movePointerId,
                            e.getHistoricalX(i, k), e.getHistoricalY(i, k), 0);
                }
                router.offer(InputRouter.EV_POINTER_MOVE, movePointerId, e.getX(i), e.getY(i), 0);
                return;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int i = e.getActionIndex();
                int id = e.getPointerId(i);
                router.offer(InputRouter.EV_POINTER_UP, id, e.getX(i), e.getY(i), 0);
                if (id == movePointerId) movePointerId = -1;
                return;
            }
            case MotionEvent.ACTION_UP: {
                int i = e.getActionIndex();
                router.offer(InputRouter.EV_POINTER_UP, e.getPointerId(i), e.getX(i), e.getY(i), 0);
                movePointerId = -1;
                return;
            }
            case MotionEvent.ACTION_CANCEL: {
                if (movePointerId >= 0) {
                    router.offer(InputRouter.EV_POINTER_UP, movePointerId, -1f, -1f, 1);
                }
                movePointerId = -1;
                return;
            }
            default:
                return;
        }
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        int code = event.getKeyCode();
        // 作弊键走事件环、不直接调 Game：面板是渲染线程在读，UI 线程上手改状态就是一条竞态
        if (debugKeys && (code == KeyEvent.KEYCODE_F9 || code == KeyEvent.KEYCODE_F8)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                game.input().offerLogic(code == KeyEvent.KEYCODE_F9
                        ? InputRouter.EV_DEBUG_KILL : InputRouter.EV_DEBUG_VICTORY, -1, 0, 0, 0);
            }
            return true;
        }
        if (isGameKey(code)) {
            int kind = event.getAction() == KeyEvent.ACTION_DOWN
                    ? InputRouter.EV_KEY_DOWN : InputRouter.EV_KEY_UP;
            if (event.getRepeatCount() == 0 || kind == InputRouter.EV_KEY_UP) {
                game.input().offerLogic(kind, -1, 0, 0, code);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private static boolean isGameKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_A:
            case KeyEvent.KEYCODE_D:
            case KeyEvent.KEYCODE_W:
            case KeyEvent.KEYCODE_S:
            case KeyEvent.KEYCODE_X:
            case KeyEvent.KEYCODE_1:
            case KeyEvent.KEYCODE_2:
            case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_4:
            case KeyEvent.KEYCODE_5:
            case KeyEvent.KEYCODE_6:
                return true;
            default:
                return false;
        }
    }

    private void installBackHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // 规格 §二：返回键统一映射到 ModalStack 弹栈，不直接退出
                game.input().offerLogic(InputRouter.EV_BACK, -1, 0, 0, 0);
            }
        });
    }

    /**
     * 震动的 binder 调用不该占渲染线程的帧预算：这里只把请求挂号到主线程，
     * removeCallbacks+post 顺带把同一帧内的重复触发并成一次。
     */
    private static final class HapticPump implements Game.Haptics {

        private final android.os.Handler main =
                new android.os.Handler(android.os.Looper.getMainLooper());
        private final Runnable heavy;
        private final Runnable tick;

        HapticPump(final Vibrate vibrate) {
            heavy = new Runnable() {
                @Override
                public void run() {
                    vibrate.heavyClick();
                }
            };
            tick = new Runnable() {
                @Override
                public void run() {
                    vibrate.tick();
                }
            };
        }

        @Override
        public void heavyClick() {
            repost(heavy);
        }

        @Override
        public void tick() {
            repost(tick);
        }

        private void repost(Runnable r) {
            main.removeCallbacks(r);
            main.post(r);
        }
    }

    // ---- 生命周期契约（规格 §零）----
    @Override
    protected void onPause() {
        super.onPause();
        game.input().offerLogic(InputRouter.EV_PAUSE, -1, 0, 0, 0);
        surface.setRenderPaused(true);
        abandonAudioFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        surface.setRenderPaused(false);
        requestAudioFocus();
        game.input().offerLogic(InputRouter.EV_RESUME, -1, 0, 0, 0);
        // 后台回来的首帧 delta 是几十秒，单独钉死（§零）
        game.time().forceNextFrameToOneStep();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        abandonAudioFocus();
        // 渲染线程在 surfaceDestroyed 里已经 joined，此刻不会再有人写：等挂起的落盘走完
        store.shutdown();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level == TRIM_MEMORY_UI_HIDDEN) {
            // S2 起：释放可重建的离屏缓存（星云、行星、文本缓存），必要时降画质档
        }
    }

    private void requestAudioFocus() {
        if (audio == null || hasAudioFocus) return;
        int res;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = new android.media.AudioFocusRequest.Builder(
                    android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setWillPauseWhenDucked(false)
                    .build();
            res = audio.requestAudioFocus(focusRequest);
        } else {
            res = audio.requestAudioFocus(focusListener, android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        }
        hasAudioFocus = res == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private final android.media.AudioManager.OnAudioFocusChangeListener focusListener =
            change -> {
                // 来电/他人音乐：S6 接音频总线时在这里做真正的 duck/pause
                if (change != android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    hasAudioFocus = false;
                }
            };

    @SuppressWarnings("deprecation")
    private void abandonAudioFocus() {
        if (audio == null || !hasAudioFocus) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audio.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        } else {
            audio.abandonAudioFocus(focusListener);
        }
        hasAudioFocus = false;
    }
}
