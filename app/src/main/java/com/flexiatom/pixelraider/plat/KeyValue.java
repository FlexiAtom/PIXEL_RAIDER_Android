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
 * 只存 long 的持久化出口（规格 §八：渲染线程不碰 Context，落盘细节全在 plat）。
 *
 * 只要 long 是因为局外数据本来就全是数（分数/波次/命中率千分比/秒数）。加一种类型就要加一个
 * 平台实现分支，而 String 之类的键值一旦进接口，"每帧零分配"的口径就要在这里重新讨论。
 *
 * 实现必须在渲染线程可读：Game 只在**死亡那一刻**读写一次，不在帧循环里。
 */
public interface KeyValue {

    /** 未 attach 真存储时的兜底：读永远给 fallback、写丢弃。纪录只在本次运行内"看起来"生效都不给——
     *  那样开发者会在真机上误以为持久化已经通了。 */
    KeyValue EMPTY = new KeyValue() {
        @Override public long getLong(String key, long fallback) {
            return fallback;
        }

        @Override public void putLong(String key, long value) { }

        @Override public void flush() { }
    };

    long getLong(String key, long fallback);

    /** 只登记，不落盘——**批量写完必须调 {@link #flush()}**，否则进程被杀就丢这批。 */
    void putLong(String key, long value);

    /** 让挂起的写入落地。渲染线程调用它必须是低频时机（结算页），不能进帧循环。 */
    void flush();
}
