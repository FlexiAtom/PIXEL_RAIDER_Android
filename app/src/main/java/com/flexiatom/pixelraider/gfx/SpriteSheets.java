/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
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
package com.flexiatom.pixelraider.gfx;

/**
 * 像素网格表（规格 §三：所有精灵用字符网格 + 调色板生成，不是手绘位图）。
 *
 * 约定：
 * - '.' 透明，'0'..'4' 是 {@link Palette#iconPalette} 的 5 档下标
 *   （0 深暗 / 1 暗 / 2 主色 / 3 亮 / 4 高光）。
 * - 图标用 12×12 或 16×16，不用矢量描边——24px 格子里细线会互相粘连，形状差异消失。
 * - 每行**必须等宽**，且网格为正方形。这条由 SpriteSheetsTest 用反射遍历所有字段守住：
 *   手工调网格时少打一个字符会让整行右移半格，画出来是"飞机歪了"，比崩更难查。
 */
public final class SpriteSheets {

    private SpriteSheets() { }

    // 离屏缓存 key 的 id 域：每个网格一个稳定小整数，改动会让旧 key 失效（重建，不是错画）。
    public static final int ID_SHIP = 1;
    public static final int ID_WINGMAN = 2;
    public static final int ID_GRUNT = 3;
    public static final int ID_BURSTER = 4;
    public static final int ID_CHIP = 5;
    public static final int ID_PAUSE = 6;
    public static final int ID_WEAVE = 7;
    public static final int ID_SHOOTER = 8;
    public static final int ID_RUSHER = 9;
    public static final int ID_ELITE = 10;
    public static final int ID_BOSS_A = 11;
    public static final int ID_BOSS_B = 12;
    public static final int ID_BOSS_C = 13;
    public static final int ID_COIN = 14;
    public static final int ID_HP = 15;
    public static final int ID_SHIELD = 16;
    public static final int ID_POWERUP = 17;
    public static final int ID_BOMB = 18;
    // 商店卡图标：只作离屏缓存 key，形状一律 9×9（跟小怪同格，卡面上不会比敌人细节少）。
    public static final int ID_SHOP_FIRE = 19;
    public static final int ID_SHOP_RATE = 20;
    public static final int ID_SHOP_CRIT = 21;
    public static final int ID_SHOP_SPEED = 22;
    public static final int ID_SHOP_HULL = 23;
    public static final int ID_SHOP_MAGNET = 24;
    public static final int ID_SHOP_RANDOM = 25;
    // 簇 II 的导弹卡（2026-10-01 实现授权）：id 续 25 之后，不复用掉落图标——四张都是机制卡，
    // 形状要说得出"弹道"，而 HP/盾/币那几枚说的是"补给"。
    public static final int ID_SHOP_MID = 26;
    public static final int ID_SHOP_IGN = 27;
    public static final int ID_SHOP_HAND = 28;
    /**
     * 簇 II 第四张：格斗导弹。它跟上面三张**不是一类图标**——那三张印的是"导引头/弹道"的机制，
     * 这一张印的是**另一条弹体池**的弹（短粗、大三角翼＝高舵效）。它没有可成长的数（买断），
     * 所以卡面只有一句"解锁"，形状是它唯一的读数。
     */
    public static final int ID_SHOP_DOG = 29;
    /**
     * 两张武器解锁卡的图标（2026-10-02）。它们与上面四张**不是一类**：簇 II 那四张印的是导引头
     * 与弹道参数（扇形、弯弹道、火化带），这两张印的是**弹体本身的形态**——因为这张卡卖的商品
     * 就是"这一把枪在不在飞机上"，图标要说的是枪，不是它的某个旋钮。
     *
     * <p>两枚必须互相认得出：{@link #SHOP_MISSILE} 是细弹体＋**底部**那对小直翼（尾喷两缕），
     * 与 {@link #SHOP_DOG} 那对摊到格边的中置大三角翼正好是一组对照（基础弹 vs 高舵效弹）；
     * {@link #SHOP_BEAM} 用**断开的一截**画束，全表只有它有分段——长刃是它唯一的读点。
     */
    public static final int ID_SHOP_MISSILE = 30;
    public static final int ID_SHOP_BEAM = 31;
    /**
     * 连续杆（2026-10-02，他逐字裁「新增『连续杆』卡，未买靠撞击」）。它刻意做成
     * {@link #SHOP_MISSILE} 的**同一枚弹加一条横亮线**——因为这张卡卖的就是那枚弹的战斗部，
     * 图标把弹体原样画回去、只在中间多一条线，读起来正是"同一发，炸开成一片"。
     * 若另起形状（又一条弯弹道、又一个扇形），它会与簇 II 那三张撞成同一句话。
     */
    public static final int ID_SHOP_ROD = 32;
    /** 雷达锁定（2026-10-03 落地：这张卡**从来没有落地过**，今天卡表里只有它的升级卡「中段引导」）。 */
    public static final int ID_SHOP_RADAR = 33;

