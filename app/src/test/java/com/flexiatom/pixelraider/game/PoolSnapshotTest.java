/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * PIXEL RAIDER — 原生 Android 纵版射击
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
package com.flexiatom.pixelraider.game;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

/**
 * 八座池的快照往返（规格 §八 的实体面）。
 *
 * <p><b>形状</b>：把<b>源</b>池灌成非默认（代次戳 {@code seq}/{@code born} 除外——那是池自己赋的，
 * 灌了会撞上"新弹必须比在途弹更年轻"那道闸），写进字节，再套进一座<b>全新的同款池</b>，
 * 然后逐字段比对。目标全新这一条是刻意的：它让"这个字段写端漏了、读端也漏了"这一格露出来
 * （源是 poison 过的非默认值，目标保持 Java 默认值，一比就红）。理由与两种布尔灌法都在
 * {@link SnapProbe} 的类注释里。
 *
 * <p>每座池比三样：<b>活跃表</b>（obj 槽号序列）、<b>空闲槽的集合</b>、<b>每一只的每个字段</b>。
 * 空闲槽只比集合不比顺序——{@code restoreSnapshot} 把 free 栈重建成"由低到高"，
 * 与构造期同形，这是它注释里写明的选择，不是漏掉的字段（源池被 {@code killAt} 打过补丁之后
 * 栈顶顺序本来就和还原结果不同）。
 */
public class PoolSnapshotTest {

    /** 战场带：写与读用同一组常数时 y 逐位往返；跨组的那条用例在 {@code SnapshotFormatTest}。 */
    static final int BAND_TOP = 100;
    static final int BAND_H = 400;

    // ---- 敌人 -------------------------------------------------------------------------------

    @Test
    public void 敌人池把活跃表与每只的读数整套带走() {
        SnapProbe.twice(boolsAllTrue -> {
            Enemies src = new Enemies(8);
            src.spawn();
            src.spawn();
            src.spawn();
            src.killAt(1);          // 末位交换：活跃表变成 [0,2]，槽号不连续，这才叫比过那张表
            for (int i = 0; i < src.activeCount(); i++) {
                SnapProbe.poisonBy(src.activeAt(i), boolsAllTrue, "born");
                SnapProbe.assertPoisoned(src.activeAt(i), "born");
            }

            Buf w = new Buf(4096);
            src.writeSnapshot(w, BAND_TOP, BAND_H);
            Enemies dst = new Enemies(8);
            Buf.Reader r = new Buf.Reader(w.toByteArray());
            dst.restoreSnapshot(r, BAND_TOP, BAND_H);
            assertEquals("敌人段必须一口不剩", 0, r.remaining());

            assertEquals(src.activeCount(), dst.activeCount());
            assertEquals("空闲槽数量", src.freeCount(), dst.freeCount());
            assertEquals("出怪总数（导引头持的身份戳来源）", src.spawnTotal(), dst.spawnTotal());
            assertSameFreeSet("敌人", src, dst, dst.freeCount(), true);
            for (int i = 0; i < src.activeCount(); i++) {
                assertEquals("活跃表第 " + i + " 格的 obj 槽号",
                        src.slotOfActive(i), dst.slotOfActive(i));
                SnapProbe.assertFieldsEqual(src.activeAt(i), dst.activeAt(i));
            }
        });
    }

    /**
     * born 与 spawnTotal 的对应是导引武器的身份判据（{@link Enemies.Enemy#born} 那条）。
     * 灌一个"比出怪总数还晚"的戳进去，读端必须当场拒——放过它， missile 会安静地追着
     * 一只从没被锁定过的敌人飞，画面与分数都解释不了。
     */
    @Test
    public void 敌人段的代次戳越界被整段拒掉() {
        Enemies src = new Enemies(8);
        src.spawn();
        src.spawn();
        SnapProbe.set(src.activeAt(0), "born", src.spawnTotal() + 1L);
        Buf w = new Buf(4096);
        src.writeSnapshot(w, BAND_TOP, BAND_H);
        Buf.Invalid bad = assertThrows(Buf.Invalid.class, () ->
                new Enemies(8).restoreSnapshot(new Buf.Reader(w.toByteArray()), BAND_TOP, BAND_H));
        assertTrue(bad.getMessage(), bad.getMessage().contains("born"));
    }

