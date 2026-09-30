package com.flexiatom.pixelraider.gfx;

/**
 * 颜色 → 缓存变体槽（0..CAPACITY-1）。
 *
 * 为什么不直接把 RGB 塞进缓存 key：key 的位域是有限的，而"哪个颜色算过了"是集合语义，
 * 用槽位编号既保住 key 的紧凑，又让不同主题色必然分开。
 *
 * 单独成类（而不是留在 SpriteFactory 里）的原因是踩了两次的那个坑：SpriteFactory 有
 * static Paint 字段，在纯 JVM 单测里整类加载就抛 ExceptionInInitializerError，
 * 于是本来最该被断言的"换色不撞 key"根本测不了。
 */
public final class ColorTags {

    public static final int CAPACITY = 16;

    private static final int[] COLORS = new int[CAPACITY];
    private static int count;

    private ColorTags() { }

    /** 已登记过返回原槽；否则占下一个空槽；槽满退回 0（宁可复用旧图，也不崩在渲染中途）。 */
    public static int tagOf(int color) {
        for (int i = 0; i < count; i++) {
            if (COLORS[i] == color) return i;
        }
        if (count < CAPACITY) {
            COLORS[count] = color;
            return count++;
        }
        return 0;
    }

    public static int usedTags() { return count; }

    /** 主题色重置时清空：旧槽可被新颜色复用，否则换 17 种颜色就永远停在槽 0。 */
    public static void reset() {
        count = 0;
    }
}
