package com.flexiatom.pixelraider;

import com.flexiatom.pixelraider.plat.Screen;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** 尺寸契约（规格 §零）。纯函数，不碰设备。 */
public class ScreenTest {

    @Test
    public void logicHeightGrowsWithTheScreenInsteadOfClamping() {
        // 1080x2400 的 aspect = 2.222 → round(240*2.222)=533。
        // 2026-09-24 适配决策前这里钳在 430，多出来的比例被切成上下黑边；现在让它长满。
        // 规格 §零 写的 "典型 1080x2400：buffer = 480x640" 对应 logicH=320，与公式矛盾，
        // 这里以公式为准，并把矛盾钉成一条断言。
        assertEquals(533, Screen.logicHeightFor(1080, 2400));
    }

    @Test
    public void logicHeightClampsAtBothEnds() {
        assertEquals(Screen.LOGIC_H_MIN, Screen.logicHeightFor(1080, 1350)); // aspect 1.25 → 300 → 320
        assertEquals(360, Screen.logicHeightFor(1080, 1620));                // aspect 1.5  → 360
        assertEquals(Screen.LOGIC_H_MIN, Screen.logicHeightFor(0, 0));        // 非法输入不抛
        // 上限仍要在：越过 21:9 的极少数异形屏退回黑边，而不是把面板布局撑到没验证过的区间
        assertEquals(Screen.LOGIC_H_MAX, Screen.logicHeightFor(400, 2400));   // aspect 6 → 1440 → 560
    }

    @Test
    public void scaleIsIntegerFloorAndCapped() {
        assertEquals(2, Screen.scaleFor(1080, 2400, 2));   // min(4.5, 7.5)=4 → 钳 2
        assertEquals(2, Screen.scaleFor(720, 1280, 2));   // min(3, 4)=3 → 钳 2
        assertEquals(1, Screen.scaleFor(400, 700, 2));    // min(1, 2)=1
        assertEquals(1, Screen.scaleFor(1080, 2400, 1));  // 低端机兜底钉死 1
    }

    @Test
    public void pixelsStaySquareAfterAdaptation() {
        Screen.Metrics m = Screen.compute(1080, 2400, 0, 0, 0, 0, 2);
        assertEquals(480, m.bufW);
        assertEquals(1066, m.bufH);
        // 方形像素：横向与纵向的"逻辑像素→物理像素"必须一致（误差只允许整除余数 1px）
        assertTrue("pxPerLogic 横纵不一致 = 像素被拉伸: " + m.pxPerLogicX + " vs " + m.pxPerLogicY,
                Math.abs(m.pxPerLogicX - m.pxPerLogicY) < 0.01f);
    }

    /**
     * 适配决策的回归哨兵：常见手机比例都必须**既无黑边、又不拉伸**。
     * 留 2px 是给 round 的余量；真变大就是 logicH 生长失效了。
     */
    @Test
    public void commonPhoneAspectsFillTheScreenWithoutBarsOrStretch() {
        int[][] phones = {
                {720, 1600},    // 本机实测：Redmi 23124RN87C
                {1080, 2400},   // 20:9
                {1080, 2340},   // 19.5:9
                {1080, 1920},   // 16:9（老机型，本来就没黑边）
                {1440, 3200},   // 20:9 高位移
                {720, 1280},    // 16:9 低配
                {411 * 3, 891 * 3},
        };
        for (int[] p : phones) {
            Screen.Metrics m = Screen.compute(p[0], p[1], 0, 0, 0, 0, 2);
            assertEquals(p[0] + "x" + p[1] + " 黑边 " + m.letterboxBarsPx() + "px",
                    true, m.letterboxBarsPx() <= 2);
            assertTrue(p[0] + "x" + p[1] + " 像素被拉伸: " + m.pxPerLogicX + " vs " + m.pxPerLogicY,
                    Math.abs(m.pxPerLogicX - m.pxPerLogicY) < 0.01f);
        }
    }

    @Test
    public void touchMappingSubtractsOffsetThenDividesScale() {
        // 规格 §九.12：忘记减 offset / 除 scale 会让命中整体偏移
        Screen.Metrics m = Screen.compute(1080, 2400, 0, 78, 0, 48, 2);
        for (int logic = 0; logic < Screen.LOGIC_W; logic += 17) {
            float raw = m.surfaceX + logic * m.pxPerLogicX;
            assertEquals(logic, m.toLogicX(raw));
        }
        int top = m.battleTop();
        assertEquals(top, m.toLogicY(m.surfaceY + top * m.pxPerLogicY));
    }

