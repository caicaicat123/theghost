# The Ghost（幽灵）— 服务器里的捣蛋鬼 AI

住在《我的世界》服务器里的一个看不见的存在，玩家叫它「幽灵」。平时它没有实体，
靠聊天、音效、屏幕标题刷存在感；**但你把它惹急了，它会真的动手**——在你头上劈闪电、
在你身后放苦力怕。核心定位是**偶尔帮忙、时常搞怪、记仇**。

适配环境：Purpur/Paper 26.1.2（Java 25），兼容 1.21+。

> 插件名（Bukkit 只允许英文字母、数字和 `._-`）用英文 **TheGhost**：数据目录 `plugins/TheGhost/`、
> jar 名 `theghost-<版本>.jar`；游戏里和中文文档里叫「幽灵」，管理命令 `/ghost`。

## 安装

1. 编译：`powershell -File build.ps1`（首次先跑 `node tools\fetch-libs.cjs` 下载编译依赖）
2. 把 `dist\theghost-1.5.3.jar` 放进服务器的 `plugins\` 目录
3. 启动或重启服务器，会生成 `plugins\TheGhost\config.yml`
4. 在配置里填 `deepseek.api-key`，然后 `/ghost reload`（或重启）

不填 API Key 也能跑：它会退回使用 `config.yml` 里的本地台词，一样会出来搞怪，只是不会临场发挥。

## 指令

| 指令 | 权限 | 说明 |
| --- | --- | --- |
| `/ghost ask <内容>` | `theghost.talk`（所有人默认有） | 私聊它，它会回答 |
| `/ghost poke [玩家]` | `theghost.admin` | 立刻让它去找某个玩家的麻烦（调试用） |
| `/ghost grudge [玩家] [set <分>\|reset]` | `theghost.admin` | 查看记仇榜 / 某个玩家的记仇值，可手动改分或清零 |
| `/ghost prank <玩家> [lightning\|creeper\|jumpscare]` | `theghost.admin` | 手动试一次真捉弄（不限等级和冷却） |
| `/ghost forget [玩家]` | `theghost.admin` | 清空某个玩家（或所有人）的对话记忆 |
| `/ghost toggle` | `theghost.admin` | 暂停/恢复定时搞怪 |
| `/ghost reload` | `theghost.admin` | 重载配置（改完 API Key 用这个；顺带清空对话记忆） |
| `/ghost status` | 所有人 | 查看当前状态、记忆与累计 token 用量 |
| `/ghost test [内容]` | `theghost.admin` | 直接打一次 API，结果写进控制台，用来排查连通性 |

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

## 对话记忆（v1.5.2 新增）

它会记得刚才聊了什么——但只记 **8 轮**、只记 **5 分钟**，而且是**每个玩家各记一份**：

- 被 `@yl` 或被 `/ghost ask` 时，之前几轮对话会作为 `user` / `assistant` 消息一起发过去，
  所以「刚才那句什么意思」「那你再说一遍」这类追问它接得住了
- 定时搞怪、`/ghost poke`、引导接话的内容**不进记忆**——只有玩家直接跟它说话才算「聊过」
- 超过 `memory.idle-seconds`（默认 300 秒）没说话就忘掉，玩家下线立刻忘；
  所以闲聊结束后的第一次 @ 仍然是全新的对话
- `/ghost forget [玩家]` 可以随时手动清空
- 重启服务器会清空（记忆只在内存里，不落盘——这是有意的，避免旧对话拖慢它的人设）

**为什么加了记忆几乎不涨钱**：请求的消息形状固定成「system → 历史 → 本次消息」，
每轮都在上一轮的前缀后面追加，DeepSeek 的输入前缀缓存就能命中，
而命中价（0.02 元/百万）只有未命中价（1 元/百万）的 1/50。按 8 轮算：

| 场景 | 输入 token | 单次成本 | 相对现状 |
| --- | --- | --- | --- |
| 无记忆（1.5.1） | 约 700 | 0.00030 元 | — |
| 8 轮记忆（缓存命中） | 约 1180 | 0.00031 元 | **+4%** |
| 8 轮记忆（缓存全不命中，最坏） | 约 1180 | 0.00050 元 | +67% |

真正的成本大头是**输出**（缓存命中时占单次成本约 66%），所以 `max-tokens` 比上下文更值得抠。
`/ghost status` 会显示累计的命中/未命中/输出 token 与估算花费，可以对账。

## 记仇与真捉弄（v1.4.0 新增，v1.5.3 改为 AI 判分）

它会给每个玩家单独记一笔账（`plugins/TheGhost/grudge.yml`，重启不丢）。
**加多少、减多少，由它自己在回复里判**——输出第三行的 `SCORE` 是带正负的整数：

- `SCORE` 正数 = 记仇：`+1` 有点烦，`+3` 明显在骂，`+5` 骂得很凶
- `SCORE` 负数 = 消气：`-1` 客气了一下，`-2` 真心道歉，`-3` 及以上很难得
- `SCORE: 0` = 普通聊天、提问、开玩笑
- 时间会冲淡一切：默认每小时 -2，7 天没动静的零分账本会被清掉

这样做的好处是**阴阳话和道歉都判得准**：「你这人真有意思」「你可真行啊」这种，
以及「对不起啦我错了」——写死的词表永远判不准，模型能读出来。
（v1.5.3 之前是靠 `prank.insult-words` / `praise-words` 词表命中才加减分的。）

记仇值上限是 **50 分**（`prank.max-score`，也会写进提示词告诉模型），等级线仍是 3 / 8 / 15。
记仇值决定它能对你动用哪一档手段：

| 记仇值 | 等级 | 它能做什么 |
| --- | --- | --- |
| 0–2 | 0 | 只动嘴 |
| 3–7 | 1 | `JUMPSCARE`：贴脸音效 + 屏幕闪字，不掉血 |
| 8–14 | 2 | `LIGHTNING` 闪电（真掉血）、`CREEPER` 身后放苦力怕（真炸） |
| 15+ | 3 | 闪电更疼（默认 4 颗心）、苦力怕一次两只 |

**词表只作为兜底**：没配 API Key、或调用失败（模型不在场）时，才会退回去看
`prank.insult-words` / `praise-words`（每命中一次 ±`insult-points` / `praise-points`）。
模型正常给出 `SCORE` 时一切以它为准。`prank.fallback-words: false` 可以连兜底也关掉。

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
- `deepseek.max-tokens`（默认 **80**）：单次回复上限。回复本体（一句话 + `ACTION` + `ANGER`）约 60 token，
  80 够用；**输出 token 是账单大头，调大它最费钱**。
- `deepseek.thinking`（默认 **false**）：思考模式。开着的话 `temperature` 失效、思维链还会白烧
  输出 token（容易被 `max-tokens` 截断导致回复为空）。它要的是嘴贫，不是推理，别开。
- `deepseek.log-usage`（默认 false）：每次调用往控制台写一行「输入命中 / 未命中 / 输出」。
- `memory.enabled` / `memory.max-turns`（8）/ `memory.idle-seconds`（300）/ `memory.max-tokens`（500）：
  对话记忆的开关与上限。**关掉它（`enabled: false`）就退回 1.5.1 的一次性问答**。
- `bot.reply-cooldown-seconds` / `mention.cooldown-seconds`：冷却，防止刷屏和连续烧 token。
- `prank.*`：记仇与捉弄的总开关和数值都在这里。`prank.enabled: false` 可一键关掉全部真捉弄
  （它退回只动嘴的版本，记仇账本仍在记，方便以后观察）。
- `prank.max-score`（默认 **50**）：记仇值上限，会写进提示词告诉模型。
- `prank.fallback-words`（默认 true）：词表兜底开关。设 false 就是彻底不看词表——
  此时没配 API Key / 调用失败时**不会有任何记分**。
- `prank.insult-words` / `prank.praise-words` / `insult-points` / `praise-points`：
  只在模型不在场时生效的兜底词表与分值。想让 AI 完全说了算，把 `fallback-words` 关掉即可。
- 玩家用 `/ghost ask` 的调用是没有概率过滤的，一定走 AI。

按默认配置，一个 5 人在线的服务器一天大概几十次调用，成本可以忽略。
带上 8 轮记忆后每天多花几分钱（按 300 次调用算：约 0.09 元/天 → 0.094 元/天）。

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
- 对话记忆只在内存里，**重启服务器/插件就没了**。要持久化得自己落盘（现在的取舍是：
  短时会话够用，且旧对话不会污染它的人设）。
- 记忆按玩家隔离，但它不会记住「别人」说过什么——`@` 时只能看到自己那一条时间线。
- 记忆相关的日志里，`/ghost status` 的估算花费按 **deepseek-flash 空闲时段价** 折算；
  高峰时段（北京时间周一至周五 9:00–12:00、14:00–18:00）实际价格翻倍。
- 记仇值现在**依赖模型把 `SCORE` 行写对**：它要是漏写或写成别的词，这次就按 0 分算
  （不会误加，也不会去看词表）。`/ghost status` 的判分行会告诉你当前是哪套在起作用。

## 文件结构

```
repo\
├─ build.ps1              编译脚本
├─ config.yml             默认配置（打包进 jar，首次启动释放到 plugins\TheGhost\）
├─ plugin.yml             插件描述
├─ src\mcbot\             Java 源码
│   ├─ McBot.java         主类：配置、定时搞怪、聊天触发、指令
│   ├─ DeepSeek.java      API 客户端（异步、带 token 用量）
│   ├─ Brain.java         提示词构造与回复解析
│   ├─ History.java       按玩家的短期对话记忆（环形缓冲 + 空闲过期）
│   ├─ Actions.java       动作执行：音效/标题/指令 + 闪电/苦力怕/惊吓
│   ├─ Grudge.java        每个玩家的记仇账本（加分、减分、自然淡忘）
│   └─ Json.java          极小 JSON 读写（不引入外部依赖）
├─ tools\
│   ├─ fetch-libs.cjs     下载编译依赖
│   └─ selftest\          离线自测（记忆、请求体形状、用量解析、回复解析）
└─ lib\                   编译依赖（paper-api、adventure）
```

## 许可证

MIT License —— 可以自由使用、修改、二次开发甚至商用，保留版权声明即可（详见 [LICENSE](LICENSE)）。

版权所有 (c) 2026 新世界网络（New World Network）。
