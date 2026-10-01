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
package com.flexiatom.pixelraider.core;

/**
 * 输入总线（规格 §二 / §八 单线程模型）。
 *
 * 主线程只做一件事：把事件写进 SPSC 环；渲染线程在帧首一次性消费。全程无锁、无对象分配
 * ——事件以定长 int 元组存进一个复用的 int[]，读端靠 volatile 写序拿到已发布的完整元组。
 *
 * 坐标在写入端就反算成逻辑坐标（Screen.Metrics 是不可变快照，volatile 发布）。
 */
public final class InputRouter {

    public static final int EV_POINTER_DOWN = 1;
    public static final int EV_POINTER_MOVE = 2;
    public static final int EV_POINTER_UP = 3;
    public static final int EV_DOUBLE_TAP = 4;
    public static final int EV_KEY_DOWN = 5;
    public static final int EV_KEY_UP = 6;
    public static final int EV_BACK = 7;
    public static final int EV_RESUME = 8;
    public static final int EV_PAUSE = 9;
    /** 仅 debug 包：作弊键，让真机取证不必靠打满一局才能看见结算链。 */
    public static final int EV_DEBUG_KILL = 10;
    /** 事件无效槽位标记。 */
    public static final int EV_NONE = 0;

    private static final int STRIDE = 5;
    private static final int CAP = 256; // 2 的幂

    private final int[] ring = new int[CAP * STRIDE];
    private volatile int head;
    private volatile int tail;

    private volatile com.flexiatom.pixelraider.plat.Screen.Metrics metrics;

    public void setMetrics(com.flexiatom.pixelraider.plat.Screen.Metrics m) {
        metrics = m;
    }

    /** 一次取出的事件；字段复用，避免每事件 new。 */
    public static final class Event {
        public int kind;
        public int pointerId;
        public int x;
        public int y;
        public int extra;
    }

    /** 生产端（主线程）。rawX/rawY 为 SurfaceView 所在窗口的物理像素。 */
    public boolean offer(int kind, int pointerId, float rawX, float rawY, int extra) {
        com.flexiatom.pixelraider.plat.Screen.Metrics m = metrics;
        int lx = m != null ? m.toLogicX(rawX) : -1;
        int ly = m != null ? m.toLogicY(rawY) : -1;
        return offerLogic(kind, pointerId, lx, ly, extra);
    }

    public boolean offerLogic(int kind, int pointerId, int logicX, int logicY, int extra) {
        int h = head;
        int next = (h + 1) & (CAP - 1);
        if (next == (tail & (CAP - 1))) {
            return false; // 环满：丢事件，绝不扩容（扩容=帧内分配）
        }
        int b = h * STRIDE;
        ring[b] = kind;
        ring[b + 1] = pointerId;
        ring[b + 2] = logicX;
        ring[b + 3] = logicY;
        ring[b + 4] = extra;
        head = next;
        return true;
    }

    /** 消费端（渲染线程）。取到一条事件返回 true；环空返回 false。 */
    public boolean poll(Event out) {
        int t = tail;
        if (t == head) {
            return false;
        }
        int b = t * STRIDE;
        out.kind = ring[b];
        out.pointerId = ring[b + 1];
        out.x = ring[b + 2];
        out.y = ring[b + 3];
        out.extra = ring[b + 4];
        tail = (t + 1) & (CAP - 1);
        return true;
    }

    /** 调试/单测用：当前排队事件数。 */
    public int pending() {
        int delta = head - tail;
        return delta < 0 ? delta + CAP : delta;
    }
}
