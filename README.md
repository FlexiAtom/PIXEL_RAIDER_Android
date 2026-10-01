<!--
SPDX-License-Identifier: AGPL-3.0-or-later
Copyright (C) 2026 FlexiAtom

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
-->

# PIXEL RAIDER · 像素突击

纵版弹幕射击游戏，跑在原生 Android 上。**纯 Java ＋ Android SDK**：没有游戏引擎，没有场景图，
渲染只有一条路径——`SurfaceView` 的软件 `Canvas`，逻辑与绘制共用自己那条游戏线程。
没有外部美术素材：所有精灵在代码里以**字符网格 ＋ 调色板**书写（`SpriteSheets`），启动时展开成位图；
进包的美术资源只有内嵌中文字体与启动图标。

## 环境

| 项 | 值 |
| --- | --- |
| 语言 | Java 17（不写 Kotlin 源码；AndroidX 自身由 Kotlin 编写，其 `kotlin-stdlib` 作为传递依赖无法避免） |
| Android Gradle Plugin / Gradle | 8.11.2 / 8.13 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 23（Android 6.0） |
| 运行时依赖 | `androidx.core:core:1.17.0`、`androidx.activity:activity:1.12.0`，没有第三条 |
| 测试依赖 | `junit:junit:4.13.2` |

## 构建与运行

```bash
./gradlew assembleDebug                          # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest                      # 全部单元测试，跑在 JVM 上，不需要设备
./gradlew testDebugUnitTest assembleDebug        # 本仓的验证闸门（提交前跑这一条）
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> `gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 是**相对地址** `gradle-8.13-bin.zip`。
> Gradle 按 wrapper jar 的同级目录解析它（实测与当前目录无关），所以**每台机器把自己那份 Gradle 8.13
> 发行包摆进 `gradle/wrapper/`**，构建用的就是本地那一份，不需要联网：
>
> ```bash
> cp ~/gradle-8.13-bin.zip gradle/wrapper/         # 没有的话先从官方地址下一份
> ```
>
> 那只 zip 已被 `.gitignore` 收掉：官方 bin 包实测 136,983,045 字节（130.6 MiB），超 GitHub 单文件
> 100 MiB 的拒推硬限，本来也进不了仓库。没摆 zip 时 `./gradlew` 报 `FileNotFoundException`，
> 指的就是这个路径——那是提示缺文件，不是配置坏了。
> 想让克隆者自动从公网下载也可以，把 `distributionUrl` 换成
> `https\://services.gradle.org/distributions/gradle-8.13-bin.zip` 即可（首次构建联网，之后落
> `~/.gradle/wrapper/dists/` 离线复用）。

`local.properties` 里写一行 `sdk.dir=<你的 Android SDK 路径>`（该文件不入库，各人本机各配一份）。

## 工程约束

这些是**设计决定**，改动它们需要连带改测试与所有以此为常量的代码，不是随手写死：

- **逻辑画布宽恒为 240**，高度按屏幕宽高比生长：`LOGIC_H = clamp(round(240 × aspect), 320, 560)`。
  长屏（最长按 21:9＝560）靠多出来的高度铺满，而不是把像素拉长；再长的机型退回黑边。
  战场本身是 240×320 那条带子（`BATTLE_H = 320`），高度余量留给 HUD、面板与远景。
- **整数倍缩放**：`scale = clamp(floor(min(屏幕宽/240, 屏幕高/320)), 1, 2)`，低端机另有一档上限兜底。
- **60Hz 固定步长**：`Time.STEP = 1/60`，累加器补步最多 5 步（`MAX_CATCHUP_STEPS`），
  单帧最大输入间隔钳在 0.25s。逻辑不吃真实帧率，掉帧不会改变任何判定结果。
- **每帧零分配**：子弹、导弹、战斗部、粒子、敌机全是预分配数组池，回收只置位。
  池容量不是拍脑袋——每个 `Balance` 里的容量字段旁边写着它的上界算式与余量倍数，
  并由对应测试从 `Balance` 现读复验（改数值会红，改算式假设也会红）。
- **中文 UI 字面量必须已内嵌字体**：`EmbeddedFontTest` 逐个 `.java` 剥掉注释后扫字符串字面量，
  缺字即失败。所以罕见字、生僻说法只能进注释，进不了界面。

## 目录