    /**
     * 安全区只推 UI，不推画面（2026-09-24 适配决策的后半段）。
     *
     * <p>数值取自本机实测：Redmi 23124RN87C 720x1600，挖孔 {@code insets=(0,52)}，
     * 孔体 x 320..400。改决策之前这 52px 是从 surface 里减掉的，于是屏幕顶上一条纯黑带。
     */
    @Test
    public void safeAreaPushesUiDownButNotTheSurface() {
        Screen.Metrics m = Screen.compute(720, 1600, 0, 52, 0, 0, 2);
        assertEquals("画面仍然铺满整窗", 720, m.surfaceW);
        assertEquals("画面仍然铺满整窗", 1599, m.surfaceH);
        assertEquals("黑边只剩 rounding 余量", 1, m.letterboxBarsPx());
        assertEquals(533, m.logicH);
        assertEquals(3.0f, m.pxPerLogicY, 0.01f);
        assertEquals(18, m.safeTop);                 // ceil(52 / 3.0)
        assertEquals(0, m.safeBottom);
        assertEquals("HUD 顶边就是安全区顶边", 18, m.hudTop());
        assertEquals("战场顶边让开挖孔", 18, m.battleTop());
        assertEquals("整页模态按自报页高在画布里居中", 106, m.pageTop(320));
    }

    /**
     * 操作区域 = 整张画布（2026-09-24 适配决策第二步）。
     *
     * <p>这一组数字是"战机飞不出中间那条带"的直接反证：战场顶边贴安全区、底边贴画布下沿，
     * 高屏多出来的竖向空间全部归玩家用，而不是留在 HUD 上面和战场下面当空白。
     */
    @Test
    public void playfieldIsTheWholeCanvasNotACentredBand() {
        Screen.Metrics tall = Screen.compute(1080, 2400, 0, 0, 0, 0, 2);
        assertEquals(0, tall.battleTop());
        assertEquals("533 高的画布就是 533 高的战场，不再是 320", 533, tall.battleHeight());
        assertEquals(533, tall.battleBottom());
        assertEquals("整页模态才居中", (533 - 320) / 2, tall.pageTop(320));

        Screen.Metrics square = Screen.compute(1080, 1350, 0, 0, 0, 0, 2);
        assertEquals(0, square.battleTop());
        assertEquals(320, square.battleHeight());

        Screen.Metrics notched = Screen.compute(1080, 2400, 0, 78, 0, 48, 2);
        assertEquals(18, notched.safeTop);
        assertEquals(11, notched.safeBottom);
        assertEquals("顶边让开挖孔", notched.safeTop, notched.battleTop());
        assertEquals("底边让开手势条，那是战机能飞到的最下沿", 522, notched.battleBottom());
        assertEquals(notched.battleBottom() - notched.battleTop(), notched.battleHeight());
    }

    @Test
    public void minTouchFloorMatchesSpecExample() {
        // 1080 宽、density 2.75：48dp = 132px，一个逻辑像素 4.5px → 需 30 逻辑像素
        int minLogic = Screen.minTouchLogic(1080f / Screen.LOGIC_W, 2.75f);
        assertEquals(30, minLogic);
        assertNotEquals(0, minLogic);
        // 暂停按钮只画 18 → 必须外扩，否则低于 48dp 触控标准
        assertTrue(18 < minLogic);
    }

    @Test
    public void sameShapeMatchesEveryIndependentFieldAndNothingElse() {
        Screen.Metrics base = Screen.compute(720, 1600, 0, 52, 0, 63, 2);
        // 重复投递的是新对象、同几何：这是 GameSurfaceView 敢早退的唯一依据
        assertTrue(base.sameShapeAs(Screen.compute(720, 1600, 0, 52, 0, 63, 2)));
        assertTrue(base.sameShapeAs(base));
        assertFalse(base.sameShapeAs(null));
        // 四边 inset 各自都能单独把几何推走：漏一个就会把"安全区变了"误判成"什么都没变"
        assertFalse(base.sameShapeAs(Screen.compute(720, 1600, 7, 52, 0, 63, 2)));
        assertFalse(base.sameShapeAs(Screen.compute(720, 1600, 0, 120, 0, 63, 2)));
        assertFalse(base.sameShapeAs(Screen.compute(720, 1600, 0, 52, 7, 63, 2)));
        assertFalse(base.sameShapeAs(Screen.compute(720, 1600, 0, 52, 0, 120, 2)));
        // 窗口尺寸与 scaleCap 走的是另外两条岔口（logicH/scale 由它们导出）
        assertFalse(base.sameShapeAs(Screen.compute(720, 1601, 0, 52, 0, 63, 2)));
        assertFalse(base.sameShapeAs(Screen.compute(720, 1600, 0, 52, 0, 63, 1)));
    }
}
