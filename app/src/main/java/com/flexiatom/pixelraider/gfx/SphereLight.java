package com.flexiatom.pixelraider.gfx;

/**
 * 行星光照的纯亮度函数（规格 §三第 3 层：统一光照来自左上 45°，右下自动变暗，带 rim light）。
 *
 * 从 Background 里拆出来是因为 Background 有 static Paint/Xfermode——那些在纯 JVM 单测里会让
 * 整个类加载失败（ExceptionInInitializerError），于是"光照方向画反了"这种只能靠断言发现的错
 * 就发现不了。光照对不对，肉眼在一张 72px 行星上很难判，断言能判。
 */
public final class SphereLight {

    /** 光源方向：左上 45°，屏幕 y 轴向下，所以 x/y 都取负。 */
    public static final float LIGHT_X = -0.7071f;
    public static final float LIGHT_Y = -0.7071f;
    /** 背光面保留的环境光：纯黑会让行星像个剪影而不是球。 */
    public static final float AMBIENT = 0.12f;
    /** rim light 起始半径比例：内面不发光，只亮最外一圈。 */
    public static final float RIM_START = 0.72f;

    private SphereLight() { }

    /**
     * Lambert 漫反射。
     * @param nx,ny 球面坐标（-1..1，原点在球心，ny 向下）
     * @param nz 朝向观察者的深度分量（0..1）
     * @return 0..1 亮度
     */
    public static float lambert(float nx, float ny, float nz) {
        float d = nx * LIGHT_X + ny * LIGHT_Y + nz * 0f;
        float diff = d < 0f ? 0f : d;
        return AMBIENT + (1f - AMBIENT) * Math.min(1f, diff);
    }

    /**
     * rim light：落在光照反方向（右下）**边缘**的一条细亮边。
     * 用随 r 单调上升的掠射带，不用在 r=1 处收 0 的衰减函数——后者把最外圈正好算成黑，
     * 而 rim 要的就是那一圈。
     */
    public static float rimLight(float nx, float ny) {
        float r = (float) Math.sqrt(nx * nx + ny * ny);
        if (r <= 0.0001f) return 0f;
        float ux = nx / r, uy = ny / r;
        float align = ux * -LIGHT_X + uy * -LIGHT_Y;    // 与光源反向越一致越亮
        float grazing = (r - RIM_START) / (1f - RIM_START);
        if (grazing < 0f) grazing = 0f;
        else if (grazing > 1f) grazing = 1f;
        return Math.max(0f, align) * grazing;
    }
}
