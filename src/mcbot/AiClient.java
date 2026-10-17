package mcbot;

import java.net.URI;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/** 多服务商异步文本客户端：服务商预设、OpenAI 兼容协议与 Anthropic Messages。 */
public class AiClient {

    /** 文本及服务商报告的缓存命中、非命中、输出 token；不推算跨服务商价格。 */
    public record Result(String content, long cacheHitTokens, long cacheMissTokens, long outputTokens) {

        public String usageLine() {
            return "输入命中 " + cacheHitTokens + " / 未命中 " + cacheMissTokens + " / 输出 " + outputTokens;
        }
    }

    public enum Provider {
        DEEPSEEK("deepseek", "https://api.deepseek.com", "deepseek-flash"),
        OPENAI("openai", "https://api.openai.com/v1", ""),
        ANTHROPIC("anthropic", "https://api.anthropic.com/v1", ""),
        KIMI("kimi", "https://api.moonshot.cn/v1", ""),
        ZHIPU("zhipu", "https://open.bigmodel.cn/api/paas/v4", ""),
        MINIMAX("minimax", "https://api.minimax.cn/v1", ""),
        BAICHUAN("baichuan", "https://api.baichuan-ai.com/v1", ""),
        STEPFUN("stepfun", "https://api.stepfun.com/v1", ""),
        DOUBAO("doubao", "https://ark.cn-beijing.volces.com/api/v3", ""),
        QWEN("qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1", ""),
        ERNIE("ernie", "https://qianfan.baidubce.com/v2", ""),
        HUNYUAN("hunyuan", "https://api.hunyuan.cloud.tencent.com/v1", ""),
        SPARK("spark", "https://spark-api-open.xf-yun.com/v1", ""),
        XAI("xai", "https://api.x.ai/v1", ""),
        MISTRAL("mistral", "https://api.mistral.ai/v1", ""),
        OPENAI_COMPATIBLE("openai-compatible", "", "");
        public final String id, baseUrl, model;
        Provider(String id, String baseUrl, String model) {
            this.id = id; this.baseUrl = baseUrl; this.model = model;
        }
        public static Provider parse(String value) {
            String id = value == null ? "deepseek" : value.trim().toLowerCase(java.util.Locale.ROOT);
            id = switch (id) {
                case "claude" -> "anthropic";
                case "moonshot" -> "kimi";
                case "glm", "bigmodel" -> "zhipu";
                case "step" -> "stepfun";
                case "bytedance", "ark" -> "doubao";
                case "alibaba", "aliyun", "dashscope", "tongyi" -> "qwen";
                case "baidu", "qianfan", "wenxin" -> "ernie";
                case "tencent" -> "hunyuan";
                case "iflytek", "xunfei" -> "spark";
                case "grok" -> "xai";
                default -> id;
            };
            for (Provider p : values()) if (p.id.equals(id)) return p;
            throw new IllegalArgumentException("未知 ai.provider：" + id);
        }
    }

    private final Provider provider;
    private final boolean sendTemperature;
    private final String tokenParameter;
    private final String apiVersion;
    private final Map<String, Object> extraBody;
    private final HttpClient http;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final int timeoutSeconds;
    private final int maxAttempts;
    private final boolean thinking;

    public AiClient(String apiKey, String baseUrl, String model, double temperature,
                    int maxTokens, int timeoutSeconds, int maxAttempts, boolean thinking) {
        this(Provider.DEEPSEEK, apiKey, baseUrl, model, temperature, maxTokens,
                timeoutSeconds, maxAttempts, thinking, true, "max_tokens", "2023-06-01");
    }

    public AiClient(Provider provider, String apiKey, String baseUrl, String model, double temperature,
                    int maxTokens, int timeoutSeconds, int maxAttempts, boolean thinking,
                    boolean sendTemperature, String tokenParameter, String apiVersion) {
        this(provider, apiKey, baseUrl, model, temperature, maxTokens, timeoutSeconds, maxAttempts,
                thinking, sendTemperature, tokenParameter, apiVersion, Map.of());
    }

