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

import java.util.HashMap;
import java.util.Map;

/**
 * 测试用的内存 {@link KeyValue}（JVM 里跑，不碰 android）。
 *
 * 顺带记账：{@code writes} 与 {@code flushes} 让"批量写只落一次盘""这条键真的被写过"
 * 变成可断言的事实，而不是读代码时的印象。
 */
public final class MemStore implements KeyValue {

    public final Map<String, Long> map = new HashMap<>();
    public int flushes;
    public int writes;

    @Override
    public long getLong(String key, long fallback) {
        Long v = map.get(key);
        return v == null ? fallback : v;
    }

    @Override
    public void putLong(String key, long value) {
        map.put(key, value);
        writes++;
    }

    @Override
    public void flush() {
        flushes++;
    }
}
