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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 九个状态对象 + 随机源的快照往返（规格 §八 的状态面）。
 *
 * <p>形状与 {@link PoolSnapshotTest} 同一条：源灌成非默认、目标保持全新、一比就知道哪一格没进快照。
 * 差别是这里的 {@code restoreSnapshot} 各自带<b>范围校验</b>（它们是对的：非法值会让下一帧读到数组外），
 * 所以纯 poison 灌出来的数会被自己的校验拒掉。每条用例因此都有一段"灌回合法值"的收尾，
 * 每一格都注明是哪道校验逼出来的——那不是探针偷懒，那是校验真实存在。
 *
 * <p>每条用例末尾都断言<b>这一段一口不剩</b>：段的写长与读长不吻合（少读一格、多读一格、
 * 段序错位）都会在这里变成失败，而不是"读出来一套看着像样但整体错位半格的状态"。
 */
public class StateSnapshotTest {

    private static final int BAND_TOP = PoolSnapshotTest.BAND_TOP;
    private static final int BAND_H = PoolSnapshotTest.BAND_H;

    private static Buf.Reader segmentOf(Object src, java.util.function.Consumer<Buf> write) {
        Buf w = new Buf(4096);
        write.accept(w);
        return new Buf.Reader(w.toByteArray());
    }

    // ---- 玩家 -------------------------------------------------------------------------------

    /**
     * 玩家这一段是 HUD 那条血条与盾条的真源。{@code maxHp}/{@code maxShield} 单独存是因为
     * 商店的装甲卡把这台上限抬过开局值——现算出来的是"这局没买装甲"的那一台，
     * 恢复后第一发子弹的过盾结算就对不上画面（{@link PlayerState} 那条注释）。
     *
     * <p>需要灌合法值的只有两处：{@code weaponId} 必须是表内的枪（读端验范围），
     * {@code alive} 与 {@code hp} 有一道耦合校验（"死了却还有血"整段拒）。
     * 交错那一遍里 {@code alive} 恰好落在真的一格，两遍都真，所以只有 weaponId 需要动手。
     */
    @Test
    public void 玩家段把上限与体系记忆一起带走() {
        SnapProbe.twice(boolsAllTrue -> {
            PlayerState src = new PlayerState();
            SnapProbe.poisonBy(src, boolsAllTrue, "scratch");
            SnapProbe.set(src, "weaponId", Balance.Weapon.MISSILE);
            SnapProbe.assertPoisoned(src, "scratch");

            Buf.Reader r = segmentOf(src, w -> src.writeSnapshot(w, BAND_TOP, BAND_H));
            PlayerState dst = new PlayerState();
            dst.restoreSnapshot(r, BAND_TOP, BAND_H);
            assertEquals("玩家段必须一口不剩", 0, r.remaining());

            SnapProbe.assertFieldsEqual(src, dst, "scratch");
        });
    }

    /**
     * {@code lastGunOfSchool} 里混进一个表外的 id：下一次 {@code cooldownFor} 就会读数组外
     * （越界抛在渲染中途）。读端验它，这条用例钉住那道验。
     */
    @Test
    public void 体系记忆里的表外枪id被拒() {
        PlayerState src = new PlayerState();
        src.lastGunOfSchool[0] = Balance.weapons.length;
        Buf.Reader r = segmentOf(src, w -> src.writeSnapshot(w, BAND_TOP, BAND_H));
        Buf.Invalid bad = assertThrows(Buf.Invalid.class, () -> new PlayerState().restoreSnapshot(r, BAND_TOP, BAND_H));
        assertTrue(bad.getMessage(), bad.getMessage().contains("school memory"));
    }

    // ---- 状态层 / 连击 / 过载 / 定格 -----------------------------------------------------------

    /**
     * {@code envKind} 是每帧从波号现算的派生量（{@link StatusLayers} 那条"纯函数实时算，不存变量"），
     * 所以它<b>不进快照</b>——列进 skip 是一次表态，探针会要求这个字段真的存在。
     */
    @Test
    public void 状态层只带拾取层那一格环境不存() {
        SnapProbe.twice(boolsAllTrue -> {
            StatusLayers src = new StatusLayers();
            SnapProbe.poisonBy(src, boolsAllTrue, "everUsed");
            SnapProbe.assertPoisoned(src, "everUsed");

            Buf.Reader r = segmentOf(src, src::writeSnapshot);
            StatusLayers dst = new StatusLayers();
            dst.restoreSnapshot(r);
            assertEquals("状态层段必须一口不剩", 0, r.remaining());

            SnapProbe.assertFieldsEqual(src, dst, "everUsed", "envKind");
        });
    }