    @Test
    public void 敌人段的重复槽号被整段拒掉() {
        Enemies src = new Enemies(8);
        src.spawn();
        src.spawn();
        SnapProbe.ints(src, "active")[1] = SnapProbe.ints(src, "active")[0];   // 同一槽列两次
        Buf w = new Buf(4096);
        src.writeSnapshot(w, BAND_TOP, BAND_H);
        Buf.Invalid bad = assertThrows(Buf.Invalid.class, () ->
                new Enemies(8).restoreSnapshot(new Buf.Reader(w.toByteArray()), BAND_TOP, BAND_H));
        assertTrue(bad.getMessage(), bad.getMessage().contains("listed twice"));
    }

    // ---- 玩家弹 / 敌弹（同一座 BulletPool 的两个实例）----------------------------------------

    @Test
    public void 弹池把活跃表与nextSeq一起带走让新弹不再顶掉在途弹() {
        SnapProbe.twice(boolsAllTrue -> {
            BulletPool src = new BulletPool(8);
            src.spawn();
            src.spawn();
            src.spawn();
            src.killAt(0);          // 活跃表变成 [1,2]（末位交换把 2 顶到 0）
            for (int i = 0; i < src.activeCount(); i++) {
                SnapProbe.poisonBy(src.activeAt(i), boolsAllTrue, "seq");
                SnapProbe.assertPoisoned(src.activeAt(i), "seq");
            }

            Buf w = new Buf(4096);
            src.writeSnapshot(w, BAND_TOP, BAND_H);
            BulletPool dst = new BulletPool(8);
            Buf.Reader r = new Buf.Reader(w.toByteArray());
            dst.restoreSnapshot(r, BAND_TOP, BAND_H);
            assertEquals("弹段必须一口不剩", 0, r.remaining());

            assertEquals(src.activeCount(), dst.activeCount());
            assertEquals("空闲槽数量", src.freeCount(), dst.freeCount());
            assertEquals("下一枚出膛序（淘汰判据自增的那把尺）",
                    SnapProbe.getLong(src, "nextSeq"), SnapProbe.getLong(dst, "nextSeq"));
            assertSameFreeSet("弹", src, dst, dst.freeCount(), false);
            for (int i = 0; i < src.activeCount(); i++) {
                assertEquals("活跃表第 " + i + " 格的 obj 槽号",
                        SnapProbe.ints(src, "active")[i], SnapProbe.ints(dst, "active")[i]);
                SnapProbe.assertFieldsEqual(src.activeAt(i), dst.activeAt(i));
            }
        });
    }

    /**
     * {@code nextSeq} 落在"比在途弹还老"的那一侧是<b>真事故</b>：恢复后第一发子弹会按 seq
     * 判定淘汰掉一枚正在飞的弹（{@code BulletPool} 存它的那条注释）。读端这条闸没有别的探针能替它，
     * 所以这里把违反形状本身钉住——灌法是把池的 nextSeq 压回 1，让字节里出现"游标 ≤ 在途序"。
     */
    @Test
    public void 弹段里nextSeq不高于在途弹的序就整段拒() {
        BulletPool src = new BulletPool(8);
        src.spawn();
        src.spawn();
        SnapProbe.set(src, "nextSeq", 1L);
        Buf w = new Buf(4096);
        src.writeSnapshot(w, BAND_TOP, BAND_H);
        Buf.Invalid bad = assertThrows(Buf.Invalid.class, () ->
                new BulletPool(8).restoreSnapshot(new Buf.Reader(w.toByteArray()), BAND_TOP, BAND_H));
        assertTrue(bad.getMessage(), bad.getMessage().contains("nextSeq"));
    }

    // ---- 导弹 -------------------------------------------------------------------------------

