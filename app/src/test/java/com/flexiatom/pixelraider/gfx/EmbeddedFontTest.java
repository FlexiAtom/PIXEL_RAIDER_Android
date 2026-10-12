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
package com.flexiatom.pixelraider.gfx;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.flexiatom.pixelraider.ui.BriefingLayout;
import com.flexiatom.pixelraider.ui.HudLayout;
import com.flexiatom.pixelraider.ui.Md3;

import static org.junit.Assert.assertTrue;

/**
 * 内嵌中文字体的**词表覆盖**（2026-09-24「只中文用字体」决策的地基）。
 *
 * <p>像素中文只能按原生网格渲染，缺一个字就掉回系统字——那一格的像素味当场断裂，
 * 而且断在玩家最容易看到的地方（"击杀"的"杀"、状态名"狂热"的"热"、Boss 名"毁灭者"的"毁"）。
 * 字库升级、文案新增、换 region 变体都可能悄悄把覆盖打掉，所以这条查**真实字面量**：
 * 扫 {@code src/main/java} 全部字符串字面量（先剥注释），凡是非 ASCII 的字符，资产里必须有。
 *
 * <p><b>2026-10-13 起这里是逐档核对的</b>（字体成了两档原生面，见 {@code TextCache}）：简报文案那一个
 * 文件的字面量查 8px 那张脸，其余一切查 12px 那张子集。合起来查（并集）等于没查——12px 那份为正式版
 * 切回小词表的那天，简报缺字会一个字都不报，而它正是<b>唯一</b>会缺字的那一屏：这批文案是新写的，
 * 切子集时按老词表切。犯过的实例：2026-10-13 简报八张稿落码后全量跑才报出 10 处缺字
 * （军 招 练 熟 望 挨 睛 利 扛——「————军事行动: 」的"军"在内），而单跑简报那三个测试类全绿。
 *
 * <p>分档判据是**文件名**，不是"这段文案按哪档画"——静态扫不到那个数。它的失效方向是安全的：
 * 把 8px 的字面量抄进别的文件，它会当场受 12px 那道闸检查，缺字就报（今天那九个字正是这么报的）。
 *
 * <p>为什么自己解析 sfnt：AGP 把单测的 bootclasspath 换成 android.jar，那里没有
 * {@code java.awt.Font}——想读 cmap 只剩两条路，要么引第三方字体库（规格 §零 只准两个 AndroidX
 * 依赖），要么把这八十行读完就扔的表解析写在这里。选后者。
 *
 * <p>字体与源码都从工作树按文件读（见 {@link #moduleDir()}）：单测跑在 JVM 上，
 * {@code assets/} 不是它的 classpath 资源，AGP 只把 {@code src/test/resources} 挂进去。
 */
public final class EmbeddedFontTest {

    /** 资产路径与运行时读的那两个常量同源（编译期常量，javac 内联，不触发 {@code TextCache} 初始化）。 */
    private static final String ASSET = TextCache.ASSET;
    private static final String ASSET_SMALL = TextCache.ASSET_SMALL;
    /** 简报文案：全仓唯一按 8px 档上屏的字面量住的地方。 */
    private static final String SMALL_FACE_FILE = "CampaignBriefing.java";
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");

