# 端到端测试方案

本文档描述 Eta 在真机上的端到端（E2E）测试方案。单元测试只覆盖到纯逻辑，跨进程 IPC、浮层窗口、无障碍、Root、系统助手入口和跨 run 资源租约只能在设备上验证。目标是让同一套用例既能由 PC 通过 adb 驱动，也能在 MCP 接口落地后交给 PC 侧 Agent（Claude Code、Codex）或 Eta 自测执行。

当前状态：方案阶段，测试钩子与驱动脚本尚未实现。按计划在现有需求全部实现后统一落地并完整执行一轮；在此之前，每个功能实现时同步维护本文档的用例。

## 功能与用例对齐

规则：

- 每个功能提交同时更新本文档：新增或修改对应用例，并在下表登记功能、提交和用例 ID。没有可测行为的重构不登记。
- 功能行为变化时同步修改已有用例的断言，不保留与现状不符的用例。
- 用例依赖新的测试钩子能力（指令、状态表字段、日志字段）时，同时补到「需要新增的测试钩子」一节，保证统一落地时钩子与用例一致。
- 执行状态只在统一执行后填写；执行前一律为“未执行”。

| 功能 | 提交 | 用例 | 执行状态 |
|---|---|---|---|
| Anthropic 缓存断点、OpenAI `prompt_cache_key` | `87727fa` | F1、F3 | 未执行 |
| 会话内记忆快照冻结 | `6e8826a` | F2 | 未执行 |
| 单项工具开关与依赖 | `e5861ca` | D1–D3 | 未执行 |
| 运行中提问 ask_user | `024588f`、`fda66a1` | C1–C4、C6 | 未执行 |
| 无障碍重绑后结果卡片重取窗口 | `dc99056` | B4 | 未执行 |
| 小爱、小布会话在侧栏单独归组 | `afd4780` | G1、G3 | 未执行 |
| 文件工具在 Root 可用时默认 Root | `e80f83c` | E1、E2 | 未执行 |
| 终端参数分级校验 | `02b37ac` | E3、E4 | 未执行 |
| 多会话并发、屏幕与浏览器租约、浮层归属 | `9875539` | A1–A10、B1–B3、B5、C5、G2 | 未执行 |
| 真实模型冒烟 | — | H1–H3 | 未执行 |
| 设备端 MCP 服务：设备工具、令牌鉴权、会话与设置页 | `ee6391e` | I1–I10 | 未执行 |
| 设备端 MCP 服务：委托任务与“电脑端”会话分组 | `6dd1dc0` | I11–I15 | 未执行 |
| 设备端 MCP 服务：切换局域网开关后立即重绑端口 | `1ada506` | I16 | 未执行 |
| 设备端 MCP 服务：电脑端连接情况与复制命令 | `89867a6` | I17–I18 | 未执行 |
| 设备端 MCP 服务：出口脱敏、可逆占位与截图开关 | `13bbbcf` | I19–I22 | 未执行 |
| 设备端 MCP 服务：委托任务限定可调用的模型 | `d3c4b2f` | I23–I26 | 未执行 |
| 第三方 Responses 端点发送会话级 `prompt_cache_key`（服务商可关闭）；委托任务状态带模型用量 | `7daef5f` | F3、F4、I27、I28 | 未执行 |
| 备用模型、厂商助手模型、MCP 可调用模型的选择按服务商分组收纳 | `fa6b48d` | K10、I29 | 未执行 |
| 思考强度：全局默认、“服务商默认”说明、Eta 语音 / 厂商助手 / MCP / 自动任务 / 备用模型各自的模型与强度，`eta_task_start` 的 `reasoning_effort` | `c0a1193` | F5、F6、I30、I31、K11、L11 | 未执行 |
| 用量账本：所有模型请求入账（含压缩、子 Agent、改写、转写润色），与会话独立；委托任务用量改读账本 | `afe334c` | N1–N4 | 未执行 |
| 用量统计：价格表（官方价注明来源）、汇率与倍率折算美元、看板、使用记录、CSV 导出、额度提醒 | 本提交 | N5–N9 | 未执行 |
| 小爱 8.2.65 唤醒与电源键语音交给 Eta | `7010ad3` | G4–G7 | 未执行 |
| 计划工具与子 Agent | `e2f14b8`、`c359ab3` | J1–J9 | 未执行 |
| 模型回退链 | `1548476` | K1–K9 | 未执行 |
| 自动任务（定时与通知触发） | `5d5a25b`、`0885c4d` | L1–L10 | 未执行 |
| 接管期间暂停横幅、优先非界面路径 | `edd96a1` | M1–M6 | 未执行 |

后续阶段的用例在实现时补充；虚拟屏执行实现时在 M 组追加。

## 设计原则

- **确定性优先**：被测 run 默认接脚本化的 Mock 模型，每一步工具调用由剧本决定；真实模型只用于少量冒烟用例，且只断言终态。
- **观测不靠猜**：断言来自结构化状态和事件，不靠截图比对文字。截图和 UI dump 只作为失败时的现场材料。
- **不碰敏感数据**：驱动端不拉取 DataStore、LSPosed 配置库等含明文 API Key 的文件；日志与请求记录只保留事件类型、run id、工具名和长度，不保留用户输入、模型输出和语音内容。
- **状态可恢复**：用例修改的系统设置（无障碍服务列表、动画缩放、亮屏策略）在开始前备份、结束后恢复，测试会话统一带 `[E2E]` 前缀，结束后可批量清理。
- **不在本地构建**：被测 APK 取自 GitHub Actions 产物。

