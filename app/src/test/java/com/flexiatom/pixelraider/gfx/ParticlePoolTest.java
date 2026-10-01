/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
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
package com.flexiatom.pixelraider.gfx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.flexiatom.pixelraider.gfx.ParticlePool.Particle;

import org.junit.Test;

import java.lang.reflect.Field;

/**
 * 粒子池（规格 §三 / §六）。三件必须守住的事：
 * 1. 稳定态**每帧零分配**——池满之后 spawn() 不得创建新对象；
 * 2. 复用时**重置全部字段**（含 px/py）。漏一个就是"新粒子带旧粒子的颜色"的鬼影，
 *    所以这里用反射遍历 Particle 的所有字段，而不是手写一份字段清单——手写清单会在加字段时悄悄失真。
 * 3. 删除走**末位交换**，倒序遍历不得漏检任何活跃粒子。
 */
public class ParticlePoolTest {

    @Test
    public void spawnTakesFromFreeListAndKillReturnsIt() {
        ParticlePool pool = new ParticlePool(4);
        assertEquals(4, pool.freeCount());
        Particle a = pool.spawn(), b = pool.spawn();
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(2, pool.activeCount());
        assertEquals(2, pool.freeCount());
        pool.killAt(0);
        assertEquals(1, pool.activeCount());
        assertEquals(3, pool.freeCount());
        assertSame("末位交换：b 应顶到槽 0", b, pool.activeAt(0));
    }

    @Test
    public void exhaustedPoolReturnsNullInsteadOfGrowing() {
        ParticlePool pool = new ParticlePool(2);
        assertNotNull(pool.spawn());
        assertNotNull(pool.spawn());
        assertNull(pool.spawn());
        assertEquals(2, pool.activeCount());
    }

    @Test
    public void reuseAllocatesNoNewObjectsAfterWarmup() {
        final int CAP = 32, ROUNDS = 200;
        ParticlePool pool = new ParticlePool(CAP);
        Particle[] seen = new Particle[CAP];
        for (int i = 0; i < CAP; i++) seen[i] = pool.spawn();
        // 稳定态：反复 spawn/kill，拿到的必须全是同一批对象实例
        for (int r = 0; r < ROUNDS; r++) {
            pool.killAt(pool.activeCount() - 1);   // 先还一格：池满时 spawn 按契约返回 null
            Particle p = pool.spawn();
            assertNotNull("第 " + r + " 轮 spawn 失败", p);
            boolean known = false;
            for (int i = 0; i < CAP; i++) if (seen[i] == p) known = true;
            assertTrue("第 " + r + " 轮出现了新对象：池在偷偷增长", known);
        }
        assertEquals("暖机后不应有复用之外的首次使用", (long) CAP, pool.spawnTotal() - pool.reuseTotal());
    }

    @Test
    public void reuseResetsEveryDeclaredField() throws Exception {
        ParticlePool pool = new ParticlePool(1);
        Particle p = pool.spawn();
        poison(p);
        pool.killAt(0);
        Particle again = pool.spawn();
        assertSame(p, again);
        assertNoFieldPoisoned(again);
    }

    @Test
    public void stepCompactionVisitsEveryParticle() {
        ParticlePool pool = new ParticlePool(8);
        for (int i = 0; i < 8; i++) {
            Particle p = pool.spawn();
            p.life = (i % 2 == 0) ? 0.5f : 1f;   // 偶数槽先死
            p.x = 10f * i;
        }
        int killed = pool.stepAndCompact(0.6f);
        assertEquals(4, killed);
        assertEquals(4, pool.activeCount());
        for (int i = 0; i < pool.activeCount(); i++) {
            Particle p = pool.activeAt(i);
            assertTrue("漏检：第 " + i + " 个已过期粒子还在活跃表里", p.life > 0f);
        }
    }

    @Test
    public void stepIntegratesMotionDragAndGravity() {
        ParticlePool pool = new ParticlePool(1);
        Particle p = pool.spawn();
        p.x = 0; p.y = 0;
        p.vx = 100; p.vy = 0;
        p.gravity = 200;
        p.drag = 0f;
        p.life = 1f;
        pool.stepAndCompact(0.1f);
        assertEquals(10f, p.x, 1e-4f);
        assertEquals(2f, p.y, 1e-4f);            // vy 先加 gravity*dt 再积分（半隐式）
        assertEquals(0f, p.px, 1e-4f);           // px/py 是上一帧位置，不是当前位置
        assertEquals(0f, p.py, 1e-4f);
        pool.stepAndCompact(0.1f);
        assertEquals(10f, p.px, 1e-4f);          // 第二帧的上一帧位置 = 第一帧末位置
    }

    @Test
    public void cullOutOfBoundsRemovesOnlyWhatLeftTheViewport() {
        ParticlePool pool = new ParticlePool(4);
        Particle inside = pool.spawn();
        inside.x = 100; inside.y = 100; inside.life = 9f;
        Particle above = pool.spawn();
        above.x = 100; above.y = -40; above.life = 9f;
        pool.cullOutOfBounds(240, 320, 16);
        assertEquals(1, pool.activeCount());
        assertSame(inside, pool.activeAt(0));
        assertNotNull(above);   // 对象没被销毁，只是回到 free-list
    }

    @Test
    public void clearReturnsEverythingAndWipesFields() throws Exception {
        ParticlePool pool = new ParticlePool(3);
        Particle a = pool.spawn(), b = pool.spawn();
        pool.spawn();
        poison(a);
        b.px = 999f;
        pool.clear();
        assertEquals(0, pool.activeCount());
        assertEquals(3, pool.freeCount());
        Particle d = pool.spawn();
        assertNoFieldPoisoned(d);
    }

    // ---- 反射毒化：字段清单由 Class 文件给出，不手写 ------------------------------------

    /** 只遍历实例字段：KIND_* 是 static final 常量，毒化它们会抛 IllegalAccessException，
     *  而"是否被重置"对常量根本不成立。 */
    private static Field[] instanceFields() {
        Field[] all = Particle.class.getDeclaredFields();
        Field[] out = new Field[all.length];
        int n = 0;
        for (Field f : all) if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) out[n++] = f;
        return java.util.Arrays.copyOf(out, n);
    }

    private static final int SENTINEL_INT = 0x7F7F7F7F;
    private static final float SENTINEL_FLOAT = 12345.5f;

    private static void poison(Particle p) throws Exception {
        for (Field f : instanceFields()) {
            f.setAccessible(true);
            Class<?> t = f.getType();
            if (t == int.class) f.setInt(p, SENTINEL_INT);
            else if (t == float.class) f.setFloat(p, SENTINEL_FLOAT);
            else if (t == boolean.class) f.setBoolean(p, true);
            else if (t == long.class) f.setLong(p, SENTINEL_INT);
            else if (t == double.class) f.setDouble(p, SENTINEL_FLOAT);
        }
    }

    private static void assertNoFieldPoisoned(Particle p) throws Exception {
        for (Field f : instanceFields()) {
            f.setAccessible(true);
            Class<?> t = f.getType();
            if (t == float.class) {
                assertEquals(f.getName() + " 残留上一颗粒子的值", 0f, f.getFloat(p), 1e-6f);
            } else if (t == int.class) {
                assertEquals(f.getName() + " 残留上一颗粒子的值", 0, f.getInt(p));
            } else if (t == boolean.class) {
                assertTrue(f.getName() + " 残留 true", !f.getBoolean(p));
            }
        }
    }
}
