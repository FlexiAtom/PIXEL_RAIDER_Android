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
package com.flexiatom.pixelraider.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.flexiatom.pixelraider.core.RectI;
import com.flexiatom.pixelraider.game.Mode;
import com.flexiatom.pixelraider.game.SaveSlots;
import com.flexiatom.pixelraider.game.Tier;
import java.util.Calendar;
import org.junit.Test;

/**
 * 存档页的静态面——照 {@link PauseScreenTest}／{@link ShopScreenTest} 的规矩，一条都不碰
 * {@code new SaveScreen(...)}：构造会拉起 {@link DrawKit} 的 Paint，而单测跑在未 mock 的
 * android 桩上（{@code returnDefaultValues = false}），类一加载就炸。绘制与命中那两半块
 * 的验证只能走真机。
 *
 * <p>这一屏的静态面里最值得钉的是**两组字符串与两个位段的对应**（模式名 ↔ {@code modeOf}、
 * 档位名 ↔ {@code tierOf}）：错一格的画面是"档位列写着终结、这一局实际按休闲跑"，而列表页是
 * 玩家决定读不读这一份的唯一依据——他看见的那两个字就是他要的那一局。
 */
public class SaveScreenTest {

    /**
     * 动作码互不相同，且 {@code NONE} 是 0：{@code Game.onSavePointerUp} 用一串 if 比较它们，
     * 两个码撞在一起就等于其中一个键永远走不到——而"删除"与"存档"撞码的后果是少一份档。
     */
    @Test
    public void actionCodesDoNotCollide() {
        int[] codes = {SaveScreen.ACTION_NONE, SaveScreen.ACTION_BACK, SaveScreen.ACTION_LOAD,
                SaveScreen.ACTION_SAVE, SaveScreen.ACTION_DELETE};
        assertEquals(0, SaveScreen.ACTION_NONE);
        for (int i = 0; i < codes.length; i++) {
            for (int j = i + 1; j < codes.length; j++) {
                assertNotEquals("第 " + i + " 与第 " + j + " 枚动作码相同", codes[i], codes[j]);
            }
        }
    }

    /**
     * 提示语那三种态同样互不相同：{@code Game} 把 {@code SaveSlots.NONE}（满槽）与"读回来的字节
     * 是空的"分别映射到 {@code STATUS_FULL}／{@code STATUS_LOAD_FAILED}，两者挤成一条的代价是
     * 玩家按"先删一份"那句去删档，而那一句对读档失败根本是错的处方。
     */
    @Test
    public void statusCodesDoNotCollide() {
        assertEquals(0, SaveScreen.STATUS_NONE);
        assertNotEquals(SaveScreen.STATUS_NONE, SaveScreen.STATUS_FULL);
        assertNotEquals(SaveScreen.STATUS_NONE, SaveScreen.STATUS_LOAD_FAILED);
        assertNotEquals(SaveScreen.STATUS_FULL, SaveScreen.STATUS_LOAD_FAILED);
    }

    /**
     * 模式名的下标就是位段的值，而位段的值就是 {@code Mode.campaign} 那一枚布尔。
     *
     * <p>三处手工对齐（位段写法在 {@code SaveSlots.packMeta}、字串表在这里、真源是 {@code Mode} 的
     * 字段）。这条红的形态是"无尽局的每一行都标成战役"——画面上只有一个字不同，截图基本挑不出来。
     */
    @Test
    public void modeLabelsAreIndexedByThePackedBit() {
        assertEquals("两位模式段的容量就是表长", 1 << SaveSlots.BITS_MODE, SaveScreen.L_MODE.length);
        Mode[] both = {Mode.ENDLESS, Mode.CAMPAIGN};
        for (Mode m : both) {
            int bit = SaveSlots.modeOf(SaveSlots.packMeta(m, 1, 1, 0));
            assertEquals("下标必须等于布尔：" + m.campaign, m.campaign ? 1 : 0, bit);
            assertEquals("campaign=" + m.campaign + " 的模式名排错了格",
                    m.campaign ? "战役" : "无尽", SaveScreen.L_MODE[bit]);
        }
    }

    /**
     * 档位名的下标就是 {@code Tier} 的声明序，且每一档都能被 {@code at(ordinalOf(…))} 取回自己。
     * {@code SaveSlots.tierOrdinal} 那枚私有 helper 已在批2-b 删掉（声明序只允许一处在数），
     * 所以这里同时钉住"表的长度"与"两向映射是同一份序"。
     */
    @Test
    public void tierLabelsAreIndexedByTheDeclaredOrder() {
        Tier[] declared = {Tier.CASUAL, Tier.NORMAL, Tier.TERMINAL};
        assertEquals("三档三位表，多一档少一档都在这里红", declared.length, SaveScreen.L_TIER.length);
        String[] expected = {"休闲", "普通", "终结"};
        for (int i = 0; i < declared.length; i++) {
            assertEquals("第 " + i + " 档的名字排错了格", expected[i], SaveScreen.L_TIER[i]);
            assertSame(Tier.at(i), declared[i]);
            assertEquals(Tier.ordinalOf(declared[i]), i);
            int packed = SaveSlots.tierOf(SaveSlots.packMeta(Mode.campaign(declared[i]), 1, 1, 0));
            assertEquals("档位段与声明序不一致：" + declared[i], i, packed);
            assertSame(SaveScreen.L_TIER[i], SaveScreen.L_TIER[packed]);
        }
    }

