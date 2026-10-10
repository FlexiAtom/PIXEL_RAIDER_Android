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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/**
 * 快照往返的反射探针。{@code PoolSnapshotTest} / {@code StateSnapshotTest} 共用这一份，
 * **不各自复制一份 switch**——{@link ResetProbe} 就是因为两份副本在"有没有 {@code long} 分支"
 * 上分叉过一次才合到一处的，这里沿用它的教训。
 *
 * <p><b>它要钉的失败形状是「一个字段在写端和读端同时被漏掉」</b>。只比"拍下去再套回来"的两个对象
 * 是查不出这一格的：两边都不碰那格，它就一路保持原值，测试照样绿。所以这里的形状是：
 * <ol>
 *   <li>把<b>源</b>对象的每个字段灌成"非默认、且同类字段彼此不同"的值；</li>
 *   <li>把<b>目标</b>留成一座全新的、没被碰过的同款（每个字段都还是 Java 默认值）；</li>
 *   <li>写 → 读 → 逐字段比对。</li>
 * </ol>
 * 漏写又漏读 ⇒ 目标保持默认、源非默认 ⇒ 第 3 步红；只漏一侧 ⇒ 段序错位，
 * 由 {@link Buf} 的"布尔只认 0/1"和 {@code Game.readAll} 末尾那句"一口不剩"接住。
 * <b>不靠字节数算术</b>：那条要求探针自己再数一遍每个字段的宽度，而那份计数本身就是第二个可能算错的地方。
 *
 * <p><b>布尔灌两遍</b>，因为单个布尔只有两种取值，一次灌不全两件事：
 * {@link #poisonDistinct} 按声明序<b>交错</b> true/false（这一遍抓"相邻两个布尔读反了"——
 * 比如 {@code Beam.crit} 与 {@code Beam.settled}，写序 crit→settled、读序写成 settled→crit，
 * 若两边都是 true 就永远看不出）；{@link #poisonBooleans} 把<b>每个布尔都灌真</b>
 * （这一遍抓"这个布尔两边都没进快照"，因为假值与默认值同形）。两条用例各自跑一遍，见
 * {@link #twice}。
 *
 * <p><b>认不出类型就当场抛</b>，宁可红也不假绿：给一个类新增字段时，探针要么比过它、
 * 要么要求测试作者显式把它列进 skip（那是一次"这格进不进快照"的表态），不存在静默跳过。
 */
final class SnapProbe {

    private SnapProbe() { }

    /** 一次灌值的两种布尔策略；数值字段两种策略下都是"非默认且互不相同"。 */
    private static final boolean BOOLS_ALTERNATE = true;
    private static final boolean BOOLS_ALL_TRUE = false;

    /**
     * 每个基本类型字段灌一个互不相同的值；布尔按声明序交错。
     *
     * @param skip 这一遍<b>不许碰</b>的字段：池自己赋值的代次戳（{@code seq}/{@code born}）、
     *             受范围校验因而得由测试灌成合法值的数组，以及不进快照的那几格
     */
    static void poisonDistinct(Object o, String... skip) {
        poison(o, BOOLS_ALTERNATE, skip);
    }

    /** 第二遍：换成<b>每个布尔都真</b>（数值字段的灌法不变），补上交错那一遍看不见的"假值＝默认值"那一格。 */
    static void poisonBooleans(Object o, String... skip) {
        poison(o, BOOLS_ALL_TRUE, skip);
    }

    private static void poison(Object o, boolean alternateBools, String... skip) {
        int idx = 0;
        int boolIdx = 0;
        for (Field f : instanceFields(o.getClass())) {
            int thisIdx = idx++;
            if (contains(skip, f.getName())) continue;
            f.setAccessible(true);
            Class<?> t = f.getType();
            try {
                if (t == int.class) f.setInt(o, thisIdx + 1);
                else if (t == long.class) f.setLong(o, 1_000_000L + thisIdx);
                else if (t == float.class) f.setFloat(o, (thisIdx + 1) * 0.25f);
                // 布尔按**布尔自己的序数**交错，不按字段序数：Beam 的 crit 与 settled 落在字段下标
                // 6 与 8（都是偶数），按字段下标交错会两个都灌真，"读序把这两格写反"就查不出来了。
                else if (t == boolean.class) f.setBoolean(o, alternateBools ? (boolIdx++ % 2 == 0) : true);
                else if (t == int[].class) {
                    int[] a = (int[]) f.get(o);
                    for (int i = 0; i < a.length; i++) a[i] = i + 1;
                } else if (t == long[].class) {
                    long[] a = (long[]) f.get(o);
                    for (int i = 0; i < a.length; i++) a[i] = 1_000_000L + i;
                } else if (t == float[].class) {
                    // 0.03 这一步是为 TrendSampler 让路：它的 hp 采样上界是 1.5，
                    // 而 24 格的数组按 0.25 递增会冲到 6——数组下标序本身就够互不相同了。
                    float[] a = (float[]) f.get(o);
                    for (int i = 0; i < a.length; i++) a[i] = (i + 1) * 0.03f;
                } else throw unknown(f, t);
            } catch (IllegalAccessException e) {
                throw new AssertionError("探针读不到 " + f.getName(), e);
            }
        }
    }

