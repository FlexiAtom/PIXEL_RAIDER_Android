package com.flexiatom.pixelraider.game;

/**
 * 掉落物池（规格 §六 掉落与保底）。
 *
 * 三件规格明写的事都在这里：
 * - 芯片：击杀 20%，受幸运/贫瘠影响（{@link #chipRoll}）；
 * - Boss 必掉 3 枚（{@link #bossChips}）；
 * - **第 2 波起每波保底 1 枚**——纯概率下连续十几个怪不掉是可能的，
 *   那种"打完一波什么都没有"的挫败感远大于概率本身的收益，所以保底是硬规则。
 *
 * 掉落物会缓慢下坠并被玩家吸住（磁力道具只是把吸引半径拉到全屏）。
 * 出界即回收，永不参与子弹网格。
 */
public final class Drops {

    /** 类型索引固定，永不换位（同道具栏的规则）。新增必须同步 {@link #label}。 */
    public static final int CHIP = 0, COIN = 1, HP = 2, SHIELD = 3, POWERUP = 4, BOMB = 5;
    public static final int KIND_COUNT = 6;

    public static final class Item {
        public int kind;
        /** 数值含义由 kind 决定：芯片=枚数、金币=数额、血/盾=点数、道具=道具索引。 */
        public int value;
        public float x, y;
        public float px, py;
        public float vy;
        public float age;
        public boolean magnetized;

        void reset() {
            kind = 0; value = 0;
            x = 0f; y = 0f;
            px = 0f; py = 0f;
            vy = 0f; age = 0f;
            magnetized = false;
        }
    }

    private final Item[] objs;
    private final int[] slotOfObj;
    private final int[] active;
    private final int[] free;
    private int activeCount;
    private int freeCount;
    private long spawnTotal;
    private long reuseTotal;
    private final boolean[] everUsed;

    public Drops(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        objs = new Item[capacity];
        slotOfObj = new int[capacity];
        active = new int[capacity];
        free = new int[capacity];
        everUsed = new boolean[capacity];
        for (int i = 0; i < capacity; i++) {
            objs[i] = new Item();
            slotOfObj[i] = -1;
            free[i] = capacity - 1 - i;
        }
        freeCount = capacity;
    }

    public int capacity() { return objs.length; }
    public int activeCount() { return activeCount; }
    public int freeCount() { return freeCount; }
    public long spawnTotal() { return spawnTotal; }
    public long reuseTotal() { return reuseTotal; }

    /** 池满返回 null：屏幕上已经有足够多的东西在闪，少一枚玩家看不出来。 */
    public Item spawn(int kind, int value, float x, float y) {
        if (freeCount == 0) return null;
        int oi = free[--freeCount];
        Item it = objs[oi];
        it.reset();
        if (!everUsed[oi]) everUsed[oi] = true; else reuseTotal++;
        spawnTotal++;
        it.kind = kind;
        it.value = value;
        it.x = it.px = x;
        it.y = it.py = y;
        it.vy = DROP_FALL;
        slotOfObj[oi] = activeCount;
        active[activeCount++] = oi;
        return it;
    }

    public Item activeAt(int i) {
        return i < 0 || i >= activeCount ? null : objs[active[i]];
    }

    public void killAt(int i) {
        if (i < 0 || i >= activeCount) return;
        int oi = active[i];
        activeCount--;
        int moved = active[activeCount];
        active[i] = moved;
        slotOfObj[moved] = i;
        slotOfObj[oi] = -1;
        free[freeCount++] = oi;
    }