## 总体结构

| 通道 | 方向 | 用途 |
|---|---|---|
| `adb shell am start` | PC → 设备 | 向 E2E 指令入口发送提交、停止、回答、追加等指令 |
| `adb shell content query` | 设备 → PC | 从 E2E 状态 Provider 读取活动 run、浮层归属、租约、待答问题 |
| `adb logcat -s EtaE2E Eta` | 设备 → PC | 结构化事件流与排查日志 |
| `adb reverse` | 设备 → PC | 被测 run 的模型请求发往 PC 上的 Mock 模型服务 |
| `screencap` / `uiautomator dump` / `dumpsys window` | 设备 → PC | 失败现场材料 |

### 驱动端

首选 PC + adb。运行器放在 `tools/e2e/`，Python 3 标准库实现，只依赖 adb 可执行文件。Windows 上用 PowerShell 启动（profile 已设 UTF-8），不要经 Git Bash 启动，避免中文输出被转成 GBK。

后续两种扩展执行方式见「执行方式」一节。

### 被测包

`pr-check.yml` 当前只在失败时上传报告。需要新增一步：`workflow_dispatch` 触发时上传 `app/build/outputs/apk/debug/*.apk` 为 `eta-debug-apk` 产物（保留 3 天）。驱动端用 `gh run download <run-id> -R Serely0306/Eta -n eta-debug-apk` 取包后 `adb install -r`。

Debug 包是 debuggable 的，可以用 `adb shell run-as io.github.mangi.eta` 读取私有目录中的测试产物，不依赖 Root。

### Mock 模型服务

PC 上运行一个 OpenAI 兼容服务（`/v1/chat/completions`、`/v1/responses`，以及 Anthropic 的 `/v1/messages`），通过 `adb reverse tcp:18080 tcp:18080` 暴露为设备上的 `http://127.0.0.1:18080`。`network_security_config` 已允许明文流量。

- 每个用例附一份剧本：按轮次返回文本或工具调用，例如第 1 轮 `observe_screen`，第 2 轮 `tap_element`，第 3 轮最终回复。
- 剧本可以设置延迟，用于制造并发重叠（例如让 run A 在持有屏幕租约时停留 20 秒）。
- 服务记录每个请求的结构摘要：工具列表、system 块数量与哈希、`cache_control` 位置、`prompt_cache_key` 是否存在、消息条数。只存哈希和结构，不存正文。
- Eta 侧通过 E2E 指令入口新增一个独立的 Provider 条目 `e2e-mock` 并只在测试 run 上使用，不修改用户已有的 Provider 和 Key。

## 需要新增的测试钩子

全部放在 `app/src/debug/`，release 构建不包含。

### E2E 指令入口

一个 debug 专用的 exported Activity（无界面、`noHistory`），用 `am start` 传参。文本参数统一用 Base64，绕开 `adb shell` 对中文和空格的转义问题。

| 指令 | 参数 | 行为 |
|---|---|---|
| `submit` | `conversation`（`new` 或已有 id）、`prompt_b64`、`provider`（可选） | 走 App 内的正常发送路径，覆盖会话通道与恢复逻辑 |
| `submit_entry` | `source`（如小爱来源常量）、`prompt_b64` | 以系统入口的 handoff 来源发起 run，模拟小爱/小布入口，不依赖 Hook |
| `stop` | `run_id` 或 `conversation` | 停止指定 run |
| `steer` | `run_id`、`text_b64` | 运行中追加要求 |
| `answer` | `run_id`、`question_id`、`selection` 或 `text_b64` | 回答 ask_user |
| `set_tools` | `disabled`（逗号分隔） | 设置工具开关 |
| `setup_mock` | `base_url`、`api`（openai/anthropic）、`as_default`（可选） | 写入 `e2e-mock` Provider；`as_default=true` 时临时设为默认模型（MCP 委托任务等走默认模型的入口需要），`cleanup` 时恢复 |
| `cleanup` | 无 | 删除 `[E2E]` 会话与 `e2e-mock` Provider，恢复工具开关 |
| `timeouts` | `ask_user_ms`、`lease_wait_ms`、`lease_idle_ms` | 缩短超时，便于测超时分支；进程重启后失效 |

入口运行在 Eta 自己的进程内，经 `AgentRuntimeClient` 与服务通信，因此能通过 `isMessageSenderAllowed` 的 uid 校验，与 App 真实路径一致。adb 无法直接绑定 Runtime 服务，这也是需要该入口的原因。

### E2E 状态 Provider

一个 debug 专用的 ContentProvider，权限设为 `android.permission.DUMP`（adb shell 具备），用 `adb shell content query --uri content://io.github.mangi.eta.e2e/<表>` 读取：

| 表 | 字段 |
|---|---|
| `runs` | run id、通道、阶段、状态、是否已操作前台、开始时间 |
| `overlay` | 浮层 run id、胶囊/流光/结果卡片是否存在、`otherRunCount`、结果卡片对应会话 |
| `leases` | 屏幕与浏览器租约持有者、使用中计数、空闲时长 |
| `questions` | 待回答问题的 run id、question id、选项数量 |
| `events` | 最近 200 条事件：时间、run id、事件类型、工具名、错误码 |
| `conversations` | 会话 id、标题长度、`entrySource`、是否运行中 |