    public AiClient(Provider provider, String apiKey, String baseUrl, String model, double temperature,
                    int maxTokens, int timeoutSeconds, int maxAttempts, boolean thinking,
                    boolean sendTemperature, String tokenParameter, String apiVersion, Map<String, Object> extraBody) {
        this.provider = java.util.Objects.requireNonNull(provider);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = (baseUrl == null || baseUrl.isBlank() ? provider.baseUrl : baseUrl.trim())
                .replaceAll("/+$", "");
        if (!this.baseUrl.isBlank()) {
            URI uri = URI.create(this.baseUrl);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("base-url 必须是 HTTP(S) API 基础地址，不含账号、查询或片段");
            }
        }
        this.model = model == null || model.isBlank() ? provider.model : model.trim();
        this.temperature = temperature;
        this.maxTokens = Math.max(1, maxTokens);
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.thinking = thinking;
        this.sendTemperature = sendTemperature;
        this.tokenParameter = tokenParameter == null || tokenParameter.isBlank()
                ? (provider == Provider.OPENAI ? "max_completion_tokens" : "max_tokens") : tokenParameter;
        if (!List.of("max_tokens", "max_completion_tokens").contains(this.tokenParameter)) {
            throw new IllegalArgumentException("token-parameter 必须是 max_tokens 或 max_completion_tokens");
        }
        this.extraBody = extraBody == null ? Map.of() : Map.copyOf(extraBody);
        for (String reserved : List.of("model", "messages", "system", "stream")) {
            if (this.extraBody.containsKey(reserved)) throw new IllegalArgumentException("extra-body 不可覆盖 " + reserved);
        }
        this.apiVersion = apiVersion == null || apiVersion.isBlank() ? "2023-06-01" : apiVersion;
        // 显式不走代理：服务器上若有插件设置过 https.proxyHost，会让连接卡死到超时
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .proxy(ProxySelector.of(null))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public boolean configured() {
        return !apiKey.isBlank() && !apiKey.contains("填入") && !baseUrl.isBlank() && !model.isBlank();
    }

    public String provider() { return provider.id; }

    public String model() {
        return model;
    }