    /** 玩家机：11×11，机头朝上（朝向由实际位移旋转绘制，不在网格里画四个方向）。 */
    public static final String[] SHIP = {
            ".....0.....",
            "....020....",
            "....020....",
            "...02320...",
            "..0234320..",
            ".0234.4320.",
            "0234.3.4320",
            ".0234.4320.",
            "..02...20..",
            "...0...0...",
            ".....0.....",
    };

    /** 僚机：7×7 小菱形，弹道不给拖尾（数量多，不抢视线）。 */
    public static final String[] WINGMAN = {
            "...0...",
            "..020..",
            ".02320.",
            "0234320",
            ".02320.",
            "..020..",
            "...0...",
    };

    /** 小怪：直线型，9×9。 */
    public static final String[] GRUNT = {
            "0.......0",
            ".0.....0.",
            "..0...0..",
            "...000...",
            "..02220..",
            ".0234320.",
            "023.4.320",
            "..0.0.0..",
            "...0.0...",
    };

    /** 自爆怪：圆胖型，9×9。 */
    public static final String[] BURSTER = {
            "..00000..",
            ".0222220.",
            "023444320",
            "024.0.420",
            "034.0.430",
            "024.0.420",
            "023444320",
            ".0222220.",
            "..00000..",
    };

    /** 拾取：芯片，7×7。 */
    public static final String[] CHIP = {
            "..000..",
            ".02320.",
            "0234320",
            "034.430",
            "0234320",
            ".02320.",
            "..000..",
    };

    /** 横摆：9×9 左右对称的展翼形——轮廓本身就是"它会横扫"的提示。 */
    public static final String[] ENEMY_WEAVE = {
            "..0...0..",
            ".0.....0.",
            ".0.000.0.",
            ".0234320.",
            "0234.4320",
            ".0234320.",
            ".0.000.0.",
            ".0.....0.",
            "..0...0..",
    };

    /** 射手：9×9 六边形带炮口（朝下），停线巡边时读得出"它在瞄我"。 */
    public static final String[] ENEMY_SHOOTER = {
            ".0000000.",
            "02222220.",
            "02.44.20.",
            "02222220.",
            ".0222200.",
            "..0440...",
            "..0440...",
            "...00....",
            ".........",
    };

    /** 冲刺：9×9 窄梭，尖端朝下——锁定前是"要冲"，锁定后是这个形状直着扎下来。 */
    public static final String[] ENEMY_RUSHER = {
            "...000...",
            "..02420..",
            "..02420..",
            "...020...",
            "...020...",
            "..0.2.0..",
            ".0..2..0.",
            "0...2...0",
            ".....2...",
    };

    /** 精英：13×13 四翼带核，比小怪整整大一圈（血量 12，需要一眼看出"这只厚"）。 */
    public static final String[] ENEMY_ELITE = {
            "......0......",
            ".....020.....",
            "....02320....",
            "...0234320...",
            "..023444320..",
            ".0234.4.4320.",
            "0.0234.4320.0",
            ".0.0234320.0.",
            "..0.02320.0..",
            "...0.020.0...",
            "..0..0.0..0..",
            ".0...0.0...0.",
            "0...........0",
    };

