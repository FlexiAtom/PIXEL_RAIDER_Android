/*
 * PIXEL RAIDER — 原生 Android 纵版弹幕射击
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * 「池复用必须重置全部字段」这条铁律的反射探针。{@code EnemiesTest} / {@code BulletAndGridTest}
 * / {@code MissilesTest} / {@code WarheadsTest} 共用这一份，**不各自复制一份 switch**。
 *
 * <p>为什么合到一处：这两族用例靠"遍历实例字段 → 灌哨兵 → 复用 → 断言归零"来钉 {@code reset()}，
 * 而遇到**认不出的类型**时过去的行为是安静跳过。两份副本已经在这一点上分叉过一次
 * （{@code BulletAndGridTest} 的 poison 有 {@code long} 分支、同文件的断言侧没有）——
 * 于是给池加一个 {@code long} 字段，poison 不上、断言也不查，测试照样绿，铁律对新字段整体失效。
 * 现在：**认不出类型就当场抛**，宁可红也不假绿。
 *
 * <p>{@link #assertAllZero} 还会核对"访问到的字段数"，并要求跳过项必须**真的存在**——
 * 少一个分支、写错一个字段名，都会变成失败而不是悄悄少测一项。
 */
final class ResetProbe {

    static final int SENTINEL_INT = 0x7F7F7F7F;
    static final float SENTINEL_FLOAT = 12345.5f;
    static final long SENTINEL_LONG = 0x7F7F7F7F3F3FL;

    private ResetProbe() { }

    /** 把每个实例字段灌成该类型的哨兵值。字段类型必须是下面四种之一，否则抛。 */
    static void poison(Object poolItem) throws Exception {
        for (Field f : instanceFields(poolItem.getClass())) {
            f.setAccessible(true);
            Class<?> t = f.getType();
            if (t == int.class) f.setInt(poolItem, SENTINEL_INT);
            else if (t == float.class) f.setFloat(poolItem, SENTINEL_FLOAT);
            else if (t == boolean.class) f.setBoolean(poolItem, true);
            else if (t == long.class) f.setLong(poolItem, SENTINEL_LONG);
            else throw new AssertionError("探针不认字段 " + f.getName() + " 的类型 " + t
                    + "：新增类型请同时补 poison 与 assertAllZero 两条分支");
        }
    }

    /**
     * 断言除 {@code skipNames} 之外每个实例字段都回到了零值。
     *
     * @param skipNames 复用时**故意**写新值的字段（代次戳一类），必须显式列出
     */
    static void assertAllZero(Object poolItem, String... skipNames) throws Exception {
        Field[] fields = instanceFields(poolItem.getClass());
        int checked = 0;
        int skipped = 0;
        for (Field f : fields) {
            f.setAccessible(true);
            if (contains(skipNames, f.getName())) {
                skipped++;
                continue;
            }
            Class<?> t = f.getType();
            String what = f.getName() + " 残留上一位的值";
            if (t == float.class) assertEquals(what, 0f, f.getFloat(poolItem), 1e-6f);
            else if (t == int.class) assertEquals(what, 0, f.getInt(poolItem));
            else if (t == long.class) assertEquals(what, 0L, f.getLong(poolItem));
            else if (t == boolean.class) assertFalse(f.getName() + " 残留 true", f.getBoolean(poolItem));
            else throw new AssertionError("探针不认字段 " + f.getName() + " 的类型 " + t
                    + "：新增类型请同时补 poison 与 assertAllZero 两条分支");
            checked++;
        }
        assertEquals("skipNames 里有没碰到的字段（写错名字或字段已删）", skipNames.length, skipped);
        assertTrue("一个字段都没检查，这条用例已经空转", checked > 0);
    }

    /** 断言每个实例字段都**还带着**哨兵（用来证明 poison 真的作用到了对象上）。 */
    static void assertStillPoisoned(Object poolItem) throws Exception {
        int checked = 0;
        for (Field f : instanceFields(poolItem.getClass())) {
            f.setAccessible(true);
            Class<?> t = f.getType();
            if (t == int.class) assertEquals(f.getName(), SENTINEL_INT, f.getInt(poolItem));
            else if (t == float.class) assertEquals(f.getName(), SENTINEL_FLOAT, f.getFloat(poolItem), 0f);
            else if (t == long.class) assertEquals(f.getName(), SENTINEL_LONG, f.getLong(poolItem));
            else if (t == boolean.class) assertTrue(f.getName(), f.getBoolean(poolItem));
            else throw new AssertionError("探针不认字段 " + f.getName() + " 的类型 " + t
                    + "：新增类型请同时补 poison 与 assertAllZero 两条分支");
            checked++;
        }
        assertTrue("一个字段都没检查，这条用例已经空转", checked > 0);
    }

    private static Field[] instanceFields(Class<?> c) {
        Field[] all = c.getDeclaredFields();
        Field[] out = new Field[all.length];
        int n = 0;
        for (Field f : all) if (!Modifier.isStatic(f.getModifiers())) out[n++] = f;
        return java.util.Arrays.copyOf(out, n);
    }

    private static boolean contains(String[] names, String name) {
        for (String s : names) if (s.equals(name)) return true;
        return false;
    }
}
