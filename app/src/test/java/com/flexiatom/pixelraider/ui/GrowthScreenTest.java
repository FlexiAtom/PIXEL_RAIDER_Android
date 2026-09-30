package com.flexiatom.pixelraider.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.flexiatom.pixelraider.game.Balance;
import org.junit.Test;

/**
 * 成长页的**静态面**——绘制要 Canvas，测不了；但这一页的字面量与表全是跨文件耦合。
 *
 * <p>{@link GrowthScreen#L_ITEM} 与 {@link GrowthScreen#ICON_CARD} 都靠**下标**对齐
 * {@code Balance.Growth.ATTACK/RATE/SHIELD/MOBILE/CRIT/BOMB}：加一项而这里没跟着加，结果不是少一行，
 * 而是绘制线程每帧一次越界异常；加了一项却忘了加图标，则是"第五行画着炸弹的图"这种没人会
 * 当 bug 报上去的东西。
 *
 * <p>这里不 {@code new GrowthScreen(...)}：构造它会建 {@link DrawKit}，它的 Paint 在未 mock 的
 * android 桩上会炸。只读静态字段不需要实例。
 */
public class GrowthScreenTest {

    @Test
    public void actionCodesLeaveRoomForEveryRow() {
        assertEquals(0, GrowthScreen.ACTION_NONE);
        assertEquals(1, GrowthScreen.ACTION_BACK);
        // 提交动作 = ACTION_BUY + 项 id，所以 BUY 必须**大于** BACK：否则第 0 行会报成"返回"
        assertTrue(GrowthScreen.ACTION_BUY > GrowthScreen.ACTION_BACK);
        assertEquals(2, GrowthScreen.ACTION_BUY);
        assertEquals(1, GrowthScreen.ACTION_BACK - GrowthScreen.ACTION_NONE);
    }

    @Test
    public void labelAndIconTablesHaveExactlyOneRowPerTreeItem() {
        assertEquals(Balance.Growth.ITEMS, GrowthScreen.L_ITEM.length);
        assertEquals("图标表与名字表逐格同长：一行对一枚图标",
                GrowthScreen.L_ITEM.length, GrowthScreen.ICON_CARD.length);
    }

    @Test
    public void rowLabelsFitTheNameColumnAndDoNotRepeat() {
        // 名字列 24 = 两个 12px 汉字，第三个字会骑到效果读数上（那两列之间只有 EFFECT_GAP=8 的空带）
        for (String name : GrowthScreen.L_ITEM) {
            assertEquals(name, 2, name.length());
            assertTrue(name, name.length() * Md3.PX_LABEL <= GrowthLayout.NAME_W);
        }
        for (int i = 0; i < GrowthScreen.L_ITEM.length; i++) {
            for (int j = i + 1; j < GrowthScreen.L_ITEM.length; j++) {
                assertNotEquals(GrowthScreen.L_ITEM[i], GrowthScreen.L_ITEM[j]);
            }
        }
    }

    @Test
    public void iconsAreDistinctShopCards() {
        for (int i = 0; i < GrowthScreen.ICON_CARD.length; i++) {
            int id = GrowthScreen.ICON_CARD[i];
            assertTrue("图标 id 越出商店卡表: " + id, id >= 0 && id < Balance.Shop.CARDS);
            for (int j = i + 1; j < GrowthScreen.ICON_CARD.length; j++) {
                assertTrue("两行同图标 → 读成同一件事", id != GrowthScreen.ICON_CARD[j]);
            }
        }
    }

    @Test
    public void columnLabelsFitTheirOwnColumns() {
        // 满级写「已满」而不是价格：它顶替的就是价格列，越界会压到点格
        assertTrue(GrowthScreen.L_MAXED.length() * Md3.PX_LABEL <= GrowthLayout.COST_W);
        // 出口是整页唯一一枚按钮，定宽 140；字加长到越界就会顶出页边
        assertTrue(GrowthScreen.L_BACK.length() * Md3.PX_BODY <= GrowthLayout.BUTTON_W);
    }

    @Test
    public void effectUnitsAreSingleCharacters() {
        // EFFECT_W_MAX = 17 + 2 + 12 的最后一项就是"一个汉字单位"，两个字的单位会把列算漏
        assertEquals(1, GrowthScreen.L_UNIT_POINT.length());
        assertEquals(1, GrowthScreen.L_UNIT_COUNT.length());
        assertNotEquals(GrowthScreen.L_UNIT_POINT, GrowthScreen.L_UNIT_COUNT);
        // 最宽一档是「+100点」：三位点阵 17 + 缝 2 + 一个 12px 汉字 = 31
        assertTrue(GrowthScreen.L_UNIT_POINT,
                17 + DrawKit.GAP + Md3.PX_LABEL <= GrowthLayout.EFFECT_W_MAX);
    }
}