    /**
     * 灌完之后的前置断言：<b>每个要进快照的数值字段确实不再是默认值</b>。
     * 少了这条，"poison 因为字段名写错而一格都没灌上"会伪装成"往返很干净"。
     *
     * <p>布尔不在其列——交错那一遍里必然有一半是 false，与默认值同形；那半场由
     * {@link #poisonBooleans} 那一遍负责（见类注释）。
     */
    static void assertPoisoned(Object o, String... skip) {
        for (Field f : instanceFields(o.getClass())) {
            if (contains(skip, f.getName())) continue;
            f.setAccessible(true);
            Class<?> t = f.getType();
            try {
                if (t == int.class) assertNotEquals(f.getName() + " 还是默认值 0", 0L, f.getInt(o));
                else if (t == long.class) assertNotEquals(f.getName() + " 还是默认值 0", 0L, f.getLong(o));
                else if (t == float.class) assertNotEquals(f.getName() + " 还是默认值 0", 0L,
                        Float.floatToRawIntBits(f.getFloat(o)));
                else if (t == int[].class) assertHasNonZero(f.getName(), (int[]) f.get(o));
                else if (t == long[].class) assertHasNonZero(f.getName(), (long[]) f.get(o));
                else if (t == float[].class) assertHasNonZero(f.getName(), (float[]) f.get(o));
                else if (t != boolean.class) throw unknown(f, t);
            } catch (IllegalAccessException e) {
                throw new AssertionError("探针读不到 " + f.getName(), e);
            }
        }
    }

    private static void assertHasNonZero(String name, int[] a) {
        for (int v : a) if (v != 0) return;
        throw new AssertionError(name + " 整段还是默认全 0，这一遍 poison 没作用上去");
    }

    private static void assertHasNonZero(String name, long[] a) {
        for (long v : a) if (v != 0) return;
        throw new AssertionError(name + " 整段还是默认全 0，这一遍 poison 没作用上去");
    }

    private static void assertHasNonZero(String name, float[] a) {
        for (float v : a) if (v != 0f) return;
        throw new AssertionError(name + " 整段还是默认全 0，这一遍 poison 没作用上去");
    }

    /**
     * 逐字段断言 {@code src} 与 {@code dst} 相同——{@code dst} 必须是<b>没被碰过的另一座同款</b>，
     * 否则"两边都漏"这一格又查不出来了。
     *
     * <p>浮点比的是 {@link Float#floatToRawIntBits}：快照承诺"浮点走原始位"，
     * 用带 epsilon 的相等就把这条承诺放掉了（而 {@code NEGATIVE_INFINITY} 这类值本来也没法用差值比）。
     *
     * @param skip 故意不进快照的字段（派生量、观测计数）与引用字段——引用类型不列进来会直接抛，
     *             免得"新加了一个引用字段、探针没比、测试照绿"
     */
    static void assertFieldsEqual(Object src, Object dst, String... skip) {
        Field[] fields = instanceFields(src.getClass());
        assertEquals("两座对象的类不同，比不了", src.getClass(), dst.getClass());
        int checked = 0;
        int skipped = 0;
        for (Field f : fields) {
            f.setAccessible(true);
            if (contains(skip, f.getName())) {
                skipped++;
                continue;
            }
            Class<?> t = f.getType();
            String what = f.getName() + " 没回到源对象的值（写端少写或读端少读这一格）";
            try {
                if (t == int.class) assertEquals(what, f.getInt(src), f.getInt(dst));
                else if (t == long.class) assertEquals(what, f.getLong(src), f.getLong(dst));
                else if (t == float.class) assertEquals(what,
                        Float.floatToRawIntBits(f.getFloat(src)),
                        Float.floatToRawIntBits(f.getFloat(dst)));
                else if (t == boolean.class) assertEquals(what, f.getBoolean(src), f.getBoolean(dst));
                else if (t == int[].class) {
                    int[] a = (int[]) f.get(src), b = (int[]) f.get(dst);
                    assertEquals(what + "（长度）", a.length, b.length);
                    for (int i = 0; i < a.length; i++) assertEquals(what + "[" + i + ']', a[i], b[i]);
                } else if (t == long[].class) {
                    long[] a = (long[]) f.get(src), b = (long[]) f.get(dst);
                    assertEquals(what + "（长度）", a.length, b.length);
                    for (int i = 0; i < a.length; i++) assertEquals(what + "[" + i + ']', a[i], b[i]);
                } else if (t == float[].class) {
                    float[] a = (float[]) f.get(src), b = (float[]) f.get(dst);
                    assertEquals(what + "（长度）", a.length, b.length);
                    for (int i = 0; i < a.length; i++) {
                        assertEquals(what + "[" + i + ']',
                                Float.floatToRawIntBits(a[i]), Float.floatToRawIntBits(b[i]));
                    }
                } else throw unknown(f, t);
            } catch (IllegalAccessException e) {
                throw new AssertionError("探针读不到 " + f.getName(), e);
            }
            checked++;
        }
        assertEquals("skip 里有没碰到的字段名（写错名字，或那个字段已经删了）", skip.length, skipped);
        assertTrue("一个字段都没比对，这条用例已经空转", checked > 0);
    }

