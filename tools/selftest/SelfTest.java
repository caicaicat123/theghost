package mcbot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** TheGhost 离线自测：记忆、请求体、响应解析、判分与提示词、心情数学、配置文件形状。 */
public final class SelfTest {

    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        memoryRingBuffer();
        memoryIdleExpiry();
        memoryTokenCap();
        requestShape();
        responseParsing();
        replyParsing();
        wordFallback();
        moodMath();
        promptShape();
        configFiles();

        System.out.println(failed == 0 ? "\n全部通过" : "\n失败 " + failed + " 项");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---- 1. 环形缓冲：轮数上限 + 顺序 + 消息形状 ----
    private static void memoryRingBuffer() {
        History h = new History();
        h.configure(true, 8, 300, 500);
        UUID id = UUID.randomUUID();
        for (int i = 1; i <= 10; i++) {
            h.remember(id, "问题" + i, "回答" + i);
        }
        List<History.Turn> turns = h.recall(id);
        check("最多留 8 轮", turns.size() == 8);
        check("丢的是最旧的（第 1、2 轮）", turns.get(0).user().equals("问题3"));
        check("留的是最新的（第 10 轮）", turns.get(7).user().equals("问题10"));

        List<Map<String, Object>> messages = History.toMessages(turns);
        check("每轮摊成 2 条消息", messages.size() == 16);
        check("第 1 条是 user", "user".equals(messages.get(0).get("role")));
        check("第 2 条是 assistant", "assistant".equals(messages.get(1).get("role")));
        check("内容没串", "回答3".equals(messages.get(1).get("content")));

        h.forget(id);
        check("forget 之后是空的", h.recall(id).isEmpty());
    }

    // ---- 2. 空闲超时：久不说话就忘掉 ----
    private static void memoryIdleExpiry() throws Exception {
        History h = new History();
        h.configure(true, 8, 1, 500);
        UUID id = UUID.randomUUID();
        h.remember(id, "在吗", "在");
        check("刚说完还记得", h.recall(id).size() == 1);
        Thread.sleep(1100);
        check("超过 1 秒空闲就忘了", h.recall(id).isEmpty());

        h.configure(true, 8, 0, 500);
        h.remember(id, "在吗", "在");
        check("idle-seconds=0 表示不过期", h.recall(id).size() == 1);

        h.configure(false, 8, 300, 500);
        check("关掉记忆就取不到", h.recall(id).isEmpty());
    }

    // ---- 3. token 上限：超了丢最旧的，但至少留一轮 ----
    private static void memoryTokenCap() {
        History h = new History();
        h.configure(true, 99, 300, 40);
        UUID id = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            h.remember(id, "这是一句比较长的中文问题内容啊" + i, "这是一句比较长的中文回答内容啊" + i);
        }
        List<History.Turn> turns = h.recall(id);
        check("被 token 上限裁过", turns.size() < 5);
        check("至少留一轮", turns.size() >= 1);

        History tight = new History();
        tight.configure(true, 99, 300, 1); // 连一轮都超
        tight.remember(id, "这是很长很长很长的中文问题内容啊", "这是很长很长很长的中文回答内容啊");
        check("再紧也留一轮", tight.recall(id).size() == 1);

