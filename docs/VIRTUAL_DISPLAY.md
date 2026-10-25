# 虚拟屏执行方案

目标：Eta 的界面操作在一块用户看不见的虚拟屏上进行，用户继续正常使用手机，互不打断。当前状态：方案与可行性验证阶段，尚未实现；已先落地两项不依赖虚拟屏的改进（见文末）。

## 为什么先验证

虚拟屏方案要同时满足下面几点，任何一点在 HyperOS 上不成立，方案就要调整，因此先在真机上验证，再写代码：

1. 能以 Root 或 shell 身份创建可启动应用的虚拟屏（需要 `TRUSTED` 与 `OWN_CONTENT_ONLY` 标志）。
2. 目标应用能被启动到虚拟屏上并正常渲染（部分应用或系统组件会拒绝在非默认屏显示）。
3. 能在虚拟屏上注入点击、滑动与文本输入，输入法能在虚拟屏上弹出。
4. 无障碍服务能读到虚拟屏上的窗口与节点。
5. 能对虚拟屏截图（`FLAG_SECURE` 窗口按约定不处理）。

## 可行性验证步骤

在电脑上用 adb 完成，不需要改 Eta：

```powershell
# 1. 用 scrcpy 新建虚拟屏并在其中启动设置（scrcpy 3.x 支持 --new-display）
scrcpy --new-display=1080x2400 --start-app=com.android.settings

# 2. 查看虚拟屏 id
adb shell dumpsys display | Select-String "mDisplayId|Virtual"

# 3. 在虚拟屏上启动其他应用、点击、截图（把 <id> 换成上一步的 id）
adb shell am start --display <id> -n com.android.settings/.Settings
adb shell input -d <id> tap 540 1200
adb shell screencap -d <id> /sdcard/vd.png
```

需要记录的结果：

| 检查项 | 预期 |
|---|---|
| 设置、微信、浏览器等常用应用能否在虚拟屏启动并渲染 | 正常显示，不回到主屏 |
| `input -d` 点击与滑动是否生效 | 生效 |
| 在虚拟屏的输入框点击后输入法是否出现在虚拟屏上 | 出现（Android 10+ 支持，ROM 可能限制） |
| 主屏是否完全不受影响 | 主屏可正常使用 |
| `screencap -d` 是否得到正常画面 | 非黑屏 |
| 在 Eta 中让 Agent 调用 `observe_screen`，节点是否包含虚拟屏窗口 | 需改造后才可验证 |

## 实现设计（验证通过后）

- **虚拟屏宿主**：沿用 Eta 已有的 Root `app_process` 固定入口，常驻一个宿主进程，以隐藏 API 创建虚拟屏（`ImageReader` 作为 Surface），进程存活期间虚拟屏存在；Eta 通过 Binder 或本地 socket 控制它。
- **应用启动**：`launch_app` / `open_uri` 增加目标屏参数，虚拟屏模式下以 `ActivityOptions.setLaunchDisplayId` 或 `am start --display` 启动。
- **观察**：截图来自宿主的 `ImageReader`；UI 树用无障碍 `getWindowsOnAllDisplays()` 取虚拟屏窗口。
- **操作**：节点动作（点击、输入）照常通过无障碍执行；坐标手势用 `GestureDescription.Builder.setDisplayId()` 或宿主注入带 displayId 的输入事件。
- **调度**：手机操作权按屏幕分开，主屏与虚拟屏各一份；虚拟屏只有一块时，多个 run 仍串行使用它。
- **可见性**：浮层胶囊显示“后台屏幕执行中”，可一键把虚拟屏画面投到浮窗查看或接管。
- **回退**：应用拒绝在虚拟屏运行、输入法不可用等情况下，退回主屏执行并在胶囊中说明。

## 已落地的相关改进

- **少走界面路径**：系统提示要求能用深链、`launch_app`、设备直达工具、文件工具或终端（`am start`、`cmd`、`content`、`settings`）完成的步骤优先用它们，没有可靠的非界面途径时才操作界面。
- **接管期间暂停横幅通知**：设置 → 工具中的“操作界面时暂停横幅通知”（默认开启）。Eta 的任务或电脑端持有手机操作权期间（覆盖观察、推理与动作，交还用户时除外）暂停横幅，结束后恢复：有 Root 时临时关闭 `heads_up_notifications_enabled`，否则在已授予勿扰权限时临时切到“仅限优先事项”。原值持久化，进程意外退出后下次启动先恢复。HyperOS 是否完全遵守全局横幅设置需要真机确认。
