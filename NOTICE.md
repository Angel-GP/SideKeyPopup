# 版权声明与第三方许可

「图片弹窗」（SideKeyPopup，包名 `com.linxi.sidekeypopup`）

---

## 一、本项目的版权与许可

    SideKeyPopup — 图片弹窗
    Copyright (C) 2026 linxi

本程序是自由软件：你可以按照自由软件基金会发布的 **GNU 宽通用公共许可证**
（GNU Lesser General Public License）**第 3 版**的条款，或者（按你的选择）任何更高版本，
重新发布和/或修改它。

本程序基于「希望它有用」而发布，但**不提供任何担保**，甚至不包括适销性或特定用途
适用性的默示担保。详见 GNU 宽通用公共许可证。

你应该已经随本程序收到了一份 GNU 宽通用公共许可证的副本；如果没有，请见
<https://www.gnu.org/licenses/>。

### 英文原文

    SideKeyPopup
    Copyright (C) 2026 linxi

    This program is free software: you can redistribute it and/or modify it under
    the terms of the GNU Lesser General Public License as published by the Free
    Software Foundation, either version 3 of the License, or (at your option) any
    later version.

    This program is distributed in the hope that it will be useful, but WITHOUT
    ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
    FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more
    details.

    You should have received a copy of the GNU Lesser General Public License along
    with this program.  If not, see <https://www.gnu.org/licenses/>.

### 为什么是 LGPL-3.0

因为本项目沿用了上游 **SlimeNull/OppoCustomSideButtonFunctions** 的实现，而那个项目
是以 LGPL-3.0 授权的（见下一节）。LGPL-3.0 是**弱传染性**的（weak copyleft）：
它不阻止别人用别的方式链接你的程序，但**基于它的那部分代码本身**必须继续以
LGPL-3.0（或 GPL-3.0）授权，不能改成 MIT / Apache-2.0 这类宽松协议。

如果不想让本项目继续受 LGPL-3.0 约束，唯一的办法是**把沿用上游的那部分重写掉**，
让它不再构成衍生作品。

### 文件说明

| 文件 | 内容 |
| --- | --- |
| `LICENSE` | GNU 宽通用公共许可证第 3 版全文（LGPL-3.0） |
| `COPYING` | GNU 通用公共许可证第 3 版全文（GPL-3.0） |
| `NOTICE.md` | 本文件：版权声明与第三方作品归属 |

**两份都要带**，理由在许可证原文里：

- 序言（第 8 行）：*"This version of the GNU Lesser General Public License incorporates
  the terms and conditions of version 3 of the GNU General Public License…"*
  —— LGPL-3.0 把 GPL-3.0 的条款整体并入，不附上 GPL-3.0 全文，读者就无从知晓完整条款；
- 第 3 条 b 项、第 4 条 b 项都直接要求：*"Accompany the … with a copy of the GNU GPL
  and this license document."*

> 许可证全文取自 [SPDX license-list-data](https://github.com/spdx/license-list-data)
> 的 `LGPL-3.0-only.txt` 与 `GPL-3.0-only.txt`，是官方原文，未作任何改动。

---

## 二、第三方作品：SlimeNull/OppoCustomSideButtonFunctions

| | |
| --- | --- |
| 项目 | OppoCustomSideButtonFunctions（一加侧键功能） |
| 作者 | **SlimeNull Issac** |
| 仓库 | <https://github.com/SlimeNull/OppoCustomSideButtonFunctions> |
| 许可 | **GNU Lesser General Public License v3.0** |

### 沿用的部分

上游用 Xposed/LSPosed hook 系统策略，并保留一条「直接读 `/dev/input`」的兜底路径。
本项目的侧键读取沿用了后者的**事件结构体解析方式**，具体在：

- `app/src/main/java/com/linxi/sidekeypopup/input/InputEventStream.java`
  —— 判断 `struct input_event` 的大小（64 位 ABI 下 24 字节，32 位下 16 字节）、
  由此推出 `type` / `code` / `value` 三个字段的偏移，以及 `EV_KEY = 1` 的用法，
  沿用上游 `app/src/main/java/.../xposed/RawInputEventReader.kt` 的做法。

本项目对这段的改动：改写成 Java；从「一个自带线程的读取器」拆成**无状态的纯解析器**
（`read(InputStream, Callback, BooleanSupplier)`）；增加了跨 `read()` 调用的
半包拼装（上游用 `readFully` 一次读满一个事件）；64 位判断改用 `Process.is64Bit()`，
`Build.SUPPORTED_64_BIT_ABIS` 只作为兜底。

### 本项目独立实现的部分

除上面那一处外，其余都是本项目自己写的，与上游的实现方式不同：

| | 上游 | 本项目 |
| --- | --- | --- |
| 拦截方式 | Xposed hook 系统策略 + 读 `/dev/input` 兜底 | 无障碍 / Root / Shizuku 三条路 |
| 动作 | 打开 App、快捷方式、手电筒、摩斯电码、光标控制… | 只弹一张悬浮图片 |
| 手势 | 单击 / 双击 / 长按 / 组合键 | 只有单击（长按交给系统） |
| 配置 | 跨进程共享偏好 + 远程文件 | 同进程 SharedPreferences |

### 上游许可声明的来源，以及它的一个缺陷

上游仓库**没有 `LICENSE` 或 `COPYING` 文件**，GitHub API 返回的 `license` 字段是 `null`，
也就是说 GitHub 识别不出它的协议。上面「LGPL-3.0」这个结论来自上游**应用内的声明**：

- `app/src/main/java/com/slimenull/customsidebuttonfunctions/MainActivity.kt`
  的「关于」页里有 `GNU Lesser General Public License v3.0` 字样，
  以及指向 <https://www.gnu.org/licenses/lgpl-3.0.html> 的「许可证」按钮；
- 上游 `README.md` 也提到「关于」页提供 GNU Lesser General Public License v3.0 许可证页面。

**在应用界面里声明协议是有法律效力的**，所以本项目按 LGPL-3.0 处理。
但严格说，上游缺一份随仓库分发的许可证副本，这本身不符合 LGPL-3.0 对分发者的要求。
如果你要正式发布本项目，稳妥的做法是同时联系上游作者，请他把 `LICENSE` 文件补进仓库。

---

## 三、其他依赖

以下库以二进制依赖的形式使用，各自保留其原有许可，本项目不对它们做任何改动：

| 依赖 | 用途 |
| --- | --- |
| `androidx.appcompat:appcompat` | 界面基类 |
| `com.google.android.material:material` | Material 控件 |
| `dev.rikka.shizuku:api` / `:provider` | Shizuku 跨进程调用 |

具体条款以各依赖自身随附的许可文件为准。

---

*本文件最后更新：2026-10-11*
