/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * PIXEL RAIDER — 原生 Android 纵版弹幕射击
 * Copyright (C) 2026 FlexiAtom
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.flexiatom.pixelraider.plat.FileSaveSink;
import java.util.Arrays;
import org.junit.Test;

/**
 * 快照的<b>格式面</b>：头部两格、越界、布尔第三值、段尾那一口不剩、跨机型坐标。
 * 每一段"字段有没有漏"归 {@link PoolSnapshotTest} 与 {@link StateSnapshotTest}，
 * 这里只管字节流的形状。
 *
 * <p>组合段一律用 <b>头部 → 折线图 → 随机源</b> 这三段代：{@code Game.readAll} 那条整份读
 * 在 JVM 上实例化不出来（{@code Game} 带 android 的 Paint/RectF/Path），而这三段串起来的失真形状
 * 和整份一样——定长头 + 变长体 + 一枚 long，够用。
 */
public class SnapshotFormatTest {

    private static final int BAND_TOP = 100;

    @Test
    public void 拿错文件时第一格就响不用读完() {
        // 'PRS0' —— 与正主只差末位一个字母，这种字节串只有魔数拦得住。
        Buf b = new Buf(16);
        b.writeInt(0x50525330);
        b.writeInt(Snapshot.FORMAT_VERSION);
        Buf.Invalid e = assertThrows(Buf.Invalid.class,
                () -> Snapshot.readHeader(new Buf.Reader(b.toByteArray())));
        assertTrue(e.getMessage(), e.getMessage().contains("magic"));
        assertTrue(e.getMessage(), e.getMessage().contains("50525330"));
    }

    @Test
    public void 版本不符直接拒读不做尽力而为的兼容() {
        for (int version : new int[] {Snapshot.FORMAT_VERSION + 1, Snapshot.FORMAT_VERSION - 1, 0}) {
            Buf b = new Buf(16);
            b.writeInt(Snapshot.MAGIC);
            b.writeInt(version);
            Buf.Invalid e = assertThrows(version + " 应被拒", Buf.Invalid.class,
                    () -> Snapshot.readHeader(new Buf.Reader(b.toByteArray())));
            assertTrue(e.getMessage(), e.getMessage().contains("cannot be read by"));
        }
    }

    /**
     * 字节布局版本与落盘目录名里那个 {@code v1} 必须是一枚：抬了布局没抬目录（或反过来），
     * 就会出现"新布局的档写进旧目录、旧代码按旧布局读"——那正是 {@link Snapshot#FORMAT_VERSION}
     * 注释里"读出来的是数、不是异常"的现场。两边各自为政时这条用例是唯一的粘合点。
     */
    @Test
    public void 字节布局版本与落盘目录版本同一枚() {
        assertEquals(Snapshot.FORMAT_VERSION, FileSaveSink.FORMAT_VERSION);
    }

    /**
     * 一份可能被截断的本机文件：每一处截断都得变成 {@link Buf.Invalid}，且不许有任何一种截断
     * 被"读完了"放过（读完了却还剩字节没读，那是 {@link #各段照读通过时只有段尾那一口不剩抓得住} 管的）。
     */
    @Test
    public void 截断的档读到半路就越界() {
        byte[] whole = composed().toByteArray();
        // 先确认完整那份能读通，否则下面的循环是"任何截断都恰好也读不通"的空证。
        Buf.Reader intact = new Buf.Reader(whole);
        readComposed(intact);
        assertEquals("完整档读通后应当一口不剩", 0, intact.remaining());

        for (int cut = 1; cut < whole.length; cut++) {
            byte[] torn = Arrays.copyOf(whole, cut);
            Buf.Invalid e = assertThrows("截到 " + cut + " 字节竟然读通了",
                    Buf.Invalid.class, () -> readComposed(new Buf.Reader(torn)));
            assertTrue(e.getMessage(), e.getMessage().contains("snapshot ends")
                    || e.getMessage().contains("does not fit"));
        }
    }

    /**
     * 布尔只认 0 与 1。第二用例是这条纪律的动机：{@code 5.0f} 的原始位首字节是 0x40，
     * 按"非零即真"读过去就是一次<b>静默错位</b>——布局错位时最先该响的就是这一格。
     */
    @Test
    public void 布尔那一格写着第三值就报布局错位() {
        Buf b = new Buf(8);
        b.writeBoolean(true);
        byte[] raw = b.toByteArray();
        raw[0] = 5;
        Buf.Invalid e = assertThrows(Buf.Invalid.class, () -> new Buf.Reader(raw).readBoolean());
        assertTrue(e.getMessage(), e.getMessage().contains("the layout is shifted"));

        Buf f = new Buf(8);
        f.writeFloat(5.0f);
        byte[] bits = f.toByteArray();
        assertNotEquals("这条用例的前提没了：0x40A00000 的首字节变成了 0/1", 1, bits[0] & 0xFF);
        assertNotEquals("这条用例的前提没了：0x40A00000 的首字节变成了 0/1", 0, bits[0] & 0xFF);
        Buf.Invalid g = assertThrows("浮点字节被当布尔读时没响", Buf.Invalid.class,
                () -> new Buf.Reader(bits).readBoolean());
        assertTrue(g.getMessage(), g.getMessage().contains("holds 64"));
    }