    // Boss 用 11×11 网格画到 33 逻辑像素：**正好 3 倍**，最近邻缩放不出宽窄不均的像素。
    // （小怪 9 格画 9、精英 15 格画 15，同理都是 1 倍。）

    /** 毁灭者：11×11 宽 V 加中心核。 */
    public static final String[] BOSS_DESTROYER = {
            "0....0....0",
            ".0...0...0.",
            "..0..0..0..",
            "...00000...",
            "..0234320..",
            ".023444320.",
            "0234...4320",
            ".023444320.",
            "..0234320..",
            ".0...0...0.",
            "0....0....0",
    };

    /** 蜘蛛：11×11 八条腿放射、中心镂空。 */
    public static final String[] BOSS_SPIDER = {
            "0..0...0..0",
            ".0..0.0..0.",
            "..0.0.0.0..",
            "...02220...",
            "..0234320..",
            ".0234.4320.",
            "..0234320..",
            "...02220...",
            "..0.0.0.0..",
            ".0..0.0..0.",
            "0..0...0..0",
    };

    /** 堡垒：11×11 方壳四角炮塔，中心一排镂空当装甲缝。 */
    public static final String[] BOSS_FORTRESS = {
            "0.0.....0.0",
            "000.000.000",
            "..0234320..",
            ".023444320.",
            "02344444320",
            "024.4.4.420",
            "02344444320",
            ".023444320.",
            "..0234320..",
            "000.000.000",
            "0.0.....0.0",
    };

    /** 金币：7×7 带竖槽的钱币。 */
    public static final String[] DROP_COIN = {
            "..000..",
            ".02220.",
            "023.320",
            "023.320",
            "023.320",
            ".02220.",
            "..000..",
    };

    /** 血包：7×7 十字。 */
    public static final String[] DROP_HP = {
            "...0...",
            ".00000.",
            ".02320.",
            "0234320",
            ".02320.",
            ".00000.",
            "...0...",
    };

    /** 护盾：7×7 盾牌。 */
    public static final String[] DROP_SHIELD = {
            "0000000",
            "0234320",
            "0234320",
            ".02320.",
            ".02320.",
            "..020..",
            "...0...",
    };

    /** 强化：7×7 四向星（拾取层状态的统一外观，好/坏状态靠**颜色**分，形状只表示"这是个状态"）。 */
    public static final String[] DROP_POWERUP = {
            "...0...",
            "...0...",
            "0.020.0",
            ".02320.",
            "0.020.0",
            "...0...",
            "...0...",
    };

    /** 炸弹：7×7 带引信的圆弹。 */
    public static final String[] DROP_BOMB = {
            ".....0.",
            "..0.0.0",
            ".00000.",
            "0222220",
            "0234320",
            "0222220",
            ".00000.",
    };

    /** 暂停图标，与 HUD 暂停按钮共用（18×18 绘制区内居中）。 */
    public static final String[] PAUSE_BARS = {
            ".0...0.",
            ".0...0.",
            ".0...0.",
            ".0...0.",
            ".0...0.",
            ".0...0.",
            ".0...0.",
    };

    // ---- 商店卡图标（规格 §四 商店：三张卡各带一枚像素图标）--------------------------------
    // 形状先于颜色（§三）：火力=弹头、射速=双上箭、暴击=四角星、推力=喷口尾焰、装甲=带筋铁板、
    // 磁吸=U 形磁铁、随机=两点骰面。每张都要在 24px 里一眼认出，所以宁可粗，不画细线。

    /** 火力：一枚弹头朝上的子弹，弹体三段、尾翼外撇。 */
    public static final String[] SHOP_FIRE = {
            "....0....",
            "...040...",
            "...040...",
            "..02420..",
            "..02420..",
            "..02420..",
            ".0024200.",
            ".02.0.20.",
            "..0...0..",
    };

    /** 射速：两枚上箭头同向叠列——"连发"的形状读法。 */
    public static final String[] SHOP_RATE = {
            ".........",
            "....0....",
            "...040...",
            "..04040..",
            ".040.040.",
            "....0....",
            "...040...",
            "..04040..",
            ".040.040.",
    };