    @Test
    public void everyNonAsciiLiteralIsInkedByItsOwnFace() throws Exception {
        Sfnt large = loadFace(ASSET);
        Sfnt small = loadFace(ASSET_SMALL);
        List<String> missing = new ArrayList<>();
        int checked = 0, checkedLarge = 0, checkedSmall = 0;
        for (Path f : Files.walk(moduleDir().resolve("src/main/java"))
                .filter(Files::isRegularFile).toList()) {
            boolean smallFace = f.getFileName().toString().equals(SMALL_FACE_FILE);
            Sfnt face = smallFace ? small : large;
            String src = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            Matcher m = LITERAL.matcher(stripComments(src));
            while (m.find()) {
                String lit = m.group(1);
                for (int i = 0; i < lit.length(); i++) {
                    char ch = lit.charAt(i);
                    if (ch < 0x80) continue;
                    checked++;
                    if (smallFace) checkedSmall++; else checkedLarge++;
                    if (!face.hasGlyphFor(ch)) {
                        missing.add(ch + "(U+" + Integer.toHexString(ch) + ") @ "
                                + f.getFileName() + " 「" + lit + "」 需 "
                                + (smallFace ? TextCache.GRID_SMALL : TextCache.GRID_PX) + "px 档");
                    }
                }
            }
        }
        assertTrue("内嵌字体缺字（" + missing.size() + " 处）：\n" + String.join("\n", missing),
                missing.isEmpty());
        // 挡"扫描逻辑空转"：字面量解析一旦失效，上面这条会绿得毫无意义。
        // 这条注释先前写着"232"，那个数早已过期（它只数 HUD 标签、结算页文案、Boss 名，而商店十一张卡的
        // name/tip/desc 是后来才成批进字面量的）。2026-09-27 用**本类同一条口径**复算（剥注释 + 同一条
        // LITERAL 正则，逐字符数 ≥0x80）得 635，其中 Balance.java 一份就占 404。真源是下面这个
        // checked，不是注释里的任何一个读数——所以这里只留量级，别再把某个总数抄成事实。
        // 阈值取 150：留得住"改文案减字"，也拦得住"正则不再匹配"。
        assertTrue("扫到 " + checked + " 个非 ASCII 字面量字符，太少说明提取失效", checked > 150);
        // 逐档各挡一次空转：分档路由若坏成"全查一张脸"，上面那条仍会绿，而另一档从此一字不查。
        assertTrue("查到 12px 档的字符只有 " + checkedLarge + " 个，路由或正则失效", checkedLarge > 150);
        assertTrue("查到 8px 档（简报文案）的字符只有 " + checkedSmall
                + " 个，说明简报稿不住 " + SMALL_FACE_FILE + " 了——分档判据得跟着换", checkedSmall > 20);
        assertTrue("ASCII 也必须在（等宽像素字里半格），'A' 缺了说明子集切错", large.hasGlyphFor('A'));
        assertTrue("8px 档同理要含 ASCII", small.hasGlyphFor('A'));
    }

    /**
     * 行高必须正好落进标签槽：烘焙贴图高 = ascent + descent，它等于 {@link HudLayout#LABEL_H}
     * 与 {@link Md3#PX_LABEL} 时，HUD 四条状态条才"相邻两行恰好相切"。
     * 字库换版本把行高偷偷改成 13/14，这条会先响。
     */
    @Test
    public void bakedLineHeightMatchesTheLabelSlotItIsLaidInto() throws Exception {
        Sfnt face = loadFace(ASSET);
        int line = face.lineHeightPx(Md3.PX_LABEL);
        assertTrue("行高 " + line + " 与 PX_LABEL=" + Md3.PX_LABEL
                        + " / LABEL_H=" + HudLayout.LABEL_H + " 不是一套网格",
                line == Md3.PX_LABEL && HudLayout.LABEL_H == HudLayout.BAR_PITCH);
    }

    /** 汉字整格、ASCII 半格——LABEL_W 是按"两个汉字"算的，宽度规则变了就得重排。 */
    @Test
    public void cjkAdvancesAreFullGridAndAsciiIsHalf() throws Exception {
        Sfnt face = loadFace(ASSET);
        assertTrue(face.advancePx('国', Md3.PX_LABEL) == Md3.PX_LABEL);
        assertTrue(face.advancePx('A', Md3.PX_LABEL) == Md3.PX_LABEL / 2);
    }

    /**
     * 8px 那张脸必须是**原生** 8px，不是 12px 缩出来的：行高 8、汉字整格、ASCII 半格。
     *
     * <p>简报那套版面数全建在这三个数上——{@code BriefingLayout.LINE_STEP = GLYPH_H + 2} 的"字身 8"、
     * 折行宽 210 容 26 字。缩放出来的 8px 会把这些关系变成"约等于"（他 2026-10-12 否掉缩放档的理由）。
     * 最后那一条同时钉住"简报字身 = 小档网格"：两张脸各自的档位与两处版面量不许各自漂。
     *
     * <p>{@code TextCache.snapToGrid}（请求值 → 档位）在这里查不了：调它的方法会触发 {@code TextCache}
     * 初始化，而 {@code BAKE} 是 {@code android.graphics.Paint}，AGP 的 mockable android.jar 里
     * 构造函数直接抛 {@code Stub!}。常量引用不受此害（javac 内联，不初始化类），所以上面读得到
     * {@code TextCache.GRID_SMALL}。规格 §九.20 那条"版面是纯算术、能在 JVM 上跑"的边界止于此：
     * 像素网格的**物理量**在这里查得，**吸附规则**只能在真机上看。
     */
    @Test
    public void theSmallFaceIsNativeToItsOwnGrid() throws Exception {
        Sfnt face = loadFace(ASSET_SMALL);
        int px = TextCache.GRID_SMALL;
        assertTrue("8px 档行高 " + face.lineHeightPx(px) + " ≠ " + px,
                face.lineHeightPx(px) == px);
        assertTrue("简报行距按字身 " + BriefingLayout.GLYPH_H + " 算，字库给的是另一档",
                face.lineHeightPx(px) == BriefingLayout.GLYPH_H);
        assertTrue(face.advancePx('国', px) == px);
        assertTrue(face.advancePx('A', px) == px / 2);
    }

