package com.flexiatom.pixelraider.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.flexiatom.pixelraider.core.RectI;
import org.junit.Test;

/**
 * 暂停面板几何（规格 §四）。期望值一律**手推写死**：这块面板要在 320~560 的每种逻辑高上
 * 把图表与八张卡分账分干净，靠"公式跑出来的结果"验公式等于没验。
 */
public class PauseLayoutTest {

    private final PauseLayout l = new PauseLayout();

    private static void assertRect(String what, RectI r, int left, int top, int right, int bottom) {
        assertEquals(what + " left", left, r.left);
        assertEquals(what + " top", top, r.top);
        assertEquals(what + " right", right, r.right);
        assertEquals(what + " bottom", bottom, r.bottom);
    }

    /** 矮屏（逻辑高 320）整块账：面板 8,6..232,314，内容列 14..226。 */
    @Test
    public void bandsTileTheShortCanvas() {
        l.layout(320, 0, 0);
        assertRect("panel", l.panel, 8, 6, 232, 314);
        assertRect("titleBar", l.titleBar, 14, 10, 226, 38);   // 24 汉字标题 + 上下各 2 呼吸位
        assertRect("tab0", l.tabs[0], 14, 42, 82, 58);
        assertRect("tab1", l.tabs[1], 86, 42, 154, 58);
        assertRect("tab2", l.tabs[2], 158, 42, 226, 58);       // 末列吸收整除余数
        assertRect("chart", l.chart, 14, 62, 226, 124);        // 池 174 → 38%=66 → 钳到 62
        assertRect("card0", l.cards[0], 14, 128, 118, 152);
        assertRect("card1", l.cards[1], 122, 128, 226, 152);
        assertRect("card7", l.cards[7], 122, 212, 226, 236);
        assertRect("resume", l.resume, 39, 242, 200, 264);
        assertRect("achievements", l.achievements, 14, 268, 116, 286);
        assertRect("settings", l.settings, 124, 268, 226, 286);
        assertRect("restart", l.restart, 14, 290, 116, 308);
        assertRect("menu", l.menu, 124, 290, 226, 308);
    }

    /**
     * 2026-09-24 自适应决策：卡片**封顶**，富余量变成上下留白。
     *
     * <p>取代原来的"卡片吃掉图表不要的一切"——那条在画布按屏幕比例长到 533 之后，
     * 把多出的 100px 全灌进 4 行卡片，真机上每张卡中间裂出一大块空白（见状态库记录）。
     */
    @Test
    public void cardsCapAtTheirHeightAndTheSlackBecomesWhitespace() {
        l.layout(320, 0, 0);
        int shortChartH = l.chart.height();
        l.layout(533, 0, 0);
        assertEquals("图表钳在 62，高屏不多给一像素", shortChartH, l.chart.height());
        assertEquals("卡片封顶，不再被画布拉成空壳", PauseLayout.CARD_MAX_H, l.cards[0].height());
        assertRect("panel@533", l.panel, 8, 6, 232, 527);
        assertRect("chart@533 居中在池子里", l.chart, 14, 148, 226, 210);
        assertRect("card0@533", l.cards[0], 14, 214, 118, 248);
        assertRect("card7@533", l.cards[7], 122, 328, 226, 362);
        assertRect("resume@533", l.resume, 39, 455, 200, 477);
        assertRect("menu@533", l.menu, 124, 503, 226, 521);
        // 富余量上下均分：图表上方与卡片下方的留白各半（奇数允许差 1）
        int above = l.chart.top - (l.tabs[0].bottom + 4);
        int below = (l.resume.top - 6) - l.cards[7].bottom;
        assertEquals(86, above);
        assertEquals(87, below);
        assertTrue("留白没有全堆在一头: above=" + above + " below=" + below,
                Math.abs(above - below) <= 1);
    }

    /** 安全区把整块面板从挖孔带让开，但**不改变**面板内部的分账比例。 */
    @Test
    public void safeAreaPushesThePanelDownWithoutDisturbingItsBands() {
        l.layout(533, 18, 0);       // 本机实测：挖孔 52px / pxPerLogic 3.0 → 18 逻辑像素
        assertRect("panel@safe18", l.panel, 8, 24, 232, 527);
        assertRect("titleBar@safe18", l.titleBar, 14, 28, 226, 56);
        assertEquals("卡片高度不受安全区影响", PauseLayout.CARD_MAX_H, l.cards[0].height());
        assertEquals("图表高度不受安全区影响", 62, l.chart.height());
        assertTrue("面板顶边必须让开安全区", l.panel.top >= 18);
    }