不返回任何文本内容。

### 结构化日志

在 debug 构建下，以 `EtaE2E` tag 输出单行事件：

```
E2E|run=<id>|lane=<lane>|event=<type>|tool=<name>|code=<error_code>
```

驱动端用 `adb logcat -v epoch -s EtaE2E:V Eta:V` 按用例时间窗切片。`Eta` tag 是现有业务日志（`ModuleConfig.TAG`），只用于失败排查。

## 用例清单

标记：`M` 为确定性用例（接 Mock 模型或直接调用工具，不依赖真实模型），`R` 需真实模型、真实系统入口或真实客户端，`慢` 表示耗时超过 1 分钟。

### A. 并发运行与资源租约

| ID | 场景 | 断言 |
|---|---|---|
| A1 `M` | 会话 X、Y 先后提交，剧本均为纯文本多轮 | `runs` 同时出现两个 run；两个会话各自收到结果；互不取消 |
| A2 `M` | 会话 X 运行中再次提交 | 旧 run 以停止结束，新 run 接管；浮层不出现旧 run 的停止卡片 |
| A3 `M` | 已有 3 个不同通道的 run，再提交第 4 个 | 第 4 个立即失败并给出上限提示；已有 3 个不受影响 |
| A4 `M` | run A 执行 `observe_screen` 后剧本停 20 秒；run B 此时调用 `tap_element` | B 等待期间 `leases.SCREEN=A`；A 结束后 B 继续执行，B 的事件晚于 A 的终态 |
| A5 `M` `慢` | A 持有屏幕租约并持续使用；B 请求屏幕 | 默认 60 秒后 B 的工具结果为 `SCREEN_BUSY`（可用 `timeouts` 缩短） |
| A6 `M` | A 用完屏幕工具后转为纯文本思考；B 请求屏幕 | 空闲超过 45 秒后 B 取得租约（可缩短）；A 再次请求屏幕时反过来等待 |
| A7 `M` | A、B 同时调用 `browser_use` | 只有一方持有 `BROWSER`；另一方等待或 `BROWSER_BUSY` |
| A8 `M` | 停止会话 X 的 run | 只有 X 的 run 终止；Y 继续 |
| A9 `M` | 两个会话运行中，按返回键退出 Eta 再从桌面打开 | 两个 run 都被重新挂接，事件继续进入各自会话；结束后都有结果 |
| A10 `M` | 运行中等待屏幕租约时停止该 run | 等待立即结束，不再执行界面操作 |

### B. 浮层与结果卡片

| ID | 场景 | 断言 |
|---|---|---|
| B1 `M` | A 操作界面期间 B 启动（纯文本） | `overlay.run=A`，`otherRunCount=1`，胶囊显示“另有 1 个” |
| B2 `M` | A 结束出结果卡片，B 随后开始操作界面 | 卡片内容保持 A 的结果；B 的胶囊出现；关闭卡片后 B 的胶囊仍在 |
| B3 `M` | A 结束出结果卡片，B（未操作界面）随后结束 | A 的卡片不被收走 |
| B4 `R` | 运行中让无障碍服务重绑（备份并重写 `enabled_accessibility_services` 后恢复） | 结果卡片仍出现；无障碍不可用时回退为普通浮窗 |
| B5 `M` | 浮层 run 结束且没有结果卡片，B 仍在运行 | 浮层交给 B；所有 run 结束且无窗口后服务停止 |

### C. 运行中提问（ask_user）

| ID | 场景 | 断言 |
|---|---|---|
| C1 `M` | App 内发起，主界面可见时提问 | 会话面板出现问题，胶囊不弹出；`answer` 后 run 继续 |
| C2 `M` | App 内发起后切到桌面再提问 | 胶囊进入提问模式；在胶囊上选择后问题消失 |
| C3 `M` `慢` | 提问后不回答 | 默认 10 分钟后以超时结束等待（可缩短），模型收到超时结果 |
| C4 `M` | 提问挂起时 `steer` | 文本作为自由回答；问题关闭 |
| C5 `M` | A 操作界面期间 B 提问（用户不在 Eta） | 浮层切到 B 并展示问题；回答后 B 继续，A 不受影响 |
| C6 `M` | 系统入口发起的 run 提问 | 走胶囊，不依赖主界面 |

### D. 工具开关与提示词

| ID | 场景 | 断言 |
|---|---|---|
| D1 `M` | 关闭 `observe_screen` 后发起 run | Mock 收到的工具列表不含界面工具；system 中含“请用户手动操作界面”的说明 |
| D2 `M` | 剧本强行调用已关闭的工具 | 工具结果为 `TOOL_DISABLED`，run 继续 |
| D3 `M` | 关闭被依赖工具 | 依赖它的工具一并从列表中消失 |

### E. 文件身份与终端参数

| ID | 场景 | 断言 |
|---|---|---|
| E1 `M` | Root 可用，文件工具读写 `/data/local/tmp/eta-e2e/` | 成功，`events` 记录以 Root 身份执行 |
| E2 `M` | 文件工具读写工作区内相对路径 | 以应用身份执行 |
| E3 `M` | `terminal` 的 `run` 动作带多余字段 | 执行成功，结果含 `ignored_arguments` |
| E4 `M` | `close` 动作带 `command` | 被拒绝，错误说明字段只适用于哪些动作 |

### F. 缓存与上下文

