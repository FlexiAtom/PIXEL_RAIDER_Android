package com.flexiatom.pixelraider.ui;

/**
 * MD3 面板层的**唯一取色与形状出处**（用户裁决：只改面板层，战场与 HUD 不动，不引入 Material 依赖）。
 *
 * MD3 的核心不是某几个颜色，是"**从一个 seed 派生一整套色阶，界面只引用语义角色**"。
 * 所以这里不写死二十个十六进制数，而是：
 * <ul>
 *   <li>{@link #applySeed} —— seed → 0..100 明度阶（tonal palette）。tone 按 **CIE L\*** 定义，
 *       与 MD3 同一个数；色相保持、彩度不重算（真 MD3 走 HCT）。
 *       这是**有意的偏离**（换真 HCT 等于引第三方库，已拒绝），代价记在池提案里。</li>
 *   <li>一组**角色名**，tone 数字照抄 MD3 暗色方案的官方值。</li>
 *   <li>形状、状态层、字阶同理：只暴露语义名，面板不许自带字面量颜色。</li>
 * </ul>
 *
 * 层级表达也跟着 MD3：**用容器色阶差表达 elevation，不加阴影、不加模糊**。
 * 这与规格 §三既有的"描边分层"是两种语法，本档选了前者。
 *
 * 静态可变（与 {@code Balance} 同一立场）：主题切换 = 重建那张 101 格的表。
 * 只有启动与设置保存两个时机走得到，都在渲染线程（§八 单线程模型），所以不需要锁。
 */
public final class Md3 {

    /** 默认 seed：沿用工程既有的探针青，面板与战场同源，不至于出现两套色。 */
    public static final int DEFAULT_SEED = 0xFF00E5FF;

    /** seed 去彩比例：0 = 完全跟随 seed，1 = 纯灰。面板底要"带一点味道"，不能是一坨青。 */
    public static final float DESATURATION = 0.78f;

    /** 近似偏离的落点：直接读某一档明度（0..100），下面的角色名都是它的一个别名。 */
    public static final int TONE_MIN = 0;
    public static final int TONE_MAX = 100;

    public static final int TONE_SURFACE = 6;
    public static final int TONE_SURFACE_DIM = 4;
    public static final int TONE_SURFACE_BRIGHT = 24;
    public static final int TONE_CONTAINER_LOWEST = 4;
    public static final int TONE_CONTAINER_LOW = 10;
    public static final int TONE_CONTAINER = 12;
    public static final int TONE_CONTAINER_HIGH = 17;
    public static final int TONE_CONTAINER_HIGHEST = 22;
    public static final int TONE_ON_SURFACE = 90;
    public static final int TONE_ON_SURFACE_VARIANT = 80;
    public static final int TONE_OUTLINE = 60;
    public static final int TONE_OUTLINE_VARIANT = 40;
    public static final int TONE_PRIMARY = 80;
    public static final int TONE_ON_PRIMARY = 20;
    public static final int TONE_PRIMARY_CONTAINER = 30;
    public static final int TONE_ON_PRIMARY_CONTAINER = 90;
    public static final int TONE_SECONDARY_CONTAINER = 32;
    public static final int TONE_ON_SECONDARY_CONTAINER = 90;

    /**
     * error 三件套**不走 seed 派生**：MD3 的 error 色板是一组固定值（它不参与动态取色），
     * 跟着 seed 走会变成"青色的危险"——语义直接失效。这三个数就是 MD3 暗色方案的官方值。
     */
    public static final int MD3_ERROR = 0xFFF2B8B5;
    public static final int MD3_ERROR_CONTAINER = 0xFF8C1D18;
    public static final int MD3_ON_ERROR_CONTAINER = 0xFFF9DEDC;

    private static final int[] PALETTE = new int[TONE_MAX + 1];
    private static int currentSeed = DEFAULT_SEED;

    static {
        applySeed(DEFAULT_SEED);
    }

    private Md3() { }

    // ---- 角色（面板只引用这一层）----------------------------------------------------