    /** 暴击：四角星，横竖两条光刺在中心加亮。 */
    public static final String[] SHOP_CRIT = {
            "....0....",
            "...040...",
            "...040...",
            "000444000",
            ".4444444.",
            "000444000",
            "...040...",
            "...040...",
            "....0....",
    };

    /** 推力：喷口朝下、尾焰向下扩散成锥形。 */
    public static final String[] SHOP_SPEED = {
            "...000...",
            "..02220..",
            "..02420..",
            "...040...",
            "...040...",
            "....0....",
            "...0.0...",
            "..0...0..",
            ".0.....0.",
    };

    /** 装甲：一块带十字筋的铁板（比 HP 心的形状硬，读得出"上限"而不是"回复"）。 */
    public static final String[] SHOP_HULL = {
            ".0000000.",
            "022242220",
            "022444220",
            "022242220",
            "024444420",
            "022242220",
            "022444220",
            "022242220",
            ".0000000.",
    };

    /** 磁吸：U 形磁铁，两极朝上且各带一段深色（不是纯高光，否则跟暴击星糊成一团）。 */
    public static final String[] SHOP_MAGNET = {
            ".44...44.",
            ".44...44.",
            ".04...40.",
            ".04...40.",
            ".04...40.",
            ".04...40.",
            ".0444440.",
            "..04440..",
            "...000...",
    };

    /**
     * 随机：一枚两点骰面。选骰子而不是选"四角星"，是因为星已经被 {@link #DROP_POWERUP} 占去表示
     * "这是一个状态层"——四种增益本来就靠颜色分形；这张卡卖的是**抽哪一种**，形状必须说"随机"，
     * 不能再说"状态"。两个斜角的点是 2×2 的**挖空**（透明当骰面点），在 9×9 里还认得出来，
     * 画三点就会被缩放宽糊掉。
     */
    public static final String[] SHOP_RANDOM = {
            ".0000000.",
            "033333320",
            "030033320",
            "030033320",
            "033333320",
            "033330020",
            "033330020",
            "022222220",
            ".0000000.",
    };

    /** 中段引导：一枚**弯的**箭头——弹道从左下角弯上来、顶端指向正上方（"没开眼也在转向"的形状读法）。 */
    public static final String[] SHOP_MID = {
            ".........",
            "....0....",
            "...040...",
            "..04440..",
            "...04....",
            "..04.....",
            ".04......",
            "04.......",
            ".........",
    };

    /** 二次点火：弹体在上、尾下一道宽的火花带（第一次点火是弹，第二次是那道爆开的焰）。 */
    public static final String[] SHOP_IGN = {
            "....0....",
            "...040...",
            "..04440..",
            "..04440..",
            "...040...",
            "..0.0.0..",
            ".0404040.",
            "..0...0..",
            ".........",
    };

    /** 机动过载：从底部一点向上张开的一对角壁（视场放宽的形状），比 {@link #SHOP_SPEED} 的实心尾焰空。 */
    public static final String[] SHOP_HAND = {
            "0.......0",
            "04.....40",
            ".04...40.",
            ".04...40.",
            "..04.40..",
            "...040...",
            "...040...",
            "....0....",
            "....0....",
    };

    /**
     * 格斗导弹：一枚**短粗的大三角翼**弹——机头在上，第 4/5 行那对摊到格边的翅膀是它全部的辨识度。
     *
     * <p>形状为什么选"翼"而不是又一条弹道或又一个扇形：这张卡卖的是<b>高舵效</b>（他的字「自带高过载
     * 和舵效」，L36568/L36897），而那在图标上唯一可读的写法就是翼面积；{@link #SHOP_MID} 已经占了
     * "弯弹道"、{@link #SHOP_HAND} 已经占了"张角"，再画一条会撞成两张同一句话。
     */
    public static final String[] SHOP_DOG = {
            "....0....",
            "...040...",
            "..04440..",
            "..04440..",
            "0.04440.0",
            "040444040",
            ".0.040.0.",
            "...040...",
            "....0....",
    };

