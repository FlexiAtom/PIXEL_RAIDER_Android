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
 * 存档字节的落盘出口（[规格 §八] 那条边界的第二条线：渲染线程拿不到 Context，也拿不到文件）。
 *
 * <p><b>为什么 {@link KeyValue} 装不下存档，非要另立一枚</b>：那份接口只有三方法
 * （{@code getLong}/{@code putLong}/{@code flush}），而局内快照需要它没有的两样东西——
 * <b>删除</b>（多份并存且无回收＝只涨不落）以及<b>非 long 的载荷</b>（一份实体快照是几万字节的二进制，
 * 不是数）。把几千枚键塞进同一份 prefs 还会连带一条平台行为：{@code apply()} 是整份 XML 全量重写，
 * 此后每一次买卡都要连存档一起重写。所以字节走这里，KeyValue 只留槽位掩码与列表页要显示的那几枚标量。
 *
 * <p><b>"哪些槽有档"不在这里问</b>：那枚枚举本该由 {@code listFiles()} 给出，但那样一来列表页每画一行
 * 就是渲染线程扫一次目录。槽位是否有档改由 {@code game} 侧那枚掩码回答（见 {@code SaveSlots}），
 * 代价是"盘上字节"与"账本"可以不一致，不一致时以账本为准——这条账在 {@code SaveSlots} 的类注释里。
 *
 * <p><b>这一层只谈介质，不谈线程</b>——与 {@link SharedPrefs} 同一条分工。四个方法都是同步的，
 * 调用线程承担全部 IO；渲染线程那条红线由 {@link AsyncSaveSink} 那层隔着（拆开才测得到"谁在什么时候真的动了盘"）。
 *
 * <p><b>实现方要在意的两条纪律</b>：① 目录名带<b>格式版本</b>（同 {@code audio/AudioEngine} 那个
 * {@code "audio/v" + GEN_VERSION} 的先例）——快照二进制不带版本的话，今后给实体加一枚字段，
 * 旧存档会被按新布局<b>静默错位读</b>，读到的是数、不是异常；② 写要么完整要么不算写（见
 * {@link FileSaveSink}，那里用临时文件＋改名，因为"覆盖"是玩家显式动作，半份档比没档更坏）。
 */
public interface SaveSink {

    /**
     * 未 attach 真出口时的兜底：写与删都什么都不做，读给 {@code null}，存在性恒假。
     * 与 {@link KeyValue#EMPTY} 同一条口径——**不做"本次运行内看起来存过了"那种假象**，
     * 否则开发者会在真机上误以为存档已经通了。
     */
    SaveSink EMPTY = new SaveSink() {
        @Override public void write(int slot, byte[] data) { }

        @Override public byte[] read(int slot) {
            return null;
        }

        @Override public void delete(int slot) { }

        @Override public boolean exists(int slot) {
            return false;
        }
    };

    /** 写这一份快照，覆盖该槽原有的档。实现必须保证"要么完整要么不算写"。 */
    void write(int slot, byte[] data);

    /** 读回该槽的字节；没有档（或读失败）给 {@code null}，<b>不给空数组</b>——空数组留给"存了一份空快照"那种真事。 */
    byte[] read(int slot);

    /** 删掉该槽的档。删不存在的槽不是错误，静默返回。 */
    void delete(int slot);

    /**
     * 该槽有没有档——介质自己的事实。⚠ 列表页那两条形<b>不</b>读这里（读这里＝渲染线程 {@code isFile()}），
     * 它们读槽位掩码。这一枚留给介质侧的可测性，以及 IO 线程真要确认一次时的合法入口。
     */
    boolean exists(int slot);
}