    /**
     * {@code lastHitAt} 走原始位：一座没开过连击的Meter 初值是 {@link Float#NEGATIVE_INFINITY}，
     * 文本化编码读不回它。这条用例把负无穷也带一遍，顺带证明 {@link Buf} 的承诺不是空话。
     */
    @Test
    public void 连击段带窗口戳且与world同进同出() {
        SnapProbe.twice(boolsAllTrue -> {
            ComboMeter src = new ComboMeter();
            SnapProbe.poisonBy(src, boolsAllTrue, "cfg");
            SnapProbe.assertPoisoned(src, "cfg");

            Buf.Reader r = segmentOf(src, src::writeSnapshot);
            ComboMeter dst = new ComboMeter();
            dst.restoreSnapshot(r);
            assertEquals("连击段必须一口不剩", 0, r.remaining());
            SnapProbe.assertFieldsEqual(src, dst, "cfg");
        });

        ComboMeter fresh = new ComboMeter();
        assertEquals("用例前提：没开过连击的初值是负无穷",
                Float.NEGATIVE_INFINITY, SnapProbe.getFloat(fresh, "lastHitAt"), 0f);
        Buf.Reader r = segmentOf(fresh, fresh::writeSnapshot);
        ComboMeter dst = new ComboMeter();
        dst.restoreSnapshot(r);
        assertEquals("负无穷必须逐位回来",
                Float.floatToRawIntBits(Float.NEGATIVE_INFINITY),
                Float.floatToRawIntBits(SnapProbe.getFloat(dst, "lastHitAt")));
    }

    @Test
    public void 过载段只带那一格余量() {
        SnapProbe.twice(boolsAllTrue -> {
            OverloadMeter src = new OverloadMeter();
            SnapProbe.poisonBy(src, boolsAllTrue);
            SnapProbe.assertPoisoned(src);

            Buf.Reader r = segmentOf(src, src::writeSnapshot);
            OverloadMeter dst = new OverloadMeter();
            dst.restoreSnapshot(r);
            assertEquals("过载段必须一口不剩", 0, r.remaining());
            SnapProbe.assertFieldsEqual(src, dst);
        });
    }

    /** 定格的余量上界是 {@code hitStopMax × 2}（0.1 秒），纯 poison 的 0.25 会被自己的校验拒掉。 */
    @Test
    public void 定格段带剩余秒数() {
        SnapProbe.twice(boolsAllTrue -> {
            HitStop src = new HitStop();
            SnapProbe.poisonBy(src, boolsAllTrue);
            SnapProbe.set(src, "remaining", 0.025f);
            SnapProbe.assertPoisoned(src);

            Buf.Reader r = segmentOf(src, src::writeSnapshot);
            HitStop dst = new HitStop();
            dst.restoreSnapshot(r);
            assertEquals("定格段必须一口不剩", 0, r.remaining());
            SnapProbe.assertFieldsEqual(src, dst);
        });
    }

    // ---- 本局计数 -----------------------------------------------------------------------------

    /**
     * 读端有三条"子集不超过全集"（命中 ≤ 发出、暴杀 ≤ 击杀），纯 poison 的下标序正好违反它们，
     * 所以这四格灌成一组自洽的数。其余六格照灌——它们只要求非负。
     */
    @Test
    public void 计数段让结算页拿到整局的输入() {
        SnapProbe.twice(boolsAllTrue -> {
            RunStats src = new RunStats();
            SnapProbe.poisonBy(src, boolsAllTrue);
            SnapProbe.set(src, "shotsFired", 1_000_010L);
            SnapProbe.set(src, "shotsHit", 1_000_005L);
            SnapProbe.set(src, "kills", 1_000_004L);
            SnapProbe.set(src, "critKills", 1_000_002L);
            SnapProbe.assertPoisoned(src);

            Buf.Reader r = segmentOf(src, src::writeSnapshot);
            RunStats dst = new RunStats();
            dst.restoreSnapshot(r);
            assertEquals("计数段必须一口不剩", 0, r.remaining());
            SnapProbe.assertFieldsEqual(src, dst);
        });
    }