    private static Sfnt loadFace(String asset) throws IOException {
        return new Sfnt(Files.readAllBytes(moduleDir().resolve("src/main/assets/" + asset)));
    }

    /**
     * 字体与源码都按文件读，不走 classpath：AGP 只把 {@code src/test/resources} 挂成 JVM
     * 测试的 java-resource 根，{@code assets/} 不在里面（那是 APK 打包的东西）。
     * 至于从哪一层起算，AGP 版本间 {@code user.dir} 时而是模块目录、时而是仓根，
     * 所以向上找"这一层有那两枚 .otf"而不是猜——顺带把源码扫描的根也钉在同一处。
     */
    private static Path moduleDir() {
        for (Path p = Paths.get("").toAbsolutePath(); p != null; p = p.getParent()) {
            for (Path cand : new Path[]{p, p.resolve("app")}) {
                if (Files.isRegularFile(cand.resolve("src/main/assets/" + ASSET))
                        && Files.isRegularFile(cand.resolve("src/main/assets/" + ASSET_SMALL))) return cand;
            }
        }
        throw new IllegalStateException("找不到内嵌字体 " + ASSET + " 或 " + ASSET_SMALL
                + "，起始目录 " + Paths.get("").toAbsolutePath());
    }

    /**
     * 剥掉注释再扫——Javadoc 里写"同步加大相关的 UI 部分"这种带引号的话，不是要渲染的字面量。
     * {@code (?s)} 是给块注释的：不开单行模式的话，{@code .} 不跨行，而 Javadoc 恰恰是多行的，
     * 于是注释里的引号会被当成字面量的起止、下一行接着匹配，报出一堆根本不上屏的"缺字"。
     */
    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }

    /**
     * 只读到够用为止的 sfnt 读取器：表目录 + cmap（format 4 / 12）+ hhea/head/hmtx 的度量。
     * 支持 TTC 与否都不管——我们的子集是单个 OTF/TTF。
     */
    private static final class Sfnt {
        private final ByteBuffer buf;
        private int cmapAt = -1;
        private int format = -1;
        private int unitsPerEm;
        private int ascent;
        private int descent;

        Sfnt(byte[] data) {
            buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            String tag = new String(data, 0, 4, StandardCharsets.US_ASCII);
            assertTrue("不是 sfnt（读到了 " + tag + "）",
                    "OTTO".equals(tag) || "\u0000\u0001\u0000\u0000".equals(tag)
                            || "true".equals(tag) || "ttcf".equals(tag) == false);
            int numTables = Short.toUnsignedInt(buf.getShort(4));
            for (int i = 0; i < numTables; i++) {
                int rec = 12 + i * 16;
                String t = new String(data, rec, 4, StandardCharsets.US_ASCII);
                int off = buf.getInt(rec + 8);
                if ("cmap".equals(t)) cmapAt = off;
                if ("head".equals(t)) unitsPerEm = Short.toUnsignedInt(buf.getShort(off + 18));
                if ("hhea".equals(t)) {
                    ascent = buf.getShort(off + 4);
                    descent = buf.getShort(off + 6);
                }
            }
            assertTrue("没有 cmap", cmapAt >= 0);
            assertTrue("没有 head/hhea", unitsPerEm > 0);
            pickCmapSubtable();
        }

        /** 取能覆盖全码位的子表：先 3/10（format 12），再 0/任何，最后 3/1（format 4）。 */
        private void pickCmapSubtable() {
            int n = Short.toUnsignedInt(buf.getShort(cmapAt + 2));
            int best = -1;
            int bestFormat = -1;
            for (int i = 0; i < n; i++) {
                int rec = cmapAt + 4 + i * 8;
                int pid = Short.toUnsignedInt(buf.getShort(rec));
                int eid = Short.toUnsignedInt(buf.getShort(rec + 2));
                int off = cmapAt + buf.getInt(rec + 4);
                int fmt = Short.toUnsignedInt(buf.getShort(off));
                boolean prefer = best < 0
                        || (fmt == 12 && bestFormat != 12)
                        || (fmt == bestFormat && pid == 3 && eid == 10);
                if (prefer && (fmt == 4 || fmt == 12)) {
                    best = off;
                    bestFormat = fmt;
                }
            }
            assertTrue("cmap 里只有 format 4/12 之外的表", best >= 0);
            cmapAt = best;
            format = bestFormat;
        }

        boolean hasGlyphFor(int cp) {
            return format == 12 ? lookup12(cp) : lookup4(cp);
        }

        private boolean lookup12(int cp) {
            int groups = buf.getInt(cmapAt + 12);
            int lo = 0, hi = groups - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int g = cmapAt + 16 + mid * 12;
                int start = buf.getInt(g);
                int end = buf.getInt(g + 4);
                if (cp < start) hi = mid - 1;
                else if (cp > end) lo = mid + 1;
                else return buf.getInt(g + 8) != 0;
            }
            return false;
        }

        private boolean lookup4(int cp) {
            int segCount = Short.toUnsignedInt(buf.getShort(cmapAt + 6)) / 2;
            int ends = cmapAt + 14;
            int starts = ends + segCount * 2 + 2;
            int deltas = starts + segCount * 2;
            int rangeOff = deltas + segCount * 2;
            for (int s = 0; s < segCount; s++) {
                int end = Short.toUnsignedInt(buf.getShort(ends + s * 2));
                if (cp > end) continue;
                int start = Short.toUnsignedInt(buf.getShort(starts + s * 2));
                if (cp < start) return false;
                int ro = Short.toUnsignedInt(buf.getShort(rangeOff + s * 2));
                if (ro == 0) return (Short.toUnsignedInt(buf.getShort(deltas + s * 2)) + cp & 0xFFFF) != 0;
                int gi = rangeOff + s * 2 + ro + (cp - start) * 2;
                return Short.toUnsignedInt(buf.getShort(gi)) != 0;
            }
            return false;
        }

        /** 原生网格的行高（px）：(ascent − descent) 按比例缩到目标字号。 */
        int lineHeightPx(int px) {
            return (int) Math.ceil((double) (ascent - descent) * px / unitsPerEm);
        }

        /** 前进宽度（px）：读 hmtx，按 cmap 给的 glyph id 索引；等宽字体里 国=12、A=6。 */
        int advancePx(int cp, int px) {
            int gid = glyphId(cp);
            int hmtx = table("hmtx");
            int numH = Short.toUnsignedInt(buf.getShort(4)) == 0 ? 0 : numberOfHorMetrics();
            int idx = Math.min(gid, numH - 1);
            int adv = Short.toUnsignedInt(buf.getShort(hmtx + idx * 4));
            return (int) Math.round((double) adv * px / unitsPerEm);
        }

        private int numberOfHorMetrics() {
            int hhea = table("hhea");
            return Short.toUnsignedInt(buf.getShort(hhea + 34));
        }

        private int glyphId(int cp) {
            if (format == 12) {
                int groups = buf.getInt(cmapAt + 12);
                for (int i = 0; i < groups; i++) {
                    int g = cmapAt + 16 + i * 12;
                    if (cp >= buf.getInt(g) && cp <= buf.getInt(g + 4)) {
                        return buf.getInt(g + 8) + (cp - buf.getInt(g));
                    }
                }
                return 0;
            }
            int segCount = Short.toUnsignedInt(buf.getShort(cmapAt + 6)) / 2;
            int ends = cmapAt + 14;
            int starts = ends + segCount * 2 + 2;
            int deltas = starts + segCount * 2;
            int rangeOff = deltas + segCount * 2;
            for (int s = 0; s < segCount; s++) {
                int end = Short.toUnsignedInt(buf.getShort(ends + s * 2));
                if (cp > end) continue;
                int start = Short.toUnsignedInt(buf.getShort(starts + s * 2));
                if (cp < start) return 0;
                int ro = Short.toUnsignedInt(buf.getShort(rangeOff + s * 2));
                if (ro == 0) return (Short.toUnsignedInt(buf.getShort(deltas + s * 2)) + cp) & 0xFFFF;
                int gi = rangeOff + s * 2 + ro + (cp - start) * 2;
                int v = Short.toUnsignedInt(buf.getShort(gi));
                return v == 0 ? 0 : (v + Short.toUnsignedInt(buf.getShort(deltas + s * 2))) & 0xFFFF;
            }
            return 0;
        }

        private int table(String tag) {
            int n = Short.toUnsignedInt(buf.getShort(4));
            for (int i = 0; i < n; i++) {
                int rec = 12 + i * 16;
                if (tag.equals(new String(buf.array(), rec, 4, StandardCharsets.US_ASCII))) {
                    return buf.getInt(rec + 8);
                }
            }
            throw new AssertionError("没有表 " + tag);
        }
    }
}
