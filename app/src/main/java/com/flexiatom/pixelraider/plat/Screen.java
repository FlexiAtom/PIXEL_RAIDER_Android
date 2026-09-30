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
package com.flexiatom.pixelraider.plat;

/**
 * 逻辑画布与像素缓冲的唯一尺寸真源（纯函数，可脱离设备在 JVM 单测直接验证）。
 *
 * <p><b>2026-09-24 决策变更：屏幕适配。</b>规格 line 36 原本要求「逻辑画布居中、留黑边」，
 * 落地成 LOGIC_H 钳在 430 + 等比 letterbox。真机实测（720x1600）上下共 310px 纯黑，
 * 占掉近 20% 屏高，已舍弃。现在的口径是**让逻辑高度随屏幕比例生长**：
 * LOGIC_H = round(240 * 屏幕宽高比)，上限放到 560（覆盖到 21:9），于是缓冲比例与屏幕比例
 * 一致，fit 只由 rounding 留下 1~2px 余量——黑边实际消失，而像素仍是正方形。
 *
 * <p>被这次决策一并剪掉的：{@code FillMode} 枚举。它的另一档 FILL_STRETCH 是「铺满但把
 * 像素纵向拉伸 1.24 倍」，像素风最忌讳，从来没有任何调用方选它，只有单测在跑——死分支。
 *
 * <p>安全区不再缩画面：挖孔/状态栏/手势条换算成逻辑像素的 {@code safe*} 内缩量，
 * 由 {@link Metrics#hudTop()} 这类出口推 UI，画面本身铺满整窗。
 *
 * <p>还没做、另立提案排期的（决策一里的 L2 档）：让 LOGIC_W 也生长，换取**整数倍**缩放。
 * 现在 fit 仍是非整数（720 宽上是 1.5），每个逻辑像素占 1.5 个设备像素，边缘疏密略不均；
 * 这与改决策之前完全一样，不是新引入的损失。要吃掉它得把所有以 240 为 static final 的
 * 布局纯函数改成运行时尺寸驱动，是一次独立重构。
 */
public final class Screen {

    public static final int LOGIC_W = 240;
    public static final int BATTLE_H = 320;
    public static final int LOGIC_H_MIN = 320;
    /** 21:9 屏幕（aspect 2.333）对应 560；再长的机型是极少数，退回黑边而不是撑坏布局。 */
    public static final int LOGIC_H_MAX = 560;
    public static final int SCALE_MIN = 1;
    public static final int SCALE_MAX = 2;
    public static final float MIN_TOUCH_DP = 48f;