    // ---- 局内商店 -----------------------------------------------------------------------------

    /**
     * 二十格等级是这一局 build 的全部真源，所以这一段是"整张表都要回来"：
     * 探针按元素比到最后一格。{@code level} 的数组引用也不能换——
     * {@code ShopRules.Snapshot} 挂的就是那一格（类注释那条"还原不换引用"）。
     */
    @Test
    public void 商店等级整张表回来且不换数组引用() {
        SnapProbe.twice(boolsAllTrue -> {
            ShopRun src = new ShopRun();
            SnapProbe.poisonBy(src, boolsAllTrue);

            Buf.Reader r = segmentOf(src, src::writeSnapshot);
            ShopRun dst = new ShopRun();
            int[] own = dst.levels();          // 挂在外层引用上的就是这一格，还原不许换掉它
            dst.restoreSnapshot(r);
            assertEquals("商店段必须一口不剩", 0, r.remaining());
            SnapProbe.assertFieldsEqual(src, dst);
            assertSame("还原必须写回同一格数组，不能换新的一格", own, SnapProbe.ints(dst, "level"));
        });
    }

    // ---- 波次导演流 -----------------------------------------------------------------------------

    /**
     * 队列必须连<b>游标</b>一起存：{@code nextGap} 的批次判据读的是绝对位置（类注释那条）。
     *
     * <p>{@code kinds} 在探针这里列进 skip 不是因为不存，而是因为<b>数组尾巴不该比</b>——
     * {@code ensure(count)} 只保证前 {@code count} 格，源池的容量可能比目标大。
     * 尾巴由 {@link WaveFlow#spawnKindAt} 那条越界返回 {@code NONE} 的判据替代，比的是"读得出的那几格"。
     *
     * <p>{@code startWave} 列进 skip 才是"不存"的那一格，这是 {@link WaveFlow#beginAtChapter} 那条理由的
     * 表态（宽限只在"进新一波的 PREP"那一刻读，读档恢复的是<b>已经拍下来的 {@code phaseLeft}</b>，
     * 那一刻过去了）。它由 {@code WaveFlowTest#chapterStartGracesItsOwnFirstWave} 那条量出来，
     * 不在这里比字节。
     */
    @Test
    public void 波次段带队列与游标绝对位置() {
        SnapProbe.twice(boolsAllTrue -> {
            WaveFlow src = new WaveFlow(new WaveDirector(), new Rng(1L));
            src.begin();
            for (int i = 0; i < 2_000 && src.phase() != WaveFlow.SPAWN; i++) src.pollSpawn(1f / 30f, 0);
            assertEquals("用例前提：队列已经建起来了", WaveFlow.SPAWN, src.phase());

            SnapProbe.poisonBy(src, boolsAllTrue,
                    "kinds", "weights", "director", "rng", "mode", "startWave");
            // 相位上界是 VICTORY=4、游标上界是队列长度——两处校验逼着把这两个数灌回合法区间。
            SnapProbe.set(src, "phase", WaveFlow.SPAWN);
            SnapProbe.set(src, "cursor", 1);
            int[] q = SnapProbe.ints(src, "kinds");
            int planned = SnapProbe.getInt(src, "kindCount");
            for (int i = 0; i < planned && i < q.length; i++) {
                // 队列只认表内的 kind 或 BOSS：灌个互不相同又都在表内的数（0 与默认值同形，避开）。
                q[i] = i == 0 ? WaveFlow.BOSS : 1 + (i % Balance.Enemy.BURSTER);
            }
            SnapProbe.assertPoisoned(src, "kinds", "weights", "director", "rng", "mode", "startWave");

            Buf.Reader r = segmentOf(src, src::writeSnapshot);
            WaveFlow dst = new WaveFlow(new WaveDirector(), new Rng(1L));
            dst.restoreSnapshot(r);
            assertEquals("波次段必须一口不剩", 0, r.remaining());

            SnapProbe.assertFieldsEqual(src, dst,
                    "kinds", "weights", "director", "rng", "mode", "startWave");
            int n = SnapProbe.getInt(src, "kindCount");
            for (int i = 0; i < n; i++) {
                assertEquals("队列第 " + i + " 格（决定接下来刷什么）",
                        src.spawnKindAt(i), dst.spawnKindAt(i));
            }
            assertEquals("队列读完后的一致游标之下，下一只该刷的还是同一只",
                    src.pendingSpawns(), dst.pendingSpawns());
        });
    }