    public static int surface() { return PALETTE[TONE_SURFACE]; }
    public static int surfaceDim() { return PALETTE[TONE_SURFACE_DIM]; }
    public static int surfaceBright() { return PALETTE[TONE_SURFACE_BRIGHT]; }
    public static int surfaceContainerLowest() { return PALETTE[TONE_CONTAINER_LOWEST]; }
    public static int surfaceContainerLow() { return PALETTE[TONE_CONTAINER_LOW]; }
    public static int surfaceContainer() { return PALETTE[TONE_CONTAINER]; }
    public static int surfaceContainerHigh() { return PALETTE[TONE_CONTAINER_HIGH]; }
    public static int surfaceContainerHighest() { return PALETTE[TONE_CONTAINER_HIGHEST]; }
    public static int onSurface() { return PALETTE[TONE_ON_SURFACE]; }
    public static int onSurfaceVariant() { return PALETTE[TONE_ON_SURFACE_VARIANT]; }
    public static int outline() { return PALETTE[TONE_OUTLINE]; }
    public static int outlineVariant() { return PALETTE[TONE_OUTLINE_VARIANT]; }
    /** 实心按钮的底：MD3 暗色方案下 primary 是浅粉彩 + 深字，这一对在暗 UI 里正是"按我"的信号。 */
    public static int primary() { return PALETTE[TONE_PRIMARY]; }
    public static int onPrimary() { return PALETTE[TONE_ON_PRIMARY]; }
    public static int primaryContainer() { return PALETTE[TONE_PRIMARY_CONTAINER]; }
    public static int onPrimaryContainer() { return PALETTE[TONE_ON_PRIMARY_CONTAINER]; }
    public static int secondaryContainer() { return PALETTE[TONE_SECONDARY_CONTAINER]; }
    public static int onSecondaryContainer() { return PALETTE[TONE_ON_SECONDARY_CONTAINER]; }
    public static int errorContainer() { return MD3_ERROR_CONTAINER; }
    public static int onErrorContainer() { return MD3_ON_ERROR_CONTAINER; }
    /** 破坏性动作的文字色（重开本局、删除）：MD3 的 destructive text button 就是这个。 */
    public static int error() { return MD3_ERROR; }

    /** 越界明度只可能来自调试面板改表：钳住，别让数组越界把渲染线程带走。 */
    public static int tone(int t) {
        return PALETTE[t < TONE_MIN ? TONE_MIN : (t > TONE_MAX ? TONE_MAX : t)];
    }

    public static int seed() {
        return currentSeed;
    }

    // ---- 形状：MD3 五档圆角，按参考宽 360dp ↔ 240 逻辑像素折算 -------------------------

    /**
     * dp → 逻辑像素。写死这个比例是因为逻辑宽本来就是固定 240（规格 §二），不是按 dp 布局；
     * 28dp 那档（19 格）留给"胶囊"级别的元素，面板上的卡片与按钮用小得多的一档。
     */
    public static int shapeForDp(int dp) {
        return Math.round(dp * 240 / 360f);
    }

    public static final int R_EXTRA_SMALL = shapeForDp(4);      // 3：图标底、小徽片
    public static final int R_SMALL = shapeForDp(8);            // 5：按钮
    public static final int R_MEDIUM = shapeForDp(12);          // 8：卡片、分段控件
    public static final int R_LARGE = shapeForDp(16);           // 11：面板
    public static final int R_EXTRA_LARGE = shapeForDp(28);     // 19：抽屉顶角

    // ---- 状态层（MD3：叠一层 onState，不换底色）--------------------------------------

    /** 255 的 10%/12%/15%——pressed / focus / dragged。触屏只有前者用得上。 */
    public static final int STATE_PRESSED_ALPHA = 26;
    public static final int STATE_FOCUS_ALPHA = 31;
    public static final int STATE_DRAGGED_ALPHA = 38;
    /** 禁用态按 MD3 是 38% on-surface：千分比避免浮点常量到处飞。 */
    public static final int DISABLED_ALPHA_PERMILLE = 380;