    public CompletableFuture<Result> chat(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, List.of());
    }

    /**
     * @param history 之前几轮的 {role, content}，按时间顺序夹在 system 和本次 user 之间。
     *                形状固定成「system → 历史 → 本次」是为了命中前缀缓存，别把历史拼进 system。
     */
    public CompletableFuture<Result> chat(String systemPrompt, String userPrompt,
                                          List<Map<String, Object>> history) {
        if (!configured()) return CompletableFuture.failedFuture(new IllegalStateException("AI 服务商配置不完整"));
        try {
            return attempt(systemPrompt, userPrompt, history, 1);
        } catch (IllegalArgumentException error) {
            return CompletableFuture.failedFuture(new IllegalStateException("API 请求配置无效，请检查模型、密钥与参数"));
        }
    }

    /** 轻量连通性检查：不带密钥地打一次根路径，只看网络能不能通。 */
    public CompletableFuture<Integer> ping() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).thenApply(HttpResponse::statusCode);
    }

    private CompletableFuture<Result> attempt(String systemPrompt, String userPrompt,
                                              List<Map<String, Object>> history, int tryNo) {
        return send(systemPrompt, userPrompt, history).exceptionallyCompose(error -> {
            if (tryNo < maxAttempts) {
                return CompletableFuture
                        .supplyAsync(() -> null,
                                CompletableFuture.delayedExecutor(700L * tryNo, TimeUnit.MILLISECONDS))
                        .thenCompose(ignored -> attempt(systemPrompt, userPrompt, history, tryNo + 1));
            }
            Throwable cause = error instanceof CompletionException && error.getCause() != null
                    ? error.getCause() : error;
            return CompletableFuture.failedFuture(cause);
        });
    }

    URI endpoint() {
        String path = provider == Provider.ANTHROPIC ? "/messages" : "/chat/completions";
        return URI.create(baseUrl + path);
    }

    HttpRequest request(String systemPrompt, String userPrompt, List<Map<String, Object>> history) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint())
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json");
        if (provider == Provider.ANTHROPIC) {
            builder.header("x-api-key", apiKey).header("anthropic-version", apiVersion);
        } else {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder.POST(HttpRequest.BodyPublishers.ofString(
                Json.write(body(systemPrompt, userPrompt, history)), StandardCharsets.UTF_8)).build();
    }

    private CompletableFuture<Result> send(String systemPrompt, String userPrompt,
                                           List<Map<String, Object>> history) {
        HttpRequest request = request(systemPrompt, userPrompt, history);

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("HTTP " + response.statusCode() + " " + shorten(response.body().replace(apiKey, "[REDACTED]")));
                    }
                    Result result = provider == Provider.ANTHROPIC ? extractAnthropic(response.body()) : extract(response.body());
                    // 部分 MiniMax 模型将思考内容放在 content 内，不能送入游戏或历史。
                    result = new Result(result.content().replaceAll("(?is)<think>.*?</think>", "")
                            .replaceAll("(?is)<think>.*$", "").trim(), result.cacheHitTokens(), result.cacheMissTokens(), result.outputTokens());
                    if (result.content().isBlank()) throw new IllegalStateException("API 返回空文本，请检查模型与 max-tokens");
                    return result;
                });
    }

    /**
     * 请求体。消息顺序固定为「system → 历史 → 本次消息」，这是前缀缓存能命中的前提，别改。
     * 拆出来是为了能离线验证请求体（见 tools 里的自测）。
     */
    Map<String, Object> body(String systemPrompt, String userPrompt, List<Map<String, Object>> history) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (provider != Provider.ANTHROPIC) messages.add(Map.of("role", "system", "content", systemPrompt));
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(Map.of("role", "user", "content", userPrompt));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        if (provider == Provider.ANTHROPIC) body.put("system", systemPrompt);
        if (provider == Provider.DEEPSEEK) body.put("thinking", Map.of("type", thinking ? "enabled" : "disabled"));
        if (sendTemperature && !(provider == Provider.DEEPSEEK && thinking)) body.put("temperature", temperature);
        body.put(provider == Provider.ANTHROPIC ? "max_tokens" : tokenParameter, maxTokens);
        body.put("stream", false);
        body.putAll(extraBody);
        return body;
    }

    private static String shorten(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 200 ? t.substring(0, 200) + "..." : t;
    }

    /** 解析一次响应：正文 + usage 里的缓存命中/未命中/输出 token。 */
    static Result extract(String responseBody) {
        Map<String, Object> root = Json.parseObject(responseBody);
        Map<String, Object> usage = asMap(root.get("usage"));
        long hit = usage.containsKey("prompt_cache_hit_tokens") ? number(usage, "prompt_cache_hit_tokens")
                : number(asMap(usage.get("prompt_tokens_details")), "cached_tokens");
        long miss = usage.containsKey("prompt_cache_miss_tokens") ? number(usage, "prompt_cache_miss_tokens")
                : Math.max(0, number(usage, "prompt_tokens") - hit);
        return new Result(contentOf(root), hit, miss, number(usage, "completion_tokens"));
    }

    static Result extractAnthropic(String responseBody) {
        Map<String, Object> root = Json.parseObject(responseBody);
        Map<String, Object> usage = asMap(root.get("usage"));
        StringBuilder text = new StringBuilder();
        if (root.get("content") instanceof List<?> blocks) {
            for (Object block : blocks) {
                Map<String, Object> part = asMap(block);
                if ("text".equals(part.get("type")) && part.get("text") instanceof String value) text.append(value);
            }
        }
        // Anthropic input_tokens 不含缓存读取/写入；写入也计入非命中，避免漏计。
        return new Result(text.toString().trim(), number(usage, "cache_read_input_tokens"),
                number(usage, "input_tokens") + number(usage, "cache_creation_input_tokens"),
                number(usage, "output_tokens"));
    }

    static String extractContent(String responseBody) {
        return extract(responseBody).content();
    }

    private static String contentOf(Map<String, Object> root) {
        Object choices = root.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> choice)) {
            return "";
        }
        Object message = choice.get("message");
        if (!(message instanceof Map<?, ?> msg)) {
            return "";
        }
        Object content = msg.get("content");
        return content == null ? "" : String.valueOf(content).trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static long number(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