    @Test
    public void 导弹池带走导引身份的一对数不做跨池校验() {
        SnapProbe.twice(boolsAllTrue -> {
            Missiles src = new Missiles(8);
            src.spawn();
            src.spawn();
            src.killAt(1);
            for (int i = 0; i < src.activeCount(); i++) {
                Missiles.Missile m = src.activeAt(i);
                SnapProbe.poisonBy(m, boolsAllTrue, "seq");
                SnapProbe.assertPoisoned(m, "seq");
                // targetSlot/born 灌的是"看着像另一个池的槽号"的数：读端**故意**不校验它们
                // （下一帧 MissileBehavior 的判据自己会把断掉的一侧降级成 NO_TARGET），
                // 所以这里必须能原样回来，否则等于在还原端造了第二个判据。
                SnapProbe.set(m, "targetSlot", 999);
                SnapProbe.set(m, "targetBorn", 123_456L);
            }

            Buf w = new Buf(4096);
            src.writeSnapshot(w, BAND_TOP, BAND_H);
            Missiles dst = new Missiles(8);
            Buf.Reader r = new Buf.Reader(w.toByteArray());
            dst.restoreSnapshot(r, BAND_TOP, BAND_H);
            assertEquals("导弹段必须一口不剩", 0, r.remaining());

            assertEquals(src.activeCount(), dst.activeCount());
            assertEquals("空闲槽数量", src.freeCount(), dst.freeCount());
            assertEquals("下一枚出膛序", SnapProbe.getLong(src, "nextSeq"),
                    SnapProbe.getLong(dst, "nextSeq"));
            assertSameFreeSet("导弹", src, dst, dst.freeCount(), false);
            for (int i = 0; i < src.activeCount(); i++) {
                assertEquals("活跃表第 " + i + " 格的 obj 槽号",
                        SnapProbe.ints(src, "active")[i], SnapProbe.ints(dst, "active")[i]);
                SnapProbe.assertFieldsEqual(src.activeAt(i), dst.activeAt(i));
            }
        });
    }

    // ---- 战斗部 -------------------------------------------------------------------------------

    @Test
    public void 战斗部带走接触位图让亮线少结的伤害不丢() {
        SnapProbe.twice(boolsAllTrue -> {
            Warheads src = new Warheads(16, 28);
            src.spawn();
            src.spawn();
            src.killAt(0);
            for (int i = 0; i < src.activeCount(); i++) {
                SnapProbe.poisonBy(src.activeAt(i), boolsAllTrue);
                SnapProbe.assertPoisoned(src.activeAt(i));
                // contact 是"敌 obj 槽号"的位图：构造期那条 ≤32 的断言保证位号装得下，
                // 于是这一格不需要任何跨池校验，但它必须**原样回来**（清零=少结一次伤害）。
                SnapProbe.set(src.activeAt(i), "contact", 1 << 5 | 1 << 2);
            }

            Buf w = new Buf(4096);
            src.writeSnapshot(w, BAND_TOP, BAND_H);
            Warheads dst = new Warheads(16, 28);
            Buf.Reader r = new Buf.Reader(w.toByteArray());
            dst.restoreSnapshot(r, BAND_TOP, BAND_H);
            assertEquals("战斗部段必须一口不剩", 0, r.remaining());

            assertEquals(src.activeCount(), dst.activeCount());
            assertEquals("空闲槽数量", src.freeCount(), dst.freeCount());
            assertSameFreeSet("战斗部", src, dst, dst.freeCount(), false);
            for (int i = 0; i < src.activeCount(); i++) {
                assertEquals("活跃表第 " + i + " 格的 obj 槽号",
                        SnapProbe.ints(src, "active")[i], SnapProbe.ints(dst, "active")[i]);
                SnapProbe.assertFieldsEqual(src.activeAt(i), dst.activeAt(i));
            }
        });
    }

    // ---- 光束（环形窗口，与其余五座不同形）------------------------------------------------------