    /** 下坠 + 被吸引；返回捡到的数量，捡到的同时就地回收（倒序遍历，末位交换不漏检）。 */
    public int stepAndCollect(float dt, int logicW, int battleBottom, int margin,
                             float px, float py, float pickupRadius, float pullRadius,
                             int[] collectedByKind) {
        for (int i = activeCount - 1; i >= 0; i--) {
            Item it = objs[active[i]];
            it.px = it.x;
            it.py = it.y;
            it.age += dt;
            float dx = px - it.x, dy = py - it.y;
            float d2 = dx * dx + dy * dy;
            if (it.magnetized || d2 <= pullRadius * pullRadius) {
                // 吸引：越近吸得越快，掉落物会有"被拽走"的手感而不是匀速穿过
                float k = 1f + 900f / (d2 + 200f);
                it.x += dx * Math.min(1f, dt * 3.4f * k);
                it.y += dy * Math.min(1f, dt * 3.4f * k);
            } else {
                it.y += it.vy * dt;
                if (it.x < 4f) it.x = 4f;
                else if (it.x > logicW - 4f) it.x = logicW - 4f;
            }
            // 捡取判据要用**移动后**的距离：读移动前的值会白白晚一帧才捡到
            dx = px - it.x;
            dy = py - it.y;
            d2 = dx * dx + dy * dy;
            if (d2 <= pickupRadius * pickupRadius) {
                collectedByKind[it.kind] += it.value <= 0 ? 1 : it.value;
                killAt(i);
                continue;
            }
            if (it.y > battleBottom + margin) killAt(i);
        }
        return activeCount;
    }

    public void clear() {
        while (activeCount > 0) killAt(activeCount - 1);
    }

    // ---- 掉落判定（纯函数，测试直接钉） ---------------------------------------------------

    public static final float DROP_FALL = 34f;

    /** 芯片：[规格] 基础 20%，乘掉落层（幸运 ×1.5 / 贫瘠 ×0.6），钳 0..1。 */
    public static boolean chipRoll(float roll01, float dropMul) {
        return roll01 < clamp01(Balance.drop.chipChance * dropMul);
    }

    /** 金币：与芯片是两次独立判定，一枚怪可以既掉芯片又掉金币。 */
    public static boolean coinRoll(float roll01, float dropMul) {
        return roll01 < clamp01(Balance.drop.coinChance * dropMul);
    }

    /** [规格] Boss 必掉 3 枚，不看概率、不受贫瘠影响。 */
    public static int bossChips() {
        return Balance.drop.bossChips;
    }

    /**
     * [规格] 第 2 波起每波保底 1 枚芯片：整波一颗没掉才补，已经掉了就不补。
     * @param wave        当前波次
     * @param chipsThisWave 本波已经掉出的芯片数
     */
    public static int waveGuarantee(int wave, int chipsThisWave) {
        if (wave < Balance.drop.chipGuaranteeFromWave) return 0;
        return chipsThisWave >= Balance.drop.chipGuaranteeCount ? 0 : Balance.drop.chipGuaranteeCount;
    }

    /** 道具掉率同样吃掉落层。 */
    public static boolean powerupRoll(float roll01, float dropMul) {
        return roll01 < clamp01(Balance.drop.powerupChance * dropMul);
    }

    public static boolean hpRoll(float roll01, float dropMul) {
        return roll01 < clamp01(Balance.drop.hpChance * dropMul);
    }

    /**
     * 掉落物主色（ARGB 整数，不进 android.graphics）。
     * 与 {@link #label} 放在一起是故意的：新增一种掉落必须同时给出**名字与颜色**，
     * 漏一处就是规格 §五 点名的"掉出来却捡不到 / 画成透明"那一类 bug。
     * {@code EntitiesTest} 遍历 KIND_COUNT 断言两者都齐。
     */
    public static int colorOf(int kind) {
        switch (kind) {
            case CHIP: return 0xFFFFD24A;
            case COIN: return 0xFFF7E08B;
            case HP: return 0xFF6BE8A0;
            case SHIELD: return 0xFF4DD2FF;
            case POWERUP: return 0xFFFF6AD2;
            case BOMB: return 0xFFFF6A4D;
            default: return 0;
        }
    }

    public static String label(int kind) {
        switch (kind) {
            case CHIP: return "芯片";
            case COIN: return "金币";
            case HP: return "血包";
            case SHIELD: return "护盾";
            case POWERUP: return "强化";
            case BOMB: return "炸弹";
            default: return "?";
        }
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