    /** 基础导弹：细弹体一路到底，翼在**最下面**（与 {@link #SHOP_DOG} 的中置大三角翼成对照），底缘两缕尾喷。 */
    public static final String[] SHOP_MISSILE = {
            "....0....",
            "...040...",
            "...040...",
            "...040...",
            "...040...",
            "...040...",
            ".0.040.0.",
            "040444040",
            "..0...0..",
    };

    /** 基础激光：一截**断开**的长刃（弹体在上、束在下），全表只有它是分段的——长刃是这把枪唯一的读法。 */
    public static final String[] SHOP_BEAM = {
            "...000...",
            "...040...",
            "...040...",
            "....0....",
            "...040...",
            "...040...",
            "....0....",
            "..04440..",
            "...040...",
    };

    /**
     * 连续杆：{@link #SHOP_MISSILE} 的弹体原样不动，中段换成一条亮线（那一行由 4 铺满、
     * 只留两格深色是让弹体轮廓**穿过**亮线——杆是加在弹上的，不是替掉弹）。
     */
    public static final String[] SHOP_ROD = {
            "....0....",
            "...040...",
            "...040...",
            "...040...",
            "444040444",
            "...040...",
            ".0.040.0.",
            "040444040",
            "..0...0..",
    };

    /**
     * 雷达锁定：一圈环 + 中心机位 + 一条斜向扫描线 + 一枚离心的目标点。
     *
     * <p>形状为什么是"环里一个点"而不是又一个扇形：这张卡卖的是**看得见战场并且点得着**（他 2026-10-03
     * 逐字「块屏是雷达锁定那张卡的商品（因为无锁定时雷达屏幕没用）」），而扇形在本表里已经被
     * {@link #SHOP_HAND}（张角）与 {@link #SHOP_IGN}（半径）各占了一次——第三次画扇形就是三张同一句话。
     * 环＋点是这块 HUD 方块最省的缩略写法。
     */
    public static final String[] SHOP_RADAR = {
            ".........",
            "..00000..",
            ".0....40.",
            "0....4..0",
            "0...4...0",
            "0.......0",
            "0.4.....0",
            ".0.....0.",
            "..00000..",
    };