    /** 写一个值进指定字段（灌合法值用：{@code restoreSnapshot} 的范围校验会拒掉纯 poison）。 */
    static void set(Object o, String name, Object value) {
        for (Field f : instanceFields(o.getClass())) {
            if (!f.getName().equals(name)) continue;
            f.setAccessible(true);
            try {
                if (value instanceof Integer) f.setInt(o, (Integer) value);
                else if (value instanceof Long) f.setLong(o, (Long) value);
                else if (value instanceof Float) f.setFloat(o, (Float) value);
                else if (value instanceof Boolean) f.setBoolean(o, (Boolean) value);
                else throw new AssertionError("探针只会灌四种基本类型，收到 " + value.getClass());
            } catch (IllegalAccessException e) {
                throw new AssertionError("探针写不进 " + name, e);
            }
            return;
        }
        throw new AssertionError("没有字段叫 " + name + "（写错名字，或那个字段已删）");
    }

    /** 取数组句柄，让测试往里灌**合法**的互不相同值（{@code WaveFlow.kinds} 那种带范围校验的段）。 */
    static int[] ints(Object o, String name) {
        return handle(o, name, int[].class);
    }

    static long[] longs(Object o, String name) {
        return handle(o, name, long[].class);
    }

    static float[] floats(Object o, String name) {
        return handle(o, name, float[].class);
    }

    static int getInt(Object o, String name) {
        try {
            Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(o);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("读不到 int 字段 " + name + " on " + o.getClass(), e);
        }
    }

    static float getFloat(Object o, String name) {
        try {
            Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.getFloat(o);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("读不到 float 字段 " + name + " on " + o.getClass(), e);
        }
    }

    static long getLong(Object o, String name) {
        try {
            Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.getLong(o);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("读不到 long 字段 " + name + " on " + o.getClass(), e);
        }
    }

    private static <T> T handle(Object o, String name, Class<T> t) {
        try {
            Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return t.cast(f.get(o));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("读不到 " + t.getSimpleName() + " 字段 " + name
                    + " on " + o.getClass(), e);
        }
    }

    /**
     * 同一个形状跑两遍：一遍布尔交错（抓换序），一遍布尔全真（抓"两边都漏"）。
     * 用法是 {@code twice(SnapProbe::poisonBooleans, c)} 这种一对方法引用。
     */
    interface Case {
        void run(boolean boolsAllTrue);
    }

    static void twice(Case c) {
        c.run(BOOLS_ALTERNATE);
        c.run(BOOLS_ALL_TRUE);
    }

    /** 两遍 poison 的入口：{@code boolsAllTrue} 直接给调用方当分支，省得每处重述一遍交错规则。 */
    static void poisonBy(Object o, boolean boolsAllTrue, String... skip) {
        if (boolsAllTrue) poisonBooleans(o, skip);
        else poisonDistinct(o, skip);
    }

    private static AssertionError unknown(Field f, Class<?> t) {
        return new AssertionError("探针不认字段 " + f.getName() + " 的类型 " + t
                + "：它进快照就补一条分支（并让测试比到它），不进快照就把它列进 skip——"
                + "两条都不做就是让这一格静默脱离测试");
    }

    private static Field[] instanceFields(Class<?> c) {
        Field[] all = c.getDeclaredFields();
        Field[] out = new Field[all.length];
        int n = 0;
        for (Field f : all) if (!Modifier.isStatic(f.getModifiers())) out[n++] = f;
        return Arrays.copyOf(out, n);
    }

    private static boolean contains(String[] names, String name) {
        for (String s : names) if (s.equals(name)) return true;
        return false;
    }
}
