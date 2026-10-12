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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 简报内容层的**形状**。钉的是他那两份实例教我的三件事：分隔枚数、冒号半角、幕划分 3＋3＋2——
 * 这三格都是"字面看着差不多、错一格就不是他给的形状"。内容高（几行、几个 px）归
 * {@code BriefingLayoutTest}，那里才是视口那笔账。
 */
public class CampaignBriefingTest {

    @Test
    public void theActSplitIsThreeThreeTwo() {
        assertEquals(1, CampaignBriefing.actOf(1));
        assertEquals(1, CampaignBriefing.actOf(3));
        assertEquals(2, CampaignBriefing.actOf(4));
        assertEquals(2, CampaignBriefing.actOf(6));
        assertEquals(3, CampaignBriefing.actOf(7));
        assertEquals(3, CampaignBriefing.actOf(8));
        assertEquals(3, CampaignBriefing.ACTS);
        assertEquals(8, CampaignBriefing.MISSIONS);
    }

    @Test
    public void theHeadlineKeepsFourDashesAndAHalfWidthColon() {
        assertEquals("第一幕————任务1————军事行动: 猎户边境", CampaignBriefing.headline(1));
        assertEquals("第二幕————任务4————军事行动: 赤色深空", CampaignBriefing.headline(4));
        assertEquals("第三幕————任务8————军事行动: 虚空洞穴", CampaignBriefing.headline(8));
        assertEquals("八条首行同字符数：3幕+4破+3任务+4破+4行动+2冒号空格+4星区 = 24", 24,
                CampaignBriefing.headline(1).length());
        assertEquals(CampaignBriefing.headline(1).length(), CampaignBriefing.headline(8).length());
    }

    @Test
    public void militaryActionNameComesFromTheZoneTable() {
        for (int m = 1; m <= CampaignBriefing.MISSIONS; m++) {
            String head = CampaignBriefing.headline(m);
            assertEquals("首行末段必须逐字等于 " + WaveDirector.zoneName(m - 1) + "，不许有第二份星区名",
                    WaveDirector.zoneName(m - 1), head.substring(head.indexOf(": ") + 2));
        }
    }

    @Test
    public void theLineSequenceCarriesBothBlankLines() {
        String[] lines = CampaignBriefing.lines(1);
        assertEquals("首行1 + 空 + 背景3 + 空 + 目标2 = 8 行", 8, lines.length);
        assertEquals("", lines[1]);
        assertEquals("", lines[5]);
        assertEquals("任务目标一: 完成新手引导", lines[6]);
        assertEquals("任务目标二: 击杀一个敌人", lines[7]);
        assertEquals("教学关，玩家需要在引导下击退敌人", lines[2]);
    }

    @Test
    public void everyMissionHasThreeBackgroundLinesAndTwoGoals() {
        for (int m = 1; m <= CampaignBriefing.MISSIONS; m++) {
            assertEquals("任务 " + m + " 的背景行数（上限 3 是写作纪律，这里数的是本稿）",
                    3, CampaignBriefing.background(m).length);
            assertEquals("任务 " + m + " 的目标条数（上限 3）", 2, CampaignBriefing.goals(m).length);
            assertEquals("任务 " + m + " 的上屏行数 = 3 + 背景 + 目标", 8, CampaignBriefing.lines(m).length);
        }
    }

    @Test
    public void outOfRangeMissionNumbersClampInsteadOfThrowing() {
        assertEquals(CampaignBriefing.headline(1), CampaignBriefing.headline(0));
        assertEquals(CampaignBriefing.headline(8), CampaignBriefing.headline(99));
        assertArrayEquals(CampaignBriefing.lines(8), CampaignBriefing.lines(99));
    }
}