| ID | 场景 | 断言 |
|---|---|---|
| F1 `M` | Anthropic 格式 Mock，多轮工具调用 | 工具列表末尾、system 末块、观察块之前的最后稳定块带 `cache_control`；前缀哈希逐轮不变 |
| F2 `M` | 同一会话中途修改记忆 | 本会话后续轮次的 system 哈希不变；新会话才反映修改 |
| F3 `M` | Mock 服务（非 api.openai.com）分别以 Chat Completions 与 Responses 两种端点模式各跑两轮；再在 Responses 服务商设置中关闭“发送缓存路由键”重复一次 | Chat Completions 请求不带 `prompt_cache_key`；Responses 请求带，且同一会话两轮的值相同、不同会话不同；关闭开关后 Responses 请求也不带 |
| F4 `M` | Responses 服务商的自定义请求体中显式设置 `prompt_cache_key` | 请求中的值保持用户设置，不被会话路由键覆盖 |
| F5 `M` | Mock 模型在内置目录中有 Low–Max 档位；“默认思考强度”保持“服务商默认”发一轮；改为 High 后新建会话再发一轮；改为 XHigh，模型不支持 XHigh 时再发一轮 | 第一轮请求与改动前完全一致（Responses 只有 `reasoning.summary`）；第二轮新会话初始强度为 High，请求带 `reasoning.effort=high`；第三轮按最接近的较低档发送；已有会话的强度不受影响 |
| F6 `M` | 设置 Eta 语音模型为 `e2e-mock-b`、强度 Low；厂商助手强度 High；分别以 Eta 语音和小爱入口提交 | 语音入口请求发往 `e2e-mock-b` 且 `effort=low`；小爱入口 `effort=high`；关闭“允许思考”后两者都不带强度且为 Off |

### G. 系统入口与会话归组

| ID | 场景 | 断言 |
|---|---|---|
| G1 `M` | `submit_entry` 以小爱来源连续发起两次 | 第二次替换第一次；会话列表中两条都归入“超级小爱”分组，不进入普通会话区 |
| G2 `M` | 小爱入口 run 与 App 会话 run 同时存在 | 互不替换，A1 的断言成立 |
| G3 `R` | 真实唤醒超级小爱并下达任务 | Hook 链路可用；结果出现在“超级小爱”分组；需人工发起语音 |
| G4 `R` | 小爱 8.2.65（508002065）开启自定义模型、关闭强制前缀，分别用“小爱同学”唤醒和按住电源键说话 | 最终识别后由 Eta 回答，结果出现在“超级小爱”分组；没有原生回答、卡片或第二段播报；电源键按住与松手的语义不变 |
| G5 `R` | 关闭自定义模型，或开启强制前缀后不带前缀提问；重启小爱进程后再测 | 完全回到原生回答，没有 Eta 结果，原生操作指令也没有被吞掉 |
| G6 `R` | 在原生支持的时机取消一轮；随后用不同语句、再用相同语句各问一轮 | 被取消的一轮没有产生 Eta 任务；后两轮各自独立认领，相同文本不会被去重吞掉 |
| G7 `R` | Eta 回答结束后，在 2 分钟内再次唤醒小爱提问；同时观察上一轮的界面能否正常收尾 | 上一轮没有迟到的原生回答或播报；新一轮正常；原生对话框能正常关闭（认领期间会屏蔽 Dialog.Finish，这一项需重点观察） |

### H. 真实模型冒烟

| ID | 场景 | 断言 |
|---|---|---|
| H1 `R` | “打开设置里的电池页面” | run 成功结束；最后前台包为设置 |
| H2 `R` | “用终端查看剩余存储空间” | run 成功结束；调用过 `terminal` |
| H3 `R` | 两个会话同时执行 H1、H2 | 都成功；没有 `SCREEN_BUSY` 以外的错误码 |

### I. 设备端 MCP 服务

这一组不需要测试入口：驱动端经 `adb forward` 直接以 HTTP 调用 MCP 服务，开关与令牌在 Eta 的 MCP 页面操作（统一落地时可加 `mcp_server` 指令自动化）。委托任务（I11–I15）走默认模型，需先 `setup_mock` 并带 `as_default=true`。

