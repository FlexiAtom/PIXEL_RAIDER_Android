package com.flexiatom.pixelraider;

import com.flexiatom.pixelraider.core.Time;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 固定步长时钟（规格 §一 / §零 首帧契约）。 */
public class TimeTest {

    private static final float EPS = 1e-5f;

    @Test
    public void oneSecondProducesSixtySteps() {
        Time t = new Time();
        int total = 0;
        for (int frame = 0; frame < 60; frame++) {
            total += t.advance(1f / 60f);
        }
        assertEquals(60, total);
        assertEquals(1f, t.game(), 0.02f);
    }

    @Test
    public void remainderIsKeptNotLost() {
        Time t = new Time();
        // 每帧 1/40s 跑 15 秒：步数必须等于墙钟秒数 / 步长（±1 步只允许是浮点余量）
        int total = 0;
        for (int frame = 0; frame < 600; frame++) {
            total += t.advance(1f / 40f);
        }
        assertTrue("步数漂移: " + total, Math.abs(total - 900) <= 1);
        assertTrue("game 与 ui 时钟漂移: " + t.game() + " vs " + t.ui(),
                Math.abs(t.game() - t.ui()) < 0.05f);
    }

    @Test
    public void giantDeltaIsClampedAndBacklogIsDropped() {
        Time t = new Time();
        // 后台回来 30s：先截断到 0.25s，再受补帧上限约束，绝不逐帧偿还
        int steps = t.advance(30f);
        assertEquals(Time.MAX_CATCHUP_STEPS, steps);
        int next = t.advance(1f / 60f);
        assertTrue("积压未被丢弃: next=" + next, next <= 1);
    }

    @Test
    public void firstFrameAfterResumeIsPinnedToStep() {
        Time t = new Time();
        t.forceNextFrameToOneStep();
        assertEquals(1, t.advance(47.5f));
    }

    @Test
    public void pausedFreezesGameTimeButNotUiTime() {
        Time t = new Time();
        t.setPaused(true);
        int steps = t.advance(1f);
        assertEquals("暂停时不得推进逻辑步", 0, steps);
        assertEquals("暂停时 state.time 必须冻结", 0f, t.game(), EPS);
        // uiTime 走的是截断后的 d（0.25s），不是真实 1s——它只负责"动画别僵住"，不负责追时间
        assertTrue("暂停时 uiTime 必须照走，否则暂停菜单动画僵住", t.ui() > 0.24f && t.ui() < 0.26f);

        t.setPaused(false);
        assertTrue(t.advance(1f / 60f) >= 1);
        assertTrue(t.game() > 0f);
    }

    @Test
    public void negativeDeltaIsIgnored() {
        Time t = new Time();
        assertEquals(0, t.advance(-5f));
    }
}