    /**
     * 按**玩法枚举的序号**索引的网格表：小怪读 {@code Balance.Enemy.STRAIGHT..BURSTER}、
     * Boss 读 {@code Balance.Boss.DESTROYER..FORTRESS}、掉落读 {@code Drops.CHIP..BOMB}。
     *
     * 索引而不是 switch：规格 §五 点名"新增一样东西要同步三处"。表长由
     * {@code SpriteSheetsTest} 断言（与枚举的数量一致），漏一种会立刻红；
     * {@code enemySheet} 越界时退回 0 号而不是抛——渲染中途崩掉比画错一只怪严重得多。
     *
     * ⚠ 声明必须在各网格字段之后：Java 的 static 字段按声明顺序初始化，反过来写会拿到 null。
     */
    public static final String[][] ENEMY_SHEETS = {
            GRUNT, ENEMY_WEAVE, ENEMY_SHOOTER, ENEMY_RUSHER, ENEMY_ELITE, BURSTER,
    };
    public static final int[] ENEMY_IDS = {
            ID_GRUNT, ID_WEAVE, ID_SHOOTER, ID_RUSHER, ID_ELITE, ID_BURSTER,
    };
    public static final String[][] BOSS_SHEETS = { BOSS_DESTROYER, BOSS_SPIDER, BOSS_FORTRESS };
    public static final int[] BOSS_IDS = { ID_BOSS_A, ID_BOSS_B, ID_BOSS_C };
    public static final String[][] DROP_SHEETS = {
            CHIP, DROP_COIN, DROP_HP, DROP_SHIELD, DROP_POWERUP, DROP_BOMB,
    };
    public static final int[] DROP_IDS = {
            ID_CHIP, ID_COIN, ID_HP, ID_SHIELD, ID_POWERUP, ID_BOMB,
    };
    /**
     * 商店卡图标：下标对齐 {@code Balance.ShopCard} 的 id 常量（FIREPOWER..RANDOM，再加簇 II 的
     * MID_COURSE/IGNITION/HANDLING/DOGFIGHT/ROD，最后是两张武器解锁卡 MISSILE/BEAM）。五张一次性
     * 补给卡复用掉落图标（HP/盾/炸弹/能量/金币），
     * 所以这里**不是**每个 id 一张新网格，而是同形状的第二次引用。
     *
     * <p>⚠ 这两条数组与卡表是**按下标**绑死的：加一张卡必须同时在这里尾上加一格，否则新卡会画成
     * 第 0 张（{@code shopIconSheet} 越界退回首项），表现是"图标错了"而不是崩溃——不会有任何东西替你响。
     */
    public static final String[][] SHOP_ICON_SHEETS = {
            SHOP_FIRE, SHOP_RATE, SHOP_CRIT, SHOP_SPEED, SHOP_HULL,
            DROP_HP, DROP_SHIELD, DROP_BOMB, DROP_POWERUP, SHOP_MAGNET, DROP_COIN,
            SHOP_RANDOM,
            SHOP_MID, SHOP_IGN, SHOP_HAND, SHOP_DOG,
            SHOP_MISSILE, SHOP_BEAM, SHOP_ROD, SHOP_RADAR,
    };
    public static final int[] SHOP_ICON_IDS = {
            ID_SHOP_FIRE, ID_SHOP_RATE, ID_SHOP_CRIT, ID_SHOP_SPEED, ID_SHOP_HULL,
            ID_HP, ID_SHIELD, ID_BOMB, ID_POWERUP, ID_SHOP_MAGNET, ID_COIN,
            ID_SHOP_RANDOM,
            ID_SHOP_MID, ID_SHOP_IGN, ID_SHOP_HAND, ID_SHOP_DOG,
            ID_SHOP_MISSILE, ID_SHOP_BEAM, ID_SHOP_ROD, ID_SHOP_RADAR,
    };

    public static int widthOf(String[] rows) {
        return SpriteGrid.widthOf(rows);
    }

    public static int heightOf(String[] rows) {
        return rows.length;
    }

    /** 越界一律退回第 0 张：渲染中途抛异常会整帧中断（规格 §九.15 同源问题）。 */
    public static String[] enemySheet(int kind) {
        return kind < 0 || kind >= ENEMY_SHEETS.length ? ENEMY_SHEETS[0] : ENEMY_SHEETS[kind];
    }

    public static int enemyId(int kind) {
        return kind < 0 || kind >= ENEMY_IDS.length ? ENEMY_IDS[0] : ENEMY_IDS[kind];
    }

    public static String[] bossSheet(int bossIndex) {
        return bossIndex < 0 || bossIndex >= BOSS_SHEETS.length
                ? BOSS_SHEETS[0] : BOSS_SHEETS[bossIndex];
    }

    public static int bossId(int bossIndex) {
        return bossIndex < 0 || bossIndex >= BOSS_IDS.length ? BOSS_IDS[0] : BOSS_IDS[bossIndex];
    }

    public static String[] dropSheet(int dropKind) {
        return dropKind < 0 || dropKind >= DROP_SHEETS.length
                ? DROP_SHEETS[0] : DROP_SHEETS[dropKind];
    }

    public static int dropId(int dropKind) {
        return dropKind < 0 || dropKind >= DROP_IDS.length ? DROP_IDS[0] : DROP_IDS[dropKind];
    }

    public static String[] shopIconSheet(int cardId) {
        return cardId < 0 || cardId >= SHOP_ICON_SHEETS.length
                ? SHOP_ICON_SHEETS[0] : SHOP_ICON_SHEETS[cardId];
    }

    public static int shopIconId(int cardId) {
        return cardId < 0 || cardId >= SHOP_ICON_IDS.length ? SHOP_ICON_IDS[0] : SHOP_ICON_IDS[cardId];
    }
}
