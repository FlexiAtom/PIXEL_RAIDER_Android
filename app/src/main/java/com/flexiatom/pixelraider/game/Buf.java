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
 * 快照的字节面：可生长的写端 + 带越界闸的读端。大端，浮点走**原始位**。
 *
 * <p><b>为什么不用 {@code java.io.DataOutputStream}／{@code ByteArrayInputStream}</b>：那两个把
 * "读到文件尾"报成 {@code IOException}，于是调用方必须处理受检异常；而快照的读端面对的是一份
 * <b>可能被截断、可能被改坏的本机文件</b>，越界不是" IO 出错"，是"这份档不合法"——它该在类型上就
 * 和 IO 分开（{@link Invalid}）。顺带第二条：写端自己长数组，{@code capture} 结束一次性裁掉余量，
 * 不必让 {@code ByteArrayOutputStream} 再抄一遍。
 *
 * <p><b>浮点按 {@link Float#floatToRawIntBits} 的原始位走</b>，不是十进制、不做四舍五入。理由有两条：
 * 快照里躺着 {@code ComboMeter.lastHitAt}（初值 {@code Float.NEGATIVE_INFINITY}）这类非有限值，
 * 任何"文本化"的编码都会把它变成异常；而坐标必须是<b>逐位相同</b>的数，恢复后第一帧的判定才与
 * 拍下那一刻一致（截断成短小数会让敌机与子弹各偏半像素）。
 *
 * <p><b>布尔只认 0 与 1</b>：{@code 5.0f} 的字节串里恰好有非零字节，把它当"真"读过去就是一次
 * <b>静默错位</b>。读端因此对第三个值抛 {@link Invalid}——布局一旦错位，最先响的是这一格，
 * 而不是三屏之后某只导弹的相位。
 */
public final class Buf {

    /** 这份字节流不合法（越界、布尔位不是 0/1、长度不吻合）。与 IO 失败分开的就是这一族。 */
    public static final class Invalid extends RuntimeException {
        Invalid(String message) {
            super(message);
        }
    }

    private byte[] a;
    private int size;

    /**
     * @param hint 预计字节数。给小了只是多搬一次，给大了白占内存；
     *             一份实体快照的量级见 {@code SaveSlots} 与池件 §8.11（45–106 KB）。
     */
    public Buf(int hint) {
        if (hint <= 0) throw new IllegalArgumentException("hint must be > 0: " + hint);
        a = new byte[hint];
    }

    /** 已写字节数。 */
    public int size() {
        return size;
    }

    /** 裁掉容量余量，返回**恰好**size 字节的数组（交给 {@code plat.SaveSink} 的就是这份）。 */
    public byte[] toByteArray() {
        byte[] out = new byte[size];
        System.arraycopy(a, 0, out, 0, size);
        return out;
    }

    public Buf writeInt(int v) {
        reserve(4);
        int p = size;
        a[p] = (byte) (v >>> 24);
        a[p + 1] = (byte) (v >>> 16);
        a[p + 2] = (byte) (v >>> 8);
        a[p + 3] = (byte) v;
        size = p + 4;
        return this;
    }

    public Buf writeLong(long v) {
        writeInt((int) (v >>> 32));
        writeInt((int) v);
        return this;
    }

    public Buf writeFloat(float v) {
        return writeInt(Float.floatToRawIntBits(v));
    }

    public Buf writeBoolean(boolean v) {
        reserve(1);
        a[size++] = (byte) (v ? 1 : 0);
        return this;
    }

    /** 连续写 n 个 int（出怪队列、卡等级、折线图那几段用）。 */
    public Buf writeInts(int[] src, int off, int n) {
        reserve(n * 4);
        for (int i = 0; i < n; i++) writeInt(src[off + i]);
        return this;
    }

    private void reserve(int more) {
        int need = size + more;
        if (need <= a.length) return;
        int cap = a.length;
        while (cap < need) {
            cap = cap > (Integer.MAX_VALUE >> 1) - 1 ? Integer.MAX_VALUE - 1 : cap << 1;
        }
        byte[] grown = new byte[cap];
        System.arraycopy(a, 0, grown, 0, size);
        a = grown;
    }

    /**
     * 读端。构造时不校验内容，<b>读到哪里校验到哪里</b>——一次 {@link Invalid} 就足够让上层
     * 整份放弃（{@code Game} 那侧的回退是"把退回点那份快照套回去"，不是"接着读剩下的"）。
     */
    public static final class Reader {
        private final byte[] src;
        private int p;

        public Reader(byte[] src) {
            if (src == null) throw new Invalid("null snapshot bytes");
            this.src = src;
        }

        /** 还剩多少字节没读。{@code apply} 用它钉"必须一口不剩"。 */
        public int remaining() {
            return src.length - p;
        }

        public int readInt() {
            if (p + 4 > src.length) throw outOfRange("int");
            int v = (src[p] & 0xFF) << 24
                    | (src[p + 1] & 0xFF) << 16
                    | (src[p + 2] & 0xFF) << 8
                    | (src[p + 3] & 0xFF);
            p += 4;
            return v;
        }

        public long readLong() {
            long hi = readInt() & 0xFFFFFFFFL;
            long lo = readInt() & 0xFFFFFFFFL;
            return hi << 32 | lo;
        }

        public float readFloat() {
            return Float.intBitsToFloat(readInt());
        }

        public boolean readBoolean() {
            if (p + 1 > src.length) throw outOfRange("boolean");
            byte v = src[p++];
            if (v == 0) return false;
            if (v == 1) return true;
            throw new Invalid("boolean slot at byte " + (p - 1) + " holds " + (v & 0xFF)
                    + " - the layout is shifted, this is not a save");
        }

        /** 读 n 个 int 进目标数组的 [off, off+n)，越界即抛（不静默截断）。 */
        public void readInts(int[] dst, int off, int n) {
            // 两条判据都写成"减法在已知非负的一侧"：n > 2^30 这种坏计数一乘 4 就溢出成负数，
            // 用 p + n*4 > length 那种形式反而会放过它，然后撞到数组下标上。
            if (n < 0 || off < 0 || n > dst.length - off) {
                throw new Invalid("int[" + n + "] at " + off + " does not fit a dst of " + dst.length);
            }
            if (n > remaining() / 4) throw outOfRange("int[" + n + ']');
            for (int i = 0; i < n; i++) dst[off + i] = readInt();
        }

        private Invalid outOfRange(String what) {
            return new Invalid("snapshot ends while reading " + what + " at byte " + p
                    + " of " + src.length);
        }
    }
}
