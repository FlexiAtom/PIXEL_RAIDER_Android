package com.flexiatom.pixelraider.game;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 五维评级（规格 §四"各自归一，加权合成字母等级"）。
 * 期望值全部手推写死，不在测试里重算同一套公式（规格 §九.20）。
 */
public class RatingTest {

    private static Balance.Grade g() {
        return new Balance.Grade();
    }

    @Test
    public void weightsSumToOne() {
        // 权重和不为 1 的话"满分参照"就不是 S，整套等级会整体偏移——改表必须过这条
        Balance.Grade g = g();
        float sum = 0f;
        for (int i = 0; i < Rating.COUNT; i++) sum += Rating.weightOf(i, g);
        assertEquals(1f, sum, 1e-6f);
    }

    @Test
    public void emptyRunIsDNotCrash() {
        Balance.Grade g = g();
        RunStats s = new RunStats();
        float[] d = new float[Rating.COUNT];
        Rating.dimensions(s, 0, g, d);
        for (int i = 0; i < Rating.COUNT; i++) assertEquals(0f, d[i], 0f);
        assertEquals(0, Rating.letterIndex(Rating.overall(d, g), g));
        assertEquals('D', Rating.letterOf(Rating.letterIndex(Rating.overall(d, g), g)));
    }

    @Test
    public void referenceRunScoresExactlyS() {
        Balance.Grade g = g();
        RunStats s = new RunStats();
        s.kills = 280;
        s.addShots(100);
        s.addHit();
        for (int i = 0; i < 99; i++) s.addHit();
        s.overloadUses = 6;
        s.peakCombo = 20;
        float[] d = new float[Rating.COUNT];
        Rating.dimensions(s, 20, g, d);
        for (int i = 0; i < Rating.COUNT; i++) {
            assertEquals("第 " + i + " 维没到参照值", 1f, d[i], 1e-6f);
        }
        assertEquals(1f, Rating.overall(d, g), 1e-6f);
        assertEquals(4, Rating.letterIndex(Rating.overall(d, g), g));
        assertEquals('S', Rating.letterOf(4));
    }

    @Test
    public void deathsAreChargedAgainstSurvival() {
        Balance.Grade g = g();          // deathCostWaves=2, survivalWaves=20
        // 清 20 波但死了 5 次：抵掉 10 波 → 0.5
        assertEquals(0.5f, Rating.survival(20, 5, g), 1e-6f);
        // 抵过头不为负（负分没有意义，且会把总分拉到权重之外）
        assertEquals(0f, Rating.survival(2, 5, g), 0f);
        // 超纲成绩钳在 1，不让"打到 40 波"把总分顶出表外
        assertEquals(1f, Rating.survival(40, 0, g), 0f);
    }

    @Test
    public void zeroReferenceScoresZeroNotInfinity() {
        Balance.Grade g = g();
        g.killRef = 0f;                 // 调试面板可以把参照改成 0
        RunStats s = new RunStats();
        s.kills = 123;
        assertEquals(0f, Rating.kill(s, g), 0f);
        float[] d = new float[Rating.COUNT];
        d[Rating.KILL] = Rating.kill(s, g);
        assertEquals(0f, Rating.overall(d, g), 1e-6f);   // 不得是 NaN / Infinity
    }

    @Test
    public void efficiencyIsAccuracyAndStaysFinite() {
        RunStats s = new RunStats();
        assertEquals(0f, Rating.efficiency(s), 0f);      // 一枪未开：0 而不是 NaN
        s.addShots(8);
        s.addHit();
        s.addHit();
        assertEquals(0.25f, Rating.efficiency(s), 1e-6f);
        s.shotsHit = 99;                                 // 记账错乱也不能越出 0..1
        assertEquals(1f, Rating.efficiency(s), 0f);
    }

    @Test
    public void letterBandsMatchTable() {
        Balance.Grade g = g();          // S .85 / A .70 / B .52 / C .34
        assertEquals(4, Rating.letterIndex(0.85f, g));
        assertEquals(3, Rating.letterIndex(0.70f, g));
        assertEquals(2, Rating.letterIndex(0.52f, g));
        assertEquals(1, Rating.letterIndex(0.40f, g));
        assertEquals(0, Rating.letterIndex(0.33f, g));
        assertEquals(0, Rating.letterIndex(0f, g));
        // NaN 掉到最低档而不是穿透所有 >= 比较（NaN 与任何数比较都是 false）
        assertEquals(0, Rating.letterIndex(Float.NaN, g));
    }

    @Test
    public void peakComboSurvivesWindowBreaks() {
        ComboMeter cm = new ComboMeter();
        for (int i = 0; i < 12; i++) cm.hit(i * 0.5f);   // 0.5s 间隔，在 2s 窗内
        assertEquals(12, cm.peakHits());
        cm.hit(100f);                                    // 窗口早已过期，连击断成 1
        assertEquals(1, cm.count(100f));
        assertEquals("断连不能把峰值抹掉", 12, cm.peakHits());
        cm.reset();
        assertEquals(0, cm.peakHits());
    }
}
