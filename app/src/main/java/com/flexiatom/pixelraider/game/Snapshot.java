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
 * 局内快照的<strong>格式面</strong>：魔数、版本、坐标换算，以及<b>这一份字节里到底有什么、没什么</b>的清单。
 * 序列化本身没有这里——八座池与九个状态对象各自带一对
 * {@code writeSnapshot(Buf, int, int)} / {@code restoreSnapshot(Buf, int, int)}，
 * 把"我有哪些字段"留在离字段最近的地方；{@code Game} 只负责按下面的顺序把它们串起来。
 *
 * <p><b>段序（写与读必须逐项镜像，两处各写一遍是这条格式最大的失真风险）</b>：
 * <ol>
 *   <li>魔数 + 版本</li>
 *   <li>敌人 / 玩家弹 / 敌弹 / 普通导弹 / 格斗导弹 / 战斗部 / 光束 / 掉落（八座池，顺序即 {@code Game} 的字段声明序）</li>
 *   <li>玩家 → 状态层 → 连击 → 过载 → 定格 → 波次导演流 → 局内商店 → 本局计数 → 折线图</li>
 *   <li>随机源一枚 long</li>
 *   <li>{@code Game} 自己的标量面（分数、金币、炸弹、指定目标那一对、商店待开…）</li>
 * </ol>
 * 读端最后断言<b>一口不剩</b>：段序写错、少读一个字段、多读一个字段，都会在这里当场变成
 * {@link Buf.Invalid}，而不是"读出来一套看着像样但整体错位半格的状态"。
 *
 * <p><strong>不带在字节里的东西，以及为什么</strong>：
 * <ul>
 *   <li><b>模式与档（{@code Mode}/{@code Tier}）</b>：真源在槽位账本 {@link SaveSlots} 的那枚 packed meta。
 *       字节里再存一份就是第二个真源，而这两份一旦不一致，会出现"列表写着终结档、局内跑的是普通档"
 *       这种既测不出来也解释不了的局。读档入口的次序因此是先取 meta、再套字节。</li>
 *   <li><b>章节号</b>：{@link Mode#chapterForWave} 由波号推得出（八章＝40 波那条既有星区界），
 *       而波号在波次流那一段里。存两份就会有一天不一致。</li>
 *   <li><b>商店货架</b>（{@code shopScore/shopOrder/shopOffer}）：开架时由
 *       {@code ShopRules.selectOffer} 现抽，抽的时候读的是<b>已经还原的随机源</b> ⇒ 派生量。</li>
 *   <li><b>导弹导引的身份缓存</b>（{@code MissileBehavior} 的 {@code occupiedStamp}/{@code frame}/
 *       {@code designated}／那一族 {@code *Eff} 阈值）：每帧按当前弹池与当前乘子重算 ⇒ 派生量。</li>
 *   <li><b>粒子、特效注册表、背景、震屏与残影</b>：画面。丢了它们仿真仍逐帧一致。</li>
 *   <li><b>{@code Scheduler} 里还没响的延迟音效</b>：最多丢几个已经排上的 cue，不影响任何判定。</li>
 *   <li><b>池的诊断计数</b>（{@code reuseTotal} 与它的判据 {@code everUsed}、{@code evictTotal}、
 *       {@code refuseTotal}）：读的是调试页那几行数字，不是玩法真源 ⇒ 读档后从当前时刻重新统计。
 *       <b>但 {@code spawnTotal} 与 {@code nextSeq} 是例外，必须存</b>——它们是代次戳与出膛序的来源，
 *       不接着涨就会让恢复后新生成的敌人<b>顶掉快照里某个导引头正持有的身份</b>（见
 *       {@link Enemies.Enemy#born} 那条"槽号不是身份的全部"）。</li>
 * </ul>
 */
public final class Snapshot {

    /** {@code 'P''R''S''1'}。放在最前面只为一件事：拿错文件（比如误把 wav 当档读）第一格就响。 */
    public static final int MAGIC = 0x50525331;

    /**
     * 字节布局的版本。<b>加字段必须抬它</b>：不抬的话旧档会被按新布局静默错位读——读出来的是数、
     * 不是异常（这条纪律的原文与 {@code plat.FileSaveSink} 的目录版本是同一个担忧的两侧）。
     * 与目录名里那个 {@code v1} 的相等关系由 {@code SnapshotFormatTest} 钉住，不靠人记。
     */
    public static final int FORMAT_VERSION = 1;

    private Snapshot() { }

    /**
     * 逻辑 y → 存储值：<b>相对战场带的归一化分数</b>。
     *
     * <p>为什么只有 y 要办：{@code Screen.LOGIC_W} 是 240 常量，而逻辑高随屏幕比例在 320–560 之间
     * 生长。一份在 21:9 上拍下的档，若按原始逻辑像素存 y，拿到 16:9 的机器上就有半屏实体落在
     * {@code battleBottom()} 之外——出界判据会<b>立刻把它们收掉</b>，于是"读档后敌机凭空消失"，
     * 而这条丢失没有任何一行日志。归一化之后落点变成"同一相对位置"。
     *
     * <p><b>代价要说出来</b>：速度仍按逻辑像素/秒存（那是 {@code Balance} 的口径，不是这台机器的属性），
     * 所以同一份档在高屏上跑出来的绝对位移比原局大一点。这是"跨机型还原"与"跨机型等价"之间的取舍，
     * 我取前者：位置错是看不见的损失，把 {@code Balance} 的速度改成随屏幕缩放才是真错。
     */
    public static float toStoredY(float y, int bandTop, int bandH) {
        return (y - bandTop) / bandH;
    }

    /** 存储值 → 本机逻辑 y。与 {@link #toStoredY} 严格互逆（同一个 bandTop/bandH 时逐位往返）。 */
    public static float fromStoredY(float stored, int bandTop, int bandH) {
        return bandTop + stored * bandH;
    }

    /** 写一端。{@code bandH} 必须为正——它是除数，0 会把整段坐标变成 NaN 而<b>照样写得出去</b>。 */
    public static void putY(Buf w, float y, int bandTop, int bandH) {
        requireBand(bandH);
        w.writeFloat(toStoredY(y, bandTop, bandH));
    }

    /** 读一端。 */
    public static float getY(Buf.Reader r, int bandTop, int bandH) {
        requireBand(bandH);
        return fromStoredY(r.readFloat(), bandTop, bandH);
    }

    /** 头部两格：魔数与版本。版本不符直接拒读（不做"尽力而为的兼容"，那正是静默错位读的名字）。 */
    public static void putHeader(Buf w) {
        w.writeInt(MAGIC);
        w.writeInt(FORMAT_VERSION);
    }

    public static void readHeader(Buf.Reader r) {
        int magic = r.readInt();
        if (magic != MAGIC) {
            throw new Buf.Invalid("not a Pixel Raider snapshot: magic "
                    + Integer.toHexString(magic) + " != " + Integer.toHexString(MAGIC));
        }
        int version = r.readInt();
        if (version != FORMAT_VERSION) {
            throw new Buf.Invalid("snapshot layout v" + version + " cannot be read by v"
                    + FORMAT_VERSION);
        }
    }

    private static void requireBand(int bandH) {
        if (bandH <= 0) throw new IllegalStateException("battle band height must be > 0: " + bandH);
    }
}
