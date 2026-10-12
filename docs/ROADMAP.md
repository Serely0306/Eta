# 路线与待办

本分支（`hyperos4-adaptation`）Agent 能力改进的阶段规划与待办。已完成功能的测试用例见 [E2E_TESTING.md](E2E_TESTING.md) 的「功能与用例对齐」表。

## 已完成

| 阶段 | 内容 |
|---|---|
| 一 | 缓存断点与 `prompt_cache_key`、会话内记忆快照、单项工具开关、ask_user |
| 修复 | 无障碍重绑后结果卡片、小爱/小布会话归组、文件工具默认 Root、终端参数分级校验 |
| 二 | 多会话并发、屏幕与浏览器租约、浮层归属 |
| 三 | 设备端 MCP 服务：电脑端 Agent 经 USB 或局域网直接调用设备工具，或把任务委托给 Eta 的 Agent（见 [MCP_SERVER.md](MCP_SERVER.md)），待真机验证 |
| 四 | 计划工具 `update_plan` 与子 Agent `run_subagents`（见 [AGENT_RUNTIME.md](AGENT_RUNTIME.md)「计划与子 Agent」），待真机验证 |
| 五 | 用户配置的模型回退链，最多 3 个备用模型（见 [AGENT_RUNTIME.md](AGENT_RUNTIME.md)「模型回退链」），待真机验证 |
| 六 | 自动任务：定时、间隔、单次与通知触发，Agent 可用 `manage_automations` 自行创建（见 [AGENT_RUNTIME.md](AGENT_RUNTIME.md)「自动任务」），待真机验证 |
| 七（部分） | 系统提示优先非界面路径；操作界面时暂停横幅通知。虚拟屏执行完成方案与可行性验证步骤（见 [VIRTUAL_DISPLAY.md](VIRTUAL_DISPLAY.md)） |

## 后续阶段

- 虚拟屏执行界面操作：先按 VIRTUAL_DISPLAY.md 在真机上验证，通过后实现
- 超级小爱语音接管（可行时）

## 待办

| 项 | 说明 | 时机 |
|---|---|---|
| 多标签页 Agent 浏览器 | 每个会话一个 WebView 标签页（最多 3 个，LRU 回收，Cookie 共享），浏览器页面支持切换标签；完成后去掉 `BROWSER` 租约。改动集中在 `AgentBrowserSession` 与浏览器页面 | 暂缓 |
| 端到端测试落地 | 按 E2E_TESTING.md 实现 debug 测试入口、状态 Provider、Mock 模型服务与运行器，完整执行一轮 | 现有需求全部实现后 |
| CI 上传 Debug APK | `pr-check.yml` 在手动触发时上传 Debug APK，供真机测试取包 | 随端到端测试落地 |
| MCP 操作界面时的可视提示 | 电脑端经 MCP 操作屏幕时显示边缘流光或胶囊，便于用户察觉 | MCP 基础功能完成后 |