```
app/src/main/java/com/flexiatom/pixelraider/
  core/   7 个   时间步长、调度、游戏线程、输入路由、模态栈、帧探针、矩形值类型
  game/  33 个   全部规则与状态：波次导演、敌机/Boss 行为、弹链、导弹导引、战斗部、伤害、商店、结算
  gfx/   15 个   位图字体、调色板、精灵生成与图集、粒子池、星空背景、辉光、离屏缓存
  ui/    19 个   各页面与其布局纯函数：主菜单、HUD、暂停、商店、结算、成长树、MD3 面板层
  plat/   8 个   Android 边界：Activity、SurfaceView、屏幕度量与安全区、按键/振动、偏好存储
```

规则层与布局层的 android 依赖是**按文件切干净的**：`game/` 里只有 `Game.java`（组装根）import `android.*`，
其余规则类一律数值进、数值出；`ui/` 的分界是「布局 vs 绘制」——六个 `*Layout` 加上 `Md3`、`Widgets`、
`Easing`、`PressSelector` 零 android 依赖，只有 `*Screen` 与 `DrawKit` 碰 `Canvas`。
这是它们能跑在 JVM 测试上的前提。
`Balance.java` 是数值的唯一真源；`ShopCard` 卡表、波次曲线、弹道参数都在那里，带 `[可调]` 标记。

## 玩法速览

- **波次推进 ＋ 升级商店**：16 张构筑卡分两个入口——通用侧每波三选一（抽样后按优先级排序），
  非通用侧常驻全价（不看优先级，也不会被抽样漏掉）。
- **两条导弹弹体池**：标准流由扳机驱动、出膛后自行索敌；格斗弹半角 30°／半径 50 的发射扇形内择最近
  未锁定者，导航比更高（`N' = 5`，标准流为 3），发射即带锁、全程不换手，脱锁同帧自爆。
  两本锁账相互独立，因此同一只敌机可以同时挂着不同流的锁定。
- **比例导引（PN）与连续杆战斗部**：导弹按视线角速度做比例导引，命中后战斗部展开为环，
  并留下碎片二次杀伤；接触判据取的是扫掠带而不是点，高速接近不会穿模。
- **走位是「位移账」**：相对位移拖动，按下点即锚点，手指每动一次记一笔欠账，机体每帧按限速放一部分。
  直接搬手指位移的话，成长树的机动项、商店的推进卡、状态层加成在主输入上全是 0——记账之后才有人读。
- **过载与连击**：急转吃过载，超了就失速惩罚；贴弹与连续命中喂连击倍率。
- **结算与成长**：一局结束给出五维评级（生存／击杀／效率／超载／风格）与分阶段揭示的结算页，
  并与本机历史纪录对比；局外成长树用击杀掉落的芯片解锁六项永久等级，效果一律是乘数、与局内 buff 叠乘。

## 测试

`app/src/test/java/` 下 44 个文件，覆盖规则、布局几何、字体覆盖与缓存键。当前闸门全绿为
**42 suites ／ 508 tests ／ 0 failures ／ 0 errors**（这个数字随用例增减而变，以实跑输出为准）。

几条约定值得知道，因为它们决定了断言长什么样：

- 数值断言尽量**从 `Balance` 现算**，而不是抄一个手写的常数。
- 「某条流没有做 X」这类否定断言必须配一个对照组，否则它永远绿。
- 边界测试用 `dt = 0` 的步进取恰好压满的那一档，用等式而不是不等式钉住换算关系。

## 许可与第三方

- **本仓库原创代码**：GNU Affero General Public License v3.0 or later
  （`SPDX-License-Identifier: AGPL-3.0-or-later`）。每个自有文本文件头部一行 SPDX ＋ 版权块；
  `LICENSE` 是许可证原文（662 行，FSF 出品），按惯例**不加**我们的 SPDX／版权头——加上就成冒领。
- **内嵌字体**：`app/src/main/assets/fonts/pr-cjk-12px.otf`，为
  [Fusion Pixel 12px Mono](https://github.com/TakWolf/fusion-pixel-font)（TakWolf）zh_hans 2026.09.25
  的子集，SIL Open Font License 1.1。随附许可证原文与子集说明在同目录 `LICENSE.txt`。
- **Gradle Wrapper**：`gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar` 由 Gradle 项目
  生成并自带 `Apache-2.0` 署名，会被 `gradle wrapper` 重新生成，因此不改写其头部。
- **二进制资源**（图标 PNG、字体 OTF、wrapper JAR）不加注释头：那会改变文件字节。

## 状态

`versionName 0.1.0`，未发布。规则层与 UI 层在持续迭代，真机验收项（帧率与帧时分布、
各家 ROM 的生命周期与重入行为）仍在进行中。
