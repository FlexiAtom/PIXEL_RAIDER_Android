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
