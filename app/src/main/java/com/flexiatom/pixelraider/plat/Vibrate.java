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