        check("token 估算：中文按 0.6（10 字 → 6）", History.estimate("中文中文中文中文中文") == 6);
        check("token 估算：空串是 0", History.estimate("") == 0);
    }

    // ---- 4. 请求体：顺序 + 不开思考模式 + 80 输出上限 ----
    private static void requestShape() {
        DeepSeek ai = new DeepSeek("test-key", "https://api.deepseek.com", "deepseek-flash",
                1.35, 80, 25, 2, false);
        String system = "（system 提示词占位：人设 + 心情 + 动作白名单）";
        List<Map<String, Object>> history = History.toMessages(List.of(
                new History.Turn("CaiCai_Cat 用 @ 对你说了：你好", "哼，谁啊"),
                new History.Turn("CaiCai_Cat 用 @ 对你说了：刚才那句什么意思", "自己想去")));
        Map<String, Object> body = ai.body(system, "场景：本次\n请给出你的回应。", history);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        check("2 轮历史（4 条）+ system + 本次 = 6 条消息", messages.size() == 6);
        check("第 1 条是 system", "system".equals(messages.get(0).get("role")));
        check("第 2 条是历史里的玩家话", "user".equals(messages.get(1).get("role")));
        check("历史内容确实带上了", String.valueOf(messages.get(1).get("content")).contains("你好"));
        check("第 3 条是历史里的回复", "assistant".equals(messages.get(2).get("role")));
        check("第 4 条是第 2 轮玩家话", String.valueOf(messages.get(3).get("content")).contains("刚才那句"));
        check("第 5 条是第 2 轮回复", "自己想去".equals(messages.get(4).get("content")));
        check("最后 1 条是本次消息", "user".equals(messages.get(5).get("role"))
                && String.valueOf(messages.get(5).get("content")).contains("本次"));

        @SuppressWarnings("unchecked")
        Map<String, Object> thinking = (Map<String, Object>) body.get("thinking");
        check("显式关掉思考模式", "disabled".equals(thinking.get("type")));
        check("max_tokens = 80", ((Number) body.get("max_tokens")).longValue() == 80L);
        check("非思考模式下 temperature 生效", body.containsKey("temperature"));
        check("model = deepseek-flash", "deepseek-flash".equals(body.get("model")));
        check("stream = false", Boolean.FALSE.equals(body.get("stream")));

        Map<String, Object> noHistory = ai.body(system, "本次", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bare = (List<Map<String, Object>>) noHistory.get("messages");
        check("无历史时只有 system + user", bare.size() == 2);

        DeepSeek thinker = new DeepSeek("k", "https://api.deepseek.com", "deepseek-flash",
                1.35, 80, 25, 2, true);
        Map<String, Object> tb = thinker.body(system, "本次", List.of());
        check("开思考模式时不传 temperature", !tb.containsKey("temperature"));
        check("开思考模式时 thinking=enabled",
                "enabled".equals(((Map<?, ?>) tb.get("thinking")).get("type")));

        String json = Json.write(body);
        check("请求体是合法 JSON 且能读回", Json.parseObject(json).containsKey("messages"));
    }

    // ---- 5. 响应解析：正文 + usage（缓存命中/未命中/输出） ----
    private static void responseParsing() {
        String response = """
                {"id":"abc","choices":[{"index":0,"message":{"role":"assistant",
                "content":" 哼，谁啊\\nACTION: NONE\\nSCORE: 1 "},"finish_reason":"stop"}],
                "usage":{"prompt_tokens":720,"completion_tokens":23,"total_tokens":743,
                "prompt_cache_hit_tokens":640,"prompt_cache_miss_tokens":80}}""";
        DeepSeek.Result r = DeepSeek.extract(response);
        check("正文解析出来了", r.content().startsWith("哼，谁啊"));
        check("正文两端空白被剪掉", !r.content().endsWith(" "));
        check("缓存命中 token", r.cacheHitTokens() == 640);
        check("缓存未命中 token", r.cacheMissTokens() == 80);
        check("输出 token", r.outputTokens() == 23);

        DeepSeek.Result empty = DeepSeek.extract("{\"choices\":[]}");
        check("空响应不炸", empty.content().isEmpty() && empty.cacheHitTokens() == 0);
        DeepSeek.Result noUsage = DeepSeek.extract("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}");
        check("没有 usage 字段时按 0 记", noUsage.outputTokens() == 0 && "hi".equals(noUsage.content()));
    }

    // ---- 6. 回复解析：SCORE 带正负（正=让它开心，负=惹它生气） ----
    private static void replyParsing() {
        Brain.Reply r = Brain.parse("哼，谁啊\nACTION: NONE\nSCORE: 3");
        check("台词解析", "哼，谁啊".equals(r.say()));
        check("SCORE 正数（让它开心）", r.score() == 3);
        check("NONE 不算动作", !r.hasAction());

        Brain.Reply mad = Brain.parse("你再说一遍\nACTION: JUNK\nSCORE: -2");
        check("SCORE 负数（惹它生气）", mad.score() == -2 && "JUNK".equals(mad.actionType()));

        Brain.Reply plus = Brain.parse("还行\nACTION: NONE\nSCORE: +1");
        check("显式正号", plus.score() == 1);

        Brain.Reply wideMinus = Brain.parse("对不起啦\nACTION: NONE\nSCORE: －3");
        check("全角负号", wideMinus.score() == -3);

        Brain.Reply plain = Brain.parse("今天天气不错\nACTION: NONE\nSCORE: 0");
        check("普通聊天 0 分", plain.score() == 0);

        Brain.Reply chineseKey = Brain.parse("闭嘴\n动作：JUMPSCARE\n心情: -4");
        check("中文键名也算判分行", chineseKey.score() == -4 && "JUMPSCARE".equals(chineseKey.actionType()));

        Brain.Reply legacy = Brain.parse("你好\nACTION: NONE\nANGER: 5");
        check("旧的 ANGER 行仍兼容", legacy.score() == 5);

        Brain.Reply clamped = Brain.parse("气死我了\nACTION: NONE\nSCORE: 9");
        check("超出区间按边界算", clamped.score() == Brain.SCORE_MAX);
        Brain.Reply clampedDown = Brain.parse("对不起\nACTION: NONE\nSCORE: -9");
        check("负向也夹住", clampedDown.score() == Brain.SCORE_MIN);

        Brain.Reply doubled = Brain.parse("随便\nSCORE: 0\nSCORE: -4");
        check("多行时取绝对值最大的", doubled.score() == -4);

        Brain.Reply gift = Brain.parse("拿去用吧\nACTION: GIFT\nSCORE: 2");
        check("GIFT 动作能解析出来", gift.hasAction() && "GIFT".equals(gift.actionType()));

        Brain.Reply sound = Brain.parse("看好\nACTION: SOUND entity.enderman.stare\nSCORE: 0");
        check("SOUND 带参数", sound.hasAction() && "SOUND".equals(sound.actionType())
                && "entity.enderman.stare".equals(sound.actionValue()));

        check("null 不炸", Brain.parse(null).score() == 0);
    }

    // ---- 7. 词表兜底（只在模型不可用时用），注意心情值的正负 ----
    private static void wordFallback() {
        List<String> insult = List.of("傻逼", "滚", "去死");
        List<String> praise = List.of("谢谢", "对不起");
        int insultPts = -3;
        int praisePts = 1;

        check("骂人词 → 心情下降", Brain.wordDelta("你是不是傻逼", insult, praise, insultPts, praisePts) == -3);
        check("夸人/道歉 → 心情回升", Brain.wordDelta("对不起啊", insult, praise, insultPts, praisePts) == 1);
        check("两样都有时按骂人算", Brain.wordDelta("对不起，但你真是个傻逼", insult, praise, insultPts, praisePts) == -3);
        check("普通聊天 → 0", Brain.wordDelta("今天天气不错", insult, praise, insultPts, praisePts) == 0);
        check("空消息不炸", Brain.wordDelta("", insult, praise, insultPts, praisePts) == 0);
        check("null 不炸", Brain.wordDelta(null, insult, praise, insultPts, praisePts) == 0);
        check("空词表不误判", Brain.wordDelta("随便说说", List.of(), List.of(), insultPts, praisePts) == 0);
    }

    // ---- 8. 心情数学：默认值、往中性回归、档位 ----
    private static void moodMath() {
        Mood empty = new Mood(Path.of("definitely-not-a-real-file.yml").toFile());
        empty.setDefault(25);
        check("没记录的人按默认值 25", empty.score(UUID.randomUUID(), 2, 25) == 25);
        empty.setDefault(40);
        check("默认值可配", empty.score(UUID.randomUUID(), 2, 25) == 40);

        long now = System.currentTimeMillis();
        long threeHoursAgo = now - 3L * 3600_000L;
        check("高于中性：每小时往下降",
                Mood.drifted(40, threeHoursAgo, now, 2, 25) == 34);
        check("低于中性：每小时往上升",
                Mood.drifted(10, threeHoursAgo, now, 2, 25) == 16);
        check("回归不会越过中性（从上面）",
                Mood.drifted(26, threeHoursAgo, now, 2, 25) == 25);
        check("回归不会越过中性（从下面）",
                Mood.drifted(24, threeHoursAgo, now, 2, 25) == 25);
        check("正好在中性就不动", Mood.drifted(25, threeHoursAgo, now, 2, 25) == 25);
        check("不到一小时不动", Mood.drifted(40, now - 60000L, now, 2, 25) == 40);
        check("关闭回归（0/小时）就不动", Mood.drifted(40, threeHoursAgo, now, 0, 25) == 40);

        int[] anger = {20, 14, 8};
        int[] joy = {30, 36, 43};
        check("25 → 两档都是 0", new Brain.MoodInfo(25, 25, 50, anger, joy).angerTier() == 0
                && new Brain.MoodInfo(25, 25, 50, anger, joy).joyTier() == 0);
        check("20 → 生气档 1", new Brain.MoodInfo(20, 25, 50, anger, joy).angerTier() == 1);
        check("14 → 生气档 2", new Brain.MoodInfo(14, 25, 50, anger, joy).angerTier() == 2);
        check("8 → 生气档 3", new Brain.MoodInfo(8, 25, 50, anger, joy).angerTier() == 3);
        check("0 → 生气档 3（不越界）", new Brain.MoodInfo(0, 25, 50, anger, joy).angerTier() == 3);
        check("30 → 开心档 1", new Brain.MoodInfo(30, 25, 50, anger, joy).joyTier() == 1);
        check("43 → 开心档 3", new Brain.MoodInfo(43, 25, 50, anger, joy).joyTier() == 3);
        check("50 → 开心档 3（不越界）", new Brain.MoodInfo(50, 25, 50, anger, joy).joyTier() == 3);
        check("门槛数组乱序也算得对",
                new Brain.MoodInfo(14, 25, 50, new int[]{8, 20, 14}, joy).angerTier() == 2);
        check("档位描述：中性", "平常心".equals(new Brain.MoodInfo(25, 25, 50, anger, joy).moodWord()));
        check("档位描述：暴怒", "对他很火大".equals(new Brain.MoodInfo(5, 25, 50, anger, joy).moodWord()));
        check("档位描述：非常喜欢",
                "非常喜欢他".equals(new Brain.MoodInfo(48, 25, 50, anger, joy).moodWord()));
    }

    // ---- 9. 提示词：心情、平常心、GIFT/JUNK 的档位门槛 ----
    private static void promptShape() {
        Brain.Caps all = new Brain.Caps(true, true, true, 1, 1, true, 1, true, 2, true, 2);
        Brain.Caps noRewards = new Brain.Caps(true, false, false, 1, 1, true, 1, true, 2, true, 2);
        int[] anger = {20, 14, 8};
        int[] joy = {30, 36, 43};

        String neutral = Brain.systemPrompt(all, List.of("ambient.cave"), List.of("say"),
                new Brain.MoodInfo(25, 25, 50, anger, joy));
        check("提示词写明平常心 25", neutral.contains("25 是平常心"));
        check("提示词写明上限 50", neutral.contains("0 到 50"));
        check("提示词要求 SCORE 而不是 ANGERS", neutral.contains("SCORE: -5 到 +5") && !neutral.contains("ANGER"));
        check("提示词说明正=开心", neutral.contains("正数 = 他让你开心了"));
        check("提示词说明负=生气", neutral.contains("负数 = 他惹你生气了"));
        check("平常心时不给 GIFT", !neutral.contains("ACTION: GIFT"));
        check("平常心时不给 JUNK", !neutral.contains("ACTION: JUNK"));
        check("平常心时没有闪电/苦力怕/惊吓",
                !neutral.contains("ACTION: LIGHTNING") && !neutral.contains("ACTION: CREEPER")
                        && !neutral.contains("ACTION: JUMPSCARE"));

        String happy = Brain.systemPrompt(all, List.of(), List.of(),
                new Brain.MoodInfo(45, 25, 50, anger, joy));
        check("很开心时提示词给出 GIFT", happy.contains("ACTION: GIFT"));
        check("很开心时不给 JUNK", !happy.contains("ACTION: JUNK"));
        check("很开心时不给 LIGHTNING", !happy.contains("ACTION: LIGHTNING"));
        check("GIFT 说明写明不用它指定物品", happy.contains("不用你指定物品"));

        String mad = Brain.systemPrompt(all, List.of(), List.of(),
                new Brain.MoodInfo(10, 25, 50, anger, joy));
        check("很生气时提示词给出 JUNK", mad.contains("ACTION: JUNK"));
        check("很生气时给出闪电/苦力怕/惊吓",
                mad.contains("ACTION: LIGHTNING") && mad.contains("ACTION: CREEPER")
                        && mad.contains("ACTION: JUMPSCARE"));
        check("很生气时不给 GIFT", !mad.contains("ACTION: GIFT"));

        String mild = Brain.systemPrompt(all, List.of(), List.of(),
                new Brain.MoodInfo(18, 25, 50, anger, joy));
        check("中间档只给 JUNK + 惊吓，不给闪电/苦力怕",
                mild.contains("ACTION: JUNK") && mild.contains("ACTION: JUMPSCARE")
                        && !mild.contains("ACTION: LIGHTNING") && !mild.contains("ACTION: CREEPER"));

        String off = Brain.systemPrompt(noRewards, List.of(), List.of(),
                new Brain.MoodInfo(45, 25, 50, anger, joy));
        check("reward/junk 关掉时提示词里没有 GIFT/JUNK",
                !off.contains("ACTION: GIFT") && !off.contains("ACTION: JUNK"));

        String prankOff = Brain.systemPrompt(new Brain.Caps(false, true, true, 1, 1, true, 1, true, 2, true, 2),
                List.of(), List.of(), new Brain.MoodInfo(5, 25, 50, anger, joy));
        check("prank 关掉时提示词里没有任何真动作",
                !prankOff.contains("ACTION: LIGHTNING") && !prankOff.contains("ACTION: CREEPER")
                        && !prankOff.contains("ACTION: JUMPSCARE") && !prankOff.contains("ACTION: GIFT")
                        && !prankOff.contains("ACTION: JUNK"));

        // 提示词的档位门槛必须跟着配置走，否则会出现"提示词说能送、代码却拒绝"
        String strict = Brain.systemPrompt(new Brain.Caps(true, true, true, 3, 3, true, 1, true, 2, true, 2),
                List.of(), List.of(), new Brain.MoodInfo(38, 25, 50, anger, joy));
        check("开心档 2 但门槛设成 3 时，提示词不给 GIFT", !strict.contains("ACTION: GIFT"));
        check("生气档 0 时门槛 3 也不给 JUNK", !strict.contains("ACTION: JUNK"));
        String loose = Brain.systemPrompt(new Brain.Caps(true, true, true, 3, 3, true, 1, true, 2, true, 2),
                List.of(), List.of(), new Brain.MoodInfo(45, 25, 50, anger, joy));
        check("开心档 3 时门槛 3 就给 GIFT", loose.contains("ACTION: GIFT"));
    }

    // ---- 10. 配置文件形状：物品表能读、命令白名单不撞硬拦截、兜底分符号正确 ----
    private static void configFiles() {
        String repo = System.getProperty("theghost.repo");
        if (repo == null || repo.isBlank()) {
            System.out.println("  [SKIP] 没给 -Dtheghost.repo，跳过配置文件检查");
            return;
        }
        String text;
        try {
            text = Files.readString(Path.of(repo, "config.yml"));
        } catch (IOException e) {
            check("读得到 config.yml（" + e.getMessage() + "）", false);
            return;
        }

        Map<String, List<String>> tables = new LinkedHashMap<>();
        for (String key : List.of("small", "good", "big")) {
            tables.put(key, itemList(text, "^\\s*" + key + ":\\s*$"));
        }
        List<String> junk = itemList(text, "^\\s*items:\\s*$", "^junk:\\s*$");

        check("小礼物表非空（" + tables.get("small").size() + " 项）", tables.get("small").size() >= 8);
        check("好东西表非空（" + tables.get("good").size() + " 项）", tables.get("good").size() >= 8);
        check("钻石档表非空（" + tables.get("big").size() + " 项）", tables.get("big").size() >= 5);
        check("垃圾表非空（" + junk.size() + " 种）", junk.size() >= 8);
        check("物品总数够多（" + (tables.get("small").size() + tables.get("good").size()
                        + tables.get("big").size() + junk.size()) + " 项）",
                tables.get("small").size() + tables.get("good").size()
                        + tables.get("big").size() + junk.size() >= 40);
        check("钻石档里有钻石装备", tables.get("big").stream().anyMatch(s -> s.startsWith("diamond_")));

        List<String> badFormat = new ArrayList<>();
        for (List<String> list : tables.values()) {
            for (String entry : list) {
                if (!entry.matches("[a-z_]+(:\\d+)?")) {
                    badFormat.add(entry);
                }
            }
        }
        for (String entry : junk) {
            if (!entry.matches("[a-z_]+(:\\d+)?")) {
                badFormat.add(entry);
            }
        }
        check("物品条目格式都对（小写 id 或 id:数量）" + (badFormat.isEmpty() ? "" : " → " + badFormat),
                badFormat.isEmpty());

        // 命令白名单不能和硬拦截名单有交集（否则就是"配置写了但永远不会生效"，或者更糟）
        List<String> allowed = itemList(text, "^\\s*allowed:\\s*$", "^  commands:\\s*$");
        List<String> blocked = Actions.hardBlocked();
        List<String> clash = new ArrayList<>();
        for (String cmd : allowed) {
            if (blocked.contains(cmd.toLowerCase(java.util.Locale.ROOT))) {
                clash.add(cmd);
            }
        }
        check("命令白名单（" + allowed.size() + " 项）不与硬拦截冲突" + (clash.isEmpty() ? "" : " → " + clash),
                clash.isEmpty());
        check("命令白名单里没有 give/loot/item/xp/effect",
                allowed.stream().noneMatch(s -> List.of("give", "loot", "item", "xp", "effect", "summon")
                        .contains(s.toLowerCase(java.util.Locale.ROOT))));

        check("兜底分符号正确：骂人是负、夸人是正",
                pickInt(text, "^\\s*insult-points:\\s*(-?\\d+)") < 0
                        && pickInt(text, "^\\s*praise-points:\\s*(-?\\d+)") > 0);
        check("心情中性值是 25", pickInt(text, "^\\s*neutral:\\s*(\\d+)") == 25);
        check("心情上限是 50", pickInt(text, "^\\s*max:\\s*(\\d+)") == 50);
        check("副本世界被豁免", text.matches("(?s).*exempt-world-prefixes:\\s*\\[\\s*\"nwndungeon\".*"));
        check("先警告后杀开着", text.matches("(?s).*warn-first:\\s*true.*"));
        // 注意有两个 max-tokens（deepseek 的 80 与 memory 的上限），所以要锚在 memory 段里
        check("记忆上限提到 1500", pickInt(text, "(?s)^memory:.*?^\\s*max-tokens:\\s*(\\d+)") == 1500);
        check("记忆轮数 20", pickInt(text, "^\\s*max-turns:\\s*(\\d+)") == 20);
    }

    /** 取某个键开头那段里的 `- "值"` 列表。 */
    private static List<String> itemList(String text, String startPattern) {
        return itemList(text, startPattern, null);
    }

    /**
     * @param startPattern 从匹配这行的下一行开始收集
     * @param afterPattern 非空时先跳到这行，再从它之后找 startPattern
     */
    private static List<String> itemList(String text, String startPattern, String afterPattern) {
        List<String> out = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");
        boolean afterSeen = afterPattern == null;
        boolean collecting = false;
        for (String line : lines) {
            if (!afterSeen) {
                afterSeen = line.matches(afterPattern);
                continue;
            }
            if (!collecting) {
                if (line.matches(startPattern)) {
                    collecting = true;
                }
                continue;
            }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^\\s*-\\s*\"([^\"]+)\"\\s*$").matcher(line);
            if (m.matches()) {
                out.add(m.group(1));
                continue;
            }
            if (line.trim().isEmpty() || line.trim().startsWith("#")) {
                continue;
            }
            break; // 列表结束
        }
        return out;
    }

    private static int pickInt(String text, String pattern) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.MULTILINE)
                .matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "  [OK] " : "  [FAIL] ") + name);
        if (!ok) {
            failed++;
        }
    }

    private SelfTest() {
    }
}