| ID | 场景 | 断言 |
|---|---|---|
| I1 `M` | 服务关闭；开启（仅本机）；再开启局域网 | 关闭时端口不监听；仅本机时 `127.0.0.1` 可连、手机局域网地址不可连；开启局域网后两者都可连 |
| I2 `M` | 不带令牌、错误令牌、带 `Origin` 头 | 分别返回 401、401、403 |
| I3 `M` | `initialize`（2025-11-25）后 `tools/list` | 返回 `Mcp-Session-Id`；工具列表与工具设置一致，关闭某工具或分类后从列表消失；不含 `skills_*` |
| I4 `M` | 不经 `initialize`，以 2026-07-28 头请求 `tools/list` | 结果带 `resultType: complete`；可在 Eta 中把自身添加为 MCP 服务器交叉验证 |
| I5 `M` | `tools/call` 调用 `terminal` 执行命令；调用一个会失败的工具 | 前者 `isError=false` 且有输出；后者 `isError=true`，文本含错误码 |
| I6 `M` | MCP 会话 `observe_screen` 后，手机上的 Eta run 调用 `tap_element`；反向再测一次 | 后到的一方等待，前者空闲让出后继续；超时得到 `SCREEN_BUSY` |
| I7 `M` | 重新生成令牌；修改端口 | 旧令牌 401；旧端口不再监听，新端口可连 |
| I8 `M` | 在执行通知中点停止 | 服务停止，MCP 页开关显示关闭；再次打开 Eta 不自动启动 |
| I9 `M` `慢` | 会话空闲超过 30 分钟后继续调用 | 返回 404；客户端重新 `initialize` 后正常 |
| I10 `R` | Claude Code 分别经 USB 与局域网连接，完成一次“观察屏幕并点击” | 连接成功，工具调用生效 |
| I11 `M` | `eta_task_start`（Mock 剧本为两步工具调用后回复），`wait_seconds=0` 后用 `eta_task_status` 等待 | 先返回 `running`；随后 `completed`，`result` 为剧本回复，`recent_steps` 含两步 |
| I12 `M` | 剧本中调用 ask_user；分别用电脑端 `eta_task_answer` 与手机胶囊回答 | 状态为 `waiting_for_answer` 并带问题；任一端回答后问题关闭、任务继续，另一端的迟到回答被忽略 |
| I13 `M` | 同一 `conversation_id` 连续两个任务；第一个执行中再提交第二个 | 第二个任务能引用第一个任务的上下文；执行中重复提交返回 `CONVERSATION_BUSY` |
| I14 `M` | 两个不同 `conversation_id` 的任务与一个 App 会话任务同时运行；再提交第 4 个 | 三者并行互不替换；第 4 个以并发上限失败（同 A3） |
| I15 `M` | `eta_task_stop`；任务结束后打开 Eta | 状态为 `stopped`；侧栏“电脑端”分组出现该会话的归档 |
| I16 `M` | 服务运行时快速连续切换“允许局域网连接”三次 | 每次切换后服务都显示运行中，不出现 Address already in use；最终监听范围与开关一致 |
| I17 `R` | 按页面复制的命令登记 Claude Code（USB 与局域网各一次），执行 `claude mcp list`；在 Codex 中追加复制的配置后执行 `codex mcp list` | 两个客户端都显示已连接；手机页面“电脑端连接”列出对应客户端名称和“刚刚” |
| I18 `M` | 用错误令牌请求一次；等待超过 1 分钟；停止服务后再开启 | 页面出现“被拒绝”记录；时间变为“N 分钟前”；重新开启后记录清空 |
| I19 `M` | 默认设置下 `terminal` 执行 `echo 13812345678 a@b.com`；再执行 `printf %s [PHONE_1] \| wc -c` | 前者输出为 `[PHONE_1] [EMAIL_1]`；后者输出 `11`，证明参数在手机上还原成了 11 位号码 |
| I20 `M` | 参数中带一个从未出现过的 `[PHONE_999]` 调用 `terminal` | `isError=true`，错误码为 `UNKNOWN_PLACEHOLDER`；命令没有执行 |
| I21 `M` | 默认设置下 `observe_screen` 带 `include_screenshot=true`；`browser_use` 截图；查看 `tools/list`；开启“允许返回截图与图片”后重复 | 关闭时没有 image 内容，`observe_screen` 仍返回 UI 树，浏览器结果附“已省略”说明，列表中没有 `read_image`；开启后图片返回、`read_image` 出现 |
| I22 `M` | 取得一个占位符后切换“允许局域网连接”；关闭“号码类信息脱敏”后重复 I19 | 切换后占位符仍可还原；关闭脱敏后输出原值，传回旧占位符仍可还原 |
| I23 `M` | 不勾选任何模型时查看 `tools/list` 中的 `eta_task_start`，并带 `model` 调用一次 | schema 中没有 `model`；带 `model` 时返回 `INVALID_ARGUMENT`；不带时任务走默认模型（同 I11） |
| I24 `M` | 准备两个 Mock 模型（`e2e-mock-a`、`e2e-mock-b`，剧本中各自回复自己的名字），只勾选两者；分别不带 `model` 和带 `e2e-mock-b` 启动任务 | `model.enum` 只含这两个 id；前者 `model.id` 为列表中靠前的模型，结果为它的回复；后者结果为 `e2e-mock-b` 的回复 |
| I25 `M` | 只勾选 `e2e-mock-a`，并把它的剧本设为持续失败；回退链中放入未勾选的 `e2e-mock-b` | 任务以失败结束，Mock 服务没有收到发往 `e2e-mock-b` 的请求 |
| I26 `M` | 勾选 `e2e-mock-a` 后在模型提供商中停用它，再启动任务；传入未勾选的模型 id | 前者返回 `MODEL_UNAVAILABLE`，后者返回 `INVALID_ARGUMENT`；两者都没有向默认模型发出请求 |
| I27 `M` | 剧本两轮（一次工具调用后回复），Mock 每轮返回固定 usage；再用一个不返回 usage 的剧本跑一次 | 任务状态的 `usage.requests` 为 2，`input_tokens`、`cached_tokens`、`output_tokens` 等于两轮之和；Anthropic 格式 Mock 先报输入、再报累计输出时不重复计数；不返回 usage 时 `requests_without_usage` 等于请求数 |
| I28 `R` | 经 MCP 用同一 `conversation_id` 在 API A 上跑 AG-14 的 S1 五轮 | 第 2 轮起每轮 `cached_tokens / input_tokens` 约 99%（AG-14 验收） |
| I29 `R` | MCP 页“电脑端可调用的模型”：展开、收起各服务商并勾选；停用一个已勾选的模型 | 每个服务商一行标题，显示“已选 n / 共 m”，有勾选的默认展开；`eta_task_start` 的 `model.enum` 顺序与列表一致；停用的模型列在最后并标“已停用”，取消勾选后从 `taskModelIds` 移除 |
| I30 `M` | 勾选 `e2e-mock-a`（Low–Max）并在手机上设强度 Medium；分别不带、带 `reasoning_effort=high`、带 `reasoning_effort=turbo` 启动任务；再关闭“允许思考”启动一次 | `tools/list` 中 `reasoning_effort` 的说明列出档位与“省略时 medium”；前两次请求体分别为 `effort=medium`、`effort=high`，任务状态 `reasoning_effort` 一致；第三次 `INVALID_ARGUMENT` 且没有发出请求；最后一次 `reasoning_effort=off`，请求不带强度 |
| I31 `R` | 经 MCP 用 `reasoning_effort=high` 在 API A 上跑 AG-14 的 S1 第 1 轮 | 记录代理或任务状态显示强度为 high（AG-16 验收） |

