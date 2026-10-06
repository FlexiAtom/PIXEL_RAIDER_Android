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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

/**
 * wav 写出口：P2 里它是**唯一一处**把内存样本交给磁盘的地方，而 {@code SoundPool.load(String,int)}
 * 只认磁盘上的文件——这一格错了，整条路线没有声音，且错得毫无提示（load 返回 0，play 静默返回）。
 *
 * <p>钉的是头部的每一个字段与量化的那一步。都用字面量，不去调实现里那两个常数——
 * 测试跟着实现算一遍等于没测（规格 §九.20）。
 */
public final class WavSinkTest {

    @Test
    public void headerIsCanonicalMonoS16LittleEndian() throws IOException {
        float[] w = {0f, 0.25f, -0.25f, 1f, -1f};
        File f = write(w);
        byte[] raw = Files.readAllBytes(f.toPath());
        assertEquals("文件长度不等于 byteSizeOf", WavSink.byteSizeOf(w.length), raw.length);
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);

        assertTrue("RIFF 魔数错了，装载方一律不认",
                bb.get(0) == 'R' && bb.get(1) == 'I' && bb.get(2) == 'F' && bb.get(3) == 'F');
        assertEquals("RIFF 段长应为 36 + 数据字节", 36 + w.length * 2, bb.getInt(4));
        assertTrue("WAVE 魔数", bb.get(8) == 'W' && bb.get(9) == 'A' && bb.get(10) == 'V' && bb.get(11) == 'E');
        assertTrue("fmt 块魔数", bb.get(12) == 'f' && bb.get(13) == 'm' && bb.get(14) == 't' && bb.get(15) == ' ');
        assertEquals("fmt 块长", 16, bb.getInt(16));
        // 偏移一律按 RIFF/WAVE 的规范布局数下来：fmt 头 12+8=20 起才是 PCM 那六个字段。
        assertEquals("编码格式必须是 PCM(1)——SoundPool 不认 IEEE float", 1, bb.getShort(20));
        assertEquals("声道数必须是 1（P2 走单声道）", 1, bb.getShort(22));
        assertEquals("采样率", 44100, bb.getInt(24));
        assertEquals("每秒字节数 = 率 × 声道 × 2", 88200, bb.getInt(28));
        assertEquals("块对齐 = 声道 × 2", 2, bb.getShort(32));
        assertEquals("位深", 16, bb.getShort(34));
        assertTrue("data 块魔数", bb.get(36) == 'd' && bb.get(37) == 'a' && bb.get(38) == 't' && bb.get(39) == 'a');
        assertEquals("data 段长", w.length * 2, bb.getInt(40));
    }

    /**
     * 量化走的是软削波而不是硬钳位，所以满幅输入落到 **29126** 而不是 32767。
     *
     * <p>这条断言把"谁做限幅"钉死在写出口：如果哪天有人改成硬钳位（或把 softClip 挪进
     * 播放面），这里的数会当场错，而那正是"多声叠加时爆音还是削平"的分别。
     */
    @Test
    public void quantizationIsSoftClipped() throws IOException {
        float[] w = {0f, 1f, -1f, 0.5f};
        ByteBuffer bb = ByteBuffer.wrap(Files.readAllBytes(write(w).toPath())).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("0 样本", 0, bb.getShort(44));
        assertEquals("满幅正", 29126, bb.getShort(46));
        assertEquals("满幅负", -29126, bb.getShort(48));
        assertEquals("半幅", 15928, bb.getShort(50));
    }

    /** 写口是原子的：中途不留 {@code .part}，也不留没有头的那半个文件（缓存会把它当有效资产）。 */
    @Test
    public void noPartialFileSurvivesAWrite() throws IOException {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "pr_wav_sink_" + System.nanoTime());
        File target = new File(dir, "nested/one.wav");
        try {
            WavSink.writeMonoS16(target, new float[]{0f, 0f});
            assertTrue("嵌套父目录没建出来，或文件没落下去", target.isFile());
            assertFalse("父目录下还留着 .part", new File(target.getParentFile(), "one.wav.part").exists());
        } finally {
            deleteTree(dir);
        }
    }

    /** 空样本也要出一个**合法**的头：装载方拿到 44 字节的文件会返回 0 而不是崩。 */
    @Test
    public void emptyWaveStillWritesAValidHeader() throws IOException {
        File f = write(new float[0]);
        assertEquals(44L, f.length());
        assertEquals("byteSizeOf(0) 必须是头长", 44, WavSink.byteSizeOf(0));
    }

    private static File write(float[] w) throws IOException {
        File f = File.createTempFile("pr_wav_", ".wav");
        f.deleteOnExit();
        WavSink.writeMonoS16(f, w);
        return f;
    }

    private static void deleteTree(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteTree(k);
            }
        }
        f.delete();
    }
}
