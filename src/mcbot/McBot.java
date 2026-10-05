package mcbot;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 住在服务器里的捣蛋鬼 AI。
 *
 * 它没有实体、不会出现在世界里，只靠聊天、音效、标题和少量白名单指令刷存在感。
 * 大部分时间自己找乐子（定时骚扰），玩家喊它时会正经回答几句。
 */
public final class McBot extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    /** 打在苦力怕身上的标记，用来认出「这是幽灵放的」，善后时也只清自己放的。 */
    public static final String PRANK_META = "theghost_prank";

    /** 有这个权限的玩家完全免疫：不会被送礼，也不会被整（仍然能逗它说话）。 */
    public static final String EXEMPT_PERMISSION = "theghost.immune";

    private final Random random = new Random();
    private final Map<UUID, Long> lastReply = new HashMap<>();
    private final Map<UUID, Long> lastBigPrank = new HashMap<>();
    private final Map<UUID, Long> lastJumpscare = new HashMap<>();
    private final Set<UUID> prankCreepers = new HashSet<>();

    /** 礼物/垃圾的冷却与"每人每天"配额（滑动 24 小时窗口）。 */
    private final Map<UUID, Long> lastGift = new HashMap<>();
    private final Map<UUID, Long> lastBigGift = new HashMap<>();
    private final Map<UUID, Long> lastJunk = new HashMap<>();
    private final Map<UUID, Quota> giftQuota = new HashMap<>();
    private final Map<UUID, Quota> bigGiftQuota = new HashMap<>();
    private final Map<UUID, Quota> junkQuota = new HashMap<>();

    /** 击杀冷却，以及"先警告后杀"的窗口。 */
    private final Map<UUID, Long> lastKill = new HashMap<>();
    private final Map<UUID, Long> warnedUntil = new HashMap<>();

    /** 短期对话记忆：只有玩家直接跟它说话（被 @ 或 /ghost ask）才会用到。 */
    private final History history = new History();

    /** 物品白名单（礼物/垃圾都由这里出，模型碰不到）。 */
    private final Rewards rewards = new Rewards();

    /** 累计 token 用量，用来核对真实开销（缓存命中率才是成本的关键）。 */
    private final AtomicLong usageCalls = new AtomicLong();
    private final AtomicLong usageHit = new AtomicLong();
    private final AtomicLong usageMiss = new AtomicLong();
    private final AtomicLong usageOut = new AtomicLong();
    private boolean logUsage;

    private DeepSeek ai;
    private Mood mood;
    private BukkitTask loopTask;
    private BukkitTask decayTask;
    private int countdown;

    private boolean mischiefEnabled;
    private int intervalSeconds;
    private int jitterSeconds;
    private int minPlayers;
    private double mischiefChance;
    private boolean targetOps;
    private double privateChance;

    private List<String> mentionTriggers = List.of();
    private int mentionCooldownSeconds;

    private boolean guideEnabled;
    private int guideWindowSeconds;
    private String guideMessage;
    private boolean guideUseAi;
    private long guideWindowUntil;
    private volatile boolean guideUsed;

    private boolean soundsEnabled;
    private boolean titlesEnabled;
    private boolean commandsEnabled;
    private List<String> allowedSounds = List.of();
    private List<String> allowedCommands = List.of();
    private List<String> lines = List.of();

    private double aiChance;
    private int replyCooldownSeconds;
    private String colorPrefix;

    private boolean prankEnabled;
    private int insultPoints;
    private int praisePoints;
    private boolean fallbackWords;

    // ---- 心情与档位 ----
    private int moodNeutral;
    private int moodMax;
    private int moodDefault;
    private int moodDecayPerHour;
    private int[] angerTiers = {20, 14, 8};
    private int[] joyTiers = {30, 36, 43};

    // ---- 礼物 / 垃圾 ----
    private boolean giftEnabled;
    private boolean junkEnabled;
    private int rewardMinJoyTier;
    private int giftCooldownSeconds;
    private int giftDailyCap;
    private int bigGiftCooldownSeconds;
    private int bigGiftDailyCap;
    private int junkMinAngerTier;
    private int junkCooldownSeconds;
    private int junkDailyCap;
    private int junkSlots;

    // ---- 豁免与保命 ----
    private boolean exemptOps;
    private List<String> exemptWorldPrefixes = List.of();
    private boolean killWarnFirst;
    private double killWarnLeaveHealth;
    private int killWarnWindowSeconds;
    private int killCooldownSeconds;

    private double[] actionChance = {0.0, 0.25, 0.55, 0.85};
    private int retaliateMinSeconds;
    private int retaliateMaxSeconds;
    private int prankCooldownSeconds;
    private int jumpscareCooldownSeconds;
    private List<String> insultWords = List.of();
    private List<String> praiseWords = List.of();

    private boolean jumpscareEnabled;
    private int jumpscareMinTier;
    private List<String> jumpscareSounds = List.of();
    private List<String> jumpscareTitles = List.of();

    private boolean lightningEnabled;
    private int lightningMinTier;
    private boolean lightningRealDamage;
    private boolean lightningLethal;
    private double lightningMinHealth;
    private boolean lightningSetFire;
    private double[] lightningDamage = {0.0, 0.0, 5.0, 8.0};

    private boolean creeperEnabled;
    private int creeperMinTier;
    private int[] creeperAmount = {0, 0, 1, 2};
    private double creeperDistance;
    private int creeperRadius;
    private boolean creeperBreakBlocks;
    private boolean creeperLethal;
    private double creeperMaxDamage;
    private int creeperDespawnSeconds;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        mood = new Mood(new File(getDataFolder(), "mood.yml"));
        mood.setDefault(moodDefault);
        mood.load();
        if (!new File(getDataFolder(), "mood.yml").isFile()
                && new File(getDataFolder(), "grudge.yml").isFile()) {
            getLogger().info("检测到旧的 grudge.yml：心情值和记仇值不是一套算法（中性点从 0 变成 "
                    + moodNeutral + "），所以没有迁移——全体从 " + moodDefault + " 开始，旧文件留在原处当备份。");
        }
        rewards.logProblems(getLogger());
        startDecayTask();

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("ghost") != null) {
            getCommand("ghost").setExecutor(this);
            getCommand("ghost").setTabCompleter(this);
        }
        startLoop();
        getLogger().info("幽灵已就位。AI=" + (ai.configured() ? "开启" : "未配置，仅使用本地台词")
                + "，心情系统=" + (prankEnabled ? "开启" : "关闭")
                + "（" + moodNeutral + " 平常心 / 上限 " + moodMax
                + "，生气档 " + join(angerTiers) + "，开心档 " + join(joyTiers) + "）"
                + "，礼物=" + (giftEnabled ? "开" : "关") + "，垃圾=" + (junkEnabled ? "开" : "关")
                + "，对话记忆=" + (history.enabled() ? history.maxTurns() + " 轮" : "关闭")
                + "，物品白名单=" + rewards.tableCount() + " 份 / 垃圾 " + rewards.junkCount() + " 种");
        checkConnectivity();
    }

    /** 启动时探一次网络，把结果写进控制台，省得等玩家 @ 了才发现连不上。 */
    private void checkConnectivity() {
        if (ai == null || !ai.configured()) {
            return;
        }
        long started = System.currentTimeMillis();
        ai.ping().whenComplete((code, error) -> {
            long ms = System.currentTimeMillis() - started;
            if (error != null) {
                getLogger().warning("DeepSeek 连通性检查失败（" + ms + "ms）：" + error
                        + " —— 被 @ 时会退回本地台词");
            } else {
                getLogger().info("DeepSeek 连通性正常（HTTP " + code + "，用时 " + ms + "ms）");
            }
        });
    }

    @Override
    public void onDisable() {
        if (loopTask != null) {
            loopTask.cancel();
            loopTask = null;
        }
        if (decayTask != null) {
            decayTask.cancel();
            decayTask = null;
        }
        for (UUID id : List.copyOf(prankCreepers)) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        prankCreepers.clear();
        if (mood != null) {
            mood.save();
        }
    }

    private void startDecayTask() {
        if (decayTask != null) {
            decayTask.cancel();
        }
        // 每 5 分钟结算一次"心情回归中性"，顺手把账本落盘
        decayTask = getServer().getScheduler().runTaskTimer(this, () -> {
            if (mood != null) {
                mood.driftAll(moodDecayPerHour, moodNeutral, moodMax);
            }
        }, 6000L, 6000L);
    }

    // ---------------------------------------------------------------- 配置

    private void loadSettings() {
        reloadConfig();
        var cfg = getConfig();

        ai = new DeepSeek(
                cfg.getString("deepseek.api-key", ""),
                cfg.getString("deepseek.base-url", "https://api.deepseek.com"),
                cfg.getString("deepseek.model", "deepseek-flash"),
                cfg.getDouble("deepseek.temperature", 1.35),
                cfg.getInt("deepseek.max-tokens", 80),
                cfg.getInt("deepseek.timeout-seconds", 25),
                Math.max(1, cfg.getInt("deepseek.retries", 1) + 1),
                cfg.getBoolean("deepseek.thinking", false));
        logUsage = cfg.getBoolean("deepseek.log-usage", false);

        // ---- 对话记忆 ----
        history.configure(
                cfg.getBoolean("memory.enabled", true),
                cfg.getInt("memory.max-turns", 8),
                cfg.getInt("memory.idle-seconds", 300),
                cfg.getInt("memory.max-tokens", 500));

        colorPrefix = cfg.getString("bot.color-prefix", "§5[幽灵]§r ");
        aiChance = cfg.getDouble("bot.ai-chance", 0.55);
        replyCooldownSeconds = cfg.getInt("bot.reply-cooldown-seconds", 12);

        mischiefEnabled = cfg.getBoolean("mischief.enabled", true);
        intervalSeconds = Math.max(30, cfg.getInt("mischief.interval-seconds", 240));
        jitterSeconds = Math.max(0, cfg.getInt("mischief.jitter-seconds", 180));
        mischiefChance = cfg.getDouble("mischief.chance", 0.7);
        minPlayers = Math.max(1, cfg.getInt("mischief.min-players", 1));
        targetOps = cfg.getBoolean("mischief.target-ops", false);
        privateChance = cfg.getDouble("mischief.private-chance", 0.0);

        mentionTriggers = cfg.getStringList("mention.triggers").stream()
                .filter(s -> !s.isBlank()).collect(Collectors.toList());
        if (mentionTriggers.isEmpty()) {
            // 配置文件是旧版时兜底，否则它会永远不回应
            mentionTriggers = List.of("@yl", "@幽灵");
        }
        mentionCooldownSeconds = Math.max(1, cfg.getInt("mention.cooldown-seconds", 6));

        guideEnabled = cfg.getBoolean("guide.enabled", true);
        guideWindowSeconds = Math.max(10, cfg.getInt("guide.window-seconds", 90));
        guideMessage = cfg.getString("guide.message", "想跟我说话？直接 @yl 你要说的内容，我就听得见。");
        guideUseAi = cfg.getBoolean("guide.use-ai", false);

        soundsEnabled = cfg.getBoolean("actions.sounds.enabled", true);
        titlesEnabled = cfg.getBoolean("actions.titles.enabled", true);
        commandsEnabled = cfg.getBoolean("actions.commands.enabled", true);
        allowedSounds = cfg.getStringList("actions.sounds.allowed").stream()
                .map(s -> s.toLowerCase(Locale.ROOT).trim()).collect(Collectors.toList());
        allowedCommands = cfg.getStringList("actions.commands.allowed").stream()
                .map(s -> s.toLowerCase(Locale.ROOT).trim()).collect(Collectors.toList());

        lines = cfg.getStringList("lines");
        if (lines.isEmpty()) {
            lines = List.of("……", "有人吗。");
        }

        // ---- 心情（一套值：0-50，25 是平常心）----
        moodNeutral = Math.max(1, cfg.getInt("mood.neutral", 25));
        moodMax = Math.max(moodNeutral + 1, cfg.getInt("mood.max", 50));
        moodDefault = Math.max(0, Math.min(moodMax, cfg.getInt("mood.default", moodNeutral)));
        moodDecayPerHour = Math.max(0, cfg.getInt("mood.decay-per-hour", 2));
        angerTiers = toIntArray(cfg.getIntegerList("mood.anger-tiers"), new int[]{20, 14, 8});
        joyTiers = toIntArray(cfg.getIntegerList("mood.joy-tiers"), new int[]{30, 36, 43});

        // ---- 礼物 / 垃圾 ----
        giftEnabled = cfg.getBoolean("reward.enabled", true);
        junkEnabled = cfg.getBoolean("junk.enabled", true);
        rewardMinJoyTier = Math.max(1, cfg.getInt("reward.min-joy-tier", 1));
        giftCooldownSeconds = Math.max(0, cfg.getInt("reward.cooldown-seconds", 900));
        giftDailyCap = Math.max(0, cfg.getInt("reward.daily-cap", 3));
        bigGiftCooldownSeconds = Math.max(0, cfg.getInt("reward.big-cooldown-seconds", 86400));
        bigGiftDailyCap = Math.max(0, cfg.getInt("reward.big-daily-cap", 1));
        junkMinAngerTier = Math.max(1, cfg.getInt("junk.min-anger-tier", 1));
        junkCooldownSeconds = Math.max(0, cfg.getInt("junk.cooldown-seconds", 120));
        junkDailyCap = Math.max(0, cfg.getInt("junk.daily-cap", 8));
        junkSlots = Math.max(1, Math.min(9, cfg.getInt("junk.slots", 3)));

        // ---- 豁免与保命 ----
        exemptOps = cfg.getBoolean("prank.exempt-ops", true);
        exemptWorldPrefixes = lowerList(cfg.getStringList("prank.exempt-world-prefixes"));
        killWarnFirst = cfg.getBoolean("prank.lightning.warn-first", true);
        killWarnLeaveHealth = Math.max(0.5, cfg.getDouble("prank.lightning.warn-leave-health", 2.0));
        killWarnWindowSeconds = Math.max(10, cfg.getInt("prank.lightning.warn-window-seconds", 600));
        killCooldownSeconds = Math.max(0, cfg.getInt("prank.kill-cooldown-seconds", 600));

        // ---- 捉弄 ----
        prankEnabled = cfg.getBoolean("prank.enabled", true);
        // 词表只在模型不在场时兜底；注意正负：现在是心情值，骂人要让心情**下降**
        insultPoints = Math.min(0, cfg.getInt("prank.insult-points", -3));
        praisePoints = Math.max(0, cfg.getInt("prank.praise-points", 1));
        fallbackWords = cfg.getBoolean("prank.fallback-words", true);
        actionChance = toDoubleArray(cfg.getDoubleList("prank.action-chance"),
                new double[]{0.0, 0.25, 0.55, 0.85});
        int[] delay = toIntArray(cfg.getIntegerList("prank.retaliate-delay-seconds"), new int[]{3, 12});
        retaliateMinSeconds = Math.max(1, delay[0]);
        retaliateMaxSeconds = Math.max(retaliateMinSeconds, delay[1]);
        prankCooldownSeconds = Math.max(0, cfg.getInt("prank.cooldown-seconds", 45));
        jumpscareCooldownSeconds = Math.max(0, cfg.getInt("prank.jumpscare-cooldown-seconds", 20));
        insultWords = lowerList(cfg.getStringList("prank.insult-words"));
        praiseWords = lowerList(cfg.getStringList("prank.praise-words"));
        if (insultWords.isEmpty()) {
            insultWords = lowerList(List.of("傻逼", "傻子", "弱智", "蠢", "废物", "垃圾", "闭嘴",
                    "滚", "烦人", "恶心", "讨厌", "有病", "神经病", "狗东西", "妈的", "去死"));
        }
        if (praiseWords.isEmpty()) {
            praiseWords = lowerList(List.of("谢谢", "厉害", "牛", "喜欢你", "对不起", "抱歉",
                    "可爱", "好玩", "有意思", "辛苦了"));
        }

        jumpscareEnabled = cfg.getBoolean("prank.jumpscare.enabled", true);
        jumpscareMinTier = Math.max(0, cfg.getInt("prank.jumpscare.min-tier", 1));
        jumpscareSounds = cfg.getStringList("prank.jumpscare.sounds");
        jumpscareTitles = cfg.getStringList("prank.jumpscare.titles");
        if (jumpscareSounds.isEmpty()) {
            jumpscareSounds = List.of("entity.enderman.scream", "entity.ghast.scream", "ambient.cave");
        }
        if (jumpscareTitles.isEmpty()) {
            jumpscareTitles = List.of("别回头", "你后面有东西", "找到你了");
        }

        lightningEnabled = cfg.getBoolean("prank.lightning.enabled", true);
        lightningMinTier = Math.max(0, cfg.getInt("prank.lightning.min-tier", 2));
        lightningRealDamage = cfg.getBoolean("prank.lightning.real-damage", true);
        lightningLethal = cfg.getBoolean("prank.lightning.lethal", true);
        lightningMinHealth = Math.max(0.0, cfg.getDouble("prank.lightning.min-health", 2.0));
        lightningSetFire = cfg.getBoolean("prank.lightning.set-fire", false);
        lightningDamage = toDoubleArray(cfg.getDoubleList("prank.lightning.damage"),
                new double[]{0.0, 0.0, 5.0, 8.0});

        creeperEnabled = cfg.getBoolean("prank.creeper.enabled", true);
        creeperMinTier = Math.max(0, cfg.getInt("prank.creeper.min-tier", 2));
        creeperAmount = toIntArray(cfg.getIntegerList("prank.creeper.amount"), new int[]{0, 0, 1, 2});
        creeperDistance = Math.max(1.0, cfg.getDouble("prank.creeper.distance", 3.0));
        creeperRadius = Math.max(0, cfg.getInt("prank.creeper.explosion-radius", 3));
        creeperBreakBlocks = cfg.getBoolean("prank.creeper.break-blocks", true);
        creeperLethal = cfg.getBoolean("prank.creeper.lethal", true);
        creeperMaxDamage = Math.max(0.0, cfg.getDouble("prank.creeper.max-damage", 6.0));
        creeperDespawnSeconds = Math.max(0, cfg.getInt("prank.creeper.despawn-seconds", 30));

        // 物品白名单也在这里重载：否则 /ghost reload 改了 reward.items 也不会生效
        rewards.load(giftTables(), junkTable());
    }

    private static int[] toIntArray(List<Integer> list, int[] fallback) {
        int[] out = fallback.clone();
        if (list != null) {
            for (int i = 0; i < Math.min(out.length, list.size()); i++) {
                out[i] = list.get(i);
            }
        }
        return out;
    }

    private static double[] toDoubleArray(List<Double> list, double[] fallback) {
        double[] out = fallback.clone();
        if (list != null) {
            for (int i = 0; i < Math.min(out.length, list.size()); i++) {
                out[i] = list.get(i);
            }
        }
        return out;
    }

    private static List<String> lowerList(List<String> list) {
        return list.stream().filter(s -> !s.isBlank())
                .map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toList());
    }

    // ------------------------------------------------------------ 定时搞怪

    private void startLoop() {
        if (loopTask != null) {
            loopTask.cancel();
        }
        countdown = nextIntervalSeconds();
        loopTask = getServer().getScheduler().runTaskTimer(this, this::tick, 400L, 400L);
    }

    private int nextIntervalSeconds() {
        int jitter = jitterSeconds > 0 ? random.nextInt(jitterSeconds * 2 + 1) - jitterSeconds : 0;
        return Math.max(20, intervalSeconds + jitter);
    }

    private void tick() {
        if (!mischiefEnabled) {
            return;
        }
        countdown -= 20;
        if (countdown > 0) {
            return;
        }
        countdown = nextIntervalSeconds();
        if (random.nextDouble() > mischiefChance) {
            return;
        }
        List<Player> pool = eligiblePlayers();
        if (pool.size() < minPlayers) {
            return;
        }
        Player target = pickTarget(pool);
        boolean quiet = random.nextDouble() < privateChance;
        speak(target, "服务器里安静了一会儿，你想找点乐子", quiet, false);
        if (!quiet && guideEnabled) {
            // 公屏说完话之后，盯住接下来的第一条玩家发言
            guideWindowUntil = System.currentTimeMillis() + guideWindowSeconds * 1000L;
            guideUsed = false;
        }
    }

    private List<Player> eligiblePlayers() {
        List<Player> list = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOnline() && (targetOps || !p.isOp()) && exemptReason(p) == null) {
                list.add(p);
            }
        }
        return list;
    }

    /** 心情越差的人越容易被它盯上：权重 = 1 + (中性值 − 心情值)。 */
    private Player pickTarget(List<Player> pool) {
        if (!prankEnabled || mood == null) {
            return pool.get(random.nextInt(pool.size()));
        }
        double total = 0;
        for (Player p : pool) {
            total += weightOf(p);
        }
        double roll = random.nextDouble() * total;
        for (Player p : pool) {
            roll -= weightOf(p);
            if (roll <= 0) {
                return p;
            }
        }
        return pool.get(pool.size() - 1);
    }

    private double weightOf(Player player) {
        return Math.max(1, 1 + (moodNeutral - mood.score(player.getUniqueId(), moodDecayPerHour, moodNeutral)));
    }

    // -------------------------------------------------------------- AI 交互

    /** @param forceAi true 时跳过概率过滤，一定走 API（被 @ 和玩家主动提问用这个）。 */
    private void speak(Player target, String trigger, boolean quiet, boolean forceAi) {
        speak(target, trigger, quiet, forceAi, false, 0);
    }

    private void speak(Player target, String trigger, boolean quiet, boolean forceAi, boolean trackAnger) {
        speak(target, trigger, quiet, forceAi, trackAnger, 0);
    }

    /**
     * @param trackAnger    true 时按模型回复里的 SCORE 记分（只有玩家直接对它说话才开）；
     *                      同时也代表「这是一次真正的对话」——只有这种调用才读写短期记忆，
     *                      免得定时搞怪、管理员 poke 的台词混进玩家和它的聊天记录里。
     * @param fallbackDelta 词表兜底分：**只在模型这条路走不通时**才记（没配 Key / 调用失败）。
     *                      模型正常给出 SCORE 时一律以模型的判断为准，包括它判 0。
     */
    private void speak(Player target, String trigger, boolean quiet, boolean forceAi,
                       boolean trackAnger, int fallbackDelta) {
        if (!isEnabled()) {
            return;
        }
        Brain.MoodInfo moodInfo = moodOf(target);
        UUID id = target == null ? null : target.getUniqueId();
        boolean conversational = trackAnger && id != null;
        List<History.Turn> remembered = conversational ? history.recall(id) : List.of();
        if (ai != null && ai.configured() && (forceAi || random.nextDouble() < aiChance)) {
            String system = Brain.systemPrompt(this, allowedSounds, allowedCommands, moodInfo);
            String user = Brain.userPrompt(trigger, context(target));
            ai.chat(system, user, History.toMessages(remembered)).whenComplete((result, error) -> {
                Brain.Reply reply;
                if (error != null) {
                    getLogger().warning("DeepSeek 调用失败: " + error.getMessage());
                    reply = Brain.local(lines, allowedSounds, random);
                    if (trackAnger) {
                        bump(target, fallbackDelta);
                    }
                } else {
                    noteUsage(result, trigger);
                    reply = Brain.parse(result.content());
                    if (reply.say().isBlank()) {
                        Brain.Reply fallback = Brain.local(lines, allowedSounds, random);
                        reply = new Brain.Reply(fallback.say(), reply.actionType(), reply.actionValue(), reply.score());
                    }
                    if (trackAnger) {
                        // 心情涨多少、掉多少，全听模型的这一行 SCORE；账本自己会夹在 0~上限之间
                        bump(target, reply.score());
                    }
                    // 只记成功过的真实回复；失败/兜底不进记忆，省得它"记得"自己没说过的话
                    if (conversational && !reply.say().isBlank()) {
                        history.remember(id, trigger, reply.say());
                    }
                }
                deliver(reply, target, quiet);
            });
        } else {
            // 没配 API / 概率没命中：模型不在场，这时才用词表兜底
            if (trackAnger) {
                bump(target, fallbackDelta);
            }
            Brain.Reply reply = Brain.local(lines, allowedSounds, random);
            int angerTier = moodOf(target).angerTier();
            if (prankEnabled && target != null && angerTier > 0) {
                int idx = Math.min(actionChance.length - 1, angerTier);
                if (random.nextDouble() < actionChance[idx]) {
                    Brain.Reply extra = Brain.localPrank(angerTier, random,
                            jumpscareEnabled && angerTier >= jumpscareMinTier,
                            lightningEnabled && angerTier >= lightningMinTier,
                            creeperEnabled && angerTier >= creeperMinTier);
                    if (extra.hasAction()) {
                        reply = new Brain.Reply(reply.say(), extra.actionType(), extra.actionValue(), 0);
                    }
                }
            }
            deliver(reply, target, quiet);
        }
    }

    private void deliver(Brain.Reply reply, Player target, boolean quiet) {
        getServer().getScheduler().runTask(this, () -> {
            if (!isEnabled()) {
                return;
            }
            Player online = target != null && target.isOnline() ? target : null;
            if (!reply.say().isBlank()) {
                String message = colorize(colorPrefix + reply.say());
                if (quiet || online == null) {
                    if (online != null) {
                        online.sendMessage(message);
                    }
                } else {
                    Bukkit.broadcastMessage(message);
                }
            }
            Brain.MoodInfo info = moodOf(online);
            String result = Actions.run(this, reply, online, info.angerTier(), info.joyTier());
            if (!"none".equals(result) && !"denied".equals(result)) {
                getLogger().info("动作: " + result + (online != null ? " -> " + online.getName() : ""));
            }
        });
    }

    /** 累计一次调用的 token 用量；开了 log-usage 就顺手写一行控制台。 */
    private void noteUsage(DeepSeek.Result result, String trigger) {
        usageCalls.incrementAndGet();
        usageHit.addAndGet(result.cacheHitTokens());
        usageMiss.addAndGet(result.cacheMissTokens());
        usageOut.addAndGet(result.outputTokens());
        if (logUsage) {
            String label = trigger == null ? "" : trigger.replace('\n', ' ').replace('\r', ' ');
            if (label.length() > 40) {
                label = label.substring(0, 40) + "…";
            }
            getLogger().info("DeepSeek 用量（" + result.usageLine() + "）：" + label);
        }
    }

    private String context(Player target) {
        List<String> names = Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
        String world = target == null ? "未知" : target.getWorld().getName();
        long time = target == null ? 0L : target.getWorld().getTime();
        String phase = time < 12300 ? "白天" : (time < 23850 ? "傍晚" : "夜晚");
        String weather = target != null && target.getWorld().hasStorm() ? "下雨" : "晴";
        return "在线 " + names.size() + " 人（" + String.join("、", names) + "），"
                + "世界 " + world + "，" + phase + "，" + weather;
    }

    // ----------------------------------------------------------- 心情账本

    /** 某个玩家现在的心情快照（值 + 门槛 → 生气档 / 开心档）。 */
    private Brain.MoodInfo moodOf(Player player) {
        int value = player == null || mood == null
                ? moodNeutral
                : mood.score(player.getUniqueId(), moodDecayPerHour, moodNeutral);
        return new Brain.MoodInfo(value, moodNeutral, moodMax, angerTiers, joyTiers);
    }

    private Brain.MoodInfo moodOf(int value) {
        return new Brain.MoodInfo(value, moodNeutral, moodMax, angerTiers, joyTiers);
    }

    /** 词表兜底分：只在模型这条路走不通时才用（`prank.fallback-words` 可整个关掉）。 */
    private int fallbackDelta(String message) {
        if (!fallbackWords || !prankEnabled) {
            return 0;
        }
        return Brain.wordDelta(message, insultWords, praiseWords, insultPoints, praisePoints);
    }

    /** 心情变化：正=开心、负=生气。 */
    private void bump(Player player, int delta) {
        if (player == null || mood == null || delta == 0) {
            return;
        }
        Brain.MoodInfo before = moodOf(player);
        int score = mood.add(player.getUniqueId(), player.getName(), delta,
                moodDecayPerHour, moodNeutral, moodMax);
        Brain.MoodInfo after = moodOf(score);
        if (delta != 0 && (after.angerTier() != before.angerTier() || after.joyTier() != before.joyTier())) {
            getLogger().info(player.getName() + " 的心情到 " + score + "（生气档 " + after.angerTier()
                    + " / 开心档 " + after.joyTier() + "）");
        }
        // 只有"变得更生气"才会招来报复，哄好它是不会挨劈的
        if (delta < 0 && after.angerTier() >= 2) {
            maybeRetaliate(player, after.angerTier());
        }
    }

    /** 被骂之后隔几秒再动手，比当场动手更像"它记下了"。 */
    private void maybeRetaliate(Player player, int tier) {
        if (!prankEnabled) {
            return;
        }
        int span = retaliateMaxSeconds - retaliateMinSeconds + 1;
        long delay = (retaliateMinSeconds + random.nextInt(Math.max(1, span))) * 20L;
        getServer().getScheduler().runTaskLater(this, () -> {
            if (!isEnabled()) {
                return;
            }
            Player online = Bukkit.getPlayer(player.getUniqueId());
            if (online == null || !online.isOnline()) {
                return;
            }
            int nowTier = moodOf(online).angerTier();
            Brain.Reply prank = Brain.localPrank(nowTier, random,
                    jumpscareEnabled && nowTier >= jumpscareMinTier,
                    lightningEnabled && nowTier >= lightningMinTier,
                    creeperEnabled && nowTier >= creeperMinTier);
            if (prank.hasAction()) {
                deliver(prank, online, false);
            }
        }, delay);
    }


    // ---------------------------------------------------------------- 事件

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();
        String lower = message.toLowerCase(Locale.ROOT);

        // 1) 被 @ 才回复：这是唯一会调用 API 的入口
        boolean mentioned = mentionTriggers.stream()
                .anyMatch(t -> lower.contains(t.toLowerCase(Locale.ROOT)));
        if (mentioned) {
            long now = System.currentTimeMillis();
            Long last = lastReply.get(player.getUniqueId());
            if (last != null && now - last < mentionCooldownSeconds * 1000L) {
                return;
            }
            lastReply.put(player.getUniqueId(), now);
            // 心情怎么变交给模型的 SCORE 行；词表分只是模型不可用时的兜底
            speak(player, player.getName() + " 用 @ 对你说了：" + message,
                    false, true, true, fallbackDelta(message));
            return;
        }

        // 2) 它刚在公屏说完话：接住接下来的第一条玩家发言，告诉对方怎么找它
        if (guideEnabled && !guideUsed && System.currentTimeMillis() < guideWindowUntil) {
            guideUsed = true;
            guideWindowUntil = 0L;
            if (guideUseAi) {
                speak(player, player.getName() + " 接话了：" + message, false, true);
            } else {
                Bukkit.broadcastMessage(colorize(colorPrefix + guideMessage));
            }
            return;
        }

        // 3) 其余聊天一律不处理：不检测、不调用 API，也就没有开销
    }

    /** 人走了就把这段对话忘掉：记忆只服务于「连着聊几句」，顺便防止几个 Map 随 UUID 无限增长。 */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        history.forget(id);
        lastReply.remove(id);
    }

    // -------------------------------------------------------- 苦力怕善后

    /** 不是幽灵放的苦力怕不碰；配置说不炸方块时只清方块列表，伤害照旧。 */
    @EventHandler(ignoreCancelled = true)
    public void onPrankExplode(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof Creeper creeper) || !creeper.hasMetadata(PRANK_META)) {
            return;
        }
        prankCreepers.remove(creeper.getUniqueId());
        if (!creeperBreakBlocks) {
            event.blockList().clear();
            event.setYield(0f);
        }
    }

    /** 配置成"不炸死人"时，把爆炸伤害压到上限，避免真把玩家送走。 */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPrankDamage(EntityDamageByEntityEvent event) {
        if (creeperLethal || !(event.getDamager() instanceof Creeper creeper)
                || !creeper.hasMetadata(PRANK_META)
                || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            return;
        }
        if (event.getDamage() > creeperMaxDamage) {
            event.setDamage(creeperMaxDamage);
        }
    }

    // ---------------------------------------------------------------- 命令

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(colorize("§5[幽灵]§r /ghost ask <内容> · poke [玩家] · mood [玩家] · "
                    + "prank <玩家> <动作> · gift|junk <玩家> · forget [玩家] · toggle · reload · status · test"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "ask" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§c这个子命令只能在游戏里用。");
                    return true;
                }
                if (!hasTalk(player)) {
                    player.sendMessage(colorize("§c你还没有和它说话的权利。"));
                    return true;
                }
                String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
                if (text.isEmpty()) {
                    player.sendMessage(colorize("§7想说什么？例如 /ghost ask 你好"));
                    return true;
                }
                long now = System.currentTimeMillis();
                Long last = lastReply.get(player.getUniqueId());
                if (last != null && now - last < replyCooldownSeconds * 1000L) {
                    player.sendMessage(colorize("§7它还在消化你上一句话……"));
                    return true;
                }
                lastReply.put(player.getUniqueId(), now);
                player.sendMessage(colorize("§8……它在听着"));
                speak(player, player.getName() + " 对你说：" + text, true, true, true, fallbackDelta(text));
            }
            case "poke" -> {
                if (!hasAdmin(sender)) {
                    sender.sendMessage("§c需要 theghost.admin 权限。");
                    return true;
                }
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
                if (target == null) {
                    List<Player> pool = eligiblePlayers();
                    if (pool.isEmpty()) {
                        sender.sendMessage(colorize("§7现在没人在线，用空目标跑一次做自检。"));
                        speak(null, "被管理员推了一把，服务器里空无一人", true, true);
                        return true;
                    }
                    target = pool.get(random.nextInt(pool.size()));
                }
                sender.sendMessage(colorize("§7正在让幽灵去找 " + target.getName() + " ……"));
                speak(target, "被管理员推了一把，去找点乐子", false, false);
            }
            case "grudge", "mood" -> {
                if (!hasAdmin(sender)) {
                    sender.sendMessage("§c需要 theghost.admin 权限。");
                    return true;
                }
                if (mood == null) {
                    sender.sendMessage(colorize("§c心情系统还没初始化。"));
                    return true;
                }
                if (args.length == 1) {
                    sender.sendMessage(colorize("§5幽灵的心情榜§r（平常心 " + moodNeutral + "）"));
                    sender.sendMessage("§7  最烦的：");
                    List<Mood.Entry> angry = mood.angriest(5, moodNeutral);
                    if (angry.isEmpty()) {
                        sender.sendMessage("§7    没有人生它的气。");
                    }
                    for (Mood.Entry entry : angry) {
                        sender.sendMessage("§7    " + entry.name() + "：" + entry.score());
                    }
                    sender.sendMessage("§7  最待见的：");
                    List<Mood.Entry> happy = mood.happiest(5, moodNeutral);
                    if (happy.isEmpty()) {
                        sender.sendMessage("§7    还没人被它待见。");
                    }
                    for (Mood.Entry entry : happy) {
                        sender.sendMessage("§7    " + entry.name() + "：" + entry.score());
                    }
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(colorize("§c找不到在线的玩家 " + args[1] + "。"));
                    return true;
                }
                if (args.length >= 4 && args[2].equalsIgnoreCase("set")) {
                    int value;
                    try {
                        value = Integer.parseInt(args[3]);
                    } catch (NumberFormatException e) {
                        sender.sendMessage(colorize("§c分数要写数字。"));
                        return true;
                    }
                    mood.set(target.getUniqueId(), target.getName(), value, moodMax);
                    sender.sendMessage(colorize("§7" + target.getName() + " 的心情已设为 "
                            + Math.max(0, Math.min(moodMax, value)) + "。"));
                } else if (args.length >= 3 && args[2].equalsIgnoreCase("reset")) {
                    mood.reset(target.getUniqueId());
                    sender.sendMessage(colorize("§7已把 " + target.getName() + " 的心情重置为平常心。"));
                } else {
                    Brain.MoodInfo info = moodOf(target);
                    sender.sendMessage(colorize("§7" + target.getName() + "：心情 " + info.value()
                            + " / " + moodMax + "（" + info.moodWord() + "）"
                            + "  生气档 " + info.angerTier() + " / 开心档 " + info.joyTier()));
                    sender.sendMessage("§7  能用的捉弄：" + allowedPranks(info.angerTier())
                            + "  礼物档位：" + (info.joyTier() >= rewardMinJoyTier()
                                ? giftTierKey(info.joyTier()) : "心情不够（要 ≥" + joyTiers[0] + "）"));
                }
            }
            case "gift", "junk" -> {
                if (!hasAdmin(sender)) {
                    sender.sendMessage("§c需要 theghost.admin 权限。");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(colorize("§c用法：/ghost " + args[0].toLowerCase(Locale.ROOT) + " <玩家>"));
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(colorize("§c找不到在线的玩家 " + args[1] + "。"));
                    return true;
                }
                // 管理员手动测试：档位拉满（礼物给 big、垃圾当最生气），清掉配额，但豁免照旧生效
                boolean isGift = args[0].equalsIgnoreCase("gift");
                String action = isGift ? "GIFT" : "JUNK";
                clearRewardCooldowns(target);
                String result = isGift
                        ? Actions.run(this, new Brain.Reply("", action, "", 0), target, 0, 3)
                        : Actions.run(this, new Brain.Reply("", action, "", 0), target, 3, 0);
                sender.sendMessage(colorize("§7对 " + target.getName() + " 执行 " + action + " → " + result));
                getLogger().info("[手动" + action + "] " + sender.getName() + " -> "
                        + target.getName() + " (" + result + ")");
            }
            case "prank" -> {
                if (!hasAdmin(sender)) {
                    sender.sendMessage("§c需要 theghost.admin 权限。");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(colorize("§c用法：/ghost prank <玩家> [lightning|creeper|jumpscare]"
                            + "（送礼/塞垃圾用 /ghost gift|junk <玩家>）"));
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(colorize("§c找不到在线的玩家 " + args[1] + "。"));
                    return true;
                }
                String action = args.length >= 3 ? args[2].toUpperCase(Locale.ROOT) : "RANDOM";
                if ("RANDOM".equals(action)) {
                    List<String> pool = new ArrayList<>();
                    if (jumpscareEnabled) {
                        pool.add("JUMPSCARE");
                    }
                    if (lightningEnabled) {
                        pool.add("LIGHTNING");
                    }
                    if (creeperEnabled) {
                        pool.add("CREEPER");
                    }
                    if (pool.isEmpty()) {
                        sender.sendMessage(colorize("§c所有捉弄动作都被配置关掉了。"));
                        return true;
                    }
                    action = pool.get(random.nextInt(pool.size()));
                }
                if (!List.of("LIGHTNING", "CREEPER", "JUMPSCARE").contains(action)) {
                    sender.sendMessage(colorize("§c动作只能是 lightning / creeper / jumpscare。"));
                    return true;
                }
                // 管理员手动测试不受心情档位和冷却限制，方便调参（豁免照旧生效）
                lastBigPrank.remove(target.getUniqueId());
                lastJumpscare.remove(target.getUniqueId());
                String result = Actions.run(this, new Brain.Reply("", action, "", 0), target, 3, 3);
                sender.sendMessage(colorize("§7对 " + target.getName() + " 执行 " + action + " → " + result));
                getLogger().info("[手动捉弄] " + sender.getName() + " -> " + target.getName()
                        + " " + action + " (" + result + ")");
            }
            case "toggle" -> {
                if (!hasAdmin(sender)) {
                    sender.sendMessage("§c需要 theghost.admin 权限。");
                    return true;
                }
                mischiefEnabled = !mischiefEnabled;
                sender.sendMessage(colorize("§7幽灵的搞怪开关：" + (mischiefEnabled ? "开" : "关")));
            }
            case "forget" -> {
                if (!hasAdmin(sender)) {
                    sender.sendMessage("§c需要 theghost.admin 权限。");
                    return true;
                }
                if (args.length >= 2) {
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        sender.sendMessage(colorize("§c找不到在线的玩家 " + args[1] + "。"));
                        return true;
                    }
                    history.forget(target.getUniqueId());
                    sender.sendMessage(colorize("§7已经让幽灵忘掉和 " + target.getName() + " 的对话。"));
                } else {
                    history.clear();
                    sender.sendMessage(colorize("§7已经让幽灵忘掉所有人的对话。"));
                }
            }
            case "reload" -> {
                if (!hasAdmin(sender)) {
                    sender.sendMessage("§c需要 theghost.admin 权限。");
                    return true;
                }
                loadSettings();
                history.clear();
                startLoop();
                sender.sendMessage(colorize("§7配置已重载。AI=" + (ai.configured() ? "开启" : "未配置")
                        + "，对话记忆已清空"));
            }
            case "status" -> {
                sender.sendMessage(colorize("§5幽灵§r 状态："));
                sender.sendMessage("§7 搞怪：" + (mischiefEnabled ? "开" : "关")
                        + "  间隔：" + intervalSeconds + "±" + jitterSeconds + " 秒"
                        + "  AI：" + (ai.configured() ? "已配置" : "未配置"));
                sender.sendMessage("§7 只有被 " + String.join(" / ", mentionTriggers) + " 才回复；"
                        + "引导窗口 " + guideWindowSeconds + " 秒");
                sender.sendMessage("§7 心情：" + (prankEnabled ? "开" : "关")
                        + "  平常心 " + moodNeutral + " / 上限 " + moodMax
                        + "  生气档 ≤" + join(angerTiers) + "  开心档 ≥" + join(joyTiers)
                        + "  回归 " + moodDecayPerHour + " 分/小时");
                sender.sendMessage("§7 判分：由 AI 自己给（SCORE " + Brain.SCORE_MIN + " ~ +" + Brain.SCORE_MAX
                        + "，正=让它开心、负=惹它生气）"
                        + (fallbackWords
                            ? "；词表仅在模型不可用时兜底（骂 " + insultPoints + " / 夸 +" + praisePoints + "）"
                            : "；词表兜底已关闭"));
                sender.sendMessage("§7 礼物：" + (giftEnabled ? "开" : "关")
                        + "（心情 ≥" + joyTiers[Math.min(joyTiers.length - 1, rewardMinJoyTier - 1)]
                        + " 才给；小礼物冷却 " + giftCooldownSeconds + " 秒/上限 " + giftDailyCap
                        + " 次每天；钻石档冷却 " + bigGiftCooldownSeconds + " 秒/上限 " + bigGiftDailyCap + " 次每天）"
                        + "  物品表 " + rewards.tableCount() + " 份");
                sender.sendMessage("§7 垃圾：" + (junkEnabled ? "开" : "关")
                        + "（心情 ≤" + angerTiers[Math.min(angerTiers.length - 1, junkMinAngerTier - 1)]
                        + " 才塞；塞 " + junkSlots + " 格；冷却 " + junkCooldownSeconds
                        + " 秒/上限 " + junkDailyCap + " 次每天）  垃圾表 " + rewards.junkCount() + " 种");
                sender.sendMessage("§7 豁免：OP " + (exemptOps ? "免疫" : "不免疫")
                        + " · 权限 theghost.immune" + " · 世界前缀 "
                        + (exemptWorldPrefixes.isEmpty() ? "无" : String.join("/", exemptWorldPrefixes))
                        + "  击杀冷却 " + killCooldownSeconds + " 秒"
                        + (killWarnFirst ? "（先警告：留 " + killWarnLeaveHealth + " 血，"
                            + killWarnWindowSeconds + " 秒内再来才真杀）" : "（不警告，直接杀）"));
                sender.sendMessage("§7 捉弄：惊吓" + (jumpscareEnabled ? "开" : "关")
                        + " · 闪电" + (lightningEnabled ? "开" : "关")
                        + (lightningRealDamage ? "（真伤害" + (lightningLethal ? "·可致死" : "") + "）" : "（仅特效）")
                        + " · 苦力怕" + (creeperEnabled ? "开" : "关")
                        + (creeperBreakBlocks ? "（会炸方块）" : "（不炸方块）"));
                sender.sendMessage("§7 音效 " + allowedSounds.size() + " 个；指令白名单（"
                        + allowedCommands.size() + " 项）：" + String.join(", ", allowedCommands));
                sender.sendMessage("§7 记忆：" + (history.enabled() ? "开" : "关")
                        + "（" + history.maxTurns() + " 轮 / 空闲 " + history.idleSeconds() + " 秒 / 上限 "
                        + history.maxTokens() + " token）  正在记 " + history.tracked() + " 人");
                sender.sendMessage("§7 用量：" + usageCalls.get() + " 次调用；"
                        + "输入命中 " + usageHit.get() + " / 未命中 " + usageMiss.get()
                        + " / 输出 " + usageOut.get() + " token");
                sender.sendMessage("§7 估算花费：§f" + String.format(Locale.ROOT, "%.4f", estimatedCost())
                        + " 元§7（按 " + ai.model() + " 空闲时段价：命中 0.02 / 未命中 1 / 输出 4 元每百万 token）");
            }
            case "test" -> {
                if (!hasAdmin(sender)) {
                    sender.sendMessage("§c需要 theghost.admin 权限。");
                    return true;
                }
                if (ai == null || !ai.configured()) {
                    sender.sendMessage(colorize("§c还没配置 deepseek.api-key。"));
                    return true;
                }
                String text = args.length > 1
                        ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                        : "你好";
                sender.sendMessage(colorize("§7正在测试 DeepSeek 连通性……"));
                long started = System.currentTimeMillis();
                getLogger().info("[测试] 开始调用 DeepSeek：" + text);
                ai.chat(Brain.systemPrompt(this, allowedSounds, allowedCommands, moodOf(moodNeutral)),
                        Brain.userPrompt("管理员正在做连通性测试，对他说：" + text, "（测试场景）"))
                        .whenComplete((result, error) -> {
                            long ms = System.currentTimeMillis() - started;
                            if (error != null) {
                                getLogger().warning("[测试] 调用失败（" + ms + "ms）：" + error);
                            } else {
                                noteUsage(result, text);
                                getLogger().info("[测试] 调用成功（" + ms + "ms）："
                                        + result.content().replace("\r", " ").replace("\n", " | ")
                                        + "  [" + result.usageLine() + "]");
                            }
                        });
            }
            default -> sender.sendMessage(colorize("§c未知子命令。用法：/ghost "
                    + "ask|poke|mood|prank|gift|junk|forget|toggle|reload|status|test"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("ask", "poke", "mood", "prank", "gift", "junk", "forget",
                    "toggle", "reload", "status", "test"), args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("mood") || args[0].equalsIgnoreCase("grudge")
                || args[0].equalsIgnoreCase("prank") || args[0].equalsIgnoreCase("gift")
                || args[0].equalsIgnoreCase("junk") || args[0].equalsIgnoreCase("forget"))) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()), args[1]);
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("mood") || args[0].equalsIgnoreCase("grudge"))) {
            return filter(List.of("set", "reset"), args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("prank")) {
            return filter(List.of("lightning", "creeper", "jumpscare"), args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(lower)).collect(Collectors.toList());
    }

    /** 按 deepseek-flash 空闲时段单价估算累计花费（高峰时段翻倍）。 */
    private double estimatedCost() {
        return (usageHit.get() * 0.02 + usageMiss.get() * 1.0 + usageOut.get() * 4.0) / 1_000_000.0;
    }

    private String allowedPranks(int tier) {
        List<String> list = new ArrayList<>();
        if (jumpscareEnabled && tier >= jumpscareMinTier) {
            list.add("JUMPSCARE");
        }
        if (lightningEnabled && tier >= lightningMinTier) {
            list.add("LIGHTNING");
        }
        if (creeperEnabled && tier >= creeperMinTier) {
            list.add("CREEPER");
        }
        if (junkEnabled && tier >= junkMinAngerTier) {
            list.add("JUNK");
        }
        return list.isEmpty() ? "只有嘴贫" : String.join("/", list);
    }

    // ------------------------------------------------- 礼物 / 垃圾的政策

    /** 开心档 → 物品表档位名（配置里 reward.items 的键）。 */
    public String giftTierKey(int joyTier) {
        if (joyTier >= 3) {
            return "big";
        }
        return joyTier >= 2 ? "good" : "small";
    }

    /** 从配置里取出三档物品表。 */
    private Map<String, List<String>> giftTables() {
        var cfg = getConfig();
        Map<String, List<String>> tables = new java.util.LinkedHashMap<>();
        tables.put("small", cfg.getStringList("reward.items.small"));
        tables.put("good", cfg.getStringList("reward.items.good"));
        tables.put("big", cfg.getStringList("reward.items.big"));
        return tables;
    }

    private List<String> junkTable() {
        return getConfig().getStringList("junk.items");
    }

    /**
     * 礼物配额：普通礼物和钻石档各有一套冷却 + 每人每天上限。
     *
     * @param big true = 钻石档（单独的长时间冷却与更小的每日上限）
     */
    public boolean tryUseGift(Player player, boolean big) {
        if (player == null) {
            return false;
        }
        if (big) {
            return tryCooldown(lastBigGift, player, bigGiftCooldownSeconds)
                    && allowQuota(bigGiftQuota, player.getUniqueId(), bigGiftDailyCap);
        }
        return tryCooldown(lastGift, player, giftCooldownSeconds)
                && allowQuota(giftQuota, player.getUniqueId(), giftDailyCap);
    }

    public boolean tryUseJunk(Player player) {
        if (player == null) {
            return false;
        }
        return tryCooldown(lastJunk, player, junkCooldownSeconds)
                && allowQuota(junkQuota, player.getUniqueId(), junkDailyCap);
    }

    /** 击杀冷却：真正会劈死人的那一下，同一个人默认 10 分钟只能来一次。 */
    public boolean tryUseKill(Player player) {
        return tryCooldown(lastKill, player, killCooldownSeconds);
    }

    /**
     * 「先警告后杀」：第一次返回 false（并记下警告），警告窗口内再来返回 true（放行真杀）。
     */
    public synchronized boolean consumeWarning(Player player) {
        if (player == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long until = warnedUntil.get(player.getUniqueId());
        if (until != null && now <= until) {
            warnedUntil.remove(player.getUniqueId());
            return true;
        }
        warnedUntil.put(player.getUniqueId(), now + killWarnWindowSeconds * 1000L);
        return false;
    }

    /** 管理员手动测试前清掉配额，免得"测试"被冷却挡住。 */
    public synchronized void clearRewardCooldowns(Player player) {
        if (player == null) {
            return;
        }
        UUID id = player.getUniqueId();
        lastGift.remove(id);
        lastBigGift.remove(id);
        lastJunk.remove(id);
        giftQuota.remove(id);
        bigGiftQuota.remove(id);
        junkQuota.remove(id);
    }

    /** 滑动 24 小时窗口内还能不能再给一次。 */
    private boolean allowQuota(Map<UUID, Quota> map, UUID id, int cap) {
        if (cap <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        Quota quota = map.get(id);
        if (quota == null || now - quota.windowStart > 24L * 3600_000L) {
            map.put(id, new Quota(now, 1));
            return true;
        }
        if (quota.used >= cap) {
            return false;
        }
        quota.used++;
        return true;
    }

    /** 当天已用次数（给状态显示用）。 */
    private int usedToday(Map<UUID, Quota> map, UUID id) {
        Quota quota = map.get(id);
        if (quota == null || System.currentTimeMillis() - quota.windowStart > 24L * 3600_000L) {
            return 0;
        }
        return quota.used;
    }

    private static final class Quota {
        private final long windowStart;
        private int used;

        Quota(long windowStart, int used) {
            this.windowStart = windowStart;
            this.used = used;
        }
    }

    /**
     * 这个玩家是不是被豁免了（不该被送礼、也不该被整）。
     * 豁免只挡"真动作"，它仍然可以对你说话、放音效。
     */
    public String exemptReason(Player player) {
        if (player == null || !player.isOnline()) {
            return "offline";
        }
        if (exemptOps && player.isOp()) {
            return "op";
        }
        if (player.hasPermission(EXEMPT_PERMISSION)) {
            return "permission";
        }
        String world = player.getWorld().getName().toLowerCase(Locale.ROOT);
        for (String prefix : exemptWorldPrefixes) {
            if (!prefix.isBlank() && world.startsWith(prefix)) {
                return "world:" + player.getWorld().getName();
            }
        }
        return null;
    }

    private static String join(int[] values) {
        List<String> out = new ArrayList<>(values.length);
        for (int v : values) {
            out.add(String.valueOf(v));
        }
        return String.join("/", out);
    }

    // ---------------------------------------------------------------- 工具

    public String colorize(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    /** 权限节点只有 theghost.*，旧的 mcbot.* 已废弃。 */
    private static boolean hasAdmin(CommandSender sender) {
        return sender.hasPermission("theghost.admin");
    }

    private static boolean hasTalk(CommandSender sender) {
        return sender.hasPermission("theghost.talk");
    }

    public boolean soundsEnabled() {
        return soundsEnabled;
    }

    public boolean prankEnabled() {
        return prankEnabled;
    }

    public boolean giftEnabled() {
        return giftEnabled;
    }

    public boolean junkEnabled() {
        return junkEnabled;
    }

    public int rewardMinJoyTier() {
        return rewardMinJoyTier;
    }

    public int junkMinAngerTier() {
        return junkMinAngerTier;
    }

    public int junkSlots() {
        return junkSlots;
    }

    public boolean lightningWarnFirst() {
        return killWarnFirst;
    }

    public double lightningWarnLeaveHealth() {
        return killWarnLeaveHealth;
    }

    public Rewards rewards() {
        return rewards;
    }

    public Random random() {
        return random;
    }

    public boolean titlesEnabled() {
        return titlesEnabled;
    }

    public boolean commandsEnabled() {
        return commandsEnabled;
    }

    public List<String> allowedSounds() {
        return allowedSounds;
    }

    public List<String> allowedCommands() {
        return allowedCommands;
    }

    public boolean jumpscareEnabled() {
        return jumpscareEnabled;
    }

    public int jumpscareMinTier() {
        return jumpscareMinTier;
    }

    public List<String> jumpscareSounds() {
        return jumpscareSounds;
    }

    public List<String> jumpscareTitles() {
        return jumpscareTitles;
    }

    public boolean lightningEnabled() {
        return lightningEnabled;
    }

    public int lightningMinTier() {
        return lightningMinTier;
    }

    public boolean lightningRealDamage() {
        return lightningRealDamage;
    }

    public boolean lightningLethal() {
        return lightningLethal;
    }

    public double lightningMinHealth() {
        return lightningMinHealth;
    }

    public boolean lightningSetFire() {
        return lightningSetFire;
    }

    public double lightningDamage(int tier) {
        return index(lightningDamage, tier);
    }

    public boolean creeperEnabled() {
        return creeperEnabled;
    }

    public int creeperMinTier() {
        return creeperMinTier;
    }

    public int creeperAmount(int tier) {
        return (int) index(creeperAmount, tier);
    }

    public double creeperDistance() {
        return creeperDistance;
    }

    public int creeperRadius() {
        return creeperRadius;
    }

    public boolean creeperBreakBlocks() {
        return creeperBreakBlocks;
    }

    public boolean creeperLethal() {
        return creeperLethal;
    }

    public double creeperMaxDamage() {
        return creeperMaxDamage;
    }

    public int creeperDespawnSeconds() {
        return creeperDespawnSeconds;
    }

    public void trackCreeper(UUID id) {
        prankCreepers.add(id);
    }

    public void untrackCreeper(UUID id) {
        prankCreepers.remove(id);
    }

    /** 大捉弄（闪电/苦力怕）的冷却，防止同一个人被连着整。 */
    public boolean tryUseBigPrank(Player player) {
        return tryCooldown(lastBigPrank, player, prankCooldownSeconds);
    }

    public boolean tryUseJumpscare(Player player) {
        return tryCooldown(lastJumpscare, player, jumpscareCooldownSeconds);
    }

    private boolean tryCooldown(Map<UUID, Long> map, Player player, int seconds) {
        if (player == null) {
            return false;
        }
        if (seconds <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long last = map.get(player.getUniqueId());
        if (last != null && now - last < seconds * 1000L) {
            return false;
        }
        map.put(player.getUniqueId(), now);
        return true;
    }

    private static double index(double[] array, int tier) {
        if (array.length == 0) {
            return 0;
        }
        return array[Math.max(0, Math.min(array.length - 1, tier))];
    }

    private static double index(int[] array, int tier) {
        if (array.length == 0) {
            return 0;
        }
        return array[Math.max(0, Math.min(array.length - 1, tier))];
    }
}
