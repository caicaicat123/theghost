package mcbot;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * 动作执行。模型只能触发白名单内的动作，危险指令在此被硬拦截。
 *
 * <p>真动作（GIFT / JUNK / JUMPSCARE / LIGHTNING / CREEPER）要过四道闸：
 * ① 这个玩家是否豁免（OP / theghost.immune 权限 / 副本世界）；
 * ② 心情档位够不够；③ 冷却与每日上限；④ 击杀还要额外过「先警告后杀」和击杀冷却。
 *
 * <p>**物品一律走 Bukkit API（{@link Rewards}），`give` 永远留在硬拦截名单里**，
 * 所以模型写不出 `@a`、写不出数量，也写不出 NBT。
 */
public final class Actions {

    private static final Random RANDOM = new Random();

    /** 无论配置怎么写，这些指令永远不会被执行。 */
    // 用 LinkedHashSet 而不是 Set.of：后者遇到重复元素会在类初始化时直接抛异常
    private static final Set<String> HARD_BLOCKED = new LinkedHashSet<>(List.of(
            "op", "deop", "ban", "ban-ip", "banlist", "pardon", "pardon-ip", "kick", "stop", "restart",
            "whitelist", "reload", "rl", "plugman", "execute", "tp", "teleport", "give", "clear", "kill",
            "fill", "setblock", "clone", "summon", "gamemode", "difficulty", "worldborder", "gamerule",
            "save-off", "save-all", "datapack", "function", "recipe", "advancement", "spreadplayers",
            "forceload", "bukkit:reload", "minecraft:stop", "paper", "spark", "plugins",
            "version", "seed", "debug", "jfr", "perf", "mspt", "tps",
            // 能绕开物品白名单的后门（loot 能刷战利品表、item/data 能改 NBT、xp 是白给经验）
            "loot", "item", "data", "xp", "experience", "enchant", "damage", "attribute", "effect"));

    /** 需要过「豁免 + 档位 + 冷却」的真动作。 */
    private static final Set<String> REAL_ACTIONS = new LinkedHashSet<>(
            List.of("GIFT", "JUNK", "JUMPSCARE", "LIGHTNING", "CREEPER"));

    private Actions() {
    }

    public static String run(McBot plugin, Brain.Reply reply, Player target) {
        return run(plugin, reply, target, 0, 0);
    }

    /** @deprecated 用带两个档位的版本；这个只是给"管理员手动测试"用的（档位拉满）。 */
    @Deprecated
    public static String run(McBot plugin, Brain.Reply reply, Player target, int tier) {
        return run(plugin, reply, target, tier, tier);
    }

