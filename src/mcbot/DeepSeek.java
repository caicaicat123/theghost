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

/** DeepSeek Chat Completions 的最小客户端。所有请求都是异步的。 */
public final class DeepSeek {

    /**
     * 一次调用的结果。
     *
     * <p>带上 token 用量是为了看清真实开销：DeepSeek 对重复前缀有硬盘缓存，
     * 命中价只有未命中的 1/50 —— 所以「带历史」不等于「贵」，命中率才是决定性的。
     */
    public record Result(String content, long cacheHitTokens, long cacheMissTokens, long outputTokens) {

        public String usageLine() {
            return "输入命中 " + cacheHitTokens + " / 未命中 " + cacheMissTokens + " / 输出 " + outputTokens;
        }
    }

    private final HttpClient http;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final int timeoutSeconds;
    private final int maxAttempts;
    private final boolean thinking;

    public DeepSeek(String apiKey, String baseUrl, String model, double temperature,
                    int maxTokens, int timeoutSeconds, int maxAttempts, boolean thinking) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.deepseek.com" : baseUrl.trim();
        this.model = model == null || model.isBlank() ? "deepseek-flash" : model.trim();
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeoutSeconds = timeoutSeconds;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.thinking = thinking;
        // 显式不走代理：服务器上若有插件设置过 https.proxyHost，会让连接卡死到超时
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .proxy(ProxySelector.of(null))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public boolean configured() {
        return !apiKey.isBlank() && !apiKey.contains("填入");
    }

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
        return attempt(systemPrompt, userPrompt, history, 1);
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

    private CompletableFuture<Result> send(String systemPrompt, String userPrompt,
                                           List<Map<String, Object>> history) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(
                        Json.write(body(systemPrompt, userPrompt, history)), StandardCharsets.UTF_8))
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("HTTP " + response.statusCode() + " " + shorten(response.body()));
                    }
                    return extract(response.body());
                });
    }

    /**
     * 请求体。消息顺序固定为「system → 历史 → 本次消息」，这是前缀缓存能命中的前提，别改。
     * 拆出来是为了能离线验证请求体（见 tools 里的自测）。
     */
    Map<String, Object> body(String systemPrompt, String userPrompt, List<Map<String, Object>> history) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(Map.of("role", "user", "content", userPrompt));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        // 思考模式默认是开的，但对这个插件只有坏处：temperature 会失效，
        // 思维链还会白烧输出 token（max-tokens 只有 80，容易被它吃光导致回复为空）。
        body.put("thinking", Map.of("type", thinking ? "enabled" : "disabled"));
        if (!thinking) {
            body.put("temperature", temperature);
        }
        body.put("max_tokens", maxTokens);
        body.put("stream", false);
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
        return new Result(
                contentOf(root),
                number(usage, "prompt_cache_hit_tokens"),
                number(usage, "prompt_cache_miss_tokens"),
                number(usage, "completion_tokens"));
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