    // ---- 字阶语义名 ----------------------------------------------------------------------------
    //
    // **汉字只有 12 与 24 两档**，这是内嵌像素字体（{@code TextCache.GRID_PX}）定的物理上限：
    // 像素字按非原生网格渲染会把等宽的像素格变成宽窄不均的糊边，所以四档 MD3 角色只能并到
    // 两个真实字号上，层级改由**颜色**承担（MD3 本来就说颜色表达语义角色）。
    // ASCII 数字不受这条约束——它走自绘点阵 {@code BitmapFont}，仍是 8/16。
    public static final int PX_LABEL = 12;      // label-small / label-medium：卡片标题、次要说明
    public static final int PX_BODY = 12;       // body-large：按钮文字
    public static final int PX_TITLE = 24;      // headline-small：面板标题
    public static final int PX_DISPLAY = 24;    // display-small：结算页大标题

    // ---- 色阶派生 -------------------------------------------------------------------

    /**
     * 换主题就重建这张表：101 个 int，只在启动与设置保存时走（每帧路径只读表，不重算）。
     *
     * tone 按 **CIE L\*** 定义（MD3 的 tone 就是这个数）：先把 seed 去彩成"带味道的灰"，
     * 再把它在线性光里等比缩放到目标相对亮度。于是
     * 色相保持不变、明度阶是感知均匀的，tone 30 与 tone 90 之间的对比真的拉得开
     * ——早先用"向黑/白线性混合"做时，tone 32 的底配 tone 90 的字只有 4.3:1，读起来是糊的。
     *
     * 仍然不是完整 HCT：彩度不随明度重算，高 tone 会因通道削顶而偏淡。这层偏离写在池提案里，
     * 补它的代价是引一个色彩库，已经被"零第三方依赖"拒掉。
     */
    public static void applySeed(int seed) {
        currentSeed = seed;
        int tinted = mix2(seed, lumaOf(seed), DESATURATION);
        double rl = toLinear(tinted >> 16 & 0xFF);
        double gl = toLinear(tinted >> 8 & 0xFF);
        double bl = toLinear(tinted & 0xFF);
        double base = 0.2126 * rl + 0.7152 * gl + 0.0722 * bl;
        if (base < 1e-4) base = 1e-4;        // 近乎纯黑的 seed：除零会把整张表打成 NaN
        for (int t = TONE_MIN; t <= TONE_MAX; t++) {
            double s = targetLuminance(t) / base;
            PALETTE[t] = 0xFF000000 | (fromLinear(rl * s) << 16)
                    | (fromLinear(gl * s) << 8) | fromLinear(bl * s);
        }
    }

    /** CIE 的 L\* → 相对亮度（L ≤ 8 走线性段，否则立方段）。 */
    private static double targetLuminance(int tone) {
        if (tone <= 8) return tone / 903.3;
        double v = (tone + 16.0) / 116.0;
        return v * v * v;
    }

    private static double toLinear(int channel) {
        double v = channel / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static int fromLinear(double v) {
        double c = v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1.0 / 2.4) - 0.055;
        int out = (int) Math.round(c * 255.0);
        return out < 0 ? 0 : (out > 255 ? 255 : out);
    }

    private static int lumaOf(int argb) {
        int r = argb >> 16 & 0xFF, g = argb >> 8 & 0xFF, b = argb & 0xFF;
        int y = (r * 299 + g * 587 + b * 114) / 1000;
        return 0xFF000000 | (y << 16) | (y << 8) | y;
    }

    /** 不透明色之间的线性混合（alpha 恒 255：淡入只走 {@code Paint.setAlpha}，口径在 {@link DrawKit}）。 */
    private static int mix2(int from, int to, float k) {
        float t = Float.isNaN(k) ? 0f : (k < 0f ? 0f : (k > 1f ? 1f : k));
        int r = Math.round((from >> 16 & 0xFF) * (1f - t) + (to >> 16 & 0xFF) * t);
        int g = Math.round((from >> 8 & 0xFF) * (1f - t) + (to >> 8 & 0xFF) * t);
        int b = Math.round((from & 0xFF) * (1f - t) + (to & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
