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

import com.flexiatom.pixelraider.plat.MemStore;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 槽位账本：{@link SaveSlots} 的掩码纪律与那枚 48 位打包。
 *
 * <p>两条形状各占一半。<b>掩码</b>那一半钉的是"唯一真源"这句话——脏高位、没占用却残留的 meta 键、
 * 满了不回绕，这三格都挂在"账本与盘可以不一致"那条代价上。<b>打包</b>那一半钉的是位段互不渗漏：
 * 列表页读串一格不是显示错一位数，是把"第 3 章"读成"第 3 章外加几分"。
 *
 * <p>介质侧（谁在哪个线程落盘）在 {@code FileAndAsyncSaveSinkTest} 那边，这里一律走 {@link MemStore}：
 * 账本只认 {@code KeyValue} 那三个方法，别的都不该知道。
 */
public class SaveSlotsTest {

    // ---- 掩码：占用、腾位、拒绝 --------------------------------------------------------------

    @Test
    public void aFreshLedgerHasNoSavesAndOffersSlotZero() {
        SaveSlots s = new SaveSlots();
        assertEquals(0, s.count());
        assertEquals(0L, s.mask());
        assertFalse(s.isOccupied(0));
        assertEquals(0, s.allocateFree());
    }

    @Test
    public void occupyMarksTheSlotAndKeepsBothScalars() {
        SaveSlots s = new SaveSlots();
        long packed = SaveSlots.packMeta(Mode.CAMPAIGN, 3, 17, 4200);
        s.occupy(4, packed, 1_700_000_000_000L);
        assertTrue(s.isOccupied(4));
        assertEquals(1, s.count());
        assertEquals(packed, s.meta(4));
        assertEquals(1_700_000_000_000L, s.stamp(4));
    }

    @Test
    public void overwritingASlotDoesNotTakeASecondOne() {
        // 列表页那行"3 / 50"要是被覆盖加一，玩家删一份就永远删不干净。
        SaveSlots s = new SaveSlots();
        s.occupy(2, SaveSlots.packMeta(Mode.CAMPAIGN, 1, 2, 100), 1L);
        s.occupy(2, SaveSlots.packMeta(Mode.CAMPAIGN, 1, 5, 900), 2L);
        assertEquals(1, s.count());
        assertEquals(5, SaveSlots.waveOf(s.meta(2)));
        assertEquals(2L, s.stamp(2));
    }

    @Test
    public void releaseClearsTheScalarsAndMakesTheSlotAllocatableAgain() {
        SaveSlots s = new SaveSlots();
        s.occupy(0, 1L, 1L);
        s.occupy(7, SaveSlots.packMeta(Mode.CAMPAIGN, 2, 9, 50), 1L);
        s.release(7);
        assertFalse(s.isOccupied(7));
        assertEquals(1, s.count());
        assertEquals(0L, s.meta(7));
        assertEquals(0L, s.stamp(7));

        // 腾位真的腾出来了：清掉最后一格后，那一号槽可以被再次占用且计数回到 2。
        s.release(0);
        assertEquals(0, s.allocateFree());
        s.occupy(7, 2L, 2L);
        s.occupy(0, 3L, 3L);
        assertEquals(2, s.count());
    }

    @Test
    public void allocationSkipsOccupiedAndGivesTheLowestFree() {
        SaveSlots s = new SaveSlots();
        s.occupy(0, 1L, 1L);
        s.occupy(1, 1L, 1L);
        s.occupy(3, 1L, 1L);
        assertEquals(2, s.allocateFree());
    }

    @Test
    public void aFullLedgerRefusesInsteadOfRecyclingTheOldest() {
        // [裁决] 他 2026-10-10 逐字「上限50槽，允许覆盖和删除存档」——腾位是玩家显式动作。
        // 自动淘汰最旧那份会把"打歪之前的原始点"无声吃掉，所以这里给 NONE 而不是回绕。
        SaveSlots s = new SaveSlots();
        for (int slot = 0; slot < SaveSlots.CAPACITY; slot++) s.occupy(slot, 1L, slot);
        assertEquals(SaveSlots.CAPACITY, s.count());
        assertEquals(SaveSlots.NONE, s.allocateFree());
    }

    @Test
    public void capacityStillFitsInsideTheLongThatStoresTheMask() {
        // 那枚 long 的运气账：50 ≤ 64。上限涨到 65 就要换两枚或 long[]，这条会先红。
        assertTrue(SaveSlots.CAPACITY < 64);
        assertEquals(SaveSlots.CAPACITY, Long.bitCount((1L << SaveSlots.CAPACITY) - 1L));
    }

    // ---- 与 KeyValue 的往返 ------------------------------------------------------------------

