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
     */
    @Test
    public void actionCodesLeaveRoomForEverySlot() {
        assertEquals("NONE 必须是 0：Game 用 if-else 链比较它", 0, ShopScreen.ACTION_NONE);
        assertTrue(ShopScreen.ACTION_NEXT != ShopScreen.ACTION_NONE);
        assertTrue("购买码是 ACTION_BUY + 槽位，所以它必须排在最后一个动作之后",
                ShopScreen.ACTION_BUY > ShopScreen.ACTION_NEXT);
        for (int slot = 0; slot < ShopLayout.CARDS; slot++) {
            assertTrue(ShopScreen.ACTION_BUY + slot != ShopScreen.ACTION_NEXT);
            assertTrue(ShopScreen.ACTION_BUY + slot != ShopScreen.ACTION_NONE);
        }
        assertEquals("面板槽位数与规则端的一次上架数必须是同一个数",
                Balance.Shop.OFFER, ShopLayout.CARDS);
    }
}
