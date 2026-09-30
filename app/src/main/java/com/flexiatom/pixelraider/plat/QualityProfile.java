package com.flexiatom.pixelraider.plat;

import android.app.ActivityManager;
import android.content.Context;

/**
 * 机型兜底档（规格 §六：初始档要按屏幕像素数与 CPU 核数共同决定，低端机默认 scale=1、画质=中，
 * 不要一上来按 2 倍缓冲渲染）。
 *
 * 只做"初始档"。运行期自适应是另一件事（三档 1/0.6/0.35、低于 ~37fps 下调、变档冷却 1.5s），
 * 在 S6 接。手动档完全跳过自适应——那个开关也一并留到 S6。
 */
public final class QualityProfile {

    public static final int QUALITY_LOW = 0;
    public static final int QUALITY_MID = 1;
    public static final int QUALITY_HIGH = 2;
    /** 自动：按帧率自适应，是唯一的"有回退"选项（只给手动档的话，弱机选"高"会一路掉到 20fps）。 */
    public static final int QUALITY_AUTO = 3;

    private QualityProfile() { }

    public static int cores() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    public static long totalRamBytes(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getApplicationContext()
                .getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return 0L;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.totalMem;
    }

    /** 粗略机型档位：核数少或内存小 → LOW。 */
    public static int initialQuality(Context ctx) {
        long ram = totalRamBytes(ctx);
        int c = cores();
        boolean heapTight = Runtime.getRuntime().maxMemory() < 160L * 1024 * 1024;
        if (c <= 4 || ram < 3L * 1024 * 1024 * 1024 || heapTight) return QUALITY_LOW;
        if (c <= 6 || ram < 6L * 1024 * 1024 * 1024) return QUALITY_MID;
        return QUALITY_HIGH;
    }

    /** 传给 Screen.scaleFor 的上限。低端机钉死 1，避免 480x860 缓冲拖垮帧时间。 */
    public static int defaultScaleCap(Context ctx) {
        return initialQuality(ctx) == QUALITY_LOW ? 1 : Screen.SCALE_MAX;
    }

    /** 粒子/碎片上限与画质档联动（规格 §六）。 */
    public static int particleBudget(int quality) {
        switch (quality) {
            case QUALITY_LOW:
                return 90;
            case QUALITY_MID:
                return 220;
            default:
                return 420;
        }
    }
}
