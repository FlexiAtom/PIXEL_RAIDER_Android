/*
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
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * 震动封装（规格 §一：hit-stop 同步触发一次 EFFECT_HEAVY_CLICK，设置页可关）。
 * 需要 VIBRATE 权限；Android 12+ 走 VibratorManager。
 */
public final class Vibrate {

    private final Context ctx;
    private boolean enabled = true;
    private Vibrator cached;

    public Vibrate(Context ctx) {
        this.ctx = ctx;
    }

    public void setEnabled(boolean e) {
        enabled = e;
    }

    public boolean isEnabled() {
        return enabled;
    }

    private Vibrator vibrator() {
        if (cached != null) return cached;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vm != null) cached = vm.getDefaultVibrator();
        } else {
            cached = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        }
        return cached;
    }

    public void heavyClick() {
        if (!enabled) return;
        Vibrator v = vibrator();
        if (v == null || !v.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK));
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(24L, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            v.vibrate(24L);
        }
    }

    /** 短促确认（UI 音的触觉对应物）。 */
    public void tick() {
        if (!enabled) return;
        Vibrator v = vibrator();
        if (v == null || !v.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(10L, 80));
        } else {
            v.vibrate(10L);
        }
    }
}
