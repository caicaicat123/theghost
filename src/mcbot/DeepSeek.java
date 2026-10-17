package mcbot;

/** 保留旧客户端构造方式；新调用请使用 AiClient。 */
public final class DeepSeek extends AiClient {
    public DeepSeek(String apiKey, String baseUrl, String model, double temperature,
                    int maxTokens, int timeoutSeconds, int maxAttempts, boolean thinking) {
        super(apiKey, baseUrl, model, temperature, maxTokens, timeoutSeconds, maxAttempts, thinking);
    }
}
