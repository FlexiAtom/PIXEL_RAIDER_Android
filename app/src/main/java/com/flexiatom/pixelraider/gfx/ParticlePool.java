package com.flexiatom.pixelraider.gfx;

/**
 * 粒子池（规格 §三 / §六）：定长对象数组 + 活跃计数 + free-list，每帧零分配。
 *
 * 为什么不用 ArrayList.remove：装箱 + 数组拷贝，而且删除会把整条活跃列表搬一次，
 * 满屏粒子时这就是每帧最大的临时对象来源。这里删一个粒子只做一次末位交换（swap-remove），
 * 对象本身永远留在 objs[] 里等复用。
 *
 * ⚠ 复用时必须重置全部字段（含 px/py 上一帧位置）。漏一个就是"新粒子带旧粒子的颜色"那种
 * 鬼影 bug，而且只在池转起来之后才出现，最难复现。reset() 集中做这件事，
 * ParticlePoolTest 用反射遍历所有字段守住它。
 *
 * 本类不 import android.graphics：粒子只有 float/int 字段，绘制端从池里读值自己画。
 */
public final class ParticlePool {

    public static final class Particle {
        public static final int KIND_SQUARE = 0;
        public static final int KIND_TRAIL = 1;
        public static final int KIND_EMBER = 2;
        public static final int KIND_DEBRIS = 3;

        public float x, y;
        public float vx, vy;
        /** 上一帧位置，拖尾按速度矢量拉楔形要用它；复用必须重置，否则第一帧拖尾横跨全屏。 */
        public float px, py;
        public float life;
        public float maxLife;
        public float size;
        public float drag;
        public float gravity;
        public float spin;
        public float angle;
        public int color;
        /** {@code KIND_*} */
        public int kind;
        public boolean fadeWithLife;

        void reset() {
            x = 0f; y = 0f;
            vx = 0f; vy = 0f;
            px = 0f; py = 0f;
            life = 0f; maxLife = 0f;
            size = 0f; drag = 0f; gravity = 0f;
            spin = 0f; angle = 0f;
            color = 0; kind = 0; fadeWithLife = false;
        }
    }

    private final Particle[] objs;
    private final int[] slotOfObj;   // obj 索引 → 在 active[] 中的位置，-1 = 不在活跃表
    private final int[] active;      // 活跃 obj 索引，前 activeCount 个有效
    private final int[] free;        // 空闲 obj 索引栈
    private final boolean[] everUsed;
    private int activeCount;
    private int freeCount;
    private long spawnTotal;
    private long reuseTotal;

    public ParticlePool(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        objs = new Particle[capacity];
        slotOfObj = new int[capacity];
        active = new int[capacity];
        free = new int[capacity];
        everUsed = new boolean[capacity];
        for (int i = 0; i < capacity; i++) {
            objs[i] = new Particle();
            slotOfObj[i] = -1;
            free[i] = capacity - 1 - i;  // 弹出顺序 0,1,2...
        }
        freeCount = capacity;
    }

    public int capacity() { return objs.length; }
    public int activeCount() { return activeCount; }
    public int freeCount() { return freeCount; }
    public long spawnTotal() { return spawnTotal; }
    /** 从池里第二次起取出同一对象的次数 = 复用数；稳定态应等于 spawnTotal - capacity。 */
    public long reuseTotal() { return reuseTotal; }

    /** 唯一入口（规格 §三：spawnParticle 是唯一入口）。池空返回 null，调用方自行降级不生成。 */
    public Particle spawn() {
        if (freeCount == 0) return null;
        int oi = free[--freeCount];
        Particle p = objs[oi];
        p.reset();
        if (everUsed[oi]) reuseTotal++;
        everUsed[oi] = true;
        spawnTotal++;
        slotOfObj[oi] = activeCount;
        active[activeCount++] = oi;
        return p;
    }

    public Particle activeAt(int i) {
        return i < 0 || i >= activeCount ? null : objs[active[i]];
    }

    /** 移除活跃表中第 i 个：末位交换，O(1)。 */
    public void killAt(int i) {
        if (i < 0 || i >= activeCount) return;
        int oi = active[i];
        int moved = active[activeCount - 1];
        active[i] = moved;
        slotOfObj[moved] = i;
        activeCount--;
        slotOfObj[oi] = -1;
        free[freeCount++] = oi;
    }

    /**
     * 推进 + 回收已死粒子。**倒序遍历 + 末位交换**，正序会因交换把还没检查的粒子跳过去。
     * @return 本帧回收数
     */
    public int stepAndCompact(float dt) {
        int killed = 0;
        for (int i = activeCount - 1; i >= 0; i--) {
            Particle p = objs[active[i]];
            p.px = p.x;
            p.py = p.y;
            p.vy += p.gravity * dt;
            if (p.drag > 0f) {
                float k = 1f / (1f + p.drag * dt);
                p.vx *= k;
                p.vy *= k;
            }
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.angle += p.spin * dt;
            p.life -= dt;
            if (p.life <= 0f) {
                killAt(i);
                killed++;
            }
        }
        return killed;
    }

    /** 越界回收（视口外），坐标系是 0..w × 0..h 的逻辑画布。 */
    public int cullOutOfBounds(int w, int h, int margin) {
        int killed = 0;
        for (int i = activeCount - 1; i >= 0; i--) {
            Particle p = objs[active[i]];
            if (p.x < -margin || p.y < -margin || p.x > w + margin || p.y > h + margin) {
                killAt(i);
                killed++;
            }
        }
        return killed;
    }

    public void clear() {
        for (int i = 0; i < activeCount; i++) {
            int oi = active[i];
            objs[oi].reset();
            slotOfObj[oi] = -1;
            free[freeCount++] = oi;
        }
        activeCount = 0;
    }
}
