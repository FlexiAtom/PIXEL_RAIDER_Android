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

/**
 * {@link KeyValue} 的 SharedPreferences 实现：**只谈攒批，不谈线程**。
 *
 * 写入攒在一个 {@code Editor} 里，直到 {@link #flush()} 才交出去：一次结算有纪录七个键 +
 * 成长树七个键，每个键各落一次盘就是十四次排队。
 *
 * 但"在哪个线程调用"不归这里管——那是 {@link AsyncKeyValue} 的职责。混在一起，
 * 这条 [规格 §八] 的红线就只能连 android 类型一起测；拆开之后它是一道能在 JVM 里跑的结构约束。
 *
 * 用 {@code apply()} 不用 {@code commit()}：后者同步等磁盘，在渲染线程上是一次可感知的掉帧。
 */
public final class SharedPrefs implements KeyValue {

    private final android.content.SharedPreferences prefs;
    private android.content.SharedPreferences.Editor editor;

    public SharedPrefs(android.content.SharedPreferences prefs) {
        this.prefs = prefs;
    }

    @Override
    public long getLong(String key, long fallback) {
        return prefs.getLong(key, fallback);
    }

    @Override
    public void putLong(String key, long value) {
        if (editor == null) editor = prefs.edit();
        editor.putLong(key, value);
    }

    @Override
    public void flush() {
        if (editor == null) return;
        editor.apply();
        editor = null;
    }
}
