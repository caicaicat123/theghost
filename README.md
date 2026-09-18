# McBot — 服务器里的捣蛋鬼 AI

住在《我的世界》服务器里的一个看不见的存在，玩家叫它「回声」。平时它没有实体，
靠聊天、音效、屏幕标题刷存在感；**但你把它惹急了，它会真的动手**——在你头上劈闪电、
在你身后放苦力怕。核心定位是**偶尔帮忙、时常搞怪、记仇**。

适配环境：Purpur/Paper 26.1.2（Java 25），兼容 1.21+。

## 安装

1. 编译：`powershell -File build.ps1`（首次先跑 `node tools\fetch-libs.cjs` 下载编译依赖）
2. 把 `dist\mcbot-1.4.0.jar` 放进服务器的 `plugins\` 目录
3. 启动或重启服务器，会生成 `plugins\McBot\config.yml`
4. 在配置里填 `deepseek.api-key`，然后 `/mcbot reload`（或重启）

不填 API Key 也能跑：它会退回使用 `config.yml` 里的本地台词，一样会出来搞怪，只是不会临场发挥。

## 指令

| 指令 | 权限 | 说明 |
| --- | --- | --- |
| `/mcbot ask <内容>` | `mcbot.talk`（所有人默认有） | 私聊它，它会回答 |
| `/mcbot poke [玩家]` | `mcbot.admin` | 立刻让它去找某个玩家的麻烦（调试用） |
| `/mcbot grudge [玩家] [set <分>\|reset]` | `mcbot.admin` | 查看记仇榜 / 某个玩家的记仇值，可手动改分或清零 |
| `/mcbot prank <玩家> [lightning\|creeper\|jumpscare]` | `mcbot.admin` | 手动试一次真捉弄（不限等级和冷却） |
| `/mcbot toggle` | `mcbot.admin` | 暂停/恢复定时搞怪 |
| `/mcbot reload` | `mcbot.admin` | 重载配置（改完 API Key 用这个） |
| `/mcbot status` | 所有人 | 查看当前状态 |
| `/mcbot test [内容]` | `mcbot.admin` | 直接打一次 API，结果写进控制台，用来排查连通性 |

玩家在聊天里 `@yl` 或 `@幽灵` 它才会回话。其余聊天它完全不看——这是省钱的关键。

## 它会做什么

**定时搞怪**：每隔 `mischief.interval-seconds ± jitter-seconds` 秒，按 `chance` 概率挑一个在线玩家，
在公屏上以「幽灵：内容」的形式说一句贫嘴话（`mischief.private-chance` 控制有多大比例改成私聊），
并可能附带一个动作。

**引导接话**：它在公屏说完话后，会盯住接下来 `guide.window-seconds` 秒内的**第一条**玩家发言。
只要有玩家接话，它就丢一句固定文案（默认「想跟我说话？直接 @yl 你要说的内容，我就听得见。」），
然后关掉窗口——不再继续监听。这条文案不调用 API，不花钱。

**只有 @ 才回复**：除了上面两种情形，其余聊天一律不检测、不调用 API。所以日常开销只来自
公屏骚扰（且受 `bot.ai-chance` 控制）和玩家主动 @。

## 记仇与真捉弄（v1.4.0 新增）

它会给每个玩家单独记一笔账（`plugins/McBot/grudge.yml`，重启不丢）：

- 在 `@yl` / `@幽灵` 的消息里骂它 → 加记仇值（默认一次 +3）
- 夸它、跟它道歉 → 减一点（默认 -1）
- 时间会冲淡一切：默认每小时 -2，7 天没动静的零分账本会被清掉
- **AI 在同一次回复里顺带判断"这话有多冒犯"**（输出里的 `ANGER: 0-5` 行），最多再加 2 分，不额外花钱

记仇值决定它能对你动用哪一档手段：

| 记仇值 | 等级 | 它能做什么 |
| --- | --- | --- |
| 0–2 | 0 | 只动嘴 |
| 3–7 | 1 | `JUMPSCARE`：贴脸音效 + 屏幕闪字，不掉血 |
| 8–14 | 2 | `LIGHTNING` 闪电（真掉血）、`CREEPER` 身后放苦力怕（真炸） |
| 15+ | 3 | 闪电更疼（默认 4 颗心）、苦力怕一次两只 |

补充规则：

- 记仇值越高，定时搞怪时越容易被它点名（权重 = 1 + 记仇值）
- 被骂之后它会 3–12 秒后才动手，像是"记下了"
- 闪电/苦力怕共享 45 秒冷却，惊吓 20 秒，防止同一个人被无限连击
- 它**不会**因为玩家喊「劈我 / 劈他」而动手，只有它自己真的被冒犯了才会

默认强度（可在 `prank` 配置段逐项改）：

- 闪电：**真伤害、可致死**，但**不点火**（`set-fire: false`，改成 `true` 就是原版闪电，会烧方块）
- 苦力怕：**真的爆炸、真的炸方块、可炸死人**（`break-blocks` / `lethal` 可关）
- 玩家骂得越狠、骂得越勤，才越容易见到这两样；普通玩家只会被吓一跳

**能触发的动作**（全部受配置约束）：

- `SOUND <音效>` — 只能在 `actions.sounds.allowed` 列表里选，模型不能自由发挥
- `TITLE <文字>` — 在玩家屏幕中央闪一行字
- `COMMAND <指令>` — 只能以 `actions.commands.allowed` 里的词开头

**指令安全**：代码里有一层硬拦截（`Actions.HARD_BLOCKED`），无论配置怎么写，`op/deop/ban/kick/stop/tp/give/
fill/setblock/summon/gamemode/reload/plugman/execute` 这类指令永远不会被执行。模型输出的指令还要过白名单。

**捉弄安全**：捉弄动作不受指令白名单影响（它们走代码而不是控制台），但要过三道闸——
该玩家的记仇等级、动作的冷却、以及 `prank.*.enabled` 开关。等级不够时模型就算写了
`ACTION: LIGHTNING` 也会被拒绝，日志里会记一条。

## 调参与省钱

- `bot.ai-chance`（默认 0.35）：**只影响公屏自言自语**——走 AI 的比例，其余用本地台词。
  设成 0 就是公屏完全免费。被 @ 或 `/mcbot ask` 时一定走 API，不受这个值影响。
- `mention.triggers`：能唤起它的 @ 词，默认 `@yl` 和 `@幽灵`。
- `guide.use-ai`：引导那句话是否也用 AI 生成，默认 false（固定文案，不花钱）。
- `deepseek.max-tokens`（默认 180）：单次回复上限。它说的话本来就短，不用给多。
- `bot.reply-cooldown-seconds` / `mention.cooldown-seconds`：冷却，防止刷屏和连续烧 token。
- `prank.*`：记仇与捉弄的总开关和数值都在这里。`prank.enabled: false` 可一键关掉全部真捉弄
  （它退回只动嘴的版本，记仇账本仍在记，方便以后观察）。
- `prank.insult-words` / `prank.praise-words`：它认得的骂人词和夸人词；只在 `@` 它的消息里检测。
- 玩家用 `/mcbot ask` 的调用是没有概率过滤的，一定走 AI。

按默认配置，一个 5 人在线的服务器一天大概几十次调用，成本可以忽略。

## 提示词

人设在 `Brain.systemPrompt()` 里，可以直接改。默认设定是：有点欠、爱看热闹、偶尔阴阳但不恶毒，
不骂人、不碰现实政治、不给玩家发东西、不透露管理指令。

## 已知限制

- 聊天监听用的是旧版 `AsyncPlayerChatEvent`。Paper 仍会为监听的插件触发它，但如果某天彻底移除，
  @ 回复与引导接话会失效——届时改用 `AsyncChatEvent` 即可，`/mcbot ask` 和定时搞怪不受影响。
- 音效名用现代命名（如 `entity.enderman.stare`）。版本升级后默认列表可能需要微调。
- 闪电和苦力怕会真的伤害玩家、苦力怕还会炸方块——这是刻意做的。不想要就把
  `prank.lightning.lethal` / `prank.creeper.break-blocks` 改掉，或在 `prank.enabled: false` 一键关闭。
- 苦力怕是在玩家身后找落脚点生成的；如果附近全是水/岩浆/墙，它会放弃这次生成（返回 `no-space`）。
- `grudge.yml` 只按 UUID 记账，玩家改名不会丢；但手动删这个文件等于把账本清了。

## 文件结构

```
mcbot\
├─ build.ps1              编译脚本
├─ config.yml             默认配置（打包进 jar，首次启动释放到 plugins\McBot\）
├─ plugin.yml             插件描述
├─ src\mcbot\             Java 源码
│   ├─ McBot.java         主类：配置、定时搞怪、聊天触发、指令
│   ├─ DeepSeek.java      API 客户端（异步）
│   ├─ Brain.java         提示词构造与回复解析
│   ├─ Actions.java       动作执行：音效/标题/指令 + 闪电/苦力怕/惊吓
│   ├─ Grudge.java        每个玩家的记仇账本（加分、减分、自然淡忘）
│   └─ Json.java          极小 JSON 读写（不引入外部依赖）
├─ tools\fetch-libs.cjs   下载编译依赖
└─ lib\                   编译依赖（paper-api、adventure）
```
