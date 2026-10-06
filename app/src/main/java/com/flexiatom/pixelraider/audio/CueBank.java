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
package com.flexiatom.pixelraider.audio;

import com.flexiatom.pixelraider.game.AudioCues;

/**
 * 26 只音效的配方表（每条 {@link AudioCues} 在此有且只有一个波形）。
 *
 * <p>两类来源，分界写清楚（用户 2026-10-05 逐字「音频是抄，不是复制，因为都是代码形式的音频，所以是抄」)：
 * <ul>
 *   <li><b>重写自上游的 17 只</b>：脉冲／激光／散射三把枪、敌方四声、死亡／破盾／拾取／超载／购买／拒绝／
 *       波次／Boss 两声／切枪／开局。频率、占空比、包络时间、LFSR 种子逐字照上游的**参数表**，代码是 Java 重写的。</li>
 *   <li><b>本仓独有的 9 只</b>（他逐字「5～7新写」那一档，实际缺口是 9 个）：电弧／回旋／两档导弹出膛／
 *       锁定／雷达／哑火／连续杆撕开／道具炸弹。<b>这 9 只的参数是我定的</b>，判据只有两条：
 *       与最近的现成档**听得出是两件事**（他明确否掉过"复用最近的档"：同一只声音在两个语义上被听到是可判的），
 *       以及 UI 那一族走规格 §七 :293 的 30ms 方波档。</li>
 * </ul>
 *
 * <p>没有 {@code Split}（本仓无分裂实体）、{@code Respawn}（本仓死亡即结算）、{@code Achievement}（§五 成就未落地）、
 * 也没有僚机开火声——**僚机的开火逻辑今天还没接线**（{@code Balance.Wingman.fireGap} 全仓零消费者），
 * 生成一只没人播的声音不叫预备叫垃圾。
 */
public final class CueBank {

    private CueBank() {
    }

    /** 生成期出口：波形 + 该落 worldBus 的那五只就地烘"闷"（规格 §七 :291，运行时不做实时 DSP）。 */
    public static float[] render(int cue) {
        float[] w = waveOf(cue);
        if (AudioCues.busOf(cue) == AudioCues.BUS_WORLD) ChipWave.muffle(w);
        return w;
    }