    /**
     * 这一座没有 free 栈：槽位是被游标覆盖的，所以还原不能"重放 spawn"
     * （{@link Beams.Beam#occupy} 会把 {@code settled} 强行写成 false）。
     * 这条用例因此专门盯两件事：<b>游标与窗口大小回到原位</b>，以及 <b>{@code settled} 逐道带回</b>
     * ——漏掉后者，恢复后的第一帧会把已经结过账的那一束再结一遍。
     */
    @Test
    public void 光束段带走游标与settled不靠重放spawn() {
        SnapProbe.twice(boolsAllTrue -> {
            Beams src = new Beams(4);
            for (int i = 0; i < 5; i++) {           // 超过容量一格：游标回绕，cursor < count
                src.spawn(10f + i, 200f + i, 3f + i, 7f + i, 0xFF0000FF, 1, true, 0.1f);
            }
            assertEquals("用例前提：游标已经回绕", 1, SnapProbe.getInt(src, "cursor"));
            for (int i = 0; i < src.activeCount(); i++) {
                SnapProbe.poisonBy(src.at(i), boolsAllTrue);
                SnapProbe.assertPoisoned(src.at(i));
            }

            Buf w = new Buf(4096);
            src.writeSnapshot(w, BAND_TOP, BAND_H);
            Beams dst = new Beams(4);
            Buf.Reader r = new Buf.Reader(w.toByteArray());
            dst.restoreSnapshot(r, BAND_TOP, BAND_H);
            assertEquals("光束段必须一口不剩", 0, r.remaining());

            assertEquals("窗口大小", src.activeCount(), dst.activeCount());
            assertEquals("环形游标", SnapProbe.getInt(src, "cursor"), SnapProbe.getInt(dst, "cursor"));
            for (int i = 0; i < src.activeCount(); i++) {
                SnapProbe.assertFieldsEqual(src.at(i), dst.at(i));
            }
            // 游标回绕之后，下一道覆盖的必须是"最老的那一格"——src 与 dst 走同一条算术。
            Beams.Beam overA = src.spawn(1f, 2f, 3f, 4f, 5, 6, false, 7f);
            Beams.Beam overB = dst.spawn(1f, 2f, 3f, 4f, 5, 6, false, 7f);
            SnapProbe.assertFieldsEqual(overA, overB);
        });
    }

    // ---- 掉落物 -------------------------------------------------------------------------------

    @Test
    public void 掉落段带走没进兜的那几枚() {
        SnapProbe.twice(boolsAllTrue -> {
            Drops src = new Drops(8);
            src.spawn(1, 3, 20f, 300f);
            src.spawn(2, 50, 30f, 310f);
            src.spawn(1, 3, 40f, 320f);
            src.killAt(1);
            for (int i = 0; i < src.activeCount(); i++) {
                SnapProbe.poisonBy(src.activeAt(i), boolsAllTrue);
                SnapProbe.assertPoisoned(src.activeAt(i));
            }

            Buf w = new Buf(4096);
            src.writeSnapshot(w, BAND_TOP, BAND_H);
            Drops dst = new Drops(8);
            Buf.Reader r = new Buf.Reader(w.toByteArray());
            dst.restoreSnapshot(r, BAND_TOP, BAND_H);
            assertEquals("掉落段必须一口不剩", 0, r.remaining());

            assertEquals(src.activeCount(), dst.activeCount());
            assertEquals("空闲槽数量", src.freeCount(), dst.freeCount());
            assertSameFreeSet("掉落", src, dst, dst.freeCount(), true);
            for (int i = 0; i < src.activeCount(); i++) {
                assertEquals("活跃表第 " + i + " 格的 obj 槽号",
                        SnapProbe.ints(src, "active")[i], SnapProbe.ints(dst, "active")[i]);
                SnapProbe.assertFieldsEqual(src.activeAt(i), dst.activeAt(i));
            }
        });
    }

    // ---- 闸门的形状（每座池都用"把计数灌坏"这一招，不靠手写布局）--------------------------------

