package com.flexiatom.pixelraider;

import com.flexiatom.pixelraider.core.InputRouter;
import com.flexiatom.pixelraider.core.ModalStack;
import com.flexiatom.pixelraider.core.RectI;
import com.flexiatom.pixelraider.core.Scheduler;
import com.flexiatom.pixelraider.plat.Screen;
import com.flexiatom.pixelraider.ui.Widgets;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 输入环 + 模态栈 + 定时器 + 命中布局（规格 §二 / §八 / §九.20）。 */
public class InputAndUiMathTest {

    @Test
    public void ringPreservesOrderAndDropsWhenFull() {
        InputRouter r = new InputRouter();
        for (int i = 0; i < 100; i++) {
            assertTrue(r.offerLogic(InputRouter.EV_KEY_DOWN, -1, i, 0, 0));
        }
        assertEquals(100, r.pending());
        InputRouter.Event e = new InputRouter.Event();
        for (int i = 0; i < 100; i++) {
            assertTrue(r.poll(e));
            assertEquals("FIFO 顺序被打乱", i, e.x);
        }
        assertFalse(r.poll(e));

        // 灌满后必须拒绝而不是扩容（扩容 = 帧内分配）；SPSC 环留一格做"满/空"判据，故 255
        for (int i = 0; i < 300; i++) {
            r.offerLogic(InputRouter.EV_POINTER_MOVE, 0, i, 0, 0);
        }
        assertEquals(255, r.pending());
        assertFalse(r.offerLogic(InputRouter.EV_POINTER_MOVE, 0, 999, 0, 0));
    }

    @Test
    public void rawPixelsAreConvertedToLogicAtEnqueueTime() {
        InputRouter r = new InputRouter();
        Screen.Metrics m = Screen.compute(1080, 2400, 0, 78, 0, 48, 2);
        r.setMetrics(m);
        r.offer(InputRouter.EV_POINTER_DOWN, 0, m.surfaceX + m.pxPerLogicX * 100f,
                m.surfaceY + m.pxPerLogicY * 200f, 0);
        InputRouter.Event e = new InputRouter.Event();
        assertTrue(r.poll(e));
        assertEquals(100, e.x);
        assertEquals(200, e.y);
    }

    @Test
    public void hitRectGrowsAroundTheDrawingRect() {
        RectI draw = new RectI(214, 2, 232, 20);      // 18x18 暂停按钮
        RectI hit = new RectI();
        Widgets.hitRect(draw, 34, hit);
        assertEquals(34, hit.width());
        assertEquals(34, hit.height());
        assertEquals(draw.centerX(), hit.centerX());   // 只外扩，不挪位置
        assertEquals(draw.centerY(), hit.centerY());
        assertFalse(hit.contains(0, 0));
        assertFalse(hit.contains(200, 1));             // 外扩后仍不覆盖左上远端
        assertTrue(hit.contains(206, -6));             // 外扩出来的那圈透明 padding 也算命中
        assertTrue(hit.contains(239, 27));
        assertFalse(Widgets.meetsTouchFloor(draw, 34)); // 18x18 未达 48dp
        assertTrue(Widgets.meetsTouchFloor(hit, 34));
    }

    @Test
    public void drawingAndHitReadTheSameRect() {
        // 规格 §八：命中区与绘制读同一份矩形。布局端写回 layout，命中端直接用同一坐标断言。
        RectI layout = new RectI();
        Widgets.gridCell(10, 10, 100, 40, 2, 1, 4, 1, layout);
        assertEquals(62, layout.left);
        assertEquals(110, layout.right); // 末列吸收余数，右侧不留缝
        assertEquals(10, layout.top);
        assertEquals(50, layout.bottom);

        RectI hit = new RectI();
        Widgets.hitRect(layout, 44, hit);
        // 手算：宽 48 已 >= 44 不外扩；高 40 → 外扩 4，上下各 2 → [62,8,110,52]
        assertEquals(48, hit.width());
        assertEquals(44, hit.height());
        assertEquals(8, hit.top);
        assertEquals(52, hit.bottom);
        // 命中端读的就是这份数：边界内命中、边界外不命中
        assertTrue(hit.contains(62, 10));
        assertFalse(hit.contains(61, 10));
        assertFalse(hit.contains(62, 52));
    }

    @Test
    public void equalHalvesAreSymmetricAndSameHeight() {
        RectI l = new RectI();
        RectI rr = new RectI();
        Widgets.equalHalves(8, 300, 224, 22, 8, l, rr);
        assertEquals(l.height(), rr.height());
        assertEquals(l.width(), rr.width());
        assertEquals(8, l.left);
        assertEquals(232, rr.right);
        assertEquals(8, rr.left - l.right); // gap
    }

    @Test
    public void modalStackTopDecidesAndEmptyMeansBackToGame() {
        ModalStack s = new ModalStack();
        assertTrue(s.isEmpty());
        s.push(ModalStack.MENU);
        s.push(ModalStack.SETTINGS);
        assertEquals(ModalStack.SETTINGS, s.peek());
        s.push(ModalStack.SETTINGS); // 重复入栈必须被吃掉
        assertEquals(2, s.size());
        assertEquals(ModalStack.SETTINGS, s.pop());
        assertEquals(ModalStack.MENU, s.pop());
        assertEquals(ModalStack.NONE, s.pop());
        assertTrue(s.isEmpty());
    }

    @Test
    public void schedulerRunsOnGameTimeNotWallClock() {
        Scheduler sc = new Scheduler();
        int[] out = new int[4];
        sc.scheduleAfter(1.5f, 7, 10f);
        assertEquals(0, sc.fireDue(10.5f, out, 4));
        assertEquals(1, sc.fireDue(11.6f, out, 4));
        assertEquals(7, out[0]);
        assertEquals(0, sc.pending());

        // 同 id 重复登记只保留一条（延迟特效不该因连点堆叠）
        sc.scheduleAfter(1f, 9, 0f);
        sc.scheduleAfter(1f, 9, 0f);
        assertEquals(1, sc.pending());
        assertEquals(1, sc.fireDue(1.5f, out, 4));
        assertEquals(9, out[0]);
        assertEquals(0, sc.pending());

        // 暂停语义：game 时间不前进就永不触发（Handler.postDelayed 做不到的事）
        sc.scheduleAfter(2f, 11, 100f);          // 到期于 102
        assertEquals("未到期不得触发", 0, sc.fireDue(101f, out, 4));
        assertEquals(1, sc.fireDue(102.5f, out, 4));
        assertEquals(11, out[0]);
        sc.clear();
        assertEquals(0, sc.pending());
    }
}