### J. 计划与子 Agent

子 Agent 的模型请求同样发往 Mock 服务：剧本在主 Agent 的 `run_subagents` 参数里给每个子任务写入标记（如 `[E2E:sub-1]`），Mock 据此为子 Agent 选剧本。

| ID | 场景 | 断言 |
|---|---|---|
| J1 `M` | 剧本先 `update_plan`（第 1 步进行中）再执行工具，随后把第 1 步标为完成 | 胶囊显示第 1 步标题与“步骤 1/3”，更新后切到第 2 步；`events` 中有两条 `PlanUpdated` |
| J2 `M` | `update_plan` 提交两个 in_progress 或非法状态 | 工具结果 `INVALID_PLAN`，run 继续，不发 `PlanUpdated` |
| J3 `M` | 剧本 `run_subagents` 派出 3 个子任务，各自调用 `terminal` 后回复 | 三个子 Agent 的请求并行到达 Mock；主 Agent 收到的结果含 3 条 `ok=true` 的结论；胶囊依次显示“子 Agent n：终端” |
| J4 `M` | 子 Agent 剧本调用 `observe_screen`、`browser_use`、`ask_user` | Mock 收到的子 Agent 工具列表不含这些工具；强行调用返回 `SUBAGENT_TOOL_UNAVAILABLE` |
| J5 `M` | 子 Agent 剧本持续调用工具不结束 | 第 40 轮后该子任务停止并报告轮数上限，其余子任务正常返回 |
| J6 `M` | 子 Agent 执行中停止主 run | 所有子 Agent 的请求中断，主 run 以停止结束 |
| J7 `M` | 主 Agent 先 `observe_screen` 持有屏幕租约，再派子 Agent，子 Agent 结束后主 Agent 继续操作界面 | 子 Agent 结束不释放主 run 的屏幕租约（`leases.SCREEN` 仍为主 run） |
| J8 `M` | 工具页关闭 `update_plan` 与 `run_subagents` | Mock 收到的工具列表与系统提示都不再包含它们；从历史强行调用返回 `TOOL_DISABLED` |
| J9 `M` | 电脑端委托的任务调用 `update_plan` | `eta_task_status` 返回的快照带 `plan` |

### K. 模型回退链

Mock 服务按请求中的模型名区分主模型与备用模型（设置中配置两个指向 Mock 的模型），可对指定模型返回 401、503、超时或内容拦截。

| ID | 场景 | 断言 |
|---|---|---|
| K1 `M` | 主模型第 1 轮调用工具，第 2 轮返回 401 | 第 2 轮换到备用模型继续，备用模型请求里含第 1 轮的工具结果；`ModelFallback` 事件一条；聊天出现“已切换备用模型”提示；工具未重复执行 |
| K2 `M` | 主模型持续返回 503 | 先按 2/4/8 秒重试 3 次，之后切换；切换后的轮次编号不与失败尝试重复 |
| K3 `M` | 主模型与两个备用模型依次失败 | 依次切换两次后以最后一个模型的错误结束 run |
| K4 `M` | 主模型返回上下文超限 | 不切换，走压缩或报错 |
| K5 `M` | 主模型内容被拦截；分别在“回复被拦截时也换模型”关闭与打开时测试 | 关闭时 run 以拦截说明结束；打开时切换备用模型 |
| K6 `M` | 主模型为 OpenAI 格式，备用模型为开启思考的 Anthropic 格式，工具回合中切换 | 备用模型请求关闭思考、不带上一模型的签名块，请求成功 |
| K7 `M` | 切换后同一 run 继续多轮；下一次 run | 剩余轮次都发往备用模型；下一次 run 先请求主模型 |
| K8 `M` | 回退链中的模型被停用或删除 | 该条目被跳过，不报错 |
| K9 `M` | 子 Agent 的主模型失败 | 子 Agent 同样切换到备用模型完成 |
| K10 `R` | 准备两个以上服务商；在设置中打开“备用模型 1”与“厂商助手模型”的选择弹窗，选一个模型，再选“无”/“跟随默认模型”；随后停用已选模型所在的服务商 | 弹窗按服务商分组，只展开当前所选模型所在的服务商，其余可展开收起；选择结果与改动前一致；停用后设置行显示“（已停用）”而不是“无” |
| K11 `M` | 主模型 High；备用模型 1 不设强度、备用模型 2 设 Low；剧本让主模型与备用模型 1 依次失败 | 发往备用模型 1 的请求 `effort=high`（跟随主模型），发往备用模型 2 的 `effort=low`；设置行显示“模型 · 强度” |