    /**
     * 每座池的第一格是活跃数。把它的高位一翻就得到一个负数计数——这不是随机 fuzz，
     * 是"字节错位时最先响的那一格到底响不响"：六座池都必须在读到任何实体字段之前就拒掉，
     * 否则会拿一个负数去开数组（{@link NegativeArraySizeException} 不是 {@link Buf.Invalid}，
     * {@code Game.applyRun} 接不住它，就会把正在跑的那一局留成半还原状态）。
     */
    @Test
    public void 每座池的负活跃数都落进Invalid而不是负数组() {
        Enemies e = new Enemies(4);
        e.spawn();
        BulletPool b = new BulletPool(4);
        b.spawn();
        Missiles m = new Missiles(4);
        m.spawn();
        Warheads wh = new Warheads(4, 28);
        wh.spawn();
        Drops d = new Drops(4);
        d.spawn(1, 1, 5f, 5f);
        Beams bm = new Beams(4);
        bm.spawn(1f, 2f, 3f, 4f, 5, 6, true, 0.1f);

        assertNegativeFirstIntRejects(w -> e.writeSnapshot(w, BAND_TOP, BAND_H), "敌人", "enemy count");
        assertNegativeFirstIntRejects(w -> b.writeSnapshot(w, BAND_TOP, BAND_H), "玩家弹", "bullet count");
        assertNegativeFirstIntRejects(w -> m.writeSnapshot(w, BAND_TOP, BAND_H), "导弹", "missile count");
        assertNegativeFirstIntRejects(w -> wh.writeSnapshot(w, BAND_TOP, BAND_H), "战斗部", "warhead count");
        assertNegativeFirstIntRejects(w -> d.writeSnapshot(w, BAND_TOP, BAND_H), "掉落", "drop count");
        assertNegativeFirstIntRejects(w -> bm.writeSnapshot(w, BAND_TOP, BAND_H), "光束", "beam cursor");
    }

    private interface Write {
        void into(Buf w);
    }

    private void assertNegativeFirstIntRejects(Write write, String label, String fragment) {
        Buf w = new Buf(4096);
        write.into(w);
        byte[] bytes = w.toByteArray();
        bytes[0] |= (byte) 0x80;            // 第一格是 activeCount（光束那座是 cursor），翻高位即负
        Buf.Invalid bad = assertThrows(label + "：负数必须走 Invalid",
                Buf.Invalid.class, () -> restoreAny(label, bytes));
        assertTrue(bad.getMessage(), bad.getMessage().contains(fragment));
    }

    private static void restoreAny(String label, byte[] bytes) {
        Buf.Reader r = new Buf.Reader(bytes);
        switch (label) {
            case "敌人": new Enemies(4).restoreSnapshot(r, BAND_TOP, BAND_H); break;
            case "玩家弹": new BulletPool(4).restoreSnapshot(r, BAND_TOP, BAND_H); break;
            case "导弹": new Missiles(4).restoreSnapshot(r, BAND_TOP, BAND_H); break;
            case "战斗部": new Warheads(4, 28).restoreSnapshot(r, BAND_TOP, BAND_H); break;
            case "掉落": new Drops(4).restoreSnapshot(r, BAND_TOP, BAND_H); break;
            case "光束": new Beams(4).restoreSnapshot(r, BAND_TOP, BAND_H); break;
            default: throw new AssertionError("探针不认识这座池：" + label);
        }
    }

    /**
     * 空闲槽比**集合**不比顺序：{@code restoreSnapshot} 把 free 栈重建成"由低到高"，
     * 与构造期同形，而源池被 {@code killAt} 打过补丁之后栈顶顺序本来就可能不同（那是选择，不是漏字段）。
     *
     * @param hasBackTable 敌人座与掉落座带 {@code slotOfObj} 反查表（{@code isLive} 与 {@code kill(Enemy)}
     *                     读它）；那四座用 {@code boolean[] seen} 局部重建的池没有这一格，别去要它。
     */
    private static void assertSameFreeSet(String label, Object src, Object dst, int n, boolean hasBackTable) {
        int[] a = Arrays.copyOf(SnapProbe.ints(src, "free"), n);
        int[] b = Arrays.copyOf(SnapProbe.ints(dst, "free"), n);
        Arrays.sort(a);
        Arrays.sort(b);
        assertEquals(label + "的空闲槽集合（哪几格可以发新东西）", Arrays.toString(a), Arrays.toString(b));
        if (hasBackTable) {
            assertEquals(label + "的 slotOfObj 反查表（槽号→活跃表下标）",
                    Arrays.toString(SnapProbe.ints(src, "slotOfObj")),
                    Arrays.toString(SnapProbe.ints(dst, "slotOfObj")));
        }
    }
}
