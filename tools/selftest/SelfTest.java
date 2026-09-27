package mcbot;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** TheGhost 1.5.2 离线自测：对话记忆、请求体形状、响应解析、回复解析。 */
public final class SelfTest {

    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        memoryRingBuffer();
        memoryIdleExpiry();
        memoryTokenCap();
        requestShape();
        responseParsing();
        replyParsing();

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
        String system = "（system 提示词占位：人设 + 等级 + 动作白名单）";
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
        check("历史排在本次消息之前", messages.get(5).get("content") != messages.get(1).get("content"));

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

        // 开了思考模式就不该再传 temperature（官方：思考模式下它不生效）
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
                "content":" 哼，谁啊\\nACTION: NONE\\nANGER: 1 "},"finish_reason":"stop"}],
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

    // ---- 6. 回复解析（回归：别把 1.5.1 的行为改坏） ----
    private static void replyParsing() {
        Brain.Reply r = Brain.parse("哼，谁啊\nACTION: NONE\nANGER: 1");
        check("台词解析", "哼，谁啊".equals(r.say()));
        check("ANGER 解析", r.anger() == 1);
        check("NONE 不算动作", !r.hasAction());

        Brain.Reply sound = Brain.parse("看好\nACTION: SOUND entity.enderman.stare\nANGER: 0");
        check("SOUND 带参数", sound.hasAction() && "SOUND".equals(sound.actionType())
                && "entity.enderman.stare".equals(sound.actionValue()));

        Brain.Reply wide = Brain.parse("走开\n动作：CREEPER\n愤怒: 4");
        check("全角冒号也能认", "CREEPER".equals(wide.actionType()) && wide.anger() == 4);
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
