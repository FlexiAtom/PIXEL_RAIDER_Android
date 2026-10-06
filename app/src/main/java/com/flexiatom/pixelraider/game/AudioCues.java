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

/**
 * 音效线索表：游戏侧能叫得出的"发生了什么事"，逐个编成稳定 id。
 *
 * <p>它住在 {@code game} 包而不是 {@code audio} 包，是因为**游戏只许认识事件、不许认识 android 媒体栈**
 * （规格 §八：逻辑核与平台层解耦）。播放面拿这张表去查自己的总线与优先级，反向依赖为零。
 *
 * <p><b>总线归类照规格 §七 :290 括号里的定义机械分，不是我发明的分类</b>：
 * {@link #BUS_PLAYER}＝"玩家射击/受伤/超载/UI 音，清脆"、{@link #BUS_WORLD}＝"敌人爆炸，略闷"。
 * 两条例外与它们的理由：
 * <ul>
 *   <li>{@link #BOSS_KILL} 落 player——它是玩家投资兑现的那一下，与 {@link #PLAYER_DEATH} 同族，
 *       不是"世界里的又一次爆炸"。</li>
 *   <li>{@link #MISFIRE} 落 player 且**不给闷**（用户 2026-10-05 逐字「哑火（#58 淘汰出口）应该是上限配额不够的问题吧」
 *       ⇒ 它是玩家自己的投资没兑现那一族）。{@code Game.spawnMisfire} 的注释同向：淘汰的弹**没有炸**。</li>
 * </ul>
 *
 * <p>{@link #BGM_MENU} 等三条是音乐轨号，与音效共用这张表：音乐和音效在游戏侧都是"一个 int 事件"，
 * 分派交给播放面，{@code Game} 不知道 BGM 用的是什么播放器。
 */
public final class AudioCues {

    private AudioCues() {
    }

    // ---- 玩家侧：五种直飞枪 + 两档导弹（规格 §七 :290 的"玩家射击"） ----------------
    public static final int FIRE_PULSE = 0;
    public static final int FIRE_LASER = 1;
    public static final int FIRE_SPREAD = 2;
    public static final int FIRE_ARC = 3;
    public static final int FIRE_SPIN = 4;
    public static final int MISSILE_LAUNCH = 5;
    public static final int MISSILE_DOGFIGHT = 6;

    // ---- 玩家侧：状态与投资（受伤/超载/拾取/UI） --------------------------------------
    public static final int PLAYER_DEATH = 7;
    public static final int SHIELD_BREAK = 8;
    public static final int OVERLOAD = 9;
    public static final int ITEM_BOMB = 10;
    public static final int POWERUP = 11;
    public static final int BUY = 12;
    public static final int DENIED = 13;
    public static final int WAVE_START = 14;
    public static final int BOSS_KILL = 15;
    public static final int WEAPON_SWITCH = 16;
    public static final int RUN_START = 17;
    public static final int LOCK_ON = 18;
    public static final int RADAR_CUE = 19;
    public static final int MISFIRE = 20;

    // ---- 世界侧：敌人那一族的发声（规格 §七 :290 的"敌人爆炸，略闷"） -----------------
    public static final int ENEMY_HIT = 21;
    public static final int ENEMY_FIRE = 22;
    public static final int KILL = 23;
    public static final int BOSS_SPAWN = 24;
    public static final int ROD_TEAR = 25;

    public static final int COUNT = 26;

    public static final int BUS_PLAYER = 0;
    public static final int BUS_WORLD = 1;

    public static final int BGM_MENU = 0;
    public static final int BGM_PLAY = 1;
    public static final int BGM_BOSS = 2;

    private static final int[] BUS = {
            BUS_PLAYER, BUS_PLAYER, BUS_PLAYER, BUS_PLAYER, BUS_PLAYER, BUS_PLAYER, BUS_PLAYER,
            BUS_PLAYER, BUS_PLAYER, BUS_PLAYER, BUS_PLAYER, BUS_PLAYER, BUS_PLAYER, BUS_PLAYER,
            BUS_PLAYER, BUS_PLAYER, BUS_PLAYER, BUS_PLAYER, BUS_PLAYER, BUS_PLAYER, BUS_PLAYER,
            BUS_WORLD, BUS_WORLD, BUS_WORLD, BUS_WORLD, BUS_WORLD,
    };

    /**
     * 挤占顺序（0 最低）。上游那张表里"玩家死亡 / Boss 出场 / Boss 被击破"是最高一档，五把枪的开火音最低——
     * 枪声密到会自己把自己挤掉，而那六声是**该**被挤掉的（{@code :290} 说玩家自己的枪声"在前"，说的是它在不在世界音之前，
     * 不是它能不能挤掉别的事件）。
     */
    private static final int[] PRIORITY = {
            0, 0, 0, 0, 0, 1, 1,
            3, 2, 2, 2, 1, 1, 1, 2, 3, 1, 2, 1, 1, 2,
            1, 1, 1, 3, 1,
    };

    /** 生成文件用的稳定名。**这个字符串是缓存键的一部分**，改它等于让所有已安装设备重生成一次。 */
    public static String fileNameOf(int cue) {
        return switch (cue) {
            case FIRE_PULSE -> "sfx_fire_pulse";
            case FIRE_LASER -> "sfx_fire_laser";
            case FIRE_SPREAD -> "sfx_fire_spread";
            case FIRE_ARC -> "sfx_fire_arc";
            case FIRE_SPIN -> "sfx_fire_spin";
            case MISSILE_LAUNCH -> "sfx_missile_launch";
            case MISSILE_DOGFIGHT -> "sfx_missile_dogfight";
            case PLAYER_DEATH -> "sfx_player_death";
            case SHIELD_BREAK -> "sfx_shield_break";
            case OVERLOAD -> "sfx_overload";
            case ITEM_BOMB -> "sfx_item_bomb";
            case POWERUP -> "sfx_powerup";
            case BUY -> "sfx_buy";
            case DENIED -> "sfx_denied";
            case WAVE_START -> "sfx_wave_start";
            case BOSS_KILL -> "sfx_boss_kill";
            case WEAPON_SWITCH -> "sfx_weapon_switch";
            case RUN_START -> "sfx_run_start";
            case LOCK_ON -> "sfx_lock_on";
            case RADAR_CUE -> "sfx_radar_cue";
            case MISFIRE -> "sfx_misfire";
            case ENEMY_HIT -> "sfx_enemy_hit";
            case ENEMY_FIRE -> "sfx_enemy_fire";
            case KILL -> "sfx_kill";
            case BOSS_SPAWN -> "sfx_boss_spawn";
            case ROD_TEAR -> "sfx_rod_tear";
            default -> throw new IllegalArgumentException("未知线索 id: " + cue);
        };
    }

    public static int busOf(int cue) {
        return BUS[cue];
    }

    public static int priorityOf(int cue) {
        return PRIORITY[cue];
    }

    public static String bgmFileNameOf(int track) {
        return switch (track) {
            case BGM_MENU -> "bgm_menu";
            case BGM_PLAY -> "bgm_play";
            case BGM_BOSS -> "bgm_boss";
            default -> throw new IllegalArgumentException("未知 BGM 轨号: " + track);
        };
    }
}
