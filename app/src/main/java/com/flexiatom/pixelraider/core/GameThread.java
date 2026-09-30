/*
 * PIXEL RAIDER — 原生 Android 纵版弹幕射击
 * Copyright (C) 2026 Flexiatom
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
package com.flexiatom.pixelraider.core;

import android.view.SurfaceHolder;

/**
 * 渲染线程：pacing 用 nanoTime 精确推进到下一帧边界（规格 §一；不要用 SystemClock.sleep(16)，抖动大）。
 * 逻辑步长的固定 1/60 由 Time 负责，本线程只负责"每帧把 canvas 交出去"。
 *
 * 顺手量 LOCK 与 POST 两段：面板上那个"帧耗时 38ms"是整帧间隔，其中有多少是
 * {@code lockCanvas()} 在等空闲缓冲、有多少是提交排队，只有在这儿分得开。
 */
public final class GameThread extends Thread {

    public interface Host {
        /** deltaSeconds 为本帧真实耗时；canvas 已锁定，可直接绘。 */
        void frame(float deltaSeconds, android.graphics.Canvas canvas);

        /**
         * 线程启动时（第一帧之前，渲染线程上）取回 {@link #releaseResources()} 交还的东西。
         *
         * <p>必须有，而且必须和释放对称：{@code surfaceDestroyed} 会 join 掉本线程并释放，
         * 而 {@code surfaceCreated} 只是重新起一条线程，**不会再走一次 applyMetrics**。
         * 少这一步，凡是"回收后不会自己长回来"的资源就永久没了。
         */
        void acquireResources();

        /** 线程退出前的资源释放（在 Surface 还活着时调用）。 */
        void releaseResources();
    }

    private static final long NANOS_PER_FRAME = 1_000_000_000L / 60L;

    private final SurfaceHolder holder;
    private final Host host;
    private final FrameProbe probe;

    private volatile boolean running;
    private volatile boolean pauseRequested;

    public GameThread(SurfaceHolder holder, Host host, FrameProbe probe) {
        super("PixelRaiderRender");
        this.holder = holder;
        this.host = host;
        this.probe = probe;
    }

    public void startFrame() {
        running = true;
        pauseRequested = false;
        start();
    }

    /** 调用方必须 join() 之后再置空引用（规格 §零：否则两次 onResume 后双线程同时推进逻辑）。 */
    public void requestStop() {
        running = false;
    }

    public void setPaused(boolean p) {
        pauseRequested = p;
    }

    @Override
    public void run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        host.acquireResources();
        long prev = System.nanoTime();
        while (running) {
            long frameStart = System.nanoTime();
            float delta = (frameStart - prev) / 1_000_000_000f;
            prev = frameStart;

            android.graphics.Canvas canvas = null;
            probe.begin(FrameProbe.LOCK);
            try {
                canvas = holder.lockCanvas();
            } catch (RuntimeException ignored) {
                // Surface 正在被销毁：本帧作废，退出循环由 requestStop 决定
            }
            probe.end(FrameProbe.LOCK);
            if (canvas != null) {
                probe.begin(FrameProbe.DRAW);
                try {
                    host.frame(delta, canvas);
                } catch (RuntimeException e) {
                    // 单帧异常不能带走线程：留日志，下一帧继续
                    android.util.Log.e("PixelRaider", "frame failed", e);
                }
                probe.end(FrameProbe.DRAW);
                probe.begin(FrameProbe.POST);
                try {
                    holder.unlockCanvasAndPost(canvas);
                } catch (RuntimeException ignored) {
                    // Surface 已失效
                }
                probe.end(FrameProbe.POST);
            }

            if (pauseRequested) {
                // 暂停期间降到 15fps 维持画面，CPU 让出来
                sleepNanos(NANOS_PER_FRAME * 4L);
            } else {
                long elapsed = System.nanoTime() - frameStart;
                long wait = NANOS_PER_FRAME - elapsed;
                if (wait > 0) {
                    sleepNanos(wait);
                }
                // wait <= 0 表示这帧超预算：不追帧，直接进入下一帧
            }
        }
        host.releaseResources();
    }

    private static void sleepNanos(long nanos) {
        long ms = nanos / 1_000_000L;
        int rem = (int) (nanos % 1_000_000L);
        try {
            if (ms > 0) {
                Thread.sleep(ms, rem);
            } else {
                java.util.concurrent.locks.LockSupport.parkNanos(nanos);
            }
        } catch (InterruptedException e) {
            // 停止流程打断等待：恢复中断标志并退出
            Thread.currentThread().interrupt();
        }
    }
}