    private Screen() { }

    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static float clampf(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** LOGIC_H = clamp(round(240 * aspect), 320, 560)——长屏靠生长高度铺满，不靠拉伸像素。 */
    public static int logicHeightFor(int wpx, int hpx) {
        if (wpx <= 0 || hpx <= 0) return LOGIC_H_MIN;
        return clamp(Math.round(LOGIC_W * (float) hpx / (float) wpx), LOGIC_H_MIN, LOGIC_H_MAX);
    }

    /** scale = clamp(floor(min(screenWpx/240, screenHpx/320)), 1, 2)；scaleCap 承载低端机兜底。 */
    public static int scaleFor(int wpx, int hpx, int scaleCap) {
        if (wpx <= 0 || hpx <= 0) return SCALE_MIN;
        int fit = Math.min(wpx / LOGIC_W, hpx / BATTLE_H);
        return clamp(fit, SCALE_MIN, Math.max(SCALE_MIN, Math.min(SCALE_MAX, scaleCap)));
    }

    /** 不可变尺寸快照。所有字段在构造时算完，运行期只读。 */
    public static final class Metrics {
        public final int windowW, windowH;
        public final int scale, bufW, bufH, logicH;
        /** SurfaceView 在窗口内的实际矩形：offset 与 pxPerLogic 都由它导出。 */
        public final int surfaceX, surfaceY, surfaceW, surfaceH;
        public final float pxPerLogicX, pxPerLogicY;

        /** 安全区四边的内缩量，**逻辑像素**（向上取整：宁可多让 1px，不可让挖孔压住字）。 */
        public final int safeLeft, safeTop, safeRight, safeBottom;

        Metrics(int windowW, int windowH, int scale, int logicH,
                int surfaceX, int surfaceY, int surfaceW, int surfaceH,
                int insetL, int insetT, int insetR, int insetB) {
            this.windowW = windowW;
            this.windowH = windowH;
            this.scale = scale;
            this.logicH = logicH;
            this.bufW = LOGIC_W * scale;
            this.bufH = logicH * scale;
            this.surfaceX = surfaceX;
            this.surfaceY = surfaceY;
            this.surfaceW = surfaceW;
            this.surfaceH = surfaceH;
            this.pxPerLogicX = surfaceW / (float) LOGIC_W;
            this.pxPerLogicY = surfaceH / (float) logicH;
            this.safeTop = clamp((int) Math.ceil(insetT / this.pxPerLogicY), 0, logicH);
            this.safeBottom = clamp((int) Math.ceil(insetB / this.pxPerLogicY), 0, logicH - safeTop);
            this.safeLeft = clamp((int) Math.ceil(insetL / this.pxPerLogicX), 0, LOGIC_W);
            this.safeRight = clamp((int) Math.ceil(insetR / this.pxPerLogicX), 0, LOGIC_W - safeLeft);
        }

        /**
         * 战场原点 = 安全区顶边。
         *
         * <p><b>2026-09-24 适配决策第二步。</b>这里原本返回 {@code (logicH - BATTLE_H) / 2}，
         * 也就是把战场当成画布中央一条 240×320 的带子——那是黑边时代的产物：画布被钳在 430 高，
         * 战场居中看起来"对称"。钳位一撤，这条带子两侧各多出上百逻辑像素的空白，
         * 而玩家的操作区域仍然只有那 320，就是真机上"战机飞不出中间那块"的根因。
         *
         * <p>现在的口径：<b>战场就是整张可安全显示的画布</b>，HUD 作为覆盖层压在它上面。
         * 规格 §四 本来就把 HUD 画在战斗区之内（320 高的画布上原点就是 0），所以这不是偏离规格，
         * 而是把同一条规则推广到长屏。敌机的驻场线一律相对本值给，见 {@code EnemyBehavior}。
         */
        public int battleTop() {
            return safeTop;
        }

        /** 战场底边：让开底部安全区（手势条），不然战机能飞进手势热区。 */
        public int battleBottom() {
            return logicH - safeBottom;
        }

        /** 战场高度：随屏幕比例生长，恒等于 {@link #battleBottom()} − {@link #battleTop()}。 */
        public int battleHeight() {
            return battleBottom() - battleTop();
        }

        /**
         * 整页模态（结算页）的居中原点：内容高 {@code contentH} 那一页在画布里居中，
         * **不跟着战场长高**——和战场的分账是两件事，别混用。
         *
         * <p>页高由那一页自己报（{@code ResultLayout.PAGE_H}），不再假定它恒等于
         * {@link #BATTLE_H}：汉字抬到 12px 网格之后那一页变成了 315 高。
         */
        public int pageTop(int contentH) {
            return Math.max(0, (logicH - contentH) / 2);
        }

        /**
         * HUD 那一组的顶边。HUD 钉在**屏幕顶 + 安全区**，不跟着战斗区跑：
         * 画布高度是按屏幕比例长出来的，跟着旧的居中战场走会让整块读数悬浮在屏幕中段，
         * 上面留一条纯背景空白——那正是"UI 不自适应"的样子。
         */
        public int hudTop() {
            return safeTop;
        }

        /** 规格 §二：触摸坐标必须先反算回逻辑坐标。 */
        public int toLogicX(float rawX) {
            return (int) ((rawX - surfaceX) / pxPerLogicX);
        }

        public int toLogicY(float rawY) {
            return (int) ((rawY - surfaceY) / pxPerLogicY);
        }

        /**
         * 适配决策之后的这条是**回归哨兵**：正常机型应当只剩 1~2px 的 rounding 余量。
         * 它变大就说明这台机器的比例越过了 LOGIC_H_MAX，退回黑边而不是撑坏布局。
         */
        public int letterboxBarsPx() {
            return windowH - surfaceH;
        }

        /**
         * 几何等价：只比**独立字段**——bufW/bufH、pxPerLogic 与 battle/hud 各出口全是这些数的函数，
         * 比它们不增加信息。等价 ⟹ 由它导出的所有布局也等价。
         */
        public boolean sameShapeAs(Metrics o) {
            return o != null
                    && windowW == o.windowW && windowH == o.windowH
                    && scale == o.scale && logicH == o.logicH
                    && surfaceX == o.surfaceX && surfaceY == o.surfaceY
                    && surfaceW == o.surfaceW && surfaceH == o.surfaceH
                    && safeLeft == o.safeLeft && safeTop == o.safeTop
                    && safeRight == o.safeRight && safeBottom == o.safeBottom;
        }
    }

    /**
     * @param wpx/hpx 屏幕物理像素
     * @param insetLeft/Top/Right/Bottom 需避让的安全区（挖孔/状态栏/手势条），px。
     *        它们**不再缩小画面**，只换算成 {@link Metrics#safeTop} 等逻辑内缩量推 UI——
     *        画面铺满整窗，黑边就此消失。
     * @param scaleCap 机型兜底：低端机传 1
     */
    public static Metrics compute(int wpx, int hpx,
                                  int insetLeft, int insetTop, int insetRight, int insetBottom,
                                  int scaleCap) {
        int windowW = Math.max(1, wpx);
        int windowH = Math.max(1, hpx);
        int scale = scaleFor(windowW, windowH, scaleCap);
        int logicH = logicHeightFor(windowW, windowH);
        int bufW = LOGIC_W * scale;
        int bufH = logicH * scale;

        // 等比缩放保方形像素；logicH 已按屏幕比例生长，所以这里几乎不再产生黑边。
        float fit = Math.min(windowW / (float) bufW, windowH / (float) bufH);
        int surfaceW = Math.max(1, (int) Math.floor(bufW * fit));
        int surfaceH = Math.max(1, (int) Math.floor(bufH * fit));
        int surfaceX = (windowW - surfaceW) / 2;
        int surfaceY = (windowH - surfaceH) / 2;
        return new Metrics(windowW, windowH, scale, logicH, surfaceX, surfaceY, surfaceW, surfaceH,
                insetLeft, insetTop, insetRight, insetBottom);
    }

    /** 48dp 触控标准换算到逻辑像素，用于命中区外扩（规格 §二）。 */
    public static int minTouchLogic(float pxPerLogic, float density) {
        if (pxPerLogic <= 0f || density <= 0f) return 0;
        return (int) Math.ceil(MIN_TOUCH_DP * density / pxPerLogic);
    }
}
