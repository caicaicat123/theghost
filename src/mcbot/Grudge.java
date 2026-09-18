package mcbot;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 记仇账本：谁骂过它、骂得有多狠、什么时候该忘掉。
 *
 * 分数只升不降是没意思的——放久了要自然淡忘，玩家夸它也要抹掉一点。
 * 账本按 UUID 存，重启服务器不丢。
 */
public final class Grudge {

    private static final long HOUR_MS = 3600_000L;
    private static final long FORGET_MS = 7L * 24 * HOUR_MS;

    public static final class Entry {
        private String name;
        private int score;
        private long lastDecay;

        Entry(String name, int score, long lastDecay) {
            this.name = name;
            this.score = score;
            this.lastDecay = lastDecay;
        }

        public String name() {
            return name;
        }

        public int score() {
            return score;
        }
    }

    private final Map<UUID, Entry> entries = new HashMap<>();
    private final File file;

    public Grudge(File file) {
        this.file = file;
    }

    public synchronized void load() {
        entries.clear();
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String key : yml.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                String name = yml.getString(key + ".name", "?");
                int score = Math.max(0, yml.getInt(key + ".score", 0));
                long last = yml.getLong(key + ".last-decay", System.currentTimeMillis());
                entries.put(id, new Entry(name, score, last));
            } catch (IllegalArgumentException ignored) {
                // 不是 UUID 的键直接跳过，别让一条脏数据拖垮整本账
            }
        }
    }

    public synchronized boolean save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
            String key = e.getKey().toString();
            yml.set(key + ".name", e.getValue().name);
            yml.set(key + ".score", e.getValue().score);
            yml.set(key + ".last-decay", e.getValue().lastDecay);
        }
        try {
            yml.save(file);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /** 取当前分数（会顺手结算这段时间的自然衰减）。 */
    public synchronized int score(UUID id, int decayPerHour) {
        Entry e = entries.get(id);
        if (e == null) {
            return 0;
        }
        // 只结算内存里的衰减，落盘交给每 5 分钟的定时任务，避免聊天时写文件
        decay(e, decayPerHour, System.currentTimeMillis());
        return e.score;
    }

    /** 加分/减分，返回结算后的分数。 */
    public synchronized int add(UUID id, String name, int delta, int decayPerHour, int maxScore) {
        long now = System.currentTimeMillis();
        Entry e = entries.computeIfAbsent(id, k -> new Entry(name, 0, now));
        decay(e, decayPerHour, now);
        if (name != null && !name.isBlank()) {
            e.name = name;
        }
        e.score = Math.max(0, Math.min(maxScore, e.score + delta));
        // 从现在重新起算：否则刚加的这几分会被上一次留下的旧时间戳瞬间抹掉
        e.lastDecay = now;
        save();
        return e.score;
    }

    public synchronized void set(UUID id, String name, int score, int maxScore) {
        long now = System.currentTimeMillis();
        Entry e = entries.computeIfAbsent(id, k -> new Entry(name, 0, now));
        if (name != null && !name.isBlank()) {
            e.name = name;
        }
        e.score = Math.max(0, Math.min(maxScore, score));
        e.lastDecay = now;
        save();
    }

    public synchronized void reset(UUID id) {
        entries.remove(id);
        save();
    }

    /** 全局自然衰减，定时任务调用。 */
    public synchronized void decayAll(int decayPerHour, int maxScore) {
        if (decayPerHour <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean changed = false;
        List<UUID> forget = new ArrayList<>();
        for (Map.Entry<UUID, Entry> mapEntry : entries.entrySet()) {
            Entry e = mapEntry.getValue();
            changed |= decay(e, decayPerHour, now);
            if (e.score <= 0 && now - e.lastDecay > FORGET_MS) {
                forget.add(mapEntry.getKey());
            }
        }
        for (UUID id : forget) {
            entries.remove(id);
            changed = true;
        }
        if (changed) {
            save();
        }
    }

    /** 记仇榜（分数从高到低）。 */
    public synchronized List<Entry> top(int limit) {
        List<Entry> list = new ArrayList<>(entries.values());
        list.removeIf(e -> e.score <= 0);
        list.sort(Comparator.comparingInt(Entry::score).reversed());
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    private boolean decay(Entry e, int decayPerHour, long now) {
        if (decayPerHour <= 0 || e.score <= 0) {
            return false;
        }
        long hours = (now - e.lastDecay) / HOUR_MS;
        if (hours <= 0) {
            return false;
        }
        int next = decayedScore(e.score, e.lastDecay, now, decayPerHour);
        e.lastDecay += hours * HOUR_MS;
        e.score = next;
        return true;
    }

    /** 纯计算：从 lastDecayMs 到 nowMs 过了多少个整小时，每小时掉多少分。抽出来方便单独验证。 */
    public static int decayedScore(int score, long lastDecayMs, long nowMs, int decayPerHour) {
        if (decayPerHour <= 0 || score <= 0) {
            return score;
        }
        long hours = (nowMs - lastDecayMs) / HOUR_MS;
        if (hours <= 0) {
            return score;
        }
        return (int) Math.max(0, score - hours * decayPerHour);
    }
}