    /**
     * 每枚标签都不得越过自己那一列的定宽（见 {@link SaveLayout#COL_SLOT_W} 那条"定宽换的是行对齐"）。
     * 越过之后的画面不是"字挤一点"，而是**串列**：档位列的第三个字直接压在波列的标签上。
     */
    @Test
    public void noLabelOverflowsItsOwnColumn() {
        assertEquals(1, SaveScreen.L_SLOT.length());
        assertEquals(1, SaveScreen.L_WAVE.length());
        assertEquals(1, SaveScreen.L_SCORE.length());
        assertEquals(1, SaveScreen.L_EMPTY.length());
        for (String s : SaveScreen.L_MODE) {
            assertEquals("「" + s + "」越过模式列", SaveLayout.COL_MODE_W / Md3.PX_LABEL, s.length());
        }
        for (String s : SaveScreen.L_TIER) {
            assertEquals("「" + s + "」越过档位列", SaveLayout.COL_TIER_W / Md3.PX_LABEL, s.length());
        }
    }

    /**
     * 裁定甲的反馈：写入键按下之后**这枚键自己的标签**换成「已存档」，直到面板关闭。
     *
     * <p>钉三件事：① 那三个字逐字是裁定里的写法（"已保存""已写入"都会让这句反馈与池件对不上）；
     * ② 它以动作名结尾——同一枚键换标签，换的是**时态**不是**动作**，玩家认得出是刚才那一下；
     * ③ 它不比动作名短，所以排版上它是这一行的最长形态（下面的 {@link #buttonLabelsAreDistinctAndFitTheirCells} 收这个）。
     */
    @Test
    public void theSavedFeedbackIsTheRulingAndKeepsTheActionWord() {
        assertEquals("已存档", SaveScreen.L_SAVED);
        assertEquals("存档", SaveScreen.L_SAVE);
        assertTrue("反馈标签丢了动作名：「" + SaveScreen.L_SAVED + "」",
                SaveScreen.L_SAVED.endsWith(SaveScreen.L_SAVE));
        assertTrue(SaveScreen.L_SAVED.length() > SaveScreen.L_SAVE.length());
    }

    /**
     * 写入键的三条标签（他 2026-10-11 逐字「在覆盖的情况下“存档”的文字应变为“覆盖”（并且应为红色）」）。
     *
     * <p>钉的是**优先级**与**红色那一格的来源**，不是三条字符串本身（字符串逐字由上面那条与
     * {@link #buttonLabelsAreDistinctAndFitTheirCells} 管）。两格各自挡一种错法：
     * ① 选中一格有档、却又已经按过一次 ⇒ 画面留「已存档」——那一刻它是**回执**，不是预告，
     * 覆盖态被回执盖住；② 绘制端的红是**从标签读出来的**（{@code L_OVERWRITE.equals(标签)}），
     * 所以这里同时钉住"只有覆盖态那一个返回值会命中 {@code L_OVERWRITE}"——标签与颜色不可能一个说
     * 覆盖、一个不红。这条静态接缝存在的理由就是这一格：判据放在实例里 JVM 测不到（见池件 §10 第 7 条
     * 那条 M6 覆盖边界），放在静态面上就能钉。
     */
    @Test
    public void theSaveKeyLabelFollowsLatchThenOccupancy() {
        assertEquals("没选中或选中空格＝存进空槽", "存档", SaveScreen.saveLabel(false, false));
        assertEquals("选中格已有档＝这一按毁掉一份现存的", "覆盖", SaveScreen.saveLabel(false, true));
        assertEquals("已存档", SaveScreen.saveLabel(true, false));
        assertEquals("回执盖过预告", "已存档", SaveScreen.saveLabel(true, true));
        for (boolean latched : new boolean[]{false, true}) {
            for (boolean occupied : new boolean[]{false, true}) {
                String label = SaveScreen.saveLabel(latched, occupied);
                assertEquals("红色那一格只该由「覆盖」给出：latch=" + latched + " occupied=" + occupied,
                        "覆盖".equals(label), SaveScreen.L_OVERWRITE.equals(label));
            }
        }
    }