    /** 纯配方，不含总线知识（单测打这一层，"闷不闷"归 {@link #render}）。 */
    public static float[] waveOf(int cue) {
        switch (cue) {
            // ================= 玩家枪声（上游 FirePulse/FireLaser/FireSpread） =================
            case AudioCues.FIRE_PULSE: {
                ChipWave.Square o = new ChipWave.Square(0.5f);
                return ChipWave.build(0.08f, (t, i) ->
                        0.5f * o.sample(880f - 440f * (t / 0.08f)) * ChipWave.envelope(t, 0.002f, 30f));
            }
            case AudioCues.FIRE_LASER: {
                ChipWave.Square o = new ChipWave.Square(0.25f);
                return ChipWave.build(0.1f, (t, i) ->
                        0.45f * o.sample(500f + 900f * (t / 0.1f)) * ChipWave.envelope(t, 0.001f, 22f));
            }
            case AudioCues.FIRE_SPREAD: {
                ChipWave.Square o = new ChipWave.Square(0.4f);
                return ChipWave.build(0.12f, (t, i) -> {
                    float step = new float[]{660f, 880f, 1100f}[(int) (t * 40f) % 3];
                    return 0.4f * o.sample(step) * ChipWave.envelope(t, 0.002f, 18f);
                });
            }

            // ================= 电弧／回旋（本仓独有） =================
            case AudioCues.FIRE_ARC: {
                // 四档跳频的颤振 + 一层高频毛刺：这把枪的读法是"接触即断"，音高不能是干净的滑奏
                ChipWave.Square o = new ChipWave.Square(0.3f);
                ChipWave.Noise n = new ChipWave.Noise(0x3a7);
                return ChipWave.build(0.09f, (t, i) -> {
                    float step = new float[]{1200f, 1900f, 1500f, 2200f}[(int) (t * 70f) % 4];
                    return 0.4f * o.sample(step) * ChipWave.envelope(t, 0.001f, 26f)
                            + 0.16f * n.sample(7000f) * ChipWave.envelope(t, 0f, 40f);
                });
            }
            case AudioCues.FIRE_SPIN: {
                // 0.07 秒一个来回的往返扫频：回旋的弹道会回来，声音也得回来，不能是单向滑
                ChipWave.Square o = new ChipWave.Square(0.45f);
                return ChipWave.build(0.14f, (t, i) -> {
                    float p = (t % 0.07f) / 0.07f;
                    float f = 520f + 760f * (p < 0.5f ? 2f * p : 2f - 2f * p);
                    return 0.38f * o.sample(f) * ChipWave.envelope(t, 0.002f, 14f);
                });
            }

            // ================= 两档导弹出膛（本仓独有） =================
            case AudioCues.MISSILE_LAUNCH: {
                ChipWave.Triangle tr = new ChipWave.Triangle();
                ChipWave.Noise n = new ChipWave.Noise(0x4d21);
                return ChipWave.build(0.22f, (t, i) -> {
                    float thump = 0.5f * tr.sample(90f + 170f * (t / 0.22f)) * ChipWave.envelope(t, 0.004f, 9f);
                    float jet = 0.22f * n.sample(3000f + 2200f * (t / 0.22f)) * ChipWave.envelope(t, 0.01f, 11f);
                    return thump + jet;
                });
            }
            case AudioCues.MISSILE_DOGFIGHT: {
                // 与常规那档分辨点：更短（0.16 vs 0.22）且起音就挑上去——格斗弹是"追出去"的，不是"推出去"的
                ChipWave.Square o = new ChipWave.Square(0.25f);
                ChipWave.Noise n = new ChipWave.Noise(0x1f77);
                return ChipWave.build(0.16f, (t, i) ->
                        0.34f * o.sample(700f + 800f * (t / 0.16f)) * ChipWave.envelope(t, 0.001f, 20f)
                                + 0.18f * n.sample(4200f + 1800f * (t / 0.16f)) * ChipWave.envelope(t, 0.005f, 26f));
            }

            // ================= UI 档：走规格 §七 :293 的 30ms 方波（更短更轻，避免糊在一起） ==========
            case AudioCues.LOCK_ON: {
                ChipWave.Square o = new ChipWave.Square(0.5f);
                return ChipWave.build(0.03f, (t, i) ->
                        0.3f * o.sample(1560f) * ChipWave.envelope(t, 0.001f, 45f));
            }
            case AudioCues.RADAR_CUE: {
                // 与锁定同族但低一档音高：两者会在同一秒内各响好几次，同音高就听不出"是指定还是开眼"
                ChipWave.Square o = new ChipWave.Square(0.5f);
                return ChipWave.build(0.03f, (t, i) ->
                        0.28f * o.sample(1180f) * ChipWave.envelope(t, 0.001f, 50f));
            }
            case AudioCues.MISFIRE: {
                // 哑火＝那枚弹**没有炸**（Game.spawnMisfire 的注释逐字）。所以它既没有高频爆裂、也不给"闷"：
                // 只有一记低频的扑，像发动机点不着。上游最近的档是 6000Hz 的 EnemyHit，正向相反。
                ChipWave.Noise n = new ChipWave.Noise(0x0a11);
                ChipWave.Square o = new ChipWave.Square(0.6f);
                return ChipWave.build(0.09f, (t, i) ->
                        0.5f * n.sample(220f) * ChipWave.envelope(t, 0.001f, 30f)
                                + 0.3f * o.sample(90f) * ChipWave.envelope(t, 0.002f, 24f));
            }

            // ================= 玩家侧状态与投资（上游 PlayerDeath/ShieldBreak/Powerup/Bomb/…） =======
            case AudioCues.PLAYER_DEATH: {
                ChipWave.Triangle tr = new ChipWave.Triangle();
                ChipWave.Noise n = new ChipWave.Noise(0x1234);
                return ChipWave.build(0.5f, (t, i) ->
                        0.5f * tr.sample(600f - 500f * (t / 0.5f)) * ChipWave.envelope(t, 0.005f, 5f)
                                + 0.3f * n.sample(3000f - 2600f * (t / 0.5f)) * ChipWave.envelope(t, 0f, 6f));
            }
            case AudioCues.SHIELD_BREAK: {
                ChipWave.Noise n = new ChipWave.Noise(0xdead);
                ChipWave.Square o = new ChipWave.Square(0.2f);
                return ChipWave.build(0.22f, (t, i) ->
                        0.45f * n.sample(5000f - 4000f * (t / 0.22f)) * ChipWave.envelope(t, 0f, 16f)
                                + 0.25f * o.sample(1200f - 700f * (t / 0.22f)) * ChipWave.envelope(t, 0.002f, 14f));
            }
            case AudioCues.OVERLOAD: {
                ChipWave.Triangle tr = new ChipWave.Triangle();
                ChipWave.Noise n = new ChipWave.Noise(0x9e37);
                return ChipWave.build(0.45f, (t, i) ->
                        0.6f * tr.sample(120f - 80f * (t / 0.45f)) * ChipWave.envelope(t, 0.004f, 6f)
                                + 0.35f * n.sample(2500f - 2300f * (t / 0.45f)) * ChipWave.envelope(t, 0f, 7f));
            }
            case AudioCues.ITEM_BOMB: {
                // 上游没有"道具炸弹"这一档（它只有一声 Bomb ≈ 超载横扫）。本仓是两个事件、两套画面，
                // 所以要两只声：这一只的下坠更长（0.5 vs 0.45）、起音更低（160→60），读成"实心的一坨"。
                ChipWave.Triangle tr = new ChipWave.Triangle();
                ChipWave.Noise n = new ChipWave.Noise(0x6a17);
                return ChipWave.build(0.5f, (t, i) ->
                        0.55f * tr.sample(160f - 100f * (t / 0.5f)) * ChipWave.envelope(t, 0.006f, 5f)
                                + 0.4f * n.sample(4000f - 3200f * (t / 0.5f)) * ChipWave.envelope(t, 0f, 6f));
            }
            case AudioCues.POWERUP: {
                ChipWave.Square o = new ChipWave.Square(0.5f);
                return ChipWave.build(0.3f, (t, i) ->
                        0.4f * o.sample(400f + 1200f * (t / 0.3f)) * ChipWave.envelope(t, 0.004f, 6f));
            }
            case AudioCues.BUY: {
                ChipWave.Square o = new ChipWave.Square(0.5f);
                return ChipWave.build(0.16f, (t, i) ->
                        0.4f * o.sample(t < 0.08f ? 880f : 1320f) * ChipWave.envelope(t % 0.08f, 0.002f, 14f));
            }
            case AudioCues.DENIED: {
                ChipWave.Square o = new ChipWave.Square(0.15f);
                return ChipWave.build(0.18f, (t, i) -> {
                    float gate = ((int) (t * 20f) % 2 == 0) ? 1f : 0f;
                    return 0.4f * gate * o.sample(180f) * ChipWave.envelope(t, 0.002f, 10f);
                });
            }
            case AudioCues.WAVE_START: {
                ChipWave.Square o = new ChipWave.Square(0.4f);
                return ChipWave.build(0.2f, (t, i) ->
                        0.4f * o.sample(t < 0.1f ? 523f : 784f) * ChipWave.envelope(t % 0.1f, 0.003f, 12f));
            }
            case AudioCues.BOSS_KILL: {
                ChipWave.Square o = new ChipWave.Square(0.5f);
                ChipWave.Noise n = new ChipWave.Noise(0xbeef);
                return ChipWave.build(0.6f, (t, i) -> {
                    float[] notes = {523f, 659f, 784f, 1046f};
                    int idx = Math.min((int) (t / 0.12f), notes.length - 1);
                    float mel = 0.45f * o.sample(notes[idx]) * ChipWave.envelope(t % 0.12f, 0.003f, 6f);
                    float boom = 0.35f * n.sample(3000f - 2800f * (t / 0.6f)) * ChipWave.envelope(t, 0f, 6f);
                    return mel + boom;
                });
            }
            case AudioCues.WEAPON_SWITCH: {
                ChipWave.Square o = new ChipWave.Square(0.3f);
                return ChipWave.build(0.1f, (t, i) ->
                        0.35f * o.sample(700f + 500f * (t / 0.1f)) * ChipWave.envelope(t, 0.002f, 22f));
            }
            case AudioCues.RUN_START: {
                ChipWave.Square o = new ChipWave.Square(0.5f);
                return ChipWave.build(0.25f, (t, i) ->
                        0.45f * o.sample(t < 0.12f ? 660f : 990f) * ChipWave.envelope(t % 0.12f, 0.003f, 9f));
            }

            // ================= 世界侧（敌人那一族，落 bus 后统一烘闷） =========================
            case AudioCues.ENEMY_HIT: {
                ChipWave.Noise n = new ChipWave.Noise(0x51);
                return ChipWave.build(0.05f, (t, i) ->
                        0.4f * n.sample(6000f) * ChipWave.envelope(t, 0f, 60f));
            }
            case AudioCues.ENEMY_FIRE: {
                ChipWave.Square o = new ChipWave.Square(0.5f);
                return ChipWave.build(0.07f, (t, i) ->
                        0.35f * o.sample(320f - 120f * (t / 0.07f)) * ChipWave.envelope(t, 0.002f, 26f));
            }
            case AudioCues.KILL: {
                ChipWave.Noise n = new ChipWave.Noise(0x7c3);
                ChipWave.Square o = new ChipWave.Square(0.5f);
                return ChipWave.build(0.16f, (t, i) ->
                        0.4f * n.sample(4000f - 2500f * (t / 0.16f)) * ChipWave.envelope(t, 0f, 22f)
                                + 0.3f * o.sample(440f - 300f * (t / 0.16f)) * ChipWave.envelope(t, 0.002f, 18f));
            }
            case AudioCues.BOSS_SPAWN: {
                ChipWave.Triangle tr = new ChipWave.Triangle();
                ChipWave.Noise n = new ChipWave.Noise(0x0ff0);
                ChipWave.Square o = new ChipWave.Square(0.5f);
                return ChipWave.build(0.7f, (t, i) ->
                        0.55f * tr.sample(200f - 140f * (t / 0.7f)) * ChipWave.envelope(t, 0.01f, 3.5f)
                                + 0.3f * n.sample(900f - 700f * (t / 0.7f)) * ChipWave.envelope(t, 0.02f, 4f)
                                + 0.3f * o.sample(100f + 50f * (t / 0.7f)) * ChipWave.envelope(t, 0.01f, 4f));
            }
            case AudioCues.ROD_TEAR: {
                // 连续杆撕开：它是"命中解体那一格"的替代音 ⇒ 与 Kill 同侧（他给的只有归类，参数我定）。
                // 高频噪声快速塌下来 + 一层窄占空方波做"金属"，烘完闷之后剩下的是撕而不是爆。
                ChipWave.Noise n = new ChipWave.Noise(0x5bd1);
                ChipWave.Square o = new ChipWave.Square(0.15f);
                return ChipWave.build(0.2f, (t, i) ->
                        0.5f * n.sample(9000f - 8000f * (t / 0.2f)) * ChipWave.envelope(t, 0f, 12f)
                                + 0.25f * o.sample(2400f - 1600f * (t / 0.2f)) * ChipWave.envelope(t, 0.002f, 16f));
            }
            default:
                throw new IllegalArgumentException("线索表与配方表失配：cue=" + cue);
        }
    }
}
