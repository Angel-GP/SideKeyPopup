# 图片弹窗 SideKeyPopup

一个应用，两件事：**读侧键** + **弹图片**。

按一下手机侧键（一加 / OPPO 的那颗三段式开关下面的按键），选好的图片就作为系统级悬浮窗
浮在当前界面之上——不打开任何界面、不切走你正在用的应用。点图片以外的任意位置收起。

> 这一版是把原来的两个应用（`FloatingImage` 悬浮图片、`SideKeyLite` 侧键 Lite）
> **合并成了一个**。合并前它们靠一套导出的跨应用接口互相调用，见文末
> [为什么合并](#为什么合并)。

| | |
| --- | --- |
| 包名 | `com.linxi.sidekeypopup` |
| 应用名 | 图片弹窗 |
| minSdk / targetSdk | 26 / 34 |
| 产物 | `dist\SideKeyPopup-1.0.apk` |
| 许可 | **GNU LGPL-3.0**（见 [版权声明](NOTICE.md)） |

---

## 效果

1. 装好、打开 → 主界面就是设置页。
2. 「更换图片」→ 从相册选一张 → 存进应用私有目录，并立刻弹出来给你看一眼。
3. 「图片与外观」→ 大小 / 不透明度 / 旋转 / 形状 / 圆角 / 边缘羽化，改完即时生效。
4. 按一下侧键 → 图片浮在当前界面之上。点空白处或按返回键收起。

图片会复制到应用私有目录（`files/overlay_image`），不依赖相册 URI 的持久化授权，
重启、清缓存之外都不会丢。

---

## 界面

主界面从上到下：

| 区块 | 内容 |
| --- | --- |
| 总开关 | 启用 / 停用侧键接管。关掉之后侧键完全保持系统原样 |
| **图片** | 当前图片（带缩略图）/ 更换图片 / 图片与外观 |
| 工作方式 | 用哪条路读侧键（自动 / Shizuku / Root / 无障碍服务），点一下切换。选「自动」时直接把当前实际在跑的那条显示出来 |
| 最近捕获 | 上一次真的抓到侧键是什么时候 |
| 权限与状态 | 二级页，所有权限申请都收在里面 |

长按桌面图标还有两个快捷方式：「更换图片」「图片设置」。

单击做什么是**写死的**——就是弹图片，所以界面上没有这个选项。
长按一律不拦截、交给系统，所以也没有长按的选项。

---

## 侧键：三条路

「工作方式」可以选 **自动 / Shizuku / Root / 无障碍服务**。
自动时的优先级是 **Shizuku > Root > 无障碍服务**，任意一条走通就行：

| 优先级 | 方式 | 需要什么 | 说明 |
| --- | --- | --- | --- |
| 1 | **Shizuku** | 装并启动 Shizuku | 以 shell 身份读 `/dev/input`，免 root，最可靠 |
| 2 | **Root** | Magisk / KernelSU 等 | 用 `su` 直接读 `/dev/input` |
| 3 | **无障碍服务** | 在系统设置里开启 | 不需要额外权限，但**能看到哪些键不一定** |

> ### 为什么无障碍排在最后
>
> 无障碍只能看到**走到无障碍层**的按键。而 OPPO / 一加 的侧键在系统策略的
> `interceptKeyBeforeQueueing` 阶段就被厂商逻辑消化掉了——这也正是上游项目
> 必须去 hook 系统策略的原因——事件根本到不了 `AccessibilityService.onKeyEvent`。
>
> 一开始我把无障碍排在最前面，结果就是：Shizuku 权限也给了、界面上来源也显示正常，
> 但**按侧键完全没反应**，而且看不出哪里不对。读 `/dev/input` 是绕过整条输入管线的，
> 所以更可靠。
>
> 选了哪条路但没权限时不会硬撑，会自动退回另一条，并在界面上标出来。

当前用的是哪条、最近有没有真的抓到过侧键，主界面「工作方式」和「最近捕获」里直接能看到。
选了某条路但没权限、实际走的是另一条时会标上「未生效」；
如果走的是无障碍又一直没抓到过按键，会直接写「可能看不到侧键」——
一加 / OPPO 上这条路的失败表现就是「按了没反应」，不写出来根本查不出问题。

三条都不通时模块什么都不做，侧键保持系统原样——**不会把侧键弄失灵**。

> Root / Shizuku 这两条路要在应用进程里挂一个线程一直读设备，所以会起一个前台服务把进程吊住
> （通知栏会有一条低优先级常驻通知）。无障碍那条路不需要，系统自己会保活无障碍服务。
>
> 设备是这么找的：先跑 `getevent -lp` 找声明了按键码 `0x2df` 的那个设备；
> 一个都没有的话，退回到「所有会报 KEY 事件的设备」都盯上。
> **不会盲猜 `/dev/input/event0`**——那多半是电源键，猜错了会安静地读一个永远没有侧键的设备。

**单击判定**：按下到松开小于 `Config.TAP_MAX_MS`（300ms）算单击。长按不拦截，交给系统
（一加上是静音 / 振动 / 响铃三段式以外的原有行为）。

---

## Shizuku 用不了？先看这里

**Shizuku 的 binder 不是应用主动去拿的，是 Shizuku 主动推过来的。**

它的服务端启动时会扫描所有声明了 `moe.shizuku.manager.permission.API_V23` 的应用，
然后调用这些应用的 `ShizukuProvider.call("sendBinder", ...)`，把 binder 塞进那个进程。
只有收到 binder 之后，`requestPermission()` 才有东西可以调。

所以「Shizuku 正在运行」和「本应用拿到了它的连接」是**两件事**。
最常见的坑：**先启动了 Shizuku，之后才装本应用** —— Shizuku 那次扫描没扫到你，
binder 就一直不会来。

权限页把 Shizuku 拆成了 5 种状态，对着看就行：

| 状态 | 含义 | 怎么办 |
| --- | --- | --- |
| 未安装 | 设备上既没有 Shizuku 也没有 Sui | 点「去下载」 |
| **未连接** | 装了、在运行，但本应用没拿到 binder | 点「打开 Shizuku」；**如果它本来就在运行，在 Shizuku 里停止再启动一次**（或重启手机），然后回到本页 |
| 版本过旧 | 服务端低于 13 | 升级 Shizuku（读侧键用的是 UserService，需要 13+） |
| 未授权 | 连接正常，只是没授权 | 点「申请授权」 |
| 已授权 | 可以用 | — |

用 **Sui**（Magisk 模块形态）的话没有管理器应用，所以「装没装」那一栏查不到包是正常的——
状态判定**先看 binder 再看包名**，只要 binder 在就不会误报「未安装」。

另外：**如果之前拒绝过授权，Shizuku 不会再弹窗**。这时按钮会变成「打开 Shizuku」，
需要你去 Shizuku 的「已授权的应用」里手动允许。

> **两个踩过的坑，都加了回归测试：**
>
> 1. `requestPermission()` 抛异常时被吞掉了，界面照样弹「已发起授权请求」，
>    用户就一直等一个永远不会出现的弹窗。现在只有请求真的发出去了才会那么提示。
> 2. Shizuku 的**包名是 `moe.shizuku.privileged.api`**，`moe.shizuku.manager` 只是
>    **权限名**（`moe.shizuku.manager.permission.API_V23`）的前缀。
>    之前把它当包名用，结果 Shizuku 明明在运行却一直显示「未安装」。
>    现在直接读库里的 `ShizukuProvider.MANAGER_APPLICATION_ID`，并在清单里加了 `<queries>`。

---

## 安装

1. 装 `dist\SideKeyPopup-1.0.apk`。
2. 打开 App → 点「权限与状态」→ 开 **显示在其他应用上层**（悬浮窗，必开）。
3. 同一页里再选一条读侧键的路：**开无障碍服务**（最省事），或者申请 Shizuku / Root。
4. 回主界面 → 「更换图片」选一张。
5. 按一下侧键试试。

### ColorOS 上的额外一步：自启动

ColorOS 会拦截后台拉起 Activity，也会限制后台起前台服务，所以要给它**自启动**权限，
否则单击时图片可能弹不出来。权限页里点「后台弹出界面 / 自启动」的「去设置」直达。

#### 为什么是一串候选，还要运行时扫描

最早这里用的是 `ACTION_APPLICATION_DETAILS_SETTINGS`，打开的是**系统原生的应用信息页**——
那一页里根本没有「自启动」这一项，等于跳错。ColorOS 把它收进了厂商自己的页面。

所以 `Permissions.openAutoStartSettings` 分两层找：

1. **写死的候选表**（包名 + 类名都要猜对）：

   | 候选包 | Activity | 对应 |
   | --- | --- | --- |
   | `com.coloros.safecenter` | `…permission.startup.StartupAppListActivity` | ColorOS 12 及以前 |
   | `com.coloros.safecenter` | `…startupapp.StartupAppListActivity` | ColorOS 12 及以前 |
   | `com.oplus.safecenter` | `…permission.startup.StartupAppListActivity` | ColorOS 13 起（改 oplus 命名） |
   | `com.oplus.safecenter` | `…startupapp.StartupAppListActivity` | ColorOS 13 起 |
   | `com.oppo.safe` | `…permission.startup.StartupAppListActivity` | 更早的 OPPO |
   | `com.oneplus.security` | `…chainlaunch.view.ChainLaunchAppListActivity` | 老款一加（氢 / 氧 OS） |

2. **运行时扫描**：上表全没命中时，到 `VENDOR_PACKAGES` 那几个厂商包里把 Activity 列出来，
   挑类名带 `startup` / `autostart` / `bootstart` 的，按像的程度排序再试一遍。
   这一层只要求**包名**对得上，类名靠扫——厂商换一代 ROM 改类名是常事，猜中的概率高得多。

两个坑，都踩过：

- **不能用 `resolveActivity` 判断组件在不在**。它按 intent-filter 匹配，而厂商这些设置页
  基本都没有 `CATEGORY_DEFAULT`，会被判成「不存在」——候选一个都命中不了，
  直接掉进「没找到」。现在改用 `getActivityInfo`，只查组件本身，不掺 intent-filter 那套。
- **厂商包必须在清单 `<queries>` 里声明**。Android 11 起有包可见性限制，不声明的话
  `getActivityInfo` / `getPackageInfo` 对这些包一律抛异常，两层都白做。

两层都没命中时，不再跳应用信息页（那一页没用），而是弹一个对话框把手动路径写出来：
**手机管家 → 权限隐私 → 自启动管理**，或**设置 → 应用 → 自启动管理**。

> 候选表和包名来自 OPPO / 一加 历代 ROM 的公开约定。**ColorOS 17 上实测没命中**
> （用户反馈），所以才有第 2 层扫描和那个对话框。这台机器没有可用的模拟器 / 真机，
> 我验证不了任何一个具体候选在你的 ROM 上是否真的存在。

### 「隐藏后台」是应用自身的开关

最近任务里没有本应用的卡片，就没得划——这是「防止被不小心划掉后台」最直接的做法。

权限页最后一行就是它：**已隐藏 / 未隐藏**，点按钮切换，**默认不隐藏**。

实现是 `Recents.apply()`，走 `ActivityManager.AppTask.setExcludeFromRecents(boolean)`。
为什么不用清单里的 `android:excludeFromRecents`：

- 那是**静态属性**，装上就定死了，做不成用户随时切换的开关；
- `Activity` 上**没有** `setExcludeFromRecents` 这个方法（只有个不相干的
  `setRecentsScreenshotEnabled`），别找错地方——只有 `AppTask` 上有；
- `Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS` 只能把标志**加上**，没法在运行时取消，
  所以只能开不能关。

`AppTask.setExcludeFromRecents` 是唯一能双向切换的接口，所以清单里**故意不写**那个属性，
默认保持「会出现在最近任务里」。主界面每次 `onResume` 都按当前设置重新标一遍，
这样任务被系统重建之后设置依然生效。

服务层面另有一道：`SideKeyService` 声明了 `android:stopWithTask="false"`，
所以就算任务被清掉，服务也只会收到 `onTaskRemoved` 而继续跑。两者互不依赖。

> 注意 `ImageSettingsActivity` 自己那个 `excludeFromRecents="true"` 是另一回事，
> 从一开始就有：它挂在独立 taskAffinity 上，不排除的话从长按菜单进来再按 Home、
> 再点桌面图标时会跟着一起弹出来。和这个开关无关。

> 顺带一提：本应用自己申请了 `SYSTEM_ALERT_WINDOW`，这在 Android 12+ 上是
> 「允许后台启动前台服务」的豁免条件之一，所以侧键在别的应用里按下时也能正常拉起悬浮图。

---

## 代码结构

```
app/src/main/java/com/linxi/sidekeypopup/
├── MainActivity.java              唯一的主界面：图片入口 + 侧键开关 + 工作方式
├── PermissionActivity.java        权限二级页
├── Permissions.java               各项权限的查询与跳转（含厂商自启动页的定位）
├── Recents.java                   「隐藏后台」：把本应用从最近任务里摘掉
├── Config.java                    侧键配置（总开关 / 工作方式 / 隐藏后台）
├── SideKeyPopupApp.java           Application：进程起来就初始化引擎
│
├── input/                         「读侧键」这一半
│   ├── SideKeyEngine.java         按键分发 + 单击/长按判定（核心）
│   ├── SourceManager.java         按优先级挑一条能用的路
│   ├── SideKeyAccessibilityService.java
│   ├── SideKeyService.java        Root / Shizuku 模式下的前台服务
│   ├── InputDeviceFinder.java     找声明了 0x2df 的设备
│   ├── InputEventStream.java      解析 /dev/input 的二进制事件
│   ├── ShellKeySource.java        Root：su + getevent
│   ├── RootShell.java
│   └── CommandRunner.java
│
├── shizuku/                       Shizuku 那条路
│   ├── ShizukuBridge.java         状态判定 + 申请授权
│   ├── ShizukuKeySource.java
│   └── ShizukuUserService.java    AIDL UserService，以 shell 身份读设备
│
└── overlay/                       「弹图片」这一半
    ├── OverlayService.java        TYPE_APPLICATION_OVERLAY + 出现/消失动画
    ├── OverlayContent.java        把图片按设置摆好
    ├── MaskedImageView.java       形状 / 圆角 / 羽化（遮罩位图 + DST_IN）
    ├── ImageProcessor.java        解码（静态图 + 动图）
    ├── MovieDrawable.java         API 26/27 上的动图
    ├── ImageSettings.java         外观参数
    ├── ImageSettingsActivity.java 图片与外观设置页（棋盘格预览）
    └── Prefs.java                 图片存哪
```

两半之间只有一个调用点：`SideKeyEngine.performAction()`，整个方法就一行

```java
private static void performAction(Context context, Config config) {
    OverlayService.show(context);   // 同进程，直接调
}
```

### 关键常量

| 位置 | 常量 | 值 | 含义 |
| --- | --- | --- | --- |
| `Config` | `KEY_CODE` | `735` (`0x2df`) | 一加 / OPPO 侧键的 Linux 键码（`BTN_TRIGGER_HAPPY32`） |
| `Config` | `TAP_MAX_MS` | `300` | 按下到松开小于这个值算单击 |
| `Config` | `SOURCE_*` | `0..3` | 自动 / 无障碍 / Shizuku / Root |
| `OverlayService` | `ACTION_SHOW` / `ACTION_CLOSE` | `com.linxi.sidekeypopup.action.*` | 服务内部 action，不导出 |

---

## 图标

自适应图标：`drawable/ic_launcher_background.xml`（斜向品牌蓝渐变）+ `drawable/ic_launcher_foreground.xml`。

画面是**一台手机，左侧靠上那颗侧键亮着，右边弹出一张照片**——把「按侧键 → 图片弹出来」直接画出来。

**为什么要有手机轮廓**：侧键本身只是一根黄条，没有参照物就看不出它是「按键」。
有了机身，黄条落在左上边缘才读得出「这是机器侧面那颗键」。
位置照着真机摆：一加的侧键就在左侧靠上。

几个刻意的取舍：

- **只留一处黄，就是侧键**。山和太阳都用品牌蓝，做成单色「照片」象形。
  这样黄色是全图唯一的强调色，视线自然落到侧键上。
- **手机用 38% 白（`#61FFFFFF`）而不是纯白**。压到这个透明度后，它和背景的对比度在渐变两端
  分别是 1.77:1 / 2.63:1，轮廓始终看得见；同时它明显比纯白的照片卡片暗，两层不会糊在一起
  （卡片 vs 手机 2.48:1）。
- **侧键向左探出机身 5px**，不然看着像贴在屏幕上的一块黄斑，而不是机身侧面的键。
- **太阳和山之间留 3.9px 白缝**（48dp 下约 1.7dp）。两者同色，挨太近在图标尺寸下会糊成一团。

几何是按自适应图标规范算的（108 画布；可见区 18..90；安全区是以 54,54 为心、半径 33 的圆；
遮罩圆半径 36），并用像素统计验过：

| 图层 | 位置（108 空间） | 圆角方形遮罩（ColorOS） | 正圆遮罩 |
| --- | --- | --- | --- |
| 手机 | 32..66 × 26..82 | **0% 被裁** | **0% 被裁** |
| 侧键 | 27..34 × 33..49 | **0% 被裁** | **0% 被裁** |
| 照片卡片 | 48..84 × 34..70 | **0% 被裁** | **0% 被裁** |

三层全部落在安全圆里，两种遮罩下一刀不裁。

> 本机没有可用的模拟器，图标**没法在真机上看**；当前模型也不支持读图，所以我同样看不到。
> 上面的结论是把同一套几何用 System.Drawing 栅格化之后逐像素统计出来的，不是肉眼看的。
> 成品预览图在 `build\icon-preview\icon-sheet.png`（无遮罩 / 正圆 / 圆角方形 / 48dp / 72dp 并排）。

---

## 自己编译

本机不需要任何 Android 环境，脚本会自己拉 JDK 17 + Gradle 8.2 + SDK android-34：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build.ps1          # 出包
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build.ps1 -Test    # 连测试一起
```

工具链的查找顺序是：`-ToolchainRoot` 参数 → 项目里的 `.toolchain\` →
同级的 `FloatingImage\.toolchain`（本机已有就直接复用）→ 都没有就自己下载一份到
`.toolchain\`。下载约 1.5 GB，只有第一次需要。

生成测试用的图片素材（`build\test\photo.png` / `anim.gif`）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\make-test-assets.ps1
```

---

## 测试

**68 条 JVM 测试，全绿，不需要真机**（Robolectric 4.11.1，`@Config(sdk = 34)`）：

| 测试类 | 条数 | 覆盖 |
| --- | --- | --- |
| `MainActivityTest` | 25 | 主界面构造、权限页行数、工作方式那行的文案、单击/长按判定、弹出图片的分发、自启动跳转、隐藏后台开关、配置存取 |
| `overlay.ImageSettingsActivityTest` | 15 | 设置页构造、遮罩几何、圆角/羽化计算、缓存失效、解码 |
| `InputDeviceFinderTest` | 12 | `getevent -lp` 输出解析、设备挑选 |
| `SourceChoiceTest` | 9 | 优先级挑选、没权限时的回退 |
| `ShizukuBridgeTest` | 7 | 5 种状态判定、包名 vs 权限名前缀那个坑 |

其中两条是这次合并专门加的回归测试：

- `overlayServiceIsNotExportedAnymore` —— 悬浮图服务**必须**是 `exported="false"`，
  防止哪天又被人当成跨应用接口；
- `shortPressShowsTheOverlay` —— 走真实分发路径，确认按侧键起的是
  `OverlayService` 而不是把本应用当普通 App 拉起来；
- `workModeShowsAutoPlusTheActiveSource` 等 5 条 —— 「工作方式」那一行在各种
  组合下（自动 / 手选 / 没权限回退 / 无障碍看不到按键）显示得对不对；
- `theRemovedSectionsAreGone` —— 被删掉的那几个区块（单击动作、单独的读取方式、
  提示行）的 view id 在布局里必须已经不存在。

### 还没验证的

诚实说清楚，这些在**这台机器上无法自动验证**：

- **界面渲染**。本机没有可用的模拟器（没有 hypervisor，TCG 软渲染下设备起不来），
  Robolectric 跑在 LEGACY 图形模式下，Canvas 的绘制指令是空操作——只有
  `drawBitmap` 会真的拷像素。所以圆角、羽化、形状这些**只验证了计算和几何，没验证过画面**。
- **真机行为**：侧键能不能被抓到、Shizuku / Root 是否授权成功、
  ColorOS 的后台弹出界面限制是否放行——都需要真机。
- 无障碍那条路在 ColorOS 上大概率看不到侧键（见上文），代码里能做的只是把它标出来。

---

## 为什么合并

上一版是两个应用 + 一套导出的「特殊接口」：侧键 Lite 通过
`startForegroundService(显式组件 + SHOW action)` 去起悬浮图片的服务，
被 Android 12+ 的后台前台服务限制拦下来时，再退回拉起一个透明 Activity。

那套东西现在**全部删掉了**：

| 删掉的东西 | 为什么不再需要 |
| --- | --- |
| `FloatPicClient` | 跨应用调用客户端，同进程直接调 `OverlayService.show()` |
| `ShowOverlayActivity` | 为绕过后台 FGS 限制而存在的透明 Activity 兜底 |
| `OverlayService` 的 `exported` + intent-filter | 服务不再对外暴露，`exported="false"` |
| `ForegroundServiceStartNotAllowedException` 的处理路径 | 本应用自己有 `SYSTEM_ALERT_WINDOW`，是后台起 FGS 的豁免条件 |
| 两个应用的图标 / 安装包 / 权限页 | 一个应用一套 |

代价是：原来两个应用可以分开升级、侧键 Lite 还能去调别的应用；
现在是一个整体。对「按侧键弹图片」这个用途来说，简单比灵活重要。

### 界面也一起瘦了

合并完第一版之后主界面还是把两个应用的东西并排放着，读起来太累，所以又删了一轮：

| 删掉的东西 | 现在是什么样 |
| --- | --- |
| 「单击做什么」整块 + `AppPickerDialog` / `AppEntry` + `QUERY_ALL_PACKAGES` | 动作写死是弹图片，`SideKeyEngine.performAction()` 就一行 `OverlayService.show(context)` |
| 单独的「读取方式」区块 | 并进「工作方式」那一行，点它切换 |
| 「运行状态」里那串来源 + 设备细节 | 只留「工作方式」和「最近捕获」两行；细节（盯着哪个设备）不再显示，但引擎里还在记 |
| 底下那句「只识别短按…」 | 去掉 |
| 权限页顶部的说明段落 | 去掉，直接列权限行 |

「工作方式」那一行的取值逻辑值得单独说一句，因为它承担了原来那几行提示的职责：

| 情况 | 显示 |
| --- | --- |
| 选自动，实际在用 Shizuku | `自动 · Shizuku` |
| 选自动，一条路都没通 | `未就绪` |
| 手选 Shizuku，正常 | `Shizuku` |
| 手选 Shizuku 但没权限，退回了无障碍 | `Shizuku · 未生效` |
| 走无障碍，而且从来没抓到过按键 | `无障碍服务 · 可能看不到侧键` |

最后一条是最要紧的：一加 / OPPO 的侧键到不了无障碍层，失败表现就是「按了没反应」，
不把结论直接写在界面上，用户根本查不出问题。

---

## 和上游项目的关系

### 侧键读取

`/dev/input/eventN` 的事件结构体解析（64 位 ABI 下 24 字节、32 位下 16 字节，
以及由此推出的 `type` / `code` / `value` 偏移）沿用自
**[SlimeNull/OppoCustomSideButtonFunctions](https://github.com/SlimeNull/OppoCustomSideButtonFunctions)**
（作者 SlimeNull Issac，**GNU LGPL-3.0**）的 `xposed/RawInputEventReader.kt`。
具体到 `input/InputEventStream.java` 一个文件，改动内容写在那个文件头部。

`getevent -lp` 找设备、Shizuku UserService 这条思路则参考了
[KernelSU](https://github.com/tiann/KernelSU) 生态里的侧键模块，
以及一加社区对 `0x2df` 这个键码的讨论。

上游模块是 Xposed / 系统策略 hook，本应用不碰系统，只用公开 API + Shizuku / Root 权限，
装完即用、卸载即净。除此之外的实现（三条来源的优先级与切换、悬浮窗、界面、图标）
都是本项目自己写的，与上游的对应部分做法不同。

### 许可上的连带后果

上游以 **LGPL-3.0** 授权，而 LGPL-3.0 是传染性的：**本项目因此也必须以 LGPL-3.0 授权**，
不能改成 MIT / Apache-2.0 这类宽松协议。完整说明、沿用范围的逐条对照、
以及上游许可声明的出处，都在 **[NOTICE.md](NOTICE.md)**。

| 文件 | 内容 |
| --- | --- |
| `LICENSE` | LGPL-3.0 全文 |
| `COPYING` | GPL-3.0 全文（LGPL-3.0 序言并入 GPL-3.0 条款，§3b / §4b 要求两份一起分发） |
| `NOTICE.md` | 版权声明 + 第三方归属 |

### 旧工程

`FloatingImage` 和 `SideKeyLite` 两个旧工程仍然保留在原目录，可以单独编译，
不再维护。**注意 `SideKeyLite` 里有一份同样的 `InputEventStream.java`，
它同样受 LGPL-3.0 约束，但目前还没有加上许可声明。**
