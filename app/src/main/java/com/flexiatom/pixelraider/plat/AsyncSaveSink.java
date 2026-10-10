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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 存档出口的线程层：**只谈线程，不谈介质**（与 {@link AsyncKeyValue} 同一条分工，也同一手
 * {@code Executors.newSingleThreadExecutor()}）。真正的读写交给 {@link SaveSink}，这里负责
 * "调用线程什么都没发生"和"结果怎么交回渲染线程"。
 *
 * <p><b>这一层最重要的事：它不实现 {@link SaveSink}，四个动作全部立刻返回。</b>
 * {@link SaveSink} 的每个方法都是同步 IO；如果这一层也长成那个签名，那么
 * {@code game.attachSave(asyncSink)} 之后，渲染线程拿到一枚类型完全合法的 {@code SaveSink}，
 * 在某一帧调下去 {@code exists()} 或 {@code read()} 就是**站在渲染线程上等盘**——
 * [规格 §八] 那条红线被一次类型正确的调用踩过去。所以对外只有四个动作，全都立刻返回：
 * {@link #submitWrite 交出去}、{@link #submitDelete 交出去}、{@link #requestRead 交出去}、
 * {@link #pollRead 取回来}。"有没有档"这件事在 {@code game} 侧由槽位掩码回答，不需要问介质。
 *
 * <p><b>读为什么必须绕一圈</b>：{@link AsyncKeyValue} 的类注释逐字「读**不**等挂起的写，直接落到
 * {@code target} 的内存镜像……反过来如果这里去等 IO，就是把同一条红线从"写"换成"读"再踩一遍」。
 * 那句话的前提在快照上**不成立**：一局被放弃之后，那份状态的内存真值就不存在了（{@code Game} 里
 * "这一局已被放弃"那条注释），文件是唯一真源 ⇒ 读档必须真的碰 IO ⇒ 只能是反向两段式：
 * executor 读 {@code byte[]} → 交回渲染线程 → 渲染线程在安全点 apply。
 *
 * <p><b>交回那一步的形状是一枚 volatile 引用槽</b>，不是队列：同一时刻只可能有一份档在还原
 * （玩家点一行、等页面响应），1 槽天然够用，也不需要扩 {@code core/InputRouter} 那条
 * {@code volatile head/tail} 的无锁 ring（它 {@code STRIDE=5} 只搬 int，装不下 {@code byte[]}）。
 * 只有一个写者（executor）与一个读者（渲染线程），引用赋值本身是原子的，
 * volatile 保证的是"渲染线程不会一直看到 null 那一份旧值"。
 */
public final class AsyncSaveSink {

    /** 一次读的结果：槽号＋字节（{@code data == null} 表示那一槽没有档或读失败，由 apply 侧决定怎么报）。 */
    public static final class Read {
        public final int slot;
        public final byte[] data;

        Read(int slot, byte[] data) {
            this.slot = slot;
            this.data = data;
        }
    }

    private final SaveSink target;
    private final ExecutorService io;
    /** 唯一的那枚引用槽。写者只有 executor，读者只有渲染线程。 */
    private volatile Read pending;

    public AsyncSaveSink(SaveSink target) {
        this(target, Executors.newSingleThreadExecutor());
    }

    /** 测试注入用：给一个只收任务不跑的 executor，就能钉死"调用线程那一刻什么都没发生"。 */
    public AsyncSaveSink(SaveSink target, ExecutorService io) {
        this.target = target;
        this.io = io;
    }

    /**
     * 把渲染线程已经产好的字节交给 IO 线程落盘。调用即返回，**不复制**那份 {@code data}——
     * 前提是调用方交出来之后就再也不碰它（快照序列化器正是"一次一字节数组"的形状）。
     */
    public void submitWrite(int slot, byte[] data) {
        io.execute(() -> target.write(slot, data));
    }

    /** 删掉一份档，同样不等它做完（掩码由调用方立刻更新，列表页因此不需要再问介质）。 */
    public void submitDelete(int slot) {
        io.execute(() -> target.delete(slot));
    }

    /** 投递一次读意图；结果什么时候落在 {@code pending} 上，由 {@link #pollRead()} 的调用者自己决定等多久。 */
    public void requestRead(int slot) {
        io.execute(() -> pending = new Read(slot, target.read(slot)));
    }

    /**
     * 渲染线程在安全点抽一次：有结果就取走并清空槽，没有就给 {@code null}。
     * 取走即清空是必须的——否则同一份档会在之后的每一帧被反复还原。
     */
    public Read pollRead() {
        Read r = pending;
        if (r == null) return null;
        pending = null;
        return r;
    }

    /** 是否有已回来但还没被取走的结果。渲染线程自己那一格"等档"的状态用它判断，不必知道队列里排了几件事。 */
    public boolean readReady() {
        return pending != null;
    }

    /**
     * 等挂起的写完再走，与 {@link AsyncKeyValue#shutdown()} 同一件事：主线程在 Activity 销毁时调，
     * 那时渲染线程已经在 {@code surfaceDestroyed} 里 joined。存档写是几十 KB 量级，比一次结算的
     * 几十枚键长，所以超时给得比 {@code AsyncKeyValue} 那层的 2 秒宽一档。
     */
    public void shutdown() {
        io.shutdown();
        try {
            io.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