    /**
     * @param angerTier 生气档 0-3（心情越低越高），决定捉弄类动作放不放行
     * @param joyTier   开心档 0-3（心情越高越高），决定礼物档位
     */
    public static String run(McBot plugin, Brain.Reply reply, Player target, int angerTier, int joyTier) {
        if (!reply.hasAction()) {
            return "none";
        }
        String type = reply.actionType().toUpperCase(Locale.ROOT);
        String value = reply.actionValue() == null ? "" : reply.actionValue().trim();
        String raw = (reply.actionType() + " " + value).trim().toLowerCase(Locale.ROOT);
        // 容错：模型有时会漏掉 SOUND 关键字，直接写音效名（例如 ACTION: block.note_block.pling）
        if (!type.equals("SOUND") && !type.equals("TITLE") && !type.equals("COMMAND")
                && !type.equals("NONE") && plugin.allowedSounds().contains(raw)) {
            type = "SOUND";
            value = raw;
        }
        // 音效/标题/指令必须带参数；闪电、苦力怕、惊吓、礼物、垃圾本来就是无参数的
        if (value.isEmpty() && (type.equals("SOUND") || type.equals("TITLE") || type.equals("COMMAND"))) {
            return "empty";
        }
        // 豁免只挡"真动作"，不挡它说话/放音效——被免疫的玩家仍然能逗它
        if (REAL_ACTIONS.contains(type)) {
            String exempt = plugin.exemptReason(target);
            if (exempt != null) {
                return "exempt:" + exempt;
            }
        }
        try {
            switch (type) {
                case "SOUND" -> {
                    if (!plugin.soundsEnabled() || target == null || !target.isOnline()) {
                        return "denied";
                    }
                    String name = value.toLowerCase(Locale.ROOT).trim();
                    if (!plugin.allowedSounds().contains(name)) {
                        return "sound-not-allowed";
                    }
                    target.playSound(target.getLocation(), name, 0.9f, 1.0f);
                    return "sound:" + name;
                }
                case "TITLE" -> {
                    if (!plugin.titlesEnabled() || target == null || !target.isOnline()) {
                        return "denied";
                    }
                    String text = value.length() > 60 ? value.substring(0, 60) : value;
                    target.sendTitle(plugin.colorize("§d" + text), plugin.colorize("§7—— 幽灵"), 8, 45, 12);
                    return "title";
                }
                case "GIFT" -> {
                    return gift(plugin, target, joyTier);
                }
                case "JUNK" -> {
                    return junk(plugin, target, angerTier);
                }
                case "JUMPSCARE" -> {
                    return jumpscare(plugin, target, angerTier);
                }
                case "LIGHTNING" -> {
                    return lightning(plugin, target, angerTier);
                }
                case "CREEPER" -> {
                    return creeper(plugin, target, angerTier);
                }
                case "COMMAND" -> {
                    if (!plugin.commandsEnabled()) {
                        return "denied";
                    }
                    String command = value.startsWith("/") ? value.substring(1) : value;
                    String head = command.split("\\s+")[0].toLowerCase(Locale.ROOT);
                    String bare = head.contains(":") ? head.substring(head.indexOf(':') + 1) : head;
                    if (HARD_BLOCKED.contains(head) || HARD_BLOCKED.contains(bare)
                            || !plugin.allowedCommands().contains(bare)) {
                        plugin.getLogger().warning("拦截了模型指令: " + command);
                        return "command-blocked";
                    }
                    if (command.length() > 160) {
                        return "command-too-long";
                    }
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                    return "command:" + head;
                }
                default -> {
                    return "unknown:" + type;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("执行动作失败 (" + type + "): " + e.getMessage());
            return "error";
        }
    }

    // ------------------------------------------------------------ 礼物与垃圾

    /** 给玩家一份礼物：物品与数量由 {@link Rewards} 按开心档从白名单里抽。 */
    private static String gift(McBot plugin, Player target, int joyTier) {
        if (!plugin.prankEnabled() || !plugin.giftEnabled() || target == null || !target.isOnline()) {
            return "denied";
        }
        if (joyTier < plugin.rewardMinJoyTier()) {
            return "mood-not-happy-enough";
        }
        String tier = plugin.giftTierKey(joyTier);
        boolean big = "big".equals(tier);
        if (!plugin.tryUseGift(target, big)) {
            return "gift-quota";
        }
        String got = plugin.rewards().give(target, tier, plugin.random());
        if (got == null) {
            return "no-gift"; // 该档位白名单是空的（配置问题，启动时已经警告过）
        }
        plugin.getLogger().info("送礼: " + target.getName() + " <- " + tier + " " + got);
        return "gift:" + tier + ":" + got;
    }

    /** 往背包空位里塞垃圾。 */
    private static String junk(McBot plugin, Player target, int angerTier) {
        if (!plugin.prankEnabled() || !plugin.junkEnabled() || target == null || !target.isOnline()) {
            return "denied";
        }
        if (angerTier < plugin.junkMinAngerTier()) {
            return "mood-not-angry-enough";
        }
        if (!plugin.tryUseJunk(target)) {
            return "junk-quota";
        }
        String put = plugin.rewards().junk(target, plugin.junkSlots(), plugin.random());
        if (put == null) {
            return "junk-no-space"; // 背包满了：宁可什么都不做，也不覆盖玩家自己的东西
        }
        return "junk:" + put;
    }

    // ------------------------------------------------------------ 真·捉弄

    /** 贴脸一声响 + 屏幕闪字，不掉血，用来吓人。 */
    private static String jumpscare(McBot plugin, Player target, int angerTier) {
        if (!plugin.prankEnabled() || !plugin.jumpscareEnabled() || target == null || !target.isOnline()) {
            return "denied";
        }
        if (angerTier < plugin.jumpscareMinTier()) {
            return "tier-too-low";
        }
        if (!plugin.tryUseJumpscare(target)) {
            return "prank-cooldown";
        }
        List<String> sounds = plugin.jumpscareSounds();
        if (!sounds.isEmpty()) {
            String sound = sounds.get(RANDOM.nextInt(sounds.size()));
            target.playSound(target.getLocation(), sound, 1.6f, 0.6f);
        }
        List<String> texts = plugin.jumpscareTitles();
        if (!texts.isEmpty()) {
            String text = texts.get(RANDOM.nextInt(texts.size()));
            target.sendTitle(plugin.colorize("§c" + text), plugin.colorize("§7—— 回头看看"), 2, 26, 8);
        }
        return "jumpscare";
    }

    /**
     * 在玩家头上劈闪电。
     *
     * <p>不开 set-fire 时用 strikeLightningEffect（只打闪）+ 手动闪电伤害，
     * 这样能劈死人、能不劈死人，也不会把他家点了。
     *
     * <p>会劈死人的那一下要额外过两道保险：**第一次只警告（留一口气）**，
     * 警告窗口内再来才真杀；真杀还有自己的冷却（默认 10 分钟）。
     */
    private static String lightning(McBot plugin, Player target, int angerTier) {
        if (!plugin.prankEnabled() || !plugin.lightningEnabled() || target == null || !target.isOnline()) {
            return "denied";
        }
        if (angerTier < plugin.lightningMinTier()) {
            return "tier-too-low";
        }
        if (!plugin.tryUseBigPrank(target)) {
            return "prank-cooldown";
        }
        double damage = plugin.lightningDamage(angerTier);
        boolean real = plugin.lightningRealDamage() && damage > 0;
        Location loc = target.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            return "no-world";
        }
        if (real && plugin.lightningSetFire()) {
            // 原版闪电：掉血 + 点火，想要这种就把它配置开起来
            world.strikeLightning(loc);
            return "lightning-real-fire";
        }
        world.strikeLightningEffect(loc);
        if (!real) {
            return "lightning-effect";
        }

        double health = target.getHealth();
        double applied = damage;
        boolean lethal = damage >= health - 0.001;
        String result = "lightning";
        if (lethal) {
            if (plugin.lightningWarnFirst() && !plugin.consumeWarning(target)) {
                // 第一次只警告：留一口气，把话说明白
                applied = Math.max(0.0, health - plugin.lightningWarnLeaveHealth());
                target.sendMessage(plugin.colorize(
                        "§5幽灵§r：§c我记住你了。下一次，就不会只留你一口气。"));
                result = "lightning-warned";
            } else if (!plugin.tryUseKill(target)) {
                applied = Math.max(0.0, health - plugin.lightningMinHealth());
                result = "lightning-kill-cooldown";
            } else {
                target.sendMessage(plugin.colorize("§5幽灵§r：§4我说过我会记住的。"));
                result = "lightning-kill";
            }
        } else if (!plugin.lightningLethal()) {
            double lowest = Math.max(0.0, health - plugin.lightningMinHealth());
            applied = Math.min(applied, lowest);
        }
        if (applied > 0) {
            target.damage(applied, DamageSource.builder(DamageType.LIGHTNING_BOLT).build());
        }
        return result + ":" + String.format(Locale.ROOT, "%.1f", applied);
    }

    /** 在玩家身后放苦力怕，锁定他。 */
    private static String creeper(McBot plugin, Player target, int angerTier) {
        if (!plugin.prankEnabled() || !plugin.creeperEnabled() || target == null || !target.isOnline()) {
            return "denied";
        }
        if (angerTier < plugin.creeperMinTier()) {
            return "tier-too-low";
        }
        if (!plugin.tryUseBigPrank(target)) {
            return "prank-cooldown";
        }
        int amount = plugin.creeperAmount(angerTier);
        int spawned = 0;
        for (int i = 0; i < amount; i++) {
            Location spot = spawnSpot(target, plugin.creeperDistance());
            if (spot == null) {
                continue;
            }
            Creeper creeper = spot.getWorld().spawn(spot, Creeper.class);
            creeper.setTarget(target);
            creeper.setExplosionRadius(plugin.creeperRadius());
            creeper.setRemoveWhenFarAway(false);
            creeper.setMetadata(McBot.PRANK_META, new org.bukkit.metadata.FixedMetadataValue(plugin, true));
            plugin.trackCreeper(creeper.getUniqueId());
            if (plugin.creeperDespawnSeconds() > 0) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    plugin.untrackCreeper(creeper.getUniqueId());
                    if (creeper.isValid()) {
                        creeper.remove();
                    }
                }, plugin.creeperDespawnSeconds() * 20L);
            }
            target.playSound(spot, "entity.creeper.primed", 1.0f, 1.0f);
            spawned++;
        }
        return spawned == 0 ? "no-space" : "creeper:" + spawned;
    }

    private static Location spawnSpot(Player target, double distance) {
        World world = target.getWorld();
        Location base = target.getLocation();
        if (world == null) {
            return null;
        }
        Vector dir = base.getDirection().setY(0);
        if (dir.lengthSquared() < 1.0e-6) {
            dir = new Vector(0, 0, 1);
        }
        dir.normalize().multiply(-distance);
        Location behind = base.clone().add(dir);
        Location spot = groundNear(world, behind);
        if (spot != null) {
            return spot;
        }
        // 身后是墙/水/岩浆就绕着玩家找一圈，总能找到落脚点
        for (double angle = 0; angle < Math.PI * 2; angle += Math.PI / 4) {
            Location around = base.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
            spot = groundNear(world, around);
            if (spot != null) {
                return spot;
            }
        }
        return null;
    }

    private static Location groundNear(World world, Location base) {
        int x = base.getBlockX();
        int z = base.getBlockZ();
        int y = base.getBlockY();
        for (int dy = 2; dy >= -3; dy--) {
            Block feet = world.getBlockAt(x, y + dy, z);
            Block head = world.getBlockAt(x, y + dy + 1, z);
            Block ground = world.getBlockAt(x, y + dy - 1, z);
            if (feet.isPassable() && !feet.isLiquid() && head.isPassable() && !head.isLiquid()
                    && ground.getType().isSolid()) {
                Location out = new Location(world, x + 0.5, y + dy, z + 0.5);
                out.setYaw(base.getYaw());
                out.setPitch(0);
                return out;
            }
        }
        return null;
    }

    public static List<String> hardBlocked() {
        return List.copyOf(HARD_BLOCKED);
    }
}
