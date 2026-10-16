# 设备端 MCP 服务

Eta 可以作为 MCP 服务器，让电脑上的 Agent（Claude Code、Codex 等）直接调用手机上的设备工具，不必经由 adb 拼命令。入口：Eta → MCP → 让电脑端连接 Eta。

## 连接

服务默认关闭，开启后会自动生成访问令牌。Claude Code 和 Codex 都需要先把 Eta 登记进各自的 MCP 配置，登记一次即可，之后每次启动都会自动连接。页面上的两个复制按钮分别给出这两步需要的内容。

**1. 打通网络**

- USB（默认，只监听手机本机）：在电脑上执行 `adb forward tcp:18765 tcp:18765`。手机重新插拔或 adb 服务重启后，需要再执行一次。
- 局域网：打开“允许局域网连接”，页面会显示手机的局域网地址，下文的地址相应换成 `http://<手机 IP>:18765/mcp`。

**2. 登记服务器**

Claude Code：在电脑终端执行“复制 Claude Code 连接命令”得到的内容。USB 模式下复制内容已包含 adb forward。

```powershell
adb forward tcp:18765 tcp:18765
claude mcp add -s user --transport http eta http://127.0.0.1:18765/mcp --header "Authorization: Bearer <令牌>"
```

`-s user` 把服务器写入用户级配置，任意目录启动 Claude Code 都能使用；省略时默认只对当前目录生效。若已登记过同名服务器（例如重新生成令牌后），先执行 `claude mcp remove -s user eta`。

Codex：把“复制 Codex 配置”得到的片段追加到电脑上的 `~/.codex/config.toml`（Windows 为 `%USERPROFILE%\.codex\config.toml`）。同一文件里不能出现两个 `[mcp_servers.eta]`，更新令牌时请直接替换原片段。

```toml
[mcp_servers.eta]
url = "http://127.0.0.1:18765/mcp"
http_headers = { Authorization = "Bearer <令牌>" }
```

如果不想把令牌写进配置文件，也可以先把令牌放进环境变量 `ETA_MCP_TOKEN`，再执行 `codex mcp add eta --url http://127.0.0.1:18765/mcp --bearer-token-env-var ETA_MCP_TOKEN`。启动 Codex 的进程必须能读到这个环境变量。

**3. 确认连接**

- 电脑端：执行 `claude mcp list`（或在 Claude Code 会话中输入 `/mcp`），或执行 `codex mcp list`；也可以用 `claude mcp get eta` 查看单个服务器。令牌不对时，Claude Code 会提示 HTTP 401。
- 手机端：服务运行时，页面上的“电脑端连接”会列出最近 30 分钟内连接过的客户端，包括客户端声明的名称和最近一次请求的时间；收到令牌缺失或不符的请求时也会显示一条“被拒绝”记录。服务停止或重启后，这些记录会清空。

## 开放的工具

与 Eta 自身 Agent 看到的本地工具目录一致，并遵守同一套设置：

- 工具设置里的分类开关（终端/文件、网页与浏览器、设备直达、敏感读取与操作）和单项开关；
- Root、无障碍、通知访问等运行条件，不满足的工具不出现在列表中；
- Skill 相关工具不开放（服务于 Eta 自身的指令体系）。

界面类工具操作的是手机的真实屏幕，与手机上的 Eta 任务共用屏幕租约：同一时刻只有一方能操作界面，另一方最多等待 60 秒后得到 `SCREEN_BUSY`。`browser_use` 同理共用 Agent 浏览器。

每个 MCP 会话独占一个工具执行环境（屏幕观察缓存、终端会话等），会话空闲 30 分钟后回收，最多同时保留 8 个。

## 委托任务

除了直接调用设备工具，电脑端也可以把一项多步任务交给 Eta 自己的 Agent 执行：

| 工具 | 用途 |
|---|---|
| `eta_task_start` | 提交任务描述，立即返回 `task_id`；最多等待 `wait_seconds`（默认 30，最大 90）秒，任务结束或需要回答问题时提前返回 |
| `eta_task_status` | 查询状态、最近 20 个步骤、待回答问题与最终结果，同样支持有界等待 |
| `eta_task_answer` | 回答 Eta 的提问：`text` 自由回答，`selections` 选项标签（单题一维、多题二维），`skip=true` 不回答 |
| `eta_task_steer` | 运行中追加要求 |
| `eta_task_stop` | 停止任务 |

- 状态：`running`、`waiting_for_answer`、`completed`、`failed`、`stopped`。
- 任务使用 Eta 的默认模型，工具范围与工具设置一致；与手机上的任务一起受最多 3 个并发的限制，界面操作同样经屏幕租约串行。
- 传入相同的 `conversation_id` 可以连续对话；同一会话同时只能有一个任务在执行。会话上下文保存在内存中，Eta 进程重启后从新上下文开始。
- 提问同时出现在手机的胶囊上，电脑端与用户谁先回答以谁为准。
- 任务结束后归档进 Eta 侧栏的“电脑端”分组；已结束的任务记录保留 30 分钟供查询。

## 协议

- Streamable HTTP，路径 `/mcp`，只用 JSON 响应，不提供服务端推送（`GET` 返回 405）。
- 旧协议 2024-11-05 至 2025-11-25：`initialize` 协商版本并分配 `Mcp-Session-Id`；会话失效返回 404，客户端按规范重新初始化；`DELETE` 结束会话。
- 2026-07-28：不经 `initialize`，以 `MCP-Protocol-Version` 头或 `_meta` 声明版本，结果带 `resultType`。
- 支持 `ping`、`tools/list`、`tools/call`；不支持批量请求。

## 安全

- 令牌始终必需：即使只监听本机，同一手机上的其他应用也能连到回环地址。令牌只保存在 Eta 私有配置中，可随时重新生成，旧令牌立即失效。
- 带 `Origin` 头的请求一律拒绝，防止网页经 DNS 重绑定访问本地端口。
- 局域网模式是明文 HTTP，令牌在同一网络内可被嗅探；只在可信网络中开启，或改用 USB 模式。
- 单个请求正文上限 8 MB，同时处理的请求最多 4 个。

## 生命周期

- 服务运行在 Eta 进程内，运行期间持有前台执行服务，通知栏可见“执行中”通知；在通知中点停止会关闭服务并把开关记为关闭。
- 前台服务只能从前台启动：开关打开后，每次打开 Eta 时按设置恢复服务；手机重启后需要打开一次 Eta。
- 前台服务启动失败时状态会提示“Eta 在后台时可能被系统停止”。
