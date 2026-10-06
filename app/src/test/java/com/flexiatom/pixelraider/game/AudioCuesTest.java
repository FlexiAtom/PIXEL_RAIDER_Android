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
package com.flexiatom.pixelraider.game;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 事件 id 表：P2 里它是**游戏侧与磁盘之间唯一的接缝**——{@code fileNameOf} 的返回值既是生成器写出的
 * 文件名，也是缓存键的一部分（改一个字符串＝让所有已安装设备重生成一遍）。
 *
 * <p>所以钉的是"这张表和它自己同源"：常量集合、{@code COUNT}、文件名、总线、优先级，五者必须互相自洽，
 * 而不是各写各的。这类自洽只有反射能一次性查出来——手写清单会跟着漏。
 *
 * <p>能在 JVM 里钉是因为这个类只做整表与 switch，一个 android 符号都不认识（规格 §八 的解耦在这里的可验证形式）。
 */
public final class AudioCuesTest {

    /** 不是"线索"的公开 int 常量：表长、两个总线号、三条音乐轨号。 */
    private static final Set<String> NON_CUE_FIELDS = new HashSet<>(Arrays.asList(
            "COUNT", "BUS_PLAYER", "BUS_WORLD", "BGM_MENU", "BGM_PLAY", "BGM_BOSS"));

    @Test
    public void cueConstantsAreExactlyZeroThroughCount() throws Exception {
        List<Integer> ids = cueIds();
        assertEquals("线索常量个数与 COUNT 不一致（加了事件忘了改表长）", AudioCues.COUNT, ids.size());
        Collections.sort(ids);
        for (int i = 0; i < ids.size(); i++) {
            assertEquals("线索 id 必须是 0..COUNT-1 的连续整数，缺第 " + i + " 个", Integer.valueOf(i), ids.get(i));
        }
    }

    /**
     * 文件名是缓存键：全表唯一、小写、带 {@code sfx_} 前缀，且不与音乐名撞车。
     * 撞车的那一格在 SoundPool 里表现为"两个事件共用一条音"——没有任何报错。
     */
    @Test
    public void fileNamesAreUniqueLowerSnakedAndDisjointFromBgm() {
        Set<String> seen = new HashSet<>();
        for (int cue = 0; cue < AudioCues.COUNT; cue++) {
            String name = AudioCues.fileNameOf(cue);
            assertTrue("文件名不合形（" + cue + "）: " + name, name.matches("sfx_[a-z0-9_]+"));
            assertTrue("文件名撞车: " + name, seen.add(name));
        }
        Set<String> bgm = new HashSet<>();
        for (int track = 0; track <= AudioCues.BGM_BOSS; track++) {
            String name = AudioCues.bgmFileNameOf(track);
            assertTrue("音乐名不合形: " + name, name.matches("bgm_[a-z_]+"));
            assertTrue("音乐名内部撞车: " + name, bgm.add(name));
        }
        for (String n : bgm) {
            assertTrue("音效名与音乐名撞车: " + n, !seen.contains(n));
        }
    }

    @Test
    public void busAndPriorityStayInsideTheirOwnAlphabets() {
        for (int cue = 0; cue < AudioCues.COUNT; cue++) {
            int bus = AudioCues.busOf(cue);
            assertTrue("总线只能有两档（" + cue + "）",
                    bus == AudioCues.BUS_PLAYER || bus == AudioCues.BUS_WORLD);
            int p = AudioCues.priorityOf(cue);
            assertTrue("优先级 0..3（" + cue + "）: " + p, p >= 0 && p <= 3);
        }
    }

    /**
     * 两条总线归类的例外，各有出处，不是我的分类口味：
     * {@code BOSS_KILL} 与 {@code MISFIRE} 都归"玩家自己的投资"那一族（用户 2026-10-05 逐字
     * 「哑火（#58 淘汰出口）应该是上限配额不够的问题吧」）。
     */
    @Test
    public void theTwoBusExceptionsAreTheRuledOnes() {
        assertEquals("BOSS_KILL 是投资兑现，不是又一次世界爆炸",
                AudioCues.BUS_PLAYER, AudioCues.busOf(AudioCues.BOSS_KILL));
        assertEquals("MISFIRE 与 PLAYER_DEATH 同族",
                AudioCues.BUS_PLAYER, AudioCues.busOf(AudioCues.MISFIRE));
    }

    /** 世界总线＝敌人那一族，恰好五个，且就是那五个；多一个少一个都会把闷音派给错误的事件。 */
    @Test
    public void worldBusIsExactlyTheEnemyFamily() {
        List<Integer> world = new ArrayList<>();
        for (int cue = 0; cue < AudioCues.COUNT; cue++) {
            if (AudioCues.busOf(cue) == AudioCues.BUS_WORLD) world.add(cue);
        }
        assertEquals("世界总线成员表变了（闷音会跟着派错）",
                Arrays.asList(AudioCues.ENEMY_HIT, AudioCues.ENEMY_FIRE, AudioCues.KILL,
                        AudioCues.BOSS_SPAWN, AudioCues.ROD_TEAR), world);
    }

    /**
     * 挤占顺序的两个端点：三声"该被听见"的在顶档，五把枪的开火音在底档——
     * 枪声密到自己挤自己，这是 {@code §七 :290} 那句"在前"唯一能落地成优先级的读法。
     */
    @Test
    public void prioritiesPinTheThreeMustHeardAndTheFiveGunShots() {
        assertEquals(3, AudioCues.priorityOf(AudioCues.PLAYER_DEATH));
        assertEquals(3, AudioCues.priorityOf(AudioCues.BOSS_SPAWN));
        assertEquals(3, AudioCues.priorityOf(AudioCues.BOSS_KILL));
        int[] guns = {AudioCues.FIRE_PULSE, AudioCues.FIRE_LASER, AudioCues.FIRE_SPREAD,
                AudioCues.FIRE_ARC, AudioCues.FIRE_SPIN};
        for (int g : guns) {
            assertEquals("枪声必须留在最低档（挤占让路）", 0, AudioCues.priorityOf(g));
        }
    }

    @Test
    public void unknownIdsAreRejectedNotSilentlyDefaulted() {
        reject(() -> AudioCues.fileNameOf(AudioCues.COUNT));
        reject(() -> AudioCues.fileNameOf(-1));
        reject(() -> AudioCues.bgmFileNameOf(AudioCues.BGM_BOSS + 1));
        reject(() -> AudioCues.bgmFileNameOf(-1));
    }

    private static void reject(Runnable r) {
        try {
            r.run();
            fail("越界 id 不该被静默接住");
        } catch (IllegalArgumentException expected) {
            // 正是这一条：默认分支必须炸，不能让 SoundPool 播一条不相干的音
        }
    }

    private static List<Integer> cueIds() throws Exception {
        List<Integer> ids = new ArrayList<>();
        for (Field f : AudioCues.class.getFields()) {
            if (f.getType() != int.class || !Modifier.isStatic(f.getModifiers())) continue;
            if (NON_CUE_FIELDS.contains(f.getName())) continue;
            ids.add(f.getInt(null));
        }
        return ids;
    }
}
