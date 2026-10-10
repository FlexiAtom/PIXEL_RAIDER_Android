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
package com.flexiatom.pixelraider.plat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * {@link SaveSink} 的文件实现：**只谈介质，不谈线程**（与 {@link SharedPrefs} 同一条分工）。
 *
 * <p>构造只收一枚 {@link File} 目录，不收 {@code Context}——那件事留在 {@code MainActivity} 里做
 * （{@code new File(getFilesDir(), "saves/v" + }{@link #FORMAT_VERSION}{@code )}，形状照
 * {@code audio/AudioEngine} 那个 {@code "audio/v" + GEN_VERSION}）。省下 Context 不是为了少一个参数，
 * 是为了这一层能在 JVM 里对着一个临时目录跑真读真写：介质这条纪律（覆盖要么完整要么不算写）
 * 绑上 android 类型就只剩仪器测试能验了。
 *
 * <p><b>目录名带格式版本</b>是这里唯一不能省的纪律。快照二进制里没有"我是第几版布局"这件事，
 * 靠的是父目录名——今后给任何一座池的实体加一枚字段，只要 {@link #FORMAT_VERSION} 跟着涨，
 * 旧档落在旧目录、新码读新目录，读到的是"没有这份档"（{@code null}）而不是<b>错位读出来的假数</b>。
 * 后者是这条链上最难查的一种坏法：不抛异常、画面能跑，只是敌机血量是某个坐标的比特位。
 *
 * <p><b>覆盖走临时文件＋改名</b>，不直接覆写目标：玩家显式覆盖一份已有档（他 2026-10-10 逐字
 * 「允许覆盖和删除存档」）时，直接覆写意味着中途掉电就留下半份档——半份比没有更坏，
 * 因为列表页会说"这一槽有档"。改名在同目录内是原子的（POSIX {@code rename}），
 * 于是任何时刻盘上要么是旧的那份完整档、要么是新的那份完整档，{@code *.tmp} 只是崩溃残留。
 */
public final class FileSaveSink implements SaveSink {

    /**
     * 快照的二进制布局版本。**只在"加/删/改实体字段"这种会让旧字节错位的情形下涨**，
     * 数值调整（{@code Balance} 里那些）不算——那是同一布局里的不同数。
     */
    public static final int FORMAT_VERSION = 1;

    private final File dir;

    public FileSaveSink(File dir) {
        this.dir = dir;
    }

    @Override
    public void write(int slot, byte[] data) {
        if (slot < 0) throw new IllegalArgumentException("slot=" + slot);
        if (data == null) throw new IllegalArgumentException("null payload for slot " + slot);
        File target = file(slot);
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("mkdirs failed: " + parent);
            }
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(data);
                out.getFD().sync();       // 改名之前必须让字节真的进盘，否则"原子"只保证名字不保证内容
            } finally {
                out.close();
            }
            if (!tmp.renameTo(target)) throw new IOException("rename failed -> " + target);
        } catch (IOException ignored) {
            // 落不进去就不算写：清掉残留，让 {@link #exists} 继续反映"这一槽还是原来那份完整档"。
            // 清不掉也认了——.tmp 不在 exists() 的判据里，下一次同槽写入会覆掉它。
            tmp.delete();
        }
    }

    @Override
    public byte[] read(int slot) {
        File f = file(slot);
        if (!f.isFile()) return null;
        long len = f.length();
        if (len <= 0L || len > Integer.MAX_VALUE) return null;
        byte[] data = new byte[(int) len];
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            // 读到一半文件被截短（外部清理、或目录被别的进程动过）时，不能交回一份带零尾巴的
            // "完整"快照：字节头部的长度校验会把它判成坏档，但那要靠调用方记得查——所以这里就判读失败。
            return off == data.length ? data : null;
        } catch (IOException ignored) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // 关不掉不影响已经读进来的字节。
                }
            }
        }
    }

    @Override
    public void delete(int slot) {
        File f = file(slot);
        // 删失败（文件被别的进程占着、或权限出问题）就留成孤儿字节：槽位掩码才是"有没有档"的真源，
        // 那份字节等下一次同槽覆盖来回收。不在这里重试，也不在这里抛——删档不该让渲染线程断帧。
        if (f.isFile()) f.delete();
    }

    @Override
    public boolean exists(int slot) {
        return file(slot).isFile();
    }

    private File file(int slot) {
        return new File(dir, slot + ".bin");
    }
}