    @Test
    public void commitThenReadBackThroughTheSameStore() {
        MemStore store = new MemStore();
        SaveSlots a = new SaveSlots();
        long packed = SaveSlots.packMeta(Mode.campaign(Tier.TERMINAL), 8, 40, 123456);
        a.occupy(11, packed, 4_242L);
        a.occupy(0, SaveSlots.packMeta(Mode.ENDLESS, 0, 61, 77), 999L);
        a.commit(store);

        SaveSlots b = new SaveSlots();
        b.readFrom(store);
        assertEquals(2, b.count());
        assertTrue(b.isOccupied(11));
        assertTrue(b.isOccupied(0));
        assertFalse(b.isOccupied(1));
        assertEquals(packed, b.meta(11));
        assertEquals(4_242L, b.stamp(11));
        assertEquals(999L, b.stamp(0));
        assertEquals(61, SaveSlots.waveOf(b.meta(0)));
    }

    @Test
    public void commitWritesOneMaskPlusTwoKeysPerOccupiedSlot() {
        // 键数＝1 + 2×占用。空格不写，所以删干净之后盘上不会留一串值为 0 的键。
        MemStore store = new MemStore();
        SaveSlots s = new SaveSlots();
        s.occupy(3, 1L, 1L);
        s.occupy(9, 2L, 2L);
        s.commit(store);
        assertEquals(1 + 2 * s.count(), store.writes);
        assertEquals(5, store.map.size());
    }

    @Test
    public void commitFlushesSoTheLedgerOutlivesTheProcess() {
        // 2026-10-12 他报的缺陷：按下「存档」之后进程结束，档就没了。根因是 commit 只 putLong 不 flush，
        // 这批键一直停在 AsyncKeyValue 的待办清单里（那一层"flush ⇒ 交给 IO 线程落地"已由
        // AsyncKeyValueTest 钉住），所以这里要钉的是**这一头确实发起了那次落盘**。
        MemStore store = new MemStore();
        SaveSlots s = new SaveSlots();
        s.occupy(7, 1L, 1L);
        s.commit(store);
        assertEquals("commit 之名含落盘：不 flush 就是内存账", 1, store.flushes);
    }

    @Test
    public void aFullClearLandsOnDiskAsAnEmptyLedger() {
        MemStore store = new MemStore();
        SaveSlots s = new SaveSlots();
        s.occupy(0, 1L, 1L);
        s.occupy(49, 2L, 2L);
        s.clearAll();
        s.commit(store);

        SaveSlots b = new SaveSlots();
        b.readFrom(store);
        assertEquals(0, b.count());
        assertFalse(b.isOccupied(0));
        assertEquals(0L, b.meta(0));
    }

    @Test
    public void dirtyHighBitsInTheMaskReadBackAsNothingAtAll() {
        // 掩码只用低 50 位。脏高位（手改过盘，或将来改了 CAPACITY 而旧键还在）一律当没发生过——
        // 否则账本会为一枚不存在的槽去问介质要一份永不存在的档。
        MemStore store = new MemStore();
        store.map.put("save_mask", -1L);
        SaveSlots s = new SaveSlots();
        s.readFrom(store);
        assertEquals(SaveSlots.CAPACITY, s.count());
        assertEquals((1L << SaveSlots.CAPACITY) - 1L, s.mask());
        assertEquals(SaveSlots.NONE, s.allocateFree());
    }

    @Test
    public void metaDataLeftUnderAnUnoccupiedSlotIsIgnored() {
        // 清 meta 与清掩码位是同一格动作；但就算旧键因为别的原因还留在盘上，
        // 没占用的槽也不许从 meta 里复活出一行列表。
        MemStore store = new MemStore();
        store.map.put("save_meta_3", 1234L);
        store.map.put("save_time_3", 5678L);
        SaveSlots s = new SaveSlots();
        s.readFrom(store);
        assertFalse(s.isOccupied(3));
        assertEquals(0L, s.meta(3));
        assertEquals(0L, s.stamp(3));
    }

    // ---- 打包：位段 --------------------------------------------------------------------------

