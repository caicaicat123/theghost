package mcbot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 按玩家各存一份短期对话记忆。
 *
 * <p>消息形状固定成「system → 历史（user/assistant 交替）→ 本次消息」：
 * 每次请求都在上一轮的前缀后面追加，DeepSeek 的输入前缀缓存才有机会命中
 * （命中 0.02 元/百万，未命中 1 元/百万）——所以历史越长并不等于账单越贵。
 *
 * <p>正因为要保住前缀，{@link Brain#systemPrompt} 一个字都不改，
 * 历史只往前追加，不做重排、不改写。
 */
public final class History {

    /** 一轮对话：玩家说的话 + 它回的话。 */
    public record Turn(String user, String assistant) {
    }

    private static final class Session {
        private final List<Turn> turns = new ArrayList<>();
        private long touched;
    }

    private final Map<UUID, Session> sessions = new HashMap<>();

    private boolean enabled = true;
    private int maxTurns = 8;
    private int idleSeconds = 300;
    private int maxTokens = 500;

    public void configure(boolean enabled, int maxTurns, int idleSeconds, int maxTokens) {
        this.enabled = enabled;
        this.maxTurns = Math.max(0, maxTurns);
        this.idleSeconds = Math.max(0, idleSeconds);
        this.maxTokens = Math.max(0, maxTokens);
    }

    public boolean enabled() {
        return enabled;
    }

    public int maxTurns() {
        return maxTurns;
    }

    public int idleSeconds() {
        return idleSeconds;
    }

    public int maxTokens() {
        return maxTokens;
    }

    /** 取这个玩家之前几轮说过的话；没记录或已超时就返回空表。 */
    public synchronized List<Turn> recall(UUID id) {
        if (!usable(id)) {
            return List.of();
        }
        Session session = sessions.get(id);
        if (session == null) {
            return List.of();
        }
        if (expired(session)) {
            sessions.remove(id);
            return List.of();
        }
        return List.copyOf(session.turns);
    }

    /** 记一轮对话。只在模型真的回了话之后调用，免得把兜底台词写进记忆。 */
    public synchronized void remember(UUID id, String user, String assistant) {
        if (!usable(id) || user == null || user.isBlank() || assistant == null || assistant.isBlank()) {
            return;
        }
        Session session = sessions.computeIfAbsent(id, key -> new Session());
        if (expired(session)) {
            // 上一段对话已经聊完很久了，重新开一段
            session.turns.clear();
        }
        session.turns.add(new Turn(user, assistant));
        session.touched = System.currentTimeMillis();
        trim(session);
    }

    public synchronized void forget(UUID id) {
        if (id != null) {
            sessions.remove(id);
        }
    }

    public synchronized void clear() {
        sessions.clear();
    }

    /** 现在记着几个人的对话。 */
    public synchronized int tracked() {
        return sessions.size();
    }

    public synchronized int size(UUID id) {
        Session session = id == null ? null : sessions.get(id);
        return session == null ? 0 : session.turns.size();
    }

    private boolean usable(UUID id) {
        return enabled && maxTurns > 0 && id != null;
    }

    private boolean expired(Session session) {
        return idleSeconds > 0 && System.currentTimeMillis() - session.touched > idleSeconds * 1000L;
    }

    /** 顶掉最旧的，直到轮数和估算 token 都回到上限内（至少留一轮）。 */
    private void trim(Session session) {
        while (session.turns.size() > maxTurns) {
            session.turns.remove(0);
        }
        if (maxTokens <= 0) {
            return;
        }
        while (session.turns.size() > 1 && tokens(session.turns) > maxTokens) {
            session.turns.remove(0);
        }
    }

    private static int tokens(List<Turn> turns) {
        int sum = 0;
        for (Turn turn : turns) {
            sum += estimate(turn.user()) + estimate(turn.assistant());
        }
        return sum;
    }

    /** 官方口径估算：1 个中文字符 ≈ 0.6 token，1 个英文字符 ≈ 0.3 token。 */
    public static int estimate(String text) {
        if (text == null) {
            return 0;
        }
        int wide = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean cjk = (c >= 0x2E80 && c <= 0x9FFF) || (c >= 0xF900 && c <= 0xFAFF)
                    || (c >= 0xFF00 && c <= 0xFFEF);
            if (cjk) {
                wide++;
            }
        }
        return (int) Math.ceil(wide * 0.6 + (text.length() - wide) * 0.3);
    }

    /** 把历史摊成可直接拼进 messages 的 {role, content} 列表。 */
    public static List<Map<String, Object>> toMessages(List<Turn> turns) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (turns == null) {
            return messages;
        }
        for (Turn turn : turns) {
            messages.add(message("user", turn.user()));
            messages.add(message("assistant", turn.assistant()));
        }
        return messages;
    }

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }
}