    /**
     * 队列里出现"既不是敌人也不是 Boss"的 kind：{@code Game.spawnQueued} 拿它会去读
     * {@code Balance.enemies[kind]} 那一族数，于是越界抛在渲染中途。这条闸必须留在读端。
     */
    @Test
    public void 队列里的非法kind被整段拒() {
        WaveFlow src = new WaveFlow(new WaveDirector(), new Rng(1L));
        src.begin();
        for (int i = 0; i < 2_000 && src.phase() != WaveFlow.SPAWN; i++) src.pollSpawn(1f / 30f, 0);
        SnapProbe.ints(src, "kinds")[0] = 999;
        Buf.Reader r = segmentOf(src, src::writeSnapshot);
        Buf.Invalid bad = assertThrows(Buf.Invalid.class,
                () -> new WaveFlow(new WaveDirector(), new Rng(1L)).restoreSnapshot(r));
        assertTrue(bad.getMessage(), bad.getMessage().contains("queued kind"));
    }

    // ---- 折线图 -------------------------------------------------------------------------------

    /**
     * 环形缓冲按<b>物理槽位</b>整拍，不按"自旧到新"（{@link TrendSampler} 那条：读端 {@code slot()}
     * 的换算是同一道算式的第二份实现，存顺序就得在还原端反向重排）。
     * 所以这里连 {@code head} 与 {@code count} 一起比，物理槽位比到第 24 格。
     */
    @Test
    public void 折线三段按物理槽位整拍() {
        SnapProbe.twice(boolsAllTrue -> {
            TrendSampler src = new TrendSampler();
            SnapProbe.poisonBy(src, boolsAllTrue);
            SnapProbe.assertPoisoned(src);

            Buf.Reader r = segmentOf(src, src::writeSnapshot);
            TrendSampler dst = new TrendSampler();
            dst.restoreSnapshot(r);
            assertEquals("折线段必须一口不剩", 0, r.remaining());
            SnapProbe.assertFieldsEqual(src, dst);
            // 面板读的是换算之后的读数，那条换算也得给同一串数。
            for (int tab = 0; tab < TrendSampler.TABS; tab++) {
                assertEquals("页签 " + tab + " 的点数", src.pointCount(tab), dst.pointCount(tab));
                for (int i = 0; i < src.pointCount(tab); i++) {
                    assertEquals("页签 " + tab + " 第 " + i + " 点",
                            src.valueAt(tab, i), dst.valueAt(tab, i), 0f);
                }
            }
        });
    }

    // ---- 随机源 -------------------------------------------------------------------------------

    @Test
    public void 随机源那一枚long回来() {
        Rng src = new Rng(0x1234L);
        Buf.Reader r = segmentOf(src, src::writeSnapshot);
        Rng dst = new Rng(0L);
        dst.restoreSnapshot(r);
        assertEquals("随机源段必须一口不剩", 0, r.remaining());
        SnapProbe.assertFieldsEqual(src, dst);
    }

    /**
     * <b>这一条是池件 §6 第 4 项登记为「未实测」的那格</b>：整局可复现性的说法一直是推断
     * （"货架不存，开架时现抽"整条理由都站在它上面）。钉法是按同一枚状态各走一串读数——
     * 恢复出来的那一枚必须与源那一枚<b>逐个相同</b>，不是"看着差不多"。
     */
    @Test
    public void 恢复后的随机源与源那一枚给出同一串数() {
        Rng src = new Rng(0x9E3779B9L);
        for (int i = 0; i < 50; i++) src.next01();          // 把状态推到一段游戏的中间，而不是刚播种
        Buf.Reader r = segmentOf(src, src::writeSnapshot);
        Rng dst = new Rng(0L);
        dst.restoreSnapshot(r);
        for (int i = 0; i < 500; i++) {
            assertEquals("第 " + i + " 个读数必须一致", src.next01(), dst.next01(), 0f);
        }
        // 概率判定那条通路也走一遍：p 钳 0/1 的两侧与中间段都得给同一条结果。
        Rng tail = new Rng(0L);
        tail.restoreSnapshot(segmentOf(src, src::writeSnapshot));
        for (float p : new float[]{-1f, 0f, 0.25f, 0.5f, 0.999f, 1f, 2f}) {
            assertEquals("chance(" + p + ")", src.chance(p), tail.chance(p));
        }
    }
}
