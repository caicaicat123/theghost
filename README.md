# The Ghost（幽灵）— 服务器里的捣蛋鬼 AI

住在《我的世界》服务器里的一个看不见的存在，玩家叫它「幽灵」。平时它没有实体，
靠聊天、音效、屏幕标题刷存在感；**但你把它惹急了，它会真的动手**——在你头上劈闪电、
在你身后放苦力怕。核心定位是**偶尔帮忙、时常搞怪、记仇**。

适配环境：Purpur/Paper 26.1.2（Java 25），兼容 1.21+。

> 插件名（Bukkit 只允许英文字母、数字和 `._-`）用英文 **TheGhost**：数据目录 `plugins/TheGhost/`、
> jar 名 `theghost-<版本>.jar`；游戏里和中文文档里叫「幽灵」，管理命令 `/ghost`。

## 安装

1. 编译：`powershell -File build.ps1`（首次先跑 `node tools\fetch-libs.cjs` 下载编译依赖）
2. 把 `dist\theghost-1.6.0.jar` 放进服务器的 `plugins\` 目录
3. 启动或重启服务器，会生成 `plugins\TheGhost\config.yml`
4. 在配置里填 `deepseek.api-key`，然后 `/ghost reload`（或重启）

不填 API Key 也能跑：它会退回使用 `config.yml` 里的本地台词，一样会出来搞怪，只是不会临场发挥。

## 指令

| 指令 | 权限 | 说明 |
| --- | --- | --- |
| `/ghost ask <内容>` | `theghost.talk`（所有人默认有） | 私聊它，它会回答 |
| `/ghost poke [玩家]` | `theghost.admin` | 立刻让它去找某个玩家的麻烦（调试用） |
| `/ghost mood [玩家] [set <分>\|reset]` | `theghost.admin` | 心情榜 / 某个玩家的心情，可手动改分或重置（`grudge` 是旧别名） |
| `/ghost prank <玩家> [lightning\|creeper\|jumpscare]` | `theghost.admin` | 手动试一次真捉弄（不限档位和冷却） |
| `/ghost gift <玩家>` / `/ghost junk <玩家>` | `theghost.admin` | 手动送礼 / 塞垃圾（会先清掉配额，方便试） |
| `/ghost forget [玩家]` | `theghost.admin` | 清空某个玩家（或所有人）的对话记忆 |
| `/ghost toggle` | `theghost.admin` | 暂停/恢复定时搞怪 |
| `/ghost reload` | `theghost.admin` | 重载配置（改完 API Key / 物品表用这个；顺带清空对话记忆） |
| `/ghost status` | 所有人 | 查看心情、档位、配额、豁免、记忆与累计 token 用量 |
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

## 对话记忆

它会记得刚才聊了什么——但只记 **20 轮**、只记 **5 分钟**，而且是**每个玩家各记一份**：

- 被 `@yl` 或被 `/ghost ask` 时，之前几轮对话会作为 `user` / `assistant` 消息一起发过去，
  所以「刚才那句什么意思」「那你再说一遍」这类追问它接得住了
- 定时搞怪、`/ghost poke`、引导接话的内容**不进记忆**——只有玩家直接跟它说话才算「聊过」
- 超过 `memory.idle-seconds`（默认 300 秒）没说话就忘掉，玩家下线立刻忘；
  所以闲聊结束后的第一次 @ 仍然是全新的对话
- `/ghost forget [玩家]` 可以随时手动清空
- 重启服务器会清空（记忆只在内存里，不落盘——这是有意的，避免旧对话拖慢它的人设）

**为什么加了记忆几乎不涨钱**：请求的消息形状固定成「system → 历史 → 本次消息」，
每轮都在上一轮的前缀后面追加，DeepSeek 的输入前缀缓存就能命中，
而命中价（0.02 元/百万）只有未命中价（1 元/百万）的 1/50。生产环境实测一次调用
`输入命中 384 / 未命中 232 / 输出 31` ≈ **0.00036 元/次**（空闲价）。

**注意**：`memory.max-turns` 与 `memory.max-tokens` 必须一起调——
20 轮大约 1200 token，上限还留在 500 的话会被裁掉一大半，等于白配。

真正的成本大头是**输出**（缓存命中时占单次成本约 66%），所以 `max-tokens` 比上下文更值得抠。
`/ghost status` 会显示累计的命中/未命中/输出 token 与估算花费，可以对账。

## 心情、礼物与惩罚（v1.6.0 起）

它对每个玩家有一个**心情值：0–50，25 是平常心**（`plugins/TheGhost/mood.yml`，重启不丢）。

| 心情 | 它怎么看你 | 会发生什么 |
| --- | --- | --- |
| 43–50 | 非常喜欢你 | 有可能送你**钻石装备**（每人每天 1 次） |
| 36–42 | 挺喜欢你 | 好东西：铁块、绿宝石、钻石、金苹果、烟花… |
| 30–35 | 有点喜欢你 | 小礼物：食物、火把、箭、煤… |
| 25 | 平常心 | 只聊天、放音效、偶尔嘴贫 |
| 20–24 | 有点烦你 | 往你背包**空位**里塞 2–3 格垃圾，或吓你一跳 |
| 14–19 | 正在生你的气 | 加放苦力怕（会炸） |
| 0–13 | 对你很火大 | 加劈闪电；**第一次只警告**，10 分钟内再惹才真劈死 |

**加多少、减多少，由它自己在回复里判**——输出第三行的 `SCORE` 是带正负的整数：

- `SCORE` 正数 = 让它开心（`+1` 有点意思，`+5` 说到它心坎里）
- `SCORE` 负数 = 惹它生气（`-1` 有点烦，`-3` 明显在骂，`-5` 骂得很凶）
- `SCORE: 0` = 普通聊天、提问、开玩笑
- 时间会把它冲回 25（默认每小时 2 分）——**生气和开心都会被冲淡**，不会永远记着

这样设计的好处是**阴阳话和道歉都判得准**：「你这人真有意思」「你可真行啊」，
以及「对不起啦我错了」——写死的词表永远判不准，模型能读出来。
（词表只在模型不可用——没配 Key 或调用失败——时才兜底，`prank.fallback-words: false` 可彻底关掉。）

### 为什么物品不由模型给

模型只能写 `ACTION: GIFT` / `ACTION: JUNK`，**物品、数量、目标全由代码从白名单里抽**
（`reward.items.small/good/big` 与 `junk.items`），走的是服务端 API 而不是 `give` 指令。
这样：`give` 永远留在硬拦截名单里，它写不出 `@a`、写不出数量、写不出 NBT，玩家也刷不出钻石。
`loot` / `item` / `data` / `xp` / `enchant` / `effect` / `damage` 一并被硬拦截。

**塞垃圾只动背包空格子**，绝不覆盖玩家自己的东西；背包满了就什么都不做
（宁可不动手，也不在地上一地掉落物——那会拖服务器 TPS）。

### 三条保命线

1. **豁免**：OP（`prank.exempt-ops`）、有 `theghost.immune` 权限的玩家、
   以及 `prank.exempt-world-prefixes` 命中的世界（默认 `nwndungeon`，**副本里绝不生效**）
   ——这三类既不会被送礼，也不会被整（但仍然能逗它说话、放音效）
2. **先警告后杀**：真会劈死人的那一下，第一次只留 `warn-leave-health`（默认 1 颗心）并说一句话；
   10 分钟内再来才真杀（`warn-first: false` 可关掉警告）
3. **击杀冷却**：默认 10 分钟/人（`prank.kill-cooldown-seconds`），不会连着把人劈死

### 配额（防止被当免费装备机刷）

| 项 | 冷却 | 每人每天 |
| --- | --- | --- |
| 普通礼物 | 15 分钟 | 3 次 |
| 钻石档礼物 | 24 小时 | 1 次 |
| 塞垃圾 | 2 分钟 | 8 次 |

## 真捉弄的开关与数值

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
  设成 0 就是公屏完全免费。被 @ 或 `/ghost ask` 时一定走 API，不受这个值影响。
- `mention.triggers`：能唤起它的 @ 词，默认 `@yl` 和 `@幽灵`。
- `guide.use-ai`：引导那句话是否也用 AI 生成，默认 false（固定文案，不花钱）。
- `deepseek.max-tokens`（默认 **80**）：单次回复上限。回复本体（一句话 + `ACTION` + `SCORE`）约 60 token，
  80 够用；**输出 token 是账单大头，调大它最费钱**。
- `deepseek.thinking`（默认 **false**）：思考模式。开着的话 `temperature` 失效、思维链还会白烧
  输出 token（容易被 `max-tokens` 截断导致回复为空）。它要的是嘴贫，不是推理，别开。
- `deepseek.log-usage`（默认 false）：每次调用往控制台写一行「输入命中 / 未命中 / 输出」。
- `memory.enabled` / `memory.max-turns`（20）/ `memory.idle-seconds`（300）/ `memory.max-tokens`（1500）：
  对话记忆的开关与上限。**关掉它（`enabled: false`）就退回一次性问答**。
- `bot.reply-cooldown-seconds` / `mention.cooldown-seconds`：冷却，防止刷屏和连续烧 token。
- `mood.*`：心情的中性值（25）、上限（50）、回归速度（2/小时）与两档门槛
  （`anger-tiers: [20,14,8]`、`joy-tiers: [30,36,43]`）。
- `reward.enabled: false` / `junk.enabled: false`：**只关礼物**或**只关塞垃圾**，其他行为照旧。
- `prank.enabled: false`：一键关掉全部"真动作"（礼物、垃圾、闪电、苦力怕、惊吓），
  它退回只动嘴的版本——心情账本仍在记，方便以后观察。
- `prank.exempt-ops` / `prank.exempt-world-prefixes`：豁免名单（详见上文"三条保命线"）。
- `prank.fallback-words`（默认 true）：词表兜底开关。设 false 就是彻底不看词表——
  此时没配 API Key / 调用失败时**心情不会有任何变化**。
- 玩家用 `/ghost ask` 的调用是没有概率过滤的，一定走 AI。

按默认配置，一个 5 人在线的服务器一天大概几十次调用，成本可以忽略
（生产实测 ≈ 0.00036 元/次，300 次/天约 0.11 元）。

## 提示词

人设在 `Brain.systemPrompt()` 里，可以直接改。默认设定是：有点欠、爱看热闹、情绪写在脸上，
待见谁就送谁东西、被惹毛了当场报复；不骂人、不碰现实政治、不透露管理指令，
**也不会自己编物品和数量**（想给东西只能写 `GIFT`，由系统去挑）。

## 已知限制

- 聊天监听用的是旧版 `AsyncPlayerChatEvent`。Paper 仍会为监听的插件触发它，但如果某天彻底移除，
  @ 回复与引导接话会失效——届时改用 `AsyncChatEvent` 即可，`/ghost ask` 和定时搞怪不受影响。
- 音效名用现代命名（如 `entity.enderman.stare`）。版本升级后默认列表可能需要微调。
- 闪电和苦力怕会真的伤害玩家、苦力怕还会炸方块——这是刻意做的。不想要就把
  `prank.lightning.lethal` / `prank.creeper.break-blocks` 改掉，或在 `prank.enabled: false` 一键关闭。
- 苦力怕是在玩家身后找落脚点生成的；如果附近全是水/岩浆/墙，它会放弃这次生成（返回 `no-space`）。
- `mood.yml` 只按 UUID 记账，玩家改名不会丢；但手动删这个文件等于把心情重置成 25。
- **旧的 `grudge.yml` 不会自动迁移**：记仇值（0 起步）和心情值（25 是中点）不是一套算法，
  升级后全体从 25 开始，旧文件留在原处当备份。
- 对话记忆只在内存里，**重启服务器/插件就没了**。要持久化得自己落盘（现在的取舍是：
  短时会话够用，且旧对话不会污染它的人设）。
- 记忆按玩家隔离，但它不会记住「别人」说过什么——`@` 时只能看到自己那一条时间线。
- 心情**依赖模型把 `SCORE` 行写对**：它要是漏写或写成别的词，这次就按 0 分算
  （不会误加，也不会去看词表）。`/ghost status` 的判分行会告诉你当前是哪套在起作用。
- 物品白名单里的 ID 若写错，启动日志会警告并跳过那一条（离线自测只能验格式，验不了 ID 是否存在）。
- 配额与冷却存在内存里，**重启会清零**（例：钻石档"每天 1 次"在重启后可以再拿一次）。
- 副本豁免用的是**世界名前缀**（默认 `nwndungeon`）。如果你们的副本世界名不是这个前缀，
  改 `prank.exempt-world-prefixes`；日志里会打印被豁免的世界名，方便核对。
- 领地（Residence）**没有做检测**：领地里是否允许闪电/爆炸伤害取决于 Residence 的 flag，
  需要更细的保护就靠 `theghost.immune` 权限或世界豁免。

## 文件结构

```
repo\
├─ build.ps1              编译脚本
├─ config.yml             默认配置（打包进 jar，首次启动释放到 plugins\TheGhost\）
├─ plugin.yml             插件描述
├─ src\mcbot\             Java 源码
│   ├─ McBot.java         主类：配置、定时搞怪、聊天触发、指令、配额与豁免
│   ├─ DeepSeek.java      API 客户端（异步、带 token 用量）
│   ├─ Brain.java         提示词构造与回复解析（心情语义、GIFT/JUNK）
│   ├─ History.java       按玩家的短期对话记忆（环形缓冲 + 空闲过期）
│   ├─ Mood.java          心情账本（0-50、25 平常心、往中性回归）
│   ├─ Rewards.java       礼物与垃圾：物品白名单 + 走 API 发放（不用 give）
│   ├─ Actions.java       动作执行：音效/标题/指令 + 礼物/垃圾/闪电/苦力怕/惊吓
│   └─ Json.java          极小 JSON 读写（不引入外部依赖）
├─ tools\
│   ├─ fetch-libs.cjs     下载编译依赖
│   └─ selftest\          离线自测（124 项：记忆、请求体、心情数学、提示词、配置形状）
└─ lib\                   编译依赖（paper-api、adventure）
```

## 许可证

MIT License —— 可以自由使用、修改、二次开发甚至商用，保留版权声明即可（详见 [LICENSE](LICENSE)）。

版权所有 (c) 2026 新世界网络（New World Network）。