    /**
     * 两行六枚键的标签互不相同，且最长那枚仍放进最窄的一格。
     *
     * <p>不同名那条的理由照 {@code PauseScreenTest}：同名的两枚键在余光里读不出区别，而这一屏的
     * 「存档」与「已存档」是**同一枚键的两种时态**，所以它不进这一组两两比较（那对由上面那条钉）。
     * ⚠ 「覆盖」是同一枚键的**第三种形**（选中格已有档），但它与其余六枚字面都不同，所以放得进这组
     * 两两比较——放进来换到的是那条宽度保险：它是两字，与「存档」同长，红在这里比红在真机上便宜。
     */
    @Test
    public void buttonLabelsAreDistinctAndFitTheirCells() {
        String[] buttons = {SaveScreen.L_LOAD, SaveScreen.L_DELETE, SaveScreen.L_PREV,
                SaveScreen.L_NEXT, SaveScreen.L_BACK, SaveScreen.L_SAVED, SaveScreen.L_OVERWRITE};
        for (int i = 0; i < buttons.length; i++) {
            for (int j = i + 1; j < buttons.length; j++) {
                assertNotEquals("两枚键同名：" + buttons[i], buttons[i], buttons[j]);
            }
        }
        RectI cell = new RectI();
        SaveLayout.actionCell(0, cell);
        int narrowest = cell.width();
        SaveLayout.navCell(0, cell);
        narrowest = Math.min(narrowest, cell.width());
        for (String label : buttons) {
            assertTrue("「" + label + "」放不下最窄的一格：" + narrowest,
                    label.length() * Md3.PX_BODY <= narrowest);
        }
        assertEquals(69, narrowest);
    }

    /**
     * 时间戳的形状：十一个字符、{@code MM-dd HH:mm}、每段两位零填充。
     *
     * <p>期望值由**同一支 Calendar**现算（不写死字面量），所以这条与时区无关——这正是
     * {@code formatStamp} 收外部 sink 的理由：测试与被测读的是同一个默认时区，断言才只可能在
     * **形状**上失败。位段之外还钉长度：点阵列宽 11 格 = {@code digitWidth(11)}，画长一格就会顶到
     * 行的右内缩外面去。
     */
    @Test
    public void theStampIsElevenCellsOfZeroPaddedFields() {
        Calendar sink = Calendar.getInstance();
        sink.clear();
        sink.set(2026, Calendar.OCTOBER, 1, 9, 7, 0);
        HudText out = new HudText(32);
        SaveScreen.formatStamp(sink.getTimeInMillis(), out, sink);
        assertEquals(11, out.length());
        assertEquals("10-11 09:07".length(), out.length());
        assertEquals('-', out.buffer()[2]);
        assertEquals(' ', out.buffer()[5]);
        assertEquals(':', out.buffer()[8]);
        assertEquals(sink.get(Calendar.MONTH) + 1, field(out, 0, 2));
        assertEquals(sink.get(Calendar.DAY_OF_MONTH), field(out, 3, 2));
        assertEquals(sink.get(Calendar.HOUR_OF_DAY), field(out, 6, 2));
        assertEquals(sink.get(Calendar.MINUTE), field(out, 9, 2));
        assertEquals("10-01 09:07", out.snapshot());
    }

    /** 没档（stamp 为 0）与一个倒着走的时刻都画一格 ASCII「-」，不画出「00-00」那种假日期。 */
    @Test
    public void anAbsentStampIsADashNotAFakeDate() {
        Calendar sink = Calendar.getInstance();
        HudText out = new HudText(32);
        SaveScreen.formatStamp(0L, out, sink);
        assertEquals(1, out.length());
        assertEquals('-', out.buffer()[0]);
        SaveScreen.formatStamp(-86_400_000L, out, sink);
        assertEquals(1, out.length());
        assertEquals('-', out.buffer()[0]);
    }

    /**
     * 复用同一支 sink 连排两次：第二次的长度必须由 {@code reset()} 决定，不是"上一次剩下的加上新的"。
     * 这行每帧重排八次，所以格式化器是复用的；复用一旦漏掉 {@code reset()}，列表会画出一条越来越长
     * 的时间戳，把行右界顶穿。
     */
    @Test
    public void formattingTwiceIntoTheSameSinkDoesNotAccumulate() {
        Calendar sink = Calendar.getInstance();
        sink.clear();
        sink.set(2026, Calendar.DECEMBER, 31, 23, 59, 0);
        HudText out = new HudText(32);
        SaveScreen.formatStamp(sink.getTimeInMillis(), out, sink);
        String first = out.snapshot();
        SaveScreen.formatStamp(0L, out, sink);
        assertEquals(1, out.length());
        SaveScreen.formatStamp(sink.getTimeInMillis(), out, sink);
        assertEquals(first, out.snapshot());
        assertEquals(11, out.length());
    }

    private static int field(HudText out, int at, int width) {
        int v = 0;
        for (int i = 0; i < width; i++) {
            char c = out.buffer()[at + i];
            assertTrue("第 " + (at + i) + " 个字符不是数字：" + c, c >= '0' && c <= '9');
            v = v * 10 + (c - '0');
        }
        return v;
    }
}