    @Test
    public void packedFieldsDoNotBleedIntoEachOther() {
        long packed = SaveSlots.packMeta(Mode.campaign(Tier.TERMINAL), 7, 300, 987_654_321);
        assertEquals(1, SaveSlots.modeOf(packed));
        assertEquals(2, SaveSlots.tierOf(packed));
        assertEquals(7, SaveSlots.chapterOf(packed));
        assertEquals(300, SaveSlots.waveOf(packed));
        assertEquals(987_654_321, SaveSlots.scoreOf(packed));

        // 逐格单独抬起来、其余保持零：一次填满再全读只能发现"整体错位"，发现不了相邻段互相吃。
        for (int chapter = 1; chapter <= 15; chapter++) {
            long p = SaveSlots.packMeta(Mode.ENDLESS, chapter, 0, 0);
            assertEquals(chapter, SaveSlots.chapterOf(p));
            assertEquals(0, SaveSlots.waveOf(p));
            assertEquals(0, SaveSlots.scoreOf(p));
        }
        for (int wave = 1; wave <= 511; wave += 37) {
            long p = SaveSlots.packMeta(Mode.ENDLESS, 0, wave, 0);
            assertEquals(wave, SaveSlots.waveOf(p));
            assertEquals(0, SaveSlots.chapterOf(p));
            assertEquals(0, SaveSlots.scoreOf(p));
        }
        for (int score = 1; score > 0; score += 1_000_003) {
            long p = SaveSlots.packMeta(Mode.ENDLESS, 0, 0, score);
            assertEquals(score, SaveSlots.scoreOf(p));
            assertEquals(0, SaveSlots.chapterOf(p));
            assertEquals(0, SaveSlots.modeOf(p));
        }
    }

    @Test
    public void theScoreSegmentKeepsTheReservedHighBitsClean() {
        // SHIFT_SCORE 之上还剩 16 位，是留给今后加字段的。不掩 score 的话一笔负分的符号扩展
        // 就把这段占了——而"负分"今天不会出现，所以这一格只能靠直接喂进去来钉。
        long negative = SaveSlots.packMeta(Mode.CAMPAIGN, 0, 0, -1);
        assertEquals(0L, negative >>> SaveSlots.BITS_USED);
        assertEquals(-1, SaveSlots.scoreOf(negative));
        assertEquals(1, SaveSlots.modeOf(negative));

        long max = SaveSlots.packMeta(Mode.CAMPAIGN, 0, 0, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, SaveSlots.scoreOf(max));
        assertEquals(0L, max >>> SaveSlots.BITS_USED);
    }

    @Test
    public void outOfRangeChapterAndWaveAreClampedNotWrapped() {
        // 截断（直接 & 掩码）会把"第 20 章"读成"第 4 章"：一个不存在的章号变成另一个存在的章号，
        // 列表页照它画，玩家看不出这里被换过。夹住最多是让那行显示到上限，不会替另一档说话。
        long over = SaveSlots.packMeta(Mode.CAMPAIGN, 20, 600, 0);
        assertEquals(15, SaveSlots.chapterOf(over));
        assertEquals(511, SaveSlots.waveOf(over));

        long under = SaveSlots.packMeta(Mode.CAMPAIGN, -3, -1, 0);
        assertEquals(0, SaveSlots.chapterOf(under));
        assertEquals(0, SaveSlots.waveOf(under));
    }

    @Test
    public void modeAndTierUseTheirDeclaredOrdinals() {
        assertEquals(1, SaveSlots.modeOf(SaveSlots.packMeta(Mode.CAMPAIGN, 0, 0, 0)));
        assertEquals(0, SaveSlots.modeOf(SaveSlots.packMeta(Mode.ENDLESS, 0, 0, 0)));

        // 与 Tier 的声明序一致：休闲 0／普通 1／终结 2。2 位装得下三档并余一格。
        assertEquals(0, SaveSlots.tierOf(SaveSlots.packMeta(Mode.campaign(Tier.CASUAL), 0, 0, 0)));
        assertEquals(1, SaveSlots.tierOf(SaveSlots.packMeta(Mode.campaign(Tier.NORMAL), 0, 0, 0)));
        assertEquals(2, SaveSlots.tierOf(SaveSlots.packMeta(Mode.campaign(Tier.TERMINAL), 0, 0, 0)));
        assertTrue(SaveSlots.BITS_TIER >= 2);
    }

    // ---- 越界 ---------------------------------------------------------------------------------

    @Test
    public void occupyingOutsideTheTableIsAProgrammingError() {
        SaveSlots s = new SaveSlots();
        for (int bad : new int[]{-1, SaveSlots.CAPACITY, SaveSlots.CAPACITY + 1}) {
            try {
                s.occupy(bad, 1L, 1L);
                fail("slot=" + bad + " 该抛，不该静默写坏掩码");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("slot"));
            }
        }
        assertEquals(0, s.count());
    }

    @Test
    public void readingAndReleasingOutsideTheTableSaysNothingHappened() {
        // 与 occupy 相反：读与删是"没有就没有"，越界不抛——它们会先在列表页被画成空格。
        SaveSlots s = new SaveSlots();
        s.occupy(0, 1L, 1L);
        s.release(-1);
        s.release(SaveSlots.CAPACITY);
        assertEquals(1, s.count());
        assertEquals(0L, s.meta(-1));
        assertEquals(0L, s.stamp(SaveSlots.CAPACITY));
        assertFalse(s.isOccupied(-1));
        assertFalse(s.isOccupied(SaveSlots.CAPACITY));
    }
}
