package com.flexiatom.pixelraider.core;

/**
 * UI 模态栈（规格 §八）。规则只有两条：栈记录打开顺序，栈顶决定绘制，栈空 = 回到游戏。
 * 返回键统一映射到 pop()。只在渲染线程访问，不加锁。
 */
public final class ModalStack {

    public static final int NONE = 0;
    public static final int MENU = 1;
    public static final int SETTINGS = 2;
    public static final int PAUSE = 3;
    public static final int SHOP = 4;
    public static final int RESULT = 5;
    public static final int CODEX = 6;
    /** 成长页（局外成长树的消费面板），从主菜单进。栈容量 8 是**深度**，与这里的编号不是一回事。 */
    public static final int GROWTH = 7;

    private final int[] stack = new int[8];
    private int size;

    public void push(int modal) {
        if (modal == NONE || size == stack.length) return;
        if (peek() == modal) return; // 同一模态不重复入栈
        stack[size++] = modal;
    }

    public int pop() {
        if (size == 0) return NONE;
        int v = stack[--size];
        stack[size] = NONE;
        return v;
    }

    public int peek() {
        return size == 0 ? NONE : stack[size - 1];
    }

    public boolean contains(int modal) {
        for (int i = 0; i < size; i++) {
            if (stack[i] == modal) return true;
        }
        return false;
    }

    /** 清到某个模态之下（如从设置页返回暂停面板）。 */
    public void popUntil(int modal) {
        while (size > 0 && peek() != modal) {
            pop();
        }
    }

    public void clear() {
        while (size > 0) pop();
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }
}