    /**
     * 各段自己读得通、整体却错位的两种形态（多一字节尾巴 / 少读一段），字节流里没有任何一段
     * 会因此报错——{@code Game.readAll} 末尾那句"一口不剩"是这一格唯一的探针。这里把探针的
     * <b>触发条件</b>钉住：好档给出 0，坏档给出非 0，且坏档逐段都读得通。
     */
    @Test
    public void 各段照读通过时只有段尾那一口不剩抓得住() {
        Buf good = composed();
        Buf.Reader r = new Buf.Reader(good.toByteArray());
        readComposed(r);
        assertEquals("完整档应当一口不剩", 0, r.remaining());

        byte[] withTail = Arrays.copyOf(good.toByteArray(), good.size() + 1);
        Buf.Reader extra = new Buf.Reader(withTail);
        readComposed(extra);                       // 三段照读，一声不响
        assertEquals("多出来的那一字节只能由段尾断言抓住", 1, extra.remaining());

        Buf.Reader shortRead = new Buf.Reader(good.toByteArray());
        shortRead.readInt();
        shortRead.readInt();                       // 只读了头部两格
        assertNotEquals("漏读整段时同样得由 remaining 抓住", 0, shortRead.remaining());
    }

    /**
     * 跨战场带：{@code LOGIC_W} 是 240 常量，逻辑高却在 320–560 之间随屏幕比例生长。
     * 存进字节的必须是<b>相对带的位置</b>——否则 21:9 上拍的档拿到 16:9 上会有半屏实体落在
     * 战场界外，出界判据立刻把它们收掉（画面表现为"读档后敌机凭空消失"，且没有一行日志）。
     * 于是这里的判据是<b>相对位置逐位保住、绝对位置按新带重算</b>，x 与其余字段不受换算牵连。
     */
    @Test
    public void 跨战场带还原保住相对位置而不是绝对像素() {
        Beams src = new Beams(4);
        src.spawn(30f, 380f, 8f, 1.25f, 0x00FF00, 4, true, 0.05f);   // 560 带的正中
        src.spawn(120f, BAND_TOP, 2f, 0.5f, 0xFF00FF, 4, false, 0.02f); // 带顶：两个带里都该是同一个绝对值
        src.at(1).settled = true;   // 已经结过账的那一道：换算不许把它读回"还没结"

        Buf w = new Buf(256);
        src.writeSnapshot(w, BAND_TOP, 560);

        Beams tall = new Beams(4);   // 同一台机器读自己的档
        tall.restoreSnapshot(new Buf.Reader(w.toByteArray()), BAND_TOP, 560);
        assertEquals(380f, tall.at(0).y, 0f);          // 同带往返是逐位相同
        assertEquals(120f, tall.at(1).x, 0f);
        assertEquals(0x00FF00, tall.at(0).color);
        assertTrue(tall.at(0).crit);
        assertTrue("换算把已结账那位弄丢了", tall.at(1).settled);
        assertFalse(tall.at(0).settled);

        Beams wide = new Beams(4);   // 换到 16:9
        wide.restoreSnapshot(new Buf.Reader(w.toByteArray()), BAND_TOP, 320);
        assertEquals("中点该落到新带的中点", 260f, wide.at(0).y, 1e-4f);
        assertEquals("带顶那条不该被换算挪走", 100f, wide.at(1).y, 1e-4f);
        assertEquals("x 是绝对逻辑像素，不走归一化", 30f, wide.at(0).x, 0f);
        assertEquals("换算只碰 y：其余读数原样回来", 8f, wide.at(0).width, 0f);
        assertEquals(1.25f, wide.at(0).damage, 0f);
        assertEquals(0.05f, wide.at(0).life, 0f);
        assertTrue(wide.at(1).settled);
        assertEquals("源那份不许被写快照改动", 380f, src.at(0).y, 0f);
    }

    /**
     * 带宽是除数：0 会把整段坐标变成 NaN，而 NaN 照样写得出去（浮点走原始位，不报错）。
     * 所以这一格必须在<b>写端</b>就拒，而不是等读端的范围校验去猜——NaN 过不了任何比较。
     */
    @Test
    public void 战场带高度非正在写端就被拒() {
        for (int bandH : new int[] {0, -1, Integer.MIN_VALUE}) {
            IllegalStateException e = assertThrows(bandH + " 不该被放过",
                    IllegalStateException.class, () -> Snapshot.putY(new Buf(16), 300f, BAND_TOP, bandH));
            assertTrue(e.getMessage(), e.getMessage().contains("battle band"));
        }
        IllegalStateException r = assertThrows(IllegalStateException.class,
                () -> Snapshot.getY(new Buf.Reader(new byte[4]), BAND_TOP, 0));
        assertTrue(r.getMessage(), r.getMessage().contains("battle band"));
    }

    // ---- 组合段（头部 → 折线图 → 随机源） -----------------------------------------------------

    private static Buf composed() {
        Buf b = new Buf(1024);
        Snapshot.putHeader(b);
        trend().writeSnapshot(b);
        new Rng(0x1234_5678_9ABC_DEF0L).writeSnapshot(b);
        return b;
    }

    /** 与 {@link #composed()} 严格镜像的读序；任何一段错位都在这里变成 Invalid。 */
    private static void readComposed(Buf.Reader r) {
        Snapshot.readHeader(r);
        new TrendSampler().restoreSnapshot(r);
        new Rng(0L).restoreSnapshot(r);
    }

    /** 落几帧，让折线图那一段是变长的、非全零的字节体。 */
    private static TrendSampler trend() {
        TrendSampler t = new TrendSampler();
        for (int i = 1; i <= 8; i++) {
            t.tick(1f, i * 3, 100L * i, 1f - i * 0.05f);
        }
        assertEquals("前提没了：这段字节体是空的", 8, t.frames());
        return t;
    }
}