### L. 自动任务

定时用例用 `adb shell cmd alarm` 或把触发时间设在 1–2 分钟后观察；通知用例用 `adb shell cmd notification post -t <标题> <tag> <正文>` 以 shell 身份发通知（包名为 `com.android.shell`）。自动任务走默认模型，需 `setup_mock` 带 `as_default=true`。

| ID | 场景 | 断言 |
|---|---|---|
| L1 `M` | 设置页新建“每天定时”任务，时间设为 2 分钟后 | `dumpsys alarm` 有 Eta 的闹钟；到点后 run 启动，结束后侧栏“自动任务”分组出现该会话并收到结果通知；点通知打开该会话 |
| L2 `M` | 新建间隔 15 分钟的任务后立即重启手机 | 开机后闹钟重新排期，按创建时间起算的下一个周期触发 |
| L3 `M` | 单次任务到点执行 | 执行后列表中该任务显示已停用，`dumpsys alarm` 不再有它 |
| L4 `M` | 通知触发任务（包名 `com.android.shell`，关键词“快递”）；连续发两条匹配通知 | 第一条触发且提示词含通知标题与正文；冷却期内第二条不触发；常驻通知与 Eta 自己的通知不触发 |
| L5 `M` | 在会话中让 Eta “每天早上 8 点提醒我喝水” | 调用 `manage_automations` create，设置页出现该任务且触发方式为每天 08:00；再让它删除，任务消失 |
| L6 `M` | 关闭精确闹钟权限后查看自动任务页 | 显示“允许精确闹钟”入口；任务仍以非精确闹钟排期 |
| L7 `M` | 同一任务运行中再次“立即运行” | 提示正在运行，不重复启动 |
| L8 `M` | 已有 3 个 run 时自动任务触发 | 以并发上限失败，记录失败并发通知 |
| L9 `M` | 电脑端经 MCP 调用 `manage_automations` list | 返回与设置页一致的列表与下次运行时间 |
| L10 `M` | 自动任务提示词中让模型调用 ask_user | 提示词已声明无人值守；若仍提问，10 分钟超时后继续并在结论中说明 |
| L11 `M` | 新建自动任务，指定 `e2e-mock-b` 与强度 High 后立即运行；再停用 `e2e-mock-b` 运行一次；用 `manage_automations` 的 `update` 传 `reasoning_effort=""` | 第一次请求发往 `e2e-mock-b` 且 `effort=high`，列表摘要显示“模型 · 强度”；停用后回落到默认模型，摘要标“（已停用）”；`list` 返回 `models` 与每个任务的 `model`、`reasoning_effort`，置空后为 `follow_default` |

### M. 屏幕接管与界面路径

虚拟屏执行尚未实现，先做 [VIRTUAL_DISPLAY.md](VIRTUAL_DISPLAY.md) 中的可行性验证；这一组覆盖已落地的两项。

| ID | 场景 | 断言 |
|---|---|---|
| M1 `M` | Root 设备，Agent 执行 `observe_screen` 期间发一条普通通知 | `settings get global heads_up_notifications_enabled` 为 0，通知不弹横幅但进入通知栏；run 结束或空闲 45 秒后恢复为原值 |
| M2 `M` | 屏蔽期间强制停止 Eta 进程后重新打开 | 启动后恢复横幅设置为原值 |
| M3 `M` | 无 Root、已授予勿扰权限 | 接管期间勿扰为“仅限优先事项”，结束后恢复；期间用户自行改了勿扰模式时保留用户选择 |
| M4 `M` | 关闭“操作界面时暂停横幅通知” | 接管期间横幅设置不变 |
| M5 `M` | 剧本任务“打开系统设置的 Wi-Fi 页面” | Mock 收到的系统提示含优先非界面途径的规则；剧本可用 `open_uri`/`terminal` 完成，无需 GUI 工具 |
| M6 `R` | 按 VIRTUAL_DISPLAY.md 的验证步骤在真机上逐项检查 | 记录每项结果，决定虚拟屏方案是否继续 |

### N. 用量与费用

用量账本在本机数据库 `model_usage_records`；统一落地前可用 `adb shell run-as` 导出数据库核对，落地后由状态 Provider 提供账本摘要。

| ID | 场景 | 断言 |
|---|---|---|
| N1 `M` | 剧本：一轮工具调用后回复；同一会话再手动压缩一次；再让模型调用 `run_subagents` 派 2 个子 Agent | 每次模型请求一条记录：对话、压缩、子 Agent（`subagent_index` 为 0、1）都在，同一 run 的 `run_id` 相同；token 与 Mock 返回的 usage 一致；Anthropic 格式先报输入、再报累计输出时只记最后一次 |
| N2 `M` | Mock 不返回 usage；Mock 返回 500；请求进行中停止 run | 依次记为 `has_usage=0`、`status=failed`、`status=cancelled`，都计入请求数 |
| N3 `M` | 经 MCP 运行 N1 的剧本 | `eta_task_status.usage.requests` 与账本中该 run 的条数一致，包含压缩与子 Agent 的请求 |
| N4 `M` | 删除 N1 的会话 | 账本记录仍在 |
| N5 `M` | 自定义服务商的模型名为 `gpt-6-astra`、倍率 0.3；Mock 返回 22.7K 输入（缓存 22.5K）、300 输出 | 看板与使用记录的费用 = (0.2K×10 + 22.5K×1 + 0.3K×50)/1M × 0.3 美元；使用记录展开后显示价格来源、倍率 |
| N6 `M` | 人民币计价的模型（Kimi 官方）；把汇率从 7.1 改成 7.2 | 费用按新汇率实时重算；汇率填 0 或非数字时不保存 |
| N7 `R` | 设置 › 用量统计：切换今天 / 7 天 / 30 天 / 本月，点柱子；进入使用记录与价格页；导出 CSV | 柱数分别为 8 / 7 / 30 / 当月天数，点柱子显示该段的费用与 token；没有价格的模型显示“未计费”；CSV 有表头且每次请求一行，不含对话内容 |
| N8 `M` | 给服务商设每日 token 额度 4K、两档提醒；Mock 依次返回 3.3K、1K token | 第一次越过 80% 时发一条提醒，越过 100% 时再发一条；同一天内不重复；请求照常发出 |
| N9 `M` | 给没有预置价格的模型填写价格，再“恢复预置价格” | 填写后费用按自填价计算，来源显示“自填”；恢复后回到未预置（未计费） |

