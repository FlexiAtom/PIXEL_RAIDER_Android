/*
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

/**
 * 零分配的 HUD 文本格式化（规格 §六"每帧零分配"与 §四 HUD 每帧重排冲突的唯一解）。
 *
 * {@code "SCORE " + score} 每帧造一个 StringBuilder 加一个 String，GC 会在激战时挑最坏的
 * 时刻回收。这里把结果写进一块复用的 char[]，绘制端（BitmapFont）只读不造。
 *
 * 溢出策略是**丢弃**而不是扩容：扩容意味着运行期分配，而 HUD 串的容量是布局算得出的上界。
 * 容量不够属于布局改动没跟上，必须由 {@code HudTextTest} 红着脸报出来。
 */
public final class HudText {

    private final char[] buf;
    private final char[] digits = new char[20];     // long 十进制最长 19 位（含符号）
    private int len;

    public HudText(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        buf = new char[capacity];
    }

    public HudText reset() {
        len = 0;
        return this;
    }

    public int length() {
        return len;
    }

    public int capacity() {
        return buf.length;
    }

    public char[] buffer() {
        return buf;
    }

    /** 取当前内容（**仅测试与日志用**：会分配一个 String）。 */
    public String snapshot() {
        return new String(buf, 0, len);
    }

    public HudText chr(char c) {
        if (len < buf.length) buf[len++] = c;
        return this;
    }

    /** 只接受字符串字面量或常驻常量——逐字符拷贝，所以不分配。 */
    public HudText text(String s) {
        for (int i = 0; i < s.length(); i++) chr(s.charAt(i));
        return this;
    }

    public HudText num(int v) {
        return num((long) v);
    }

    public HudText num(long v) {
        int n = 0;
        boolean neg = v < 0;
        long x = v;
        if (x == 0) {
            digits[n++] = '0';
        } else {
            // 逐位按**负数**取模再取绝对值：-v 在 Long.MIN_VALUE 上会溢出回自身
            while (x != 0) {
                long d = x % 10;
                digits[n++] = (char) ('0' + (d < 0 ? -d : d));
                x /= 10;
            }
        }
        if (neg) chr('-');
        for (int i = n - 1; i >= 0; i--) chr(digits[i]);
        return this;
    }

    /** 定点小数：16.73 → "16.7"（decimals=1）。四舍五入靠整数运算，不碰 String.format。 */
    public HudText fixed(float v, int decimals) {
        if (Float.isNaN(v)) return text("NaN");
        boolean neg = v < 0f;
        float mag = neg ? -v : v;
        long scale = 1;
        for (int i = 0; i < decimals; i++) scale *= 10;
        long scaled = Math.round(mag * scale);
        if (neg) chr('-');
        num(scaled / scale);
        if (decimals > 0) {
            chr('.');
            long frac = scaled % scale;
            for (int shift = (int) (scale / 10); shift >= 1; shift /= 10) {
                chr((char) ('0' + frac / shift));
                frac %= shift;
            }
        }
        return this;
    }

    /** 左空格补齐到 width 位（读数列对齐用，别让数字一位一位往左蹦）。 */
    public HudText pad(int v, int width) {
        int start = len;
        num(v);
        int w = len - start;
        // 容量不够时 num 已经丢过字符，右移会写出界；这里再钳一次，宁可少补空格
        int need = Math.min(width - w, buf.length - len);
        if (need <= 0) return this;
        System.arraycopy(buf, start, buf, start + need, w);
        for (int i = 0; i < need; i++) buf[start + i] = ' ';
        len += need;
        return this;
    }

    /** 分:秒，秒补零（"1:05" 比 "65" 好读，也比 "1:5" 稳）。 */
    public HudText clock(int seconds) {
        int s = seconds < 0 ? 0 : seconds;
        num(s / 60).chr(':');
        int sec = s % 60;
        if (sec < 10) chr('0');
        return num(sec);
    }

    /** 百分号读数：0.5 → "50%"。 */
    public HudText percent(float ratio01) {
        return num(Math.round(HudLayout.clamp01(ratio01) * 100f)).chr('%');
    }
}
