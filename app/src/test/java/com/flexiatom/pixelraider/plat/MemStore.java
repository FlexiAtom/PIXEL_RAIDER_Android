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
