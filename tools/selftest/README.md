# 离线自测（TheGhost）

不连服务器、不花 API 钱，直接验证那些「改错了也看不出来」的逻辑：
对话记忆的环形缓冲与过期、请求体的消息顺序、思考模式开关、`usage` 解析、回复解析。

## 跑法

在 `repo\` 目录下：

```powershell
powershell -ExecutionPolicy Bypass -File tools\selftest\run.ps1
```

（用 pwsh 7 或加 `-ExecutionPolicy Bypass` 都行：`build.ps1` 是 UTF-8 无 BOM，
Windows PowerShell 5.1 读中文会乱码并解析失败。）

脚本会先调 `build.ps1` 重新编译，再编译并运行 `SelfTest.java`（和插件同包，
所以能直接测包内可见的 `DeepSeek.body` / `DeepSeek.extract`）。全部通过时退出码为 0，
可以用在发布前的例行检查里。

## 覆盖内容

当前 **124 项**断言：

| 分组 | 断言 |
| --- | --- |
| 记忆环形缓冲 | 只留最近 N 轮、丢最旧的、顺序不乱、每轮摊成 user+assistant 两条、`forget` 生效 |
| 空闲过期 | 超过 `idle-seconds` 就忘、`0` 表示不过期、`enabled: false` 时取不到 |
| token 上限 | 超上限丢最旧的、再紧也至少留一轮、中文按 0.6 估算 |
| 请求体 | `system → 历史 → 本次` 的顺序、历史条数、`thinking: disabled`、`max_tokens`、`model`、`temperature` 的有无 |
| 响应解析 | 正文与两端空白、缓存命中/未命中/输出 token、空响应与缺 `usage` 不炸 |
| 回复解析 | 台词 / `SCORE` 正负 / 全角负号 / 中文键名 / 旧 `ANGER` 兼容 / 越界夹取 / `ACTION: NONE`·`GIFT`·`SOUND` |
| 词表兜底 | 骂人让心情降、夸人让心情升、两样都有时按骂人算、空词表不误判 |
| 心情数学 | 默认值可配、往中性回归（不越过）、不到一小时不动、档位计算（门槛乱序也对）、档位描述 |
| 提示词 | 平常心/开心/生气各档该出现哪些 ACTION、门槛跟着配置走、`prank` 关掉时一个真动作都不出现 |
| **配置文件** | 物品表格式与条数（小/好/钻石/垃圾）、命令白名单**不与硬拦截名单冲突**、兜底分符号方向、记忆 20 轮 + 1500、豁免与警告开关 |

最后一组会读 `repo\config.yml`（靠 `run.ps1` 传的 `-Dtheghost.repo`），所以**改配置写错格式也能离线发现**。
注意它只能验格式（`id` 或 `id:数量`），**验不了物品 ID 是否真实存在**——那要启动服务器，插件会对认不出的条目打警告。

## 加新断言

`SelfTest.java` 里加一个 `check("说明", 布尔)` 就够，失败会计入退出码。别把它写进 `src\`——
`build.ps1` 会编译 `src` 下所有 `.java` 一起打进 jar。
