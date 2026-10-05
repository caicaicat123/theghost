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
 * 心情账本：它现在对每个玩家是什么态度。
 *
 * <p>0–50 一条轴，**25 是平常心**：往上（&gt;25）是开心，越高越待见你；
 * 往下（&lt;25）是生气，越低越想整你。玩家的每句话都会让这个值动一点，
 * 时间久了会慢慢**回到 25**（不是回到 0——生气和开心都会被冲淡）。
 *
 * <p>账本按 UUID 存（`mood.yml`），重启服务器不丢。
 */
public final class Mood {

    private static final long HOUR_MS = 3600_000L;
    private static final long FORGET_MS = 7L * 24 * HOUR_MS;

    /** 一条心情记录。 */
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
    private int defaultScore = 25;

    public Mood(File file) {
        this.file = file;
    }

    public void setDefault(int value) {
        this.defaultScore = value;
    }

    public int defaultScore() {
        return defaultScore;
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
                int score = yml.getInt(key + ".score", defaultScore);
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

    /** 取当前心情（没记录的按默认值 25；顺手结算这段时间的回归）。 */
    public synchronized int score(UUID id, int decayPerHour, int neutral) {
        Entry e = entries.get(id);
        if (e == null) {
            return defaultScore;
        }
        // 只结算内存里的回归，落盘交给每 5 分钟的定时任务，避免聊天时写文件
        drift(e, decayPerHour, neutral, System.currentTimeMillis());
        return e.score;
    }

    /** 心情变化（正=更开心，负=更生气），返回结算后的值。 */
    public synchronized int add(UUID id, String name, int delta, int decayPerHour, int neutral, int maxScore) {
        long now = System.currentTimeMillis();
        Entry e = entries.computeIfAbsent(id, k -> new Entry(name, defaultScore, now));
        drift(e, decayPerHour, neutral, now);
        if (name != null && !name.isBlank()) {
            e.name = name;
        }
        e.score = clamp(e.score + delta, maxScore);
        // 从现在重新起算：否则刚变的这几点会被上一次留下的旧时间戳瞬间抹掉
        e.lastDecay = now;
        save();
        return e.score;
    }

    public synchronized void set(UUID id, String name, int score, int maxScore) {
        long now = System.currentTimeMillis();
        Entry e = entries.computeIfAbsent(id, k -> new Entry(name, defaultScore, now));
        if (name != null && !name.isBlank()) {
            e.name = name;
        }
        e.score = clamp(score, maxScore);
        e.lastDecay = now;
        save();
    }

    /** 抹掉记录 = 回到平常心。 */
    public synchronized void reset(UUID id) {
        entries.remove(id);
        save();
    }

    /** 全局回归，定时任务调用。 */
    public synchronized void driftAll(int decayPerHour, int neutral, int maxScore) {
        if (decayPerHour <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean changed = false;
        List<UUID> forget = new ArrayList<>();
        for (Map.Entry<UUID, Entry> mapEntry : entries.entrySet()) {
            Entry e = mapEntry.getValue();
            changed |= drift(e, decayPerHour, neutral, now);
            e.score = clamp(e.score, maxScore);
            if (e.score == neutral && now - e.lastDecay > FORGET_MS) {
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

    /** 最生气的几个（只列低于中性值的）。 */
    public synchronized List<Entry> angriest(int limit, int neutral) {
        return sorted(limit, e -> e.score < neutral, Comparator.comparingInt(e -> e.score));
    }

    /** 最开心的几个（只列高于中性值的）。 */
    public synchronized List<Entry> happiest(int limit, int neutral) {
        List<Entry> list = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (e.score > neutral) {
                list.add(e);
            }
        }
        list.sort(Comparator.comparingInt((Entry e) -> e.score).reversed());
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    private List<Entry> sorted(int limit, java.util.function.Predicate<Entry> keep, Comparator<Entry> order) {
        List<Entry> list = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (keep.test(e)) {
                list.add(e);
            }
        }
        list.sort(order);
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    private int clamp(int score, int maxScore) {
        return Math.max(0, Math.min(Math.max(1, maxScore), score));
    }

    private boolean drift(Entry e, int decayPerHour, int neutral, long now) {
        if (decayPerHour <= 0 || e.score == neutral) {
            return false;
        }
        long hours = (now - e.lastDecay) / HOUR_MS;
        if (hours <= 0) {
            return false;
        }
        int next = drifted(e.score, e.lastDecay, now, decayPerHour, neutral);
        e.lastDecay += hours * HOUR_MS;
        boolean changed = next != e.score;
        e.score = next;
        return changed;
    }

    /** 纯计算：从 lastDecayMs 到 nowMs 过了多少个整小时，心情每小時往中性值挪多少。抽出来方便单独验证。 */
    public static int drifted(int score, long lastDecayMs, long nowMs, int decayPerHour, int neutral) {
        if (decayPerHour <= 0 || score == neutral) {
            return score;
        }
        long hours = (nowMs - lastDecayMs) / HOUR_MS;
        if (hours <= 0) {
            return score;
        }
        long step = hours * decayPerHour;
        if (score > neutral) {
            return (int) Math.max(neutral, score - step);
        }
        return (int) Math.min(neutral, score + step);
    }
}
