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

/**
 * 游戏时间定时器（规格 §八：不用 Handler.postDelayed）。
 *
 * 用 Handler 的两个后果它都躲不掉——真实时间在暂停时照样流逝（暂停了特效照触发），
 * 且 Message 持有 Activity 引用会在返回主菜单后继续触发并泄漏。
 * 这里只登记"到点后往输入环里投一条命令"，触发时机一律跟着 Time.game()。
 *
 * 定长池 + 活跃计数，零分配。
 */
public final class Scheduler {

    private static final int CAP = 32;

    private final float[] dueAt = new float[CAP];
    private final int[] command = new int[CAP];
    private final boolean[] live = new boolean[CAP];
    private int count;

    /** 同 id 重复登记时覆盖（延迟特效不该因连点而堆叠）。 */
    public void scheduleAfter(float delaySeconds, int commandId, float nowGame) {
        for (int i = 0; i < CAP; i++) {
            if (live[i] && command[i] == commandId) {
                dueAt[i] = nowGame + Math.max(0f, delaySeconds);
                return;
            }
        }
        if (count == CAP) return;
        for (int i = 0; i < CAP; i++) {
            if (!live[i]) {
                live[i] = true;
                command[i] = commandId;
                dueAt[i] = nowGame + Math.max(0f, delaySeconds);
                count++;
                return;
            }
        }
    }

    public void cancel(int commandId) {
        for (int i = 0; i < CAP; i++) {
            if (live[i] && command[i] == commandId) {
                live[i] = false;
                command[i] = 0;
                count--;
            }
        }
    }

    public void clear() {
        for (int i = 0; i < CAP; i++) {
            live[i] = false;
            command[i] = 0;
        }
        count = 0;
    }

    /** 到期的命令写进 out（由调用方在帧尾消费）；返回本次触发数。 */
    public int fireDue(float nowGame, int[] out, int outMax) {
        int fired = 0;
        for (int i = 0; i < CAP && fired < outMax; i++) {
            if (live[i] && dueAt[i] <= nowGame) {
                live[i] = false;
                count--;
                out[fired++] = command[i];
                command[i] = 0;
            }
        }
        return fired;
    }

    public int pending() {
        return count;
    }
}
