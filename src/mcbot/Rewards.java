package mcbot;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;

/**
 * 礼物与垃圾：**只走 Bukkit API，绝不碰 `give` 指令**。
 *
 * <p>物品 ID 与数量都在配置白名单里，模型只能表达意图（GIFT / JUNK），
 * 由这里按心情档位随机挑——所以它写不出 `@a`、写不出数量、也写不出 NBT，
 * 更没有机会刷钻石：`give` 仍然躺在 {@link Actions} 的硬拦截名单里。
 *
 * <p>发东西一律走 {@link PlayerInventory#addItem}（背包满了就掉在脚下，数量小所以无所谓）；
 * 塞垃圾只挑**空格子**用 setItem 放，既不会覆盖玩家自己的东西，也不会在地上刷实体。
 */
public final class Rewards {

    /** 一份物品：材质 + 数量。 */
    public record Gift(Material material, int amount) {
    }

    private final Map<String, List<Gift>> tables = new LinkedHashMap<>();
    private final List<Gift> junk = new ArrayList<>();
    private final List<String> badEntries = new ArrayList<>();

    /**
     * @param rawTables 档位名 → 物品行（`"diamond_sword:1"` 这种写法）
     * @param rawJunk   垃圾物品行
     */
    public void load(Map<String, List<String>> rawTables, List<String> rawJunk) {
        tables.clear();
        junk.clear();
        badEntries.clear();
        for (Map.Entry<String, List<String>> e : rawTables.entrySet()) {
            tables.put(e.getKey(), parse(e.getValue()));
        }
        junk.addAll(parse(rawJunk));
    }

    /** 解析失败的条目记下来，启动时打一条日志（别让配置里的错别字静默失效）。 */
    public void logProblems(Logger logger) {
        for (String bad : badEntries) {
            logger.warning("物品白名单里认不出的条目（已跳过）：" + bad);
        }
        for (Map.Entry<String, List<Gift>> e : tables.entrySet()) {
            if (e.getValue().isEmpty()) {
                logger.warning("礼物档位 " + e.getKey() + " 是空的：模型想送也送不出东西");
            }
        }
        if (junk.isEmpty()) {
            logger.warning("垃圾物品白名单是空的：JUNK 动作会什么都不做");
        }
    }

    public List<Gift> table(String tier) {
        return tables.getOrDefault(tier, List.of());
    }

    public List<Gift> junkTable() {
        return List.copyOf(junk);
    }

    public int tableCount() {
        int sum = 0;
        for (List<Gift> list : tables.values()) {
            sum += list.size();
        }
        return sum;
    }

    public int junkCount() {
        return junk.size();
    }

    private List<Gift> parse(List<String> lines) {
        List<Gift> out = new ArrayList<>();
        if (lines == null) {
            return out;
        }
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String text = line.trim();
            int amount = 1;
            int colon = text.lastIndexOf(':');
            if (colon > 0) {
                try {
                    amount = Math.max(1, Integer.parseInt(text.substring(colon + 1).trim()));
                    text = text.substring(0, colon).trim();
                } catch (NumberFormatException ignored) {
                    // 没写数量就按 1 个
                }
            }
            Material material = Material.matchMaterial(text.toUpperCase(Locale.ROOT));
            if (material == null || material.isAir()) {
                badEntries.add(line.trim());
                continue;
            }
            out.add(new Gift(material, amount));
        }
        return out;
    }

    /**
     * 给玩家一份礼物。
     *
     * @param tier 心情档位名（配置里的键，例如 small / good / big）
     * @return 描述字符串，没给成返回 null
     */
    public String give(Player player, String tier, Random random) {
        List<Gift> pool = table(tier);
        if (player == null || !player.isOnline() || pool.isEmpty()) {
            return null;
        }
        Gift gift = pool.get(random.nextInt(pool.size()));
        ItemStack stack = new ItemStack(gift.material(), gift.amount());
        Map<Integer, ItemStack> left = player.getInventory().addItem(stack);
        // 背包满了：剩下的掉在脚下，别静默吞掉（数量都很小，不会刷实体）
        for (ItemStack rest : left.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
        return gift.material().name().toLowerCase(Locale.ROOT) + " x" + gift.amount();
    }

    /**
     * 往玩家背包里塞垃圾：只挑空格子，塞 slots 格。
     *
     * @return 描述字符串，没塞成返回 null
     */
    public String junk(Player player, int slots, Random random) {
        if (player == null || !player.isOnline() || junk.isEmpty() || slots <= 0) {
            return null;
        }
        PlayerInventory inv = player.getInventory();
        List<Integer> empty = new ArrayList<>();
        // 只动主背包（0-35），盔甲和副手一律不碰
        for (int slot = 0; slot < 36; slot++) {
            ItemStack current = inv.getItem(slot);
            if (current == null || current.getType().isAir()) {
                empty.add(slot);
            }
        }
        if (empty.isEmpty()) {
            return null; // 背包真的满了：什么都不做，绝不覆盖玩家自己的东西
        }
        int want = Math.min(slots, empty.size());
        List<String> put = new ArrayList<>();
        for (int i = 0; i < want; i++) {
            int slot = empty.remove(random.nextInt(empty.size()));
            Gift item = junk.get(random.nextInt(junk.size()));
            inv.setItem(slot, new ItemStack(item.material(), item.amount()));
            put.add(item.material().name().toLowerCase(Locale.ROOT));
        }
        return String.join(", ", put);
    }
}
