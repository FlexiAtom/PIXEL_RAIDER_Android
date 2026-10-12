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
 * 战役简报的**内容层**：幕 → 任务 → 目标（池件 {@code campaign-data-tree} 要落的那半张树）。
 *
 * <p>这里只有**字**和**结构**，没有像素、没有命中——那一半在 {@code ui.BriefingLayout}。
 * 分开的理由与 {@link WaveDirector} 的星区名同一族：一张表要被版面、单测、以后的存档摘要同时读，
 * 掺进坐标就等于"改一版面要动内容"。
 *
 * <p>全部是编译期常量串。这不是风格偏好：中文一律走 {@code gfx.TextCache}（常量串烘焙一次），
 * 运行期拼出来的串每帧一次 cache miss（{@code ui.DrawKit} 类注释那条口径，逐字见其「烘焙/取色」清单）。
 *
 * <p><b>背景 ≤3 行、目标 ≤3 条是写作纪律，不是断言</b>（他 2026-10-12 逐字「{@code <背景与目的>}最多3行，
 * 任务目标最多3个」）。代码里校验的是"上屏之后内容高出没高出视口"（同一天逐字「上限在代码里校验，
 * 但是为了是否出现滚动条而校验」）——越限的后果是出轨，不是报错，所以这里不摆 {@code MAX_*} 那种没人读的常量。
 */
public final class CampaignBriefing {

    /** 战役任务数 = 章数 = Boss 场数（{@link Mode#chapterCount()} 那条除法的内容侧）。 */
    public static final int MISSIONS = 8;
    /** 幕数：3＋3＋2 划分（他 2026-10-12 逐字「你的幕划分没有问题」）。 */
    public static final int ACTS = 3;

    /** 任务号 1 起，越界夹回表内而不是抛——星区名那张表也是这个口径（{@link WaveDirector#zoneName}）。 */
    public static int missionIndex(int mission) {
        return Math.min(MISSIONS, Math.max(1, mission));
    }

    /** 幕序号 1..3：任务 1–3 第一幕、4–6 第二幕、7–8 第三幕。 */
    public static int actOf(int mission) {
        int i = missionIndex(mission);
        return i <= 3 ? 1 : (i <= 6 ? 2 : 3);
    }

    /** 「第一幕」。中文序号是他 03:52 实例的形状。 */
    public static String actLabel(int mission) {
        return "第" + ACT_LABELS[actOf(mission) - 1] + "幕";
    }

    /**
     * 首行：{@code 第一幕————任务1————军事行动: 猎户边境}。
     *
     * <p>分隔是**连续四枚**破折号（枚数由他实例给出），冒号是**半角＋一空格**（他 2026-10-12 裁的）。
     * 军事行动名与战役名同源，直接取 {@link WaveDirector#zoneName}——不另立第二份星区名。
     */
    public static String headline(int mission) {
        int i = missionIndex(mission);
        return actLabel(i) + "————任务" + i + "————军事行动: " + WaveDirector.zoneName(i - 1);
    }

    /**
     * 上屏行序列（含模板里那两枚空行）：首行 / 空 / 背景×3 / 空 / 目标×2。
     *
     * <p>空行留在**内容**里而不是版面里，因为它是他那两份实例的形状；版面要压缩时删的是行距，不是结构。
     */
    public static String[] lines(int mission) {
        int i = missionIndex(mission);
        String[] bg = BACKGROUND[i - 1];
        String[] goals = GOALS[i - 1];
        String[] out = new String[3 + bg.length + goals.length];
        out[0] = headline(i);
        out[1] = "";
        System.arraycopy(bg, 0, out, 2, bg.length);
        out[2 + bg.length] = "";
        for (int g = 0; g < goals.length; g++) {
            out[3 + bg.length + g] = "任务目标" + GOAL_PREFIX[g] + ": " + goals[g];
        }
        return out;
    }

    /** 背景那几行（不含首行、不含空行），供只想数内容高的调用方用。 */
    public static String[] background(int mission) {
        return BACKGROUND[missionIndex(mission) - 1];
    }

    /** 目标那几条（前缀已拼好）。 */
    public static String[] goals(int mission) {
        String[] raw = GOALS[missionIndex(mission) - 1];
        String[] out = new String[raw.length];
        for (int g = 0; g < raw.length; g++) {
            out[g] = "任务目标" + GOAL_PREFIX[g] + ": " + raw[g];
        }
        return out;
    }

    private static final String[] ACT_LABELS = {"一", "二", "三"};

    /** 目标前缀用中文序号（他实例的形状）；条数上限 3 就是这条数组的长度。 */
    private static final String[] GOAL_PREFIX = {"一", "二", "三"};

    /**
     * 背景与目的，8 任务 ×3 行。
     *
     * <p>每任务**首句**逐字取 {@code campaign-story-beats}（#85）那 8 句已定稿文案，不重写、不产第二真源；
     * 后两句是对 {@code zone-scene-themes} §7 签字值的复述，逐句过禁忌 6 的全量审查。审查揪出我自己写错的
     * 一处：「实弹在这儿打不透」把 T4 的**射速** ×0.85 写成了伤害承诺，已改口「跟不上」（池件 §8.5）。
     */
    private static final String[][] BACKGROUND = {
            {"教学关，玩家需要在引导下击退敌人", "边境没有船了。第一次点火，向前。", "这里没有地形花招，练熟再走。"},
            {"星云里全是碎壳。它们在等什么？", "这里的护盾补给只剩五成半。", "别指望挨一下再补满。"},
            {"雷达响，屏上没点。", "暗域里冲刺型来得格外多。", "靠眼睛，别靠读数。"},
            {"红色是热的，也是有人来过的颜色。", "实弹在这儿跟不上，激光反而更利。", "导弹照旧。"},
            {"引擎一冷就抖。别停。", "开枪慢近两成，飞出去的也慢。", "环带上的冰不像冰，像停住的浪。"},
            {"堡垒在这里铸的。现在轮到它。", "同样的弹药，多挨四成半才倒。", "熔炉里没有便宜可占。"},
            {"枪跟不上手，掉得也少。", "涡旋砍射速也砍掉落，不砍别的。", "省着打。"},
            {"没有星了。回头已经来不及。", "这儿的东西更扛，来的速度也慢一档。", "打完这一场，战役就到底了。"},
    };

    /**
     * 任务目标，8 任务 ×2 条。任务 1 那两条是**他实例的逐字**（「完成新手引导」／「击杀一个敌人」）——
     * 那是唯一见到他亲笔写目标的地方。其余按现行 Boss 轮换 {@code 0,1,2,…}：章 1/4/7 毁灭者、
     * 2/5/8 蜘蛛、3/6 堡垒（章 8 是蜘蛛不是堡垒这条仍在 #85 待裁，本表按**现行轮换**写，不预支改法）。
     */
    private static final String[][] GOALS = {
            {"完成新手引导", "击杀一个敌人"},
            {"撑过五波", "击落一只蜘蛛"},
            {"撑过五波", "击落一座堡垒"},
            {"换一次枪", "击落一枚毁灭者"},
            {"撑过五波", "击落一只蜘蛛"},
            {"撑过五波", "击落一座堡垒"},
            {"撑过五波", "击落一枚毁灭者"},
            {"撑过最后五波", "击落终章的蜘蛛"},
    };

    private CampaignBriefing() { }
}
