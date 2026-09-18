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

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * 动作执行。模型只能触发白名单内的动作，危险指令在此被硬拦截。
 *
 * 捉弄类动作（JUMPSCARE / LIGHTNING / CREEPER）还要过两道闸：
 * 该玩家的记仇等级够不够、同一个人是不是刚被整过。
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
            "version", "seed", "debug", "jfr", "perf", "mspt", "tps"));

    private Actions() {
    }

    public static String run(McBot plugin, Brain.Reply reply, Player target) {
        return run(plugin, reply, target, 0);
    }

    public static String run(McBot plugin, Brain.Reply reply, Player target, int tier) {
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
        // 音效/标题/指令必须带参数；闪电、苦力怕、惊吓本来就是无参数的
        if (value.isEmpty() && (type.equals("SOUND") || type.equals("TITLE") || type.equals("COMMAND"))) {
            return "empty";
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
                    target.sendTitle(plugin.colorize("§d" + text), plugin.colorize("§7—— 回声"), 8, 45, 12);
                    return "title";
                }
                case "JUMPSCARE" -> {
                    return jumpscare(plugin, target, tier);
                }
                case "LIGHTNING" -> {
                    return lightning(plugin, target, tier);
                }
                case "CREEPER" -> {
                    return creeper(plugin, target, tier);
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

    // ------------------------------------------------------------ 真·捉弄

    /** 贴脸一声响 + 屏幕闪字，不掉血，用来吓人。 */
    private static String jumpscare(McBot plugin, Player target, int tier) {
        if (!plugin.prankEnabled() || !plugin.jumpscareEnabled() || target == null || !target.isOnline()) {
            return "denied";
        }
        if (tier < plugin.jumpscareMinTier()) {
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
     * 不开 set-fire 时用 strikeLightningEffect（只打闪）+ 手动闪电伤害，
     * 这样能劈死人、能不劈死人，也不会把他家点了。
     */
    private static String lightning(McBot plugin, Player target, int tier) {
        if (!plugin.prankEnabled() || !plugin.lightningEnabled() || target == null || !target.isOnline()) {
            return "denied";
        }
        if (tier < plugin.lightningMinTier()) {
            return "tier-too-low";
        }
        if (!plugin.tryUseBigPrank(target)) {
            return "prank-cooldown";
        }
        double damage = plugin.lightningDamage(tier);
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
        double applied = damage;
        if (!plugin.lightningLethal()) {
            double lowest = Math.max(0.0, target.getHealth() - plugin.lightningMinHealth());
            applied = Math.min(applied, lowest);
        }
        if (applied > 0) {
            target.damage(applied, DamageSource.builder(DamageType.LIGHTNING_BOLT).build());
        }
        return "lightning:" + String.format(Locale.ROOT, "%.1f", applied);
    }

    /** 在玩家身后放苦力怕，锁定他。 */
    private static String creeper(McBot plugin, Player target, int tier) {
        if (!plugin.prankEnabled() || !plugin.creeperEnabled() || target == null || !target.isOnline()) {
            return "denied";
        }
        if (tier < plugin.creeperMinTier()) {
            return "tier-too-low";
        }
        if (!plugin.tryUseBigPrank(target)) {
            return "prank-cooldown";
        }
        int amount = plugin.creeperAmount(tier);
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
