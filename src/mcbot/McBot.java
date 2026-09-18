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

    private final Random random = new Random();
    private final Map<UUID, Long> lastReply = new HashMap<>();
    private final Map<UUID, Long> lastBigPrank = new HashMap<>();
    private final Map<UUID, Long> lastJumpscare = new HashMap<>();
    private final Set<UUID> prankCreepers = new HashSet<>();

    private DeepSeek ai;
    private Grudge grudge;
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
    private int grudgeMaxScore;
    private int insultPoints;
    private int praisePoints;
    private int aiAngerPoints;
    private int grudgeDecayPerHour;
    private int[] grudgeTiers = {3, 8, 15};
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

        grudge = new Grudge(new File(getDataFolder(), "grudge.yml"));
        grudge.load();
        startDecayTask();

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("ghost") != null) {
            getCommand("ghost").setExecutor(this);
            getCommand("ghost").setTabCompleter(this);
        }
        startLoop();
        getLogger().info("幽灵已就位。AI=" + (ai.configured() ? "开启" : "未配置，仅使用本地台词")
                + "，记仇系统=" + (prankEnabled ? "开启" : "关闭"));
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
        if (grudge != null) {
            grudge.save();
        }
    }

    private void startDecayTask() {
        if (decayTask != null) {
            decayTask.cancel();
        }
        // 每 5 分钟结算一次自然淡忘，顺手把账本落盘
        decayTask = getServer().getScheduler().runTaskTimer(this, () -> {
            if (grudge != null) {
                grudge.decayAll(grudgeDecayPerHour, grudgeMaxScore);
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
                cfg.getString("deepseek.model", "deepseek-chat"),
                cfg.getDouble("deepseek.temperature", 1.35),
                cfg.getInt("deepseek.max-tokens", 180),
                cfg.getInt("deepseek.timeout-seconds", 25),
                Math.max(1, cfg.getInt("deepseek.retries", 1) + 1));

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

        // ---- 记仇与捉弄 ----
        prankEnabled = cfg.getBoolean("prank.enabled", true);
        grudgeMaxScore = Math.max(1, cfg.getInt("prank.max-score", 30));
        insultPoints = Math.max(0, cfg.getInt("prank.insult-points", 3));
        praisePoints = Math.min(0, cfg.getInt("prank.praise-points", -1));
        aiAngerPoints = Math.max(0, cfg.getInt("prank.ai-anger-points", 2));
        grudgeDecayPerHour = Math.max(0, cfg.getInt("prank.decay-per-hour", 2));
        grudgeTiers = toIntArray(cfg.getIntegerList("prank.tiers"), new int[]{3, 8, 15});
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
            if (p.isOnline() && (targetOps || !p.isOp())) {
                list.add(p);
            }
        }
        return list;
    }

    /** 记仇的人更容易被它盯上：权重 = 1 + 记仇值。 */
    private Player pickTarget(List<Player> pool) {
        if (!prankEnabled || grudge == null) {
            return pool.get(random.nextInt(pool.size()));
        }
        double total = 0;
        for (Player p : pool) {
            total += 1 + grudge.score(p.getUniqueId(), grudgeDecayPerHour);
        }
        double roll = random.nextDouble() * total;
        for (Player p : pool) {
            roll -= 1 + grudge.score(p.getUniqueId(), grudgeDecayPerHour);
            if (roll <= 0) {
                return p;
            }
        }
        return pool.get(pool.size() - 1);
    }

    // -------------------------------------------------------------- AI 交互

    /** @param forceAi true 时跳过概率过滤，一定走 API（被 @ 和玩家主动提问用这个）。 */
    private void speak(Player target, String trigger, boolean quiet, boolean forceAi) {
        speak(target, trigger, quiet, forceAi, false);
    }

    /**
     * @param trackAnger true 时把模型判定的 ANGER 分数记进账本（只有玩家直接对它说话才开）。
     */
    private void speak(Player target, String trigger, boolean quiet, boolean forceAi, boolean trackAnger) {
        if (!isEnabled()) {
            return;
        }
        int tier = tierOf(target);
        if (ai != null && ai.configured() && (forceAi || random.nextDouble() < aiChance)) {
            String system = Brain.systemPrompt(this, allowedSounds, allowedCommands, tier);
            String user = Brain.userPrompt(trigger, context(target));
            ai.chat(system, user).whenComplete((raw, error) -> {
                Brain.Reply reply;
                if (error != null) {
                    getLogger().warning("DeepSeek 调用失败: " + error.getMessage());
                    reply = Brain.local(lines, allowedSounds, random);
                } else {
                    reply = Brain.parse(raw);
                    if (reply.say().isBlank()) {
                        Brain.Reply fallback = Brain.local(lines, allowedSounds, random);
                        reply = new Brain.Reply(fallback.say(), reply.actionType(), reply.actionValue(), reply.anger());
                    }
                    if (trackAnger && target != null && reply.anger() > 0) {
                        applyAiAnger(target, reply.anger());
                    }
                }
                deliver(reply, target, quiet);
            });
        } else {
            Brain.Reply reply = Brain.local(lines, allowedSounds, random);
            if (prankEnabled && target != null && tier > 0) {
                int idx = Math.min(actionChance.length - 1, tier);
                if (random.nextDouble() < actionChance[idx]) {
                    Brain.Reply extra = Brain.localPrank(tier, random,
                            jumpscareEnabled && tier >= jumpscareMinTier,
                            lightningEnabled && tier >= lightningMinTier,
                            creeperEnabled && tier >= creeperMinTier);
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
            String result = Actions.run(this, reply, online, tierOf(online));
            if (!"none".equals(result) && !"denied".equals(result)) {
                getLogger().info("动作: " + result + (online != null ? " -> " + online.getName() : ""));
            }
        });
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

    // -------------------------------------------------------------- 记仇账本

    private int tierOf(Player player) {
        if (player == null || grudge == null) {
            return 0;
        }
        return tierOf(grudge.score(player.getUniqueId(), grudgeDecayPerHour));
    }

    private int tierOf(int score) {
        int tier = 0;
        for (int i = 0; i < grudgeTiers.length && i < 3; i++) {
            if (score >= grudgeTiers[i]) {
                tier = i + 1;
            }
        }
        return tier;
    }

    /** 模型判定的生气值，最多只让它加 ai-anger-points 分，避免模型情绪化乱记账。 */
    private void applyAiAnger(Player player, int anger) {
        if (aiAngerPoints <= 0) {
            return;
        }
        int delta = Math.min(anger, aiAngerPoints);
        if (delta > 0) {
            bump(player, delta);
        }
    }

    private void bump(Player player, int delta) {
        if (player == null || grudge == null || delta == 0) {
            return;
        }
        int before = tierOf(player);
        int score = grudge.add(player.getUniqueId(), player.getName(), delta, grudgeDecayPerHour, grudgeMaxScore);
        int after = tierOf(score);
        if (delta > 0 && after != before) {
            getLogger().info(player.getName() + " 的记仇值到 " + score + "（等级 " + after + "）");
        }
        if (delta > 0 && after >= 2) {
            maybeRetaliate(player, after);
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
            int nowTier = tierOf(online);
            Brain.Reply prank = Brain.localPrank(nowTier, random,
                    jumpscareEnabled && nowTier >= jumpscareMinTier,
                    lightningEnabled && nowTier >= lightningMinTier,
                    creeperEnabled && nowTier >= creeperMinTier);
            if (prank.hasAction()) {
                deliver(prank, online, false);
            }
        }, delay);
    }

    private static boolean containsAny(String message, List<String> words) {
        if (message == null || words.isEmpty()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (lower.contains(word)) {
                return true;
            }
        }
        return false;
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
            if (prankEnabled) {
                if (containsAny(message, insultWords)) {
                    bump(player, insultPoints);
                } else if (containsAny(message, praiseWords)) {
                    bump(player, praisePoints);
                }
            }
            speak(player, player.getName() + " 用 @ 对你说了：" + message, false, true, true);
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
            sender.sendMessage(colorize("§5[幽灵]§r /ghost ask <内容> · poke [玩家] · grudge [玩家] · "
                    + "prank <玩家> <动作> · toggle · reload · status · test"));
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
                if (prankEnabled) {
                    if (containsAny(text, insultWords)) {
                        bump(player, insultPoints);
                    } else if (containsAny(text, praiseWords)) {
                        bump(player, praisePoints);
                    }
                }
                speak(player, player.getName() + " 对你说：" + text, true, true, true);
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
            case "grudge" -> {
                if (!hasAdmin(sender)) {
                    sender.sendMessage("§c需要 theghost.admin 权限。");
                    return true;
                }
                if (grudge == null) {
                    sender.sendMessage(colorize("§c记仇系统还没初始化。"));
                    return true;
                }
                if (args.length == 1) {
                    sender.sendMessage(colorize("§5幽灵的记仇榜§r（最多 10 人）："));
                    List<Grudge.Entry> top = grudge.top(10);
                    if (top.isEmpty()) {
                        sender.sendMessage("§7  没人骂过它，账本是空的。");
                    }
                    for (Grudge.Entry entry : top) {
                        sender.sendMessage("§7  " + entry.name() + "：" + entry.score()
                                + " 分（等级 " + tierOf(entry.score()) + "）");
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
                    grudge.set(target.getUniqueId(), target.getName(), value, grudgeMaxScore);
                    sender.sendMessage(colorize("§7" + target.getName() + " 的记仇值已设为 "
                            + Math.max(0, Math.min(grudgeMaxScore, value)) + "。"));
                } else if (args.length >= 3 && args[2].equalsIgnoreCase("reset")) {
                    grudge.reset(target.getUniqueId());
                    sender.sendMessage(colorize("§7已把 " + target.getName() + " 的账本撕了。"));
                } else {
                    int score = grudge.score(target.getUniqueId(), grudgeDecayPerHour);
                    int tier = tierOf(score);
                    sender.sendMessage(colorize("§7" + target.getName() + "：记仇值 " + score + " / "
                            + grudgeMaxScore + "，等级 " + tier + "，能用的捉弄：" + allowedPranks(tier)));
                }
            }
            case "prank" -> {
                if (!hasAdmin(sender)) {
                    sender.sendMessage("§c需要 theghost.admin 权限。");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(colorize("§c用法：/ghost prank <玩家> [lightning|creeper|jumpscare]"));
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
                // 管理员手动测试不受记仇等级和冷却限制，方便调参
                lastBigPrank.remove(target.getUniqueId());
                lastJumpscare.remove(target.getUniqueId());
                String result = Actions.run(this, new Brain.Reply("", action, "", 0), target, 3);
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
            case "reload" -> {
                if (!hasAdmin(sender)) {
                    sender.sendMessage("§c需要 theghost.admin 权限。");
                    return true;
                }
                loadSettings();
                startLoop();
                sender.sendMessage(colorize("§7配置已重载。AI=" + (ai.configured() ? "开启" : "未配置")));
            }
            case "status" -> {
                sender.sendMessage(colorize("§5幽灵§r 状态："));
                sender.sendMessage("§7 搞怪：" + (mischiefEnabled ? "开" : "关")
                        + "  间隔：" + intervalSeconds + "±" + jitterSeconds + " 秒"
                        + "  AI：" + (ai.configured() ? "已配置" : "未配置"));
                sender.sendMessage("§7 只有被 " + String.join(" / ", mentionTriggers) + " 才回复；"
                        + "引导窗口 " + guideWindowSeconds + " 秒");
                sender.sendMessage("§7 记仇：" + (prankEnabled ? "开" : "关")
                        + "  等级线 " + grudgeTiers[0] + "/" + grudgeTiers[1] + "/" + grudgeTiers[2]
                        + "  淡忘 " + grudgeDecayPerHour + " 分/小时");
                sender.sendMessage("§7 捉弄：惊吓" + (jumpscareEnabled ? "开" : "关")
                        + " · 闪电" + (lightningEnabled ? "开" : "关")
                        + (lightningRealDamage ? "（真伤害" + (lightningLethal ? "·可致死" : "") + "）" : "（仅特效）")
                        + " · 苦力怕" + (creeperEnabled ? "开" : "关")
                        + (creeperBreakBlocks ? "（会炸方块）" : "（不炸方块）"));
                sender.sendMessage("§7 音效 " + allowedSounds.size() + " 个；指令白名单："
                        + String.join(", ", allowedCommands));
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
                ai.chat(Brain.systemPrompt(this, allowedSounds, allowedCommands, 0),
                        Brain.userPrompt("管理员正在做连通性测试，对他说：" + text, "（测试场景）"))
                        .whenComplete((raw, error) -> {
                            long ms = System.currentTimeMillis() - started;
                            if (error != null) {
                                getLogger().warning("[测试] 调用失败（" + ms + "ms）：" + error);
                            } else {
                                getLogger().info("[测试] 调用成功（" + ms + "ms）："
                                        + raw.replace("\r", " ").replace("\n", " | "));
                            }
                        });
            }
            default -> sender.sendMessage(colorize("§c未知子命令。用法：/ghost ask|poke|grudge|prank|toggle|reload|status|test"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("ask", "poke", "grudge", "prank", "toggle", "reload", "status", "test"), args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("grudge") || args[0].equalsIgnoreCase("prank"))) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("grudge")) {
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
        return list.isEmpty() ? "只有嘴贫" : String.join("/", list);
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
