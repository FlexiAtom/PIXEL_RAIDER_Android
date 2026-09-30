/*
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
package com.flexiatom.pixelraider.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.flexiatom.pixelraider.game.Balance;
import org.junit.Test;

/**
 * 商店面板的静态面——照 {@link PauseScreenTest} 的规矩，一条都不碰 {@code new ShopScreen(...)}：
 * 构造会拉起 {@link DrawKit} 的 Paint，而单测跑在未 mock 的 android 桩上（
 * {@code returnDefaultValues = false}），类一加载就炸。绘制那半块的验证只能走真机截图。
 */
public class ShopScreenTest {

    /**
     * {@code Game} 收到的是**一个 int**，靠区间分诊：{@code ACTION_NEXT} 与
     * {@code ACTION_BUY + 槽位} 必须永不相交。把 {@link ShopScreen#ACTION_BUY} 改成 1 之类的
     * "看起来一样"的重排，会让买卡分支永远走不到——真机上的表现是"点卡没反应"。
     *
     * <p>槽位的上界是**版面最多摆几张**（一页）而不是 {@code Balance.Shop.OFFER}：暂停店一屏能摆到
     * 七张，按 3 留区间的话第七格会撞进下一个动作码。翻页不在这里——它由面板自己消化，
     * {@code pressUp} 翻完页报 {@code ACTION_NONE}，所以动作码区间没有页那一档。
     */
    @Test
    public void actionCodesLeaveRoomForEverySlot() {
        assertEquals("NONE 必须是 0：Game 用 if-else 链比较它", 0, ShopScreen.ACTION_NONE);
        assertTrue(ShopScreen.ACTION_NEXT != ShopScreen.ACTION_NONE);
        assertTrue("购买码是 ACTION_BUY + 槽位，所以它必须排在最后一个动作之后",
                ShopScreen.ACTION_BUY > ShopScreen.ACTION_NEXT);
        for (int slot = 0; slot < ShopLayout.MAX_CARDS; slot++) {
            assertTrue(ShopScreen.ACTION_BUY + slot != ShopScreen.ACTION_NEXT);
            assertTrue(ShopScreen.ACTION_BUY + slot != ShopScreen.ACTION_NONE);
        }
        assertTrue("版面一页至少要能摆下一次三选一，否则槽位数与动作码区间两头都对不上",
                ShopLayout.MAX_CARDS >= Balance.Shop.OFFER);
    }
}
