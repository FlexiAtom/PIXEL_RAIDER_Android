package com.flexiatom.pixelraider.game;

/**
 * 超载槽：[规格] **30 秒冷却制**，不是击杀蓄能制。
 *
 * 为什么冷却制：蓄能制下"什么时候能清场"取决于打得快不快，玩家无法规划；
 * 冷却制是一个可预期的战略资源——倒计时读得出来，就知道这一波能不能撑到转好。
 *
 * 初始即为可用：开局第一个 Boss 就允许释放，否则教学期拿不到这个机制。
 * 纯算术、不碰 android，JVM 单测直接钉"释放后 30 秒不可用、转满恰好 30 秒"。
 */
public final class OverloadMeter {

    private float remaining;

    public OverloadMeter() {
        remaining = 0f;   // 开局可用
    }

    /** 每帧推进；只减不增，夹在 0。 */
    public void step(float dt) {
        remaining -= dt;
        if (remaining < 0f) remaining = 0f;
    }

    public boolean ready() {
        return remaining <= 0f;
    }

    /** 还需等待的秒数，已就绪为 0。HUD 只在 >0 时画倒计时。 */
    public float remaining() {
        return remaining;
    }

    /** 0 = 刚释放完，1 = 就绪。用于环状进度条。 */
    public float charge01() {
        float cd = cooldown();
        if (cd <= 0f) return 1f;
        return 1f - remaining / cd;
    }

    /**
     * 释放。**未冷却则拒绝**，返回 false 且不改变状态——调用方据此决定要不要播特效，
     * 免得连点把冷却"重置回 30 秒"这种恶性反馈。
     */
    public boolean trigger() {
        if (!ready()) return false;
        remaining = cooldown();
        return true;
    }

    /** 冷却时长每次 trigger 现读表，调平衡改 {@link Balance#overload} 下一帧生效。 */
    public static float cooldown() {
        return Balance.overload.cooldownSec;
    }

    /** 调试/存档用：强制灌满或清空。 */
    public void forceReady() {
        remaining = 0f;
    }

    public void forceCooling(float seconds) {
        remaining = seconds < 0f ? 0f : seconds;
    }
}
