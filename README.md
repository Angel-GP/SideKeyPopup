# 图片弹窗 SideKeyPopup

一个应用，两件事：**读侧键** + **弹图片**。

按一下手机侧键（一加 / OPPO 的那颗三段式开关下面的按键），选好的图片就作为系统级悬浮窗
浮在当前界面之上——不打开任何界面、不切走你正在用的应用。点图片以外的任意位置收起。

| | |
| --- | --- |
| 包名 | `com.linxi.sidekeypopup` |
| 应用名 | 图片弹窗 |
| minSdk / targetSdk | 26 / 34 |
| 许可 | **GNU LGPL-3.0**（见 [许可](#许可)） |

只用公开 API + Shizuku / Root 权限，**不碰系统、不需要 Xposed**，装完即用、卸载即净。

> 仓库只放源码，不含 APK。要装的话先看[自己编译](#自己编译)。

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

三条都不通时模块什么都不做，侧键保持系统原样——**不会把侧键弄失灵**。

「工作方式」那一行不只是个选项，它承担了全部状态提示的职责：

| 情况 | 显示 |
| --- | --- |
| 选自动，实际在用 Shizuku | `自动 · Shizuku` |
| 选自动，一条路都没通 | `未就绪` |
| 手选 Shizuku，正常 | `Shizuku` |
| 手选 Shizuku 但没权限，退回了无障碍 | `Shizuku · 未生效` |
| 走无障碍，而且从来没抓到过按键 | `无障碍服务 · 可能看不到侧键` |

最后一条是最要紧的：一加 / OPPO 的侧键到不了无障碍层，失败表现就是「按了没反应」，
不把结论直接写在界面上，用户根本查不出问题。

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

1. 装构建出来的 `dist/SideKeyPopup-1.0.apk`（仓库不含 APK，见[自己编译](#自己编译)）。
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
> 预览图（无遮罩 / 正圆 / 圆角方形 / 48dp / 72dp 并排）是本机的临时产物，不在仓库里。

---

## 自己编译

本机不需要任何 Android 环境，脚本会自己拉 JDK 17 + Gradle 8.2 + SDK android-34：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build.ps1          # 出包
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build.ps1 -Test    # 连测试一起
```

脚本会按顺序找工具链：`-ToolchainRoot` 参数 → 项目里的 `.toolchain/` →
同级目录下已备好的工具链 → 都没有就自己下载一份到 `.toolchain/`。
**首次编译要下约 1.5 GB**（JDK 17 + Gradle 8.2 + SDK android-34），之后不再下。

> 已经装好 Android SDK 的话，用 `-ToolchainRoot <路径>` 指过去就能跳过下载。

生成测试用的图片素材（已经随仓库分发，只在想重新生成时才需要跑）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\make-test-assets.ps1
```

### 签名

**签名密钥不进仓库**（`.gitignore` 挡了 `keystore/`、`*.keystore`、`*.jks`）。
构建时按顺序从两处取：

| 来源 | 用在 | 内容 |
| --- | --- | --- |
| `keystore/keystore.properties` | 本地 | `storeFile` / `storePassword` / `keyAlias` / `keyPassword` |
| `SIGNING_*` 环境变量 | CI | `SIGNING_STORE_FILE` / `SIGNING_STORE_PASSWORD` / `SIGNING_KEY_ALIAS` / `SIGNING_KEY_PASSWORD` |

两处都没有时 `release` 就**不签名**，`assembleRelease` 仍会成功，产物是未签名的 APK。
`debug` 用的是 `tools/build.ps1` 在本机生成的调试密钥（同样不进仓库）。

CI 里的密钥来自仓库的 GitHub Secrets：

| Secret | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | 密钥库文件的 base64 |
| `KEYSTORE_PASSWORD` | 密钥库口令 |
| `KEY_ALIAS` | 别名 |
| `KEY_PASSWORD` | 密钥口令 |

自己生成一套：

```bash
keytool -genkeypair -v -keystore keystore/release.keystore \
  -storepass <口令> -keypass <口令> -alias sidekeypopup \
  -dname "CN=SideKeyPopup, O=<你的名字>, C=CN" \
  -keyalg RSA -keysize 4096 -validity 10000

base64 -w0 keystore/release.keystore > keystore-base64.txt   # 内容填进 KEYSTORE_BASE64
```

> ⚠️ **换签名密钥后，旧版本没法覆盖安装**——Android 会拒绝签名不一致的包，
> 得先卸载。密钥丢了就再也签不出能覆盖升级的包了，**务必自己备份好**。

### 发版

`.github/workflows/release.yml` 在 push 到 `main`、打 `v*` 标签、或手动触发时运行：
跑单元测试 → 编译 release → 上传 APK artifact。**打标签时**还会把 APK 附到 GitHub Release。

```bash
git tag v1.0
git push origin v1.0
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

几条值得一提的：

- `overlayServiceIsNotExportedAnymore` —— 悬浮图服务**必须**是 `exported="false"`，
  否则又变成一个跨应用接口；
- `shortPressShowsTheOverlay` —— 走真实分发路径，确认按侧键起的是
  `OverlayService`，而不是把本应用当普通 App 拉起来；
- `workModeShowsAutoPlusTheActiveSource` 等 5 条 —— 「工作方式」那一行在各种
  组合下（自动 / 手选 / 没权限回退 / 无障碍看不到按键）显示得对不对；
- `hideBackgroundRowTogglesTheSetting` —— 「隐藏后台」点一下要真的存下来、再点要能取消；
- `autoStartPrefersTheColorOsPageOverAppDetails` —— 自启动跳转必须优先跳厂商页，
  而不是系统原生的应用信息页。

### 还没验证的

诚实说清楚，这些在**这台机器上无法自动验证**：

- **界面渲染**。本机没有可用的模拟器（没有 hypervisor，TCG 软渲染下设备起不来），
  Robolectric 跑在 LEGACY 图形模式下，Canvas 的绘制指令是空操作——只有
  `drawBitmap` 会真的拷像素。所以圆角、羽化、形状这些**只验证了计算和几何，没验证过画面**。
- **真机行为**：侧键能不能被抓到、Shizuku / Root 是否授权成功、
  ColorOS 的后台弹出界面限制是否放行——都需要真机。
- 无障碍那条路在 ColorOS 上大概率看不到侧键（见上文），代码里能做的只是把它标出来。

---

## 设计取舍

| 取舍 | 原因 |
| --- | --- |
| 单击动作写死是「弹图片」 | 这个应用就干这一件事。留一个应用选择器只会让主界面变长——`SideKeyEngine.performAction()` 现在整个方法就一行 `OverlayService.show(context)` |
| 长按不拦截，交给系统 | 一加 / OPPO 的长按是既有行为，抢过来没有意义 |
| 不申请 `QUERY_ALL_PACKAGES` | 没有应用选择器了，不需要枚举已安装应用 |
| 悬浮图服务 `exported="false"` | 同进程直接调用，不需要对外暴露任何接口 |
| 「运行状态」只留两行 | 设备细节（盯着哪个 `eventN`）引擎里还在记，但界面上不显示，免得主界面读起来太累 |
| 只支持单击 | 双击 / 长按 / 组合键是上游 Xposed 模块的玩法；这里只做「按一下弹图片」 |

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

---

## 仓库约定

### 目录布局

| 路径 | 内容 |
| --- | --- |
| `app/src/main/java/` | 应用源码（Java） |
| `app/src/main/aidl/` | Shizuku UserService 的 AIDL 接口 |
| `app/src/main/res/` | 布局、图标、字符串 |
| `app/src/test/java/` | JVM 单元测试（Robolectric） |
| `tools/build.ps1` | 构建脚本：备工具链 → 编译 → 跑测试 → 出包 |
| `tools/make-test-assets.ps1` | 生成测试用的图片素材 |
| `LICENSE` / `COPYING` | 许可证全文 |
| `NOTICE.md` | 版权声明与第三方作品归属 |

`dist/`、`build/`、`.gradle/`、`.toolchain/` 都是构建产物，**不进仓库**（见 `.gitignore`）。

### 提交规范

提交信息用 [Conventional Commits](https://www.conventionalcommits.org/)：

```
<type>(<scope>): <描述>
```

| type | 用在 |
| --- | --- |
| `feat` | 新功能 |
| `fix` | 修 bug |
| `docs` | 只改文档 |
| `refactor` | 重构，不改行为 |
| `test` | 只改测试 |
| `chore` | 构建脚本、依赖、杂务 |
| `style` | 格式，不影响逻辑 |

例如：

```
feat(overlay): 支持边缘羽化
fix(input): 修掉 resolveActivity 误判厂商自启动页的问题
docs: 补充 LGPL-3.0 版权声明
```

主分支是 `main`，直接往 `main` 提交。

---

## 许可

本项目以 **GNU 宽通用公共许可证第 3 版（LGPL-3.0）** 授权。

    Copyright (C) 2026 linxi

| 文件 | 内容 |
| --- | --- |
| `LICENSE` | LGPL-3.0 全文 |
| `COPYING` | GPL-3.0 全文 |
| `NOTICE.md` | 版权声明 + 第三方作品归属 |

**两份许可证都要带**，理由在许可证原文里：

- 序言：*"This version of the GNU Lesser General Public License incorporates the terms
  and conditions of version 3 of the GNU General Public License…"*
  —— LGPL-3.0 把 GPL-3.0 的条款整体并入，不附上 GPL-3.0 全文，读者就无从知晓完整条款；
- 第 3 条 b 项、第 4 条 b 项都直接要求：*"Accompany the … with a copy of the GNU GPL
  and this license document."*

因为沿用了上游的 LGPL-3.0 代码（见上一节），**本项目也必须以 LGPL-3.0 授权**，
不能改成 MIT / Apache-2.0 这类宽松协议。沿用范围的逐条对照、以及上游许可声明的出处，
都在 **[NOTICE.md](NOTICE.md)**。
