package mcbot;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 真正发送 HTTP 到本地模拟服务，验证协议与错误路径；不调用外部服务。 */
public final class ProviderTest {
    private static int passed;
    private static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
        passed++;
    }

    private static AiClient client(AiClient.Provider p, String url, int attempts) {
        return new AiClient(p, "test-key", url, "test-model", 1.0, 256, 2,
                attempts, false, false, "", "2023-06-01");
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        check("Claude 别名", AiClient.Provider.parse("Claude") == AiClient.Provider.ANTHROPIC);
        try {
            AiClient.Provider.parse("typo");
            throw new AssertionError("未知服务商必须拒绝");
        } catch (IllegalArgumentException expected) { passed++; }
        Map<String, String> aliases = Map.ofEntries(
                Map.entry("moonshot", "kimi"), Map.entry("glm", "zhipu"), Map.entry("step", "stepfun"),
                Map.entry("bytedance", "doubao"), Map.entry("alibaba", "qwen"), Map.entry("baidu", "ernie"),
                Map.entry("tencent", "hunyuan"), Map.entry("iflytek", "spark"), Map.entry("grok", "xai"));
        aliases.forEach((alias, id) -> check("服务商别名 " + alias, AiClient.Provider.parse(alias).id.equals(id)));
        for (String removed : List.of("gemini", "cohere", "llama")) {
            try {
                AiClient.Provider.parse(removed);
                throw new AssertionError("已移除的原生服务商应拒绝 " + removed);
            } catch (IllegalArgumentException expected) { passed++; }
        }
        Map<String, String> endpoints = Map.ofEntries(
                Map.entry("deepseek", "https://api.deepseek.com"),
                Map.entry("openai", "https://api.openai.com/v1"),
                Map.entry("anthropic", "https://api.anthropic.com/v1"),
                Map.entry("kimi", "https://api.moonshot.cn/v1"),
                Map.entry("zhipu", "https://open.bigmodel.cn/api/paas/v4"),
                Map.entry("minimax", "https://api.minimax.cn/v1"),
                Map.entry("baichuan", "https://api.baichuan-ai.com/v1"),
                Map.entry("stepfun", "https://api.stepfun.com/v1"),
                Map.entry("doubao", "https://ark.cn-beijing.volces.com/api/v3"),
                Map.entry("qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
                Map.entry("ernie", "https://qianfan.baidubce.com/v2"),
                Map.entry("hunyuan", "https://api.hunyuan.cloud.tencent.com/v1"),
                Map.entry("spark", "https://spark-api-open.xf-yun.com/v1"),
                Map.entry("xai", "https://api.x.ai/v1"),
                Map.entry("mistral", "https://api.mistral.ai/v1"));
        endpoints.forEach((id, url) -> check("默认基础地址 " + id,
                AiClient.Provider.parse(id).baseUrl.equals(url)));
        for (AiClient.Provider p : AiClient.Provider.values()) {
            var ai = client(p, "http://127.0.0.1:1/v1///", 1);
            var history = List.<Map<String, Object>>of(Map.of("role", "user", "content", "旧问题"),
                    Map.of("role", "assistant", "content", "旧回答"));
            var body = ai.body("人设", "新问题", history);
            var messages = (List<Map<String, Object>>) body.get("messages");
            boolean claude = p == AiClient.Provider.ANTHROPIC;
            check("历史与本轮完整 " + p, messages.size() == (claude ? 3 : 4)
                    && messages.get(messages.size() - 1).get("content").equals("新问题"));
            check("Claude system 独立 " + p, claude ? "人设".equals(body.get("system"))
                    && messages.get(0).get("role").equals("user") : !body.containsKey("system"));
            check("不发送额外温度 " + p, !body.containsKey("temperature"));
            check("只给 DeepSeek 发送 thinking " + p,
                    body.containsKey("thinking") == (p == AiClient.Provider.DEEPSEEK));
            check("token 参数 " + p, body.containsKey(p == AiClient.Provider.OPENAI ? "max_completion_tokens" : "max_tokens"));
            var request = ai.request("人设", "新问题", history);
            check("尾斜杠与路径 " + p, request.uri().getPath().equals(claude ? "/v1/messages" : "/v1/chat/completions"));
            check("鉴权隔离 " + p, claude ? request.headers().firstValue("x-api-key").orElse("").equals("test-key")
                    && request.headers().firstValue("Authorization").isEmpty()
                    && request.headers().firstValue("anthropic-version").orElse("").equals("2023-06-01")
                    : request.headers().firstValue("Authorization").orElse("").equals("Bearer test-key")
                    && request.headers().firstValue("x-api-key").isEmpty());
            check("缺 Key/模型不算已配置 " + p, !new AiClient(p, "", "", "", 1, 80, 2, 1, false, false, "", "").configured());
        }
        var open = AiClient.extract("{\"choices\":[{\"message\":{\"content\":\"hello\"}}],\"usage\":{\"prompt_tokens\":100,\"prompt_tokens_details\":{\"cached_tokens\":40},\"completion_tokens\":7}}");
        check("OpenAI usage", open.cacheHitTokens() == 40 && open.cacheMissTokens() == 60 && open.outputTokens() == 7);
        var claude = AiClient.extractAnthropic("{\"content\":[{\"type\":\"thinking\",\"thinking\":\"hidden\"},{\"type\":\"text\",\"text\":\"hello\"},{\"type\":\"text\",\"text\":\" world\"}],\"usage\":{\"input_tokens\":10,\"cache_read_input_tokens\":40,\"cache_creation_input_tokens\":20,\"output_tokens\":7}}");
        check("Claude 文本及缓存", claude.content().equals("hello world") && claude.cacheHitTokens() == 40
                && claude.cacheMissTokens() == 30 && claude.outputTokens() == 7);
        check("Claude 缺 usage", AiClient.extractAnthropic("{\"content\":[]}").outputTokens() == 0);

        var custom = new AiClient(AiClient.Provider.QWEN, "test-key", "http://127.0.0.1:1", "model", 1,
                512, 2, 1, false, false, "", "", Map.of("enable_thinking", false,
                "chat_template_kwargs", Map.of("custom", "value")));
        check("模型扩展参数透传", Boolean.FALSE.equals(custom.body("人设", "问题", List.of()).get("enable_thinking"))
                && custom.body("人设", "问题", List.of()).get("chat_template_kwargs") instanceof Map);
        for (String reserved : List.of("model", "messages", "system", "stream")) {
            try {
                new AiClient(AiClient.Provider.QWEN, "test-key", "", "model", 1, 80, 2, 1,
                        false, false, "", "", Map.of(reserved, "override"));
                throw new AssertionError("不可覆盖保留字段 " + reserved);
            } catch (IllegalArgumentException expected) { passed++; }
        }

        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response;
            int status = 200;
            if (path.startsWith("/retry") && requests.get() == 1) {
                status = 503; response = "temporarily unavailable";
            } else if (path.startsWith("/error")) {
                status = 401; response = "invalid test-key";
            } else if (path.startsWith("/empty")) {
                response = "{\"choices\":[]}";
            } else if (path.endsWith("/messages")) {
                check("实际 Claude 鉴权", "test-key".equals(exchange.getRequestHeaders().getFirst("x-api-key")));
                check("实际 Claude 人设", "人设".equals(Json.parseObject(body).get("system")));
                response = "{\"content\":[{\"type\":\"text\",\"text\":\"回复\"}],\"usage\":{\"output_tokens\":3}}";
            } else {
                check("实际 Bearer 鉴权", "Bearer test-key".equals(exchange.getRequestHeaders().getFirst("Authorization")));
                String text = path.contains("/minimax/") ? "<think>内部思考</think>回复" : "回复";
                response = Json.write(Map.of("choices", List.of(Map.of("message", Map.of("content", text))),
                        "usage", Map.of("completion_tokens", 3)));
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            for (AiClient.Provider p : AiClient.Provider.values()) {
                var result = client(p, base + "/providers/" + p.id + "/v1", 1).chat("人设", "问题").get(5, TimeUnit.SECONDS);
                check("HTTP 往返 " + p, result.content().equals("回复") && result.outputTokens() == 3);
            }
            requests.set(0);
            check("503 后重试成功", client(AiClient.Provider.OPENAI, base + "/retry", 2)
                    .chat("人设", "问题").get(5, TimeUnit.SECONDS).content().equals("回复") && requests.get() == 2);
            for (String path : List.of("/error", "/empty")) {
                try {
                    client(AiClient.Provider.OPENAI, base + path, 1).chat("人设", "问题").get(5, TimeUnit.SECONDS);
                    throw new AssertionError("应报告错误 " + path);
                } catch (java.util.concurrent.ExecutionException expected) {
                    check("失败信息 " + path, path.equals("/error")
                            ? expected.getCause().getMessage().contains("401") && !expected.getCause().getMessage().contains("test-key")
                            : expected.getCause().getMessage().contains("空文本"));
                }
            }
        } finally { server.stop(0); }
        System.out.println("多服务商验证通过：" + passed + " 项（含本地 HTTP）");
    }
}