## 执行方式

### 方式一：adb 驱动（首选，先落地）

一次完整执行的流程：

1. **取包**：`gh run download` 拉取 `eta-debug-apk`，`adb install -r`。
2. **设备准备**：
   - 亮屏解锁：`input keyevent KEYCODE_WAKEUP`、`wm dismiss-keyguard`；插 USB 期间常亮：`svc power stayon usb`。
   - 备份后关闭动画：`settings get/put global window_animation_scale` 等三项。
   - 备份 `enabled_accessibility_services`，确认 Eta 无障碍已启用。
   - `adb reverse tcp:18080 tcp:18080`，启动 Mock 服务，发送 `setup_mock`。
3. **逐个执行用例**：为 Mock 加载剧本 → 记录起始时间 → 发送指令 → 轮询 `runs` / `events` 直到期望事件或超时 → 断言 → 失败时采集截图（`screencap`）、`uiautomator dump`、`dumpsys window windows` 中 Eta 的窗口、logcat 切片。
4. **收尾**：`cleanup`，恢复所有备份的设置，撤销 `adb reverse`。

用例用 JSON 描述，一个用例一个文件：

```json
{
  "id": "A4",
  "mock": {
    "run-a": [{"tool": "observe_screen"}, {"delay_ms": 20000, "text": "done"}],
    "run-b": [{"tool": "tap_element", "args": {"text": "设置"}}, {"text": "done"}]
  },
  "steps": [
    {"submit": {"conversation": "new", "script": "run-a"}},
    {"wait": {"event": "ToolFinished", "tool": "observe_screen", "script": "run-a"}},
    {"submit": {"conversation": "new", "script": "run-b"}},
    {"assert": {"table": "leases", "SCREEN": "$run-a"}},
    {"wait": {"event": "RunFinished", "script": "run-a"}},
    {"wait": {"event": "ToolFinished", "tool": "tap_element", "script": "run-b", "timeout_ms": 10000}}
  ]
}
```

Mock 服务通过 prompt 中的剧本标记（`[E2E:run-a]`）把请求对应到剧本，因此多个并发 run 可以各自按剧本推进。

### 方式二：PC 侧 Agent 经 MCP 执行（MCP 接口落地后）

设备端 MCP 接口完成后，把指令入口和状态 Provider 的能力同时暴露为 MCP 工具（`e2e_submit`、`e2e_state`、`e2e_screenshot` 等）。Claude Code 或 Codex 连上后读取本文档的用例清单逐项执行，Mock 服务仍跑在 PC 上。好处是不用维护 adb 指令拼接，Agent 可以对失败现场做初步分析；缺点是执行过程不确定，适合探索性回归，不替代方式一。

### 方式三：Eta 自测（后续）

由 Eta 中的一个“测试会话”调用上述能力驱动其他会话执行用例。限制：

- 测试会话本身也是一个 run，占用一个并发名额，A3 这类用例需要相应调整上限。
- 测试会话不能操作屏幕，否则会与被测 run 争抢屏幕租约；它只能做提交、查询和断言。
- 适合非界面用例（D、E、F、G1、G2）和版本更新后的快速自检。

## 结果记录

每次执行输出到 PC 的 `out/e2e/<时间戳>/`（加入 `.gitignore`，不提交）：

- `report.json`：设备型号、系统版本、APK 版本名（含提交号）、每个用例的结果、耗时、失败断言。
- `report.md`：人看的摘要，失败用例附现场材料链接。
- `cases/<ID>/`：logcat 切片、事件表快照、Mock 请求结构摘要，失败时附截图和 UI dump。

分享报告前检查截图是否含通知内容或个人信息。不确定的用例连续 3 次中有失败即标记为不稳定，单独跟踪，不阻塞其他用例。

## 落地顺序

在现有需求全部实现后开始，按以下顺序进行：

1. CI 上传 Debug APK 产物。
2. 实现 debug 指令入口、状态 Provider 和 `EtaE2E` 日志（不改动 release 代码路径）。
3. Mock 模型服务与运行器，先跑通 A1、A2、A4、B1、C1、D2 六个用例。
4. 补齐其余 `M` 用例，完整执行一轮（含 `R` 用例），回填「功能与用例对齐」表的执行状态。
5. 接入方式二（届时 MCP 接口已完成）；视需要再做方式三。