    @Test
    public void chartSplitClampsBothWaysAndNeverEatsTheWholePool() {
        assertEquals(0, PauseLayout.chartHeightFor(0));
        assertEquals(30, PauseLayout.chartHeightFor(30));      // 池比下限还小：给到池高，不溢出
        assertEquals(42, PauseLayout.chartHeightFor(60));      // 22.8 → 下限 42
        assertEquals(46, PauseLayout.chartHeightFor(120));     // 45.6 → 46
        assertEquals(62, PauseLayout.chartHeightFor(184));     // 69.9 → 上限 62
        assertEquals(62, PauseLayout.chartHeightFor(900));
    }

    @Test
    public void primaryRowIsSeventySixPercentAndCentered() {
        l.layout(320, 0, 0);
        assertEquals(161, l.primaryWidth());                   // round(212 × 0.76)
        int slackLeft = l.resume.left - l.contentLeft;
        int slackRight = l.contentRight - l.resume.right;
        assertTrue("主操作居中（奇数宽允许 1px 余项）", Math.abs(slackLeft - slackRight) <= 1);
        assertEquals(25, slackLeft);
        assertEquals(26, slackRight);
        assertTrue(l.resume.width() < l.contentWidth);
        assertEquals(l.contentWidth, l.restart.width() + l.menu.width() + PauseLayout.BUTTON_GAP);
    }

    @Test
    public void nothingOverlapsOrInvertsAtAnySupportedCanvasHeight() {
        int[] heights = {320, 375, 430, 533, 560};
        int[] safeTops = {0, 18, 30};
        for (int h : heights) {
            for (int st : safeTops) {
                l.layout(h, st, 0);
                RectI[] all = grouped();
                for (RectI r : all) {
                    assertTrue(h + "/" + st + " 反向矩形会被 drawRect 静默吞掉: " + r,
                            r.top < r.bottom && r.left < r.right);
                    assertTrue(h + "/" + st + " 越出面板: " + r,
                            r.left >= l.panel.left && r.right <= l.panel.right
                                    && r.top >= l.panel.top && r.bottom <= l.panel.bottom);
                    assertTrue(h + "/" + st + " 压进安全区: " + r, r.top >= st);
                }
                assertTrue(h + " tab 压住图表", l.tabs[2].bottom <= l.chart.top);
                assertTrue(h + " 卡片压住图表", l.cards[0].top >= l.chart.bottom);
                assertTrue(h + " 卡片压住按钮", l.cards[7].bottom <= l.resume.top);
                assertTrue(h + " 按钮行互压", l.resume.bottom <= l.achievements.top
                        && l.settings.bottom <= l.restart.top);
                assertTrue(h + " 末行贴出面板底", l.menu.bottom <= l.panel.bottom - PauseLayout.BORDER);
            }
        }
    }

    // 入场节奏（enterProgress / buttonProgress / maskAlphaAt / liftFor）的断言搬到了
    // PanelMotionTest —— 那四块面板共用同一个时钟，测一处就够，而且不该由某一块的几何测试代管。

    @Test
    public void degenerateCanvasHeightStillProducesAusablePanel() {
        l.layout(0, 0, 0);        // metrics 没算出来时的兜底
        assertRect("panel", l.panel, 8, 6, 232, 314);
        l.layout(-40, 0, 0);
        assertRect("panel", l.panel, 8, 6, 232, 314);
    }

    private RectI[] grouped() {
        RectI[] all = new RectI[2 + l.tabs.length + PauseLayout.CARDS + 5];
        all[0] = l.titleBar;
        all[1] = l.chart;
        System.arraycopy(l.tabs, 0, all, 2, l.tabs.length);
        int at = 2 + l.tabs.length;
        System.arraycopy(l.cards, 0, all, at, PauseLayout.CARDS);
        all[at + PauseLayout.CARDS] = l.resume;
        all[at + PauseLayout.CARDS + 1] = l.achievements;
        all[at + PauseLayout.CARDS + 2] = l.settings;
        all[at + PauseLayout.CARDS + 3] = l.restart;
        all[at + PauseLayout.CARDS + 4] = l.menu;
        return all;
    }
}
