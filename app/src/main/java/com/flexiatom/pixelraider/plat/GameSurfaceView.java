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
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.flexiatom.pixelraider.core.GameThread;
import com.flexiatom.pixelraider.game.Game;

/**
 * 唯一的游戏视图（规格 §零：单 Activity + 单 SurfaceView 全自绘，菜单/设置/暂停/结算/图鉴都画在这里）。
 *
 * 本类只做四件事：把缓冲尺寸钉成「逻辑分辨率 x scale」、摆好 Screen 算给它的矩形、
 * 起停渲染线程、把帧转交给 Game。不做任何布局决策——那是 Screen 的纯函数职责。
 */
public final class GameSurfaceView extends SurfaceView implements SurfaceHolder.Callback {

    private final Game game;

    private GameThread thread;
    private Screen.Metrics metrics;
    private boolean surfaceAlive;
    private boolean destroyInProgress;

    public GameSurfaceView(Context ctx, Game game) {
        super(ctx);
        this.game = game;
        getHolder().setFormat(PixelFormat.OPAQUE);
        getHolder().addCallback(this);
        setZOrderMediaOverlay(false);
    }

    /**
     * 规格 §零硬约束：必须 setFixedSize，把 Surface 实际缓冲钉成逻辑分辨率 x scale，
     * 放大交给 SurfaceFlinger。按物理分辨率逐帧软件绘制会直接掉到 15fps。
     */
    public void applyMetrics(Screen.Metrics m, float density, int qualityTier) {
        // 同一份几何会被反复投递（insets 与 layout 两个监听器），而 onSurfaceReady 末尾挂着 resetRun()：
        // 不挡住重复投递，切到最近任务的动画里玩家会亲眼看见这一局被清零（2026-09-29 用户报）。
        // density 不进判据——它只喂 48dp 命中外扩，几何一位没变而只有 density 变的事件不存在。
        if (metrics != null && metrics.sameShapeAs(m) && qualityTier == game.qualityTier()) return;
        metrics = m;
        getHolder().setFixedSize(m.bufW, m.bufH);
        game.onSurfaceReady(m, density, qualityTier);
        requestLayout();
    }

    public Screen.Metrics metrics() {
        return metrics;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceAlive = true;
        startThreadIfNeeded();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 120Hz 屏上告知合成器真实帧率，避免无谓的重复提交（逻辑仍是固定 1/60）
            Surface s = holder.getSurface();
            if (s != null && s.isValid()) {
                try {
                    s.setFrameRate(60f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
                } catch (RuntimeException ignored) {
                    // 个别 ROM 不支持该调用，缺了就按默认 pacing 跑
                }
            }
        }
        game.time().forceNextFrameToOneStep();
        startThreadIfNeeded();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceAlive = false;
        joinThread();
    }

    private void startThreadIfNeeded() {
        if (destroyInProgress || !surfaceAlive || thread != null) return;
        GameThread t = new GameThread(getHolder(), game, game.probe());
        thread = t;
        t.startFrame();
    }

    /**
     * 规格 §零生命周期契约：surfaceDestroyed 必须 join() 线程后再置空，
     * 否则两次 onResume 之后会有两条线程同时推进逻辑，敌人速度翻倍。
     */
    private void joinThread() {
        GameThread t = thread;
        if (t == null) return;
        destroyInProgress = true;
        t.requestStop();
        try {
            t.join(1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        thread = null;
        destroyInProgress = false;
    }

    public void setRenderPaused(boolean paused) {
        GameThread t = thread;
        if (t != null) t.setPaused(paused);
    }
}
