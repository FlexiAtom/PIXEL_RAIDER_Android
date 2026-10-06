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

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 单声道 16 位 PCM 的 WAV 出口。
 *
 * <p><b>单声道是有意的</b>：所有配方都是"同一值写左右"的真单声道，复制成两声道只让私有目录翻倍
 * （26 只音效 5.4 秒 ≈463 KB 单声道／926 KB 立体声），听感一点东西都不多。规格 §七 :290 的"双总线"
 * 是**两个播放器**，不是两个声道——这两件事在旧文案里被我混过一次，别再混。
 *
 * <p>写盘是**先 {@code .part} 再改名**：P2 的生成结果按版本目录缓存，半途被杀留下的截断文件
 * 会被下次启动当成有效资产读，那是一条只在"上次崩了"时才出现的难查故障。改名在同目录内是原子的。
 */
public final class WavSink {

    public static final int CHANNELS = 1;

    private WavSink() {
    }

    /** 落盘后的字节数（缓存完整性检查用：文件短过这个数就是坏的）。 */
    public static int byteSizeOf(int samples) {
        return 44 + samples * CHANNELS * 2;
    }

    public static void writeMonoS16(File target, float[] mono) throws IOException {
        File dir = target.getParentFile();
        if (dir != null) dir.mkdirs();
        File part = new File(target.getAbsolutePath() + ".part");
        ByteBuffer bb = ByteBuffer.allocate(byteSizeOf(mono.length)).order(ByteOrder.LITTLE_ENDIAN);
        int dataBytes = mono.length * CHANNELS * 2;
        bb.put("RIFF".getBytes()).putInt(36 + dataBytes).put("WAVE".getBytes());
        bb.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) CHANNELS);
        bb.putInt(ChipWave.RATE).putInt(ChipWave.RATE * CHANNELS * 2);
        bb.putShort((short) (CHANNELS * 2)).putShort((short) 16);
        bb.put("data".getBytes()).putInt(dataBytes);
        for (float v : mono) bb.putShort((short) ((int) (ChipWave.softClip(v) * Short.MAX_VALUE)));
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(part))) {
            os.write(bb.array());
            os.flush();
        }
        if (!part.renameTo(target)) {
            part.delete();
            throw new IOException("wav 改名失败: " + part + " -> " + target);
        }
    }
}
