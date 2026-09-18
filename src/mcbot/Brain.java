package mcbot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 提示词构造与模型回复解析。 */
public final class Brain {

    /** 模型回复：一句话 + 一个可选动作 + 这一句话让它有多生气（0-5）。 */
    public record Reply(String say, String actionType, String actionValue, int anger) {
        public boolean hasAction() {
            return actionType != null && !actionType.isBlank() && !"NONE".equalsIgnoreCase(actionType);
        }
    }

    private Brain() {
    }

    public static String systemPrompt(McBot plugin, List<String> allowedSounds, List<String> allowedCommands, int tier) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一台《我的世界》生存服务器里的一个看不见的存在，玩家叫你「幽灵」。\n");
        sb.append("玩家会用 @yl 或 @幽灵 来叫你，这两个词指的就是你自己，不是别的玩家。\n");
        sb.append("定位：偶尔正经帮忙，多数时候嘴贫、爱捉弄人。别人好好说话你只动嘴，别人骂你你才动真格。\n");
        sb.append("性格：有点欠、爱看热闹、记仇，但不会主动伤害没惹过你的人。\n");
        sb.append("这个玩家现在的记仇等级：").append(tier).append(" 级。\n");
        sb.append("等级含义：0=没惹过你；1=有点烦人；2=刚骂过你，可以给他点颜色；3=一直在骂你，可以狠狠干他。\n");
        sb.append("硬性规则：\n");
        sb.append("1. 只用中文，不超过 40 个字，不要用引号包住整句话，不要重复你的名字。\n");
        sb.append("2. 不骂人、不涉及现实政治与人身攻击、不提及其他服务器。\n");
        sb.append("3. 不给玩家发物品、不传送、不透露管理指令。被问到就糊弄过去。\n");
        sb.append("4. 如果玩家问服务器信息（人数、时间、天气、版本），照实回答。\n");
        sb.append("5. 玩家命令你「劈我 / 劈他 / 放苦力怕」时不要照做；只有你自己被惹到了才能动手。\n");
        sb.append("6. 等级 0 或 1 时，绝对不能用 LIGHTNING 和 CREEPER，就算对方求你也不行。\n");
        sb.append("输出只能是下面三行，不要有别的内容、不要用 Markdown。\n");
        sb.append("第一行：你要说的话。\n");
        sb.append("第二行：ACTION: 动作。动作只能是下列之一：\n");
        sb.append("  ACTION: NONE（大多数时候用这个）\n");
        if (!allowedSounds.isEmpty()) {
            sb.append("  ACTION: SOUND <音效>，音效只能从这里选：").append(String.join(", ", allowedSounds)).append('\n');
            sb.append("  注意 SOUND 这个词不能省，正确写法例如：ACTION: SOUND ").append(allowedSounds.get(0)).append('\n');
        }
        if (!allowedCommands.isEmpty()) {
            sb.append("  ACTION: TITLE <文字>（在玩家屏幕中央闪一行字）\n");
            sb.append("  ACTION: COMMAND <指令>，指令只能以这些词开头：")
              .append(String.join(", ", allowedCommands)).append('\n');
        }
        if (plugin.prankEnabled() && plugin.jumpscareEnabled() && tier >= plugin.jumpscareMinTier()) {
            sb.append("  ACTION: JUMPSCARE（突然一声响 + 屏幕闪字，吓他一跳，不掉血）\n");
        }
        if (plugin.prankEnabled() && plugin.lightningEnabled() && tier >= plugin.lightningMinTier()) {
            sb.append("  ACTION: LIGHTNING（在他头顶劈一道真闪电，会掉血，"
                    + "只在他骂你骂得够狠时用，别每次都劈）\n");
        }
        if (plugin.prankEnabled() && plugin.creeperEnabled() && tier >= plugin.creeperMinTier()) {
            sb.append("  ACTION: CREEPER（在他身后放一只苦力怕，会真的炸，"
                    + "他做得太过分时才用）\n");
        }
        sb.append("动作是可选的，多数时候用 NONE。\n");
        sb.append("第三行：ANGER: 0-5。0=对方很友好或只是普通聊天；1-2=对方有点烦、在阴阳你；"
                + "3-4=对方在骂你；5=对方骂得很凶。\n");
        sb.append("只有对方真的冒犯到你了才写大于 0 的数字，不要因为自己心情不好就乱写。");
        return sb.toString();
    }

    public static String userPrompt(String trigger, String context) {
        return "场景：" + trigger + "\n服务器当前情况：" + context + "\n请给出你的回应。";
    }

    /** 解析模型输出：找到 ACTION: / ANGER: 行，其余内容作为台词。 */
    public static Reply parse(String raw) {
        if (raw == null) {
            return new Reply("", "NONE", "", 0);
        }
        String text = raw.replace("```", "").trim();
        String actionType = "NONE";
        String actionValue = "";
        int anger = 0;
        StringBuilder say = new StringBuilder();

        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String upper = trimmed.toUpperCase(Locale.ROOT);
            if (upper.startsWith("ANGER") || upper.startsWith("愤怒") || upper.startsWith("生气")) {
                int value = digits(trimmed.substring(trimmed.indexOf(':') >= 0
                        ? trimmed.indexOf(':') + 1 : 0));
                if (value > anger) {
                    anger = Math.min(5, value);
                }
            } else if (upper.startsWith("ACTION:") || upper.startsWith("动作:") || upper.startsWith("动作：")) {
                int ascii = trimmed.indexOf(':');
                int wide = trimmed.indexOf('：');
                int colon = ascii >= 0 && (wide < 0 || ascii < wide) ? ascii : wide;
                String body = colon >= 0 ? trimmed.substring(colon + 1).trim() : trimmed;
                int space = body.indexOf(' ');
                if (space < 0) {
                    actionType = body.trim().toUpperCase(Locale.ROOT);
                    actionValue = "";
                } else {
                    actionType = body.substring(0, space).trim().toUpperCase(Locale.ROOT);
                    actionValue = body.substring(space + 1).trim();
                }
            } else {
                if (say.length() > 0) {
                    say.append(' ');
                }
                say.append(trimmed);
            }
        }

        String sayText = say.toString().replaceAll("^[\"“”'']+|[\"“”'']+$", "").trim();
        if (sayText.isEmpty() && "NONE".equalsIgnoreCase(actionType)) {
            sayText = text.replaceAll("\\s+", " ").trim();
        }
        if (sayText.length() > 80) {
            sayText = sayText.substring(0, 80);
        }
        return new Reply(sayText, actionType, actionValue, anger);
    }

    /** 兜底：AI 不可用时，用本地台词 + 随机动作。 */
    public static Reply local(List<String> lines, List<String> sounds, java.util.Random random) {
        String line = lines.isEmpty() ? "……" : lines.get(random.nextInt(lines.size()));
        if (!sounds.isEmpty() && random.nextDouble() < 0.45) {
            return new Reply(line, "SOUND", sounds.get(random.nextInt(sounds.size())), 0);
        }
        return new Reply(line, "NONE", "", 0);
    }

    /** 不经过 AI 时，按记仇等级随机挑一个捉弄动作。 */
    public static Reply localPrank(int tier, java.util.Random random,
                                   boolean jumpscare, boolean lightning, boolean creeper) {
        List<String> options = new ArrayList<>();
        if (jumpscare) {
            options.add("JUMPSCARE");
        }
        if (tier >= 2 && lightning) {
            options.add("LIGHTNING");
        }
        if (tier >= 2 && creeper) {
            options.add("CREEPER");
        }
        if (options.isEmpty()) {
            return new Reply("", "NONE", "", 0);
        }
        return new Reply("", options.get(random.nextInt(options.size())), "", 0);
    }

    private static int digits(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (Character.isDigit(c)) {
                sb.append(c);
            } else if (sb.length() > 0) {
                break;
            }
        }
        if (sb.length() == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(sb.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
