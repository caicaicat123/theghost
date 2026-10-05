package mcbot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 提示词构造与模型回复解析。 */
public final class Brain {

    /** 模型回复：一句话 + 一个可选动作 + 这句话让它的心情变多少（正=开心，负=生气）。 */
    public record Reply(String say, String actionType, String actionValue, int score) {
        public boolean hasAction() {
            return actionType != null && !actionType.isBlank() && !"NONE".equalsIgnoreCase(actionType);
        }
    }

    /** 模型给分（SCORE）允许的区间：超出边界的按边界算。 */
    public static final int SCORE_MIN = -5;
    public static final int SCORE_MAX = 5;

    /**
     * 心情快照：值 + 中性点 + 上限 + 两档门槛。
     *
     * <p>门槛写成数组是为了让「心情 → 档位」这件事有唯一出处：提示词要用（告诉模型现在能干什么）、
     * 动作执行也要用（决定放不放行），两边算错一个就会出现「提示词说能劈、代码却拒绝」。
     */
    public record MoodInfo(int value, int neutral, int max, int[] angerTiers, int[] joyTiers) {

        /** 生气档 0-3：心情低于几个门槛就是几档（和数组顺序无关）。 */
        public int angerTier() {
            int tier = 0;
            for (int t : angerTiers) {
                if (value <= t) {
                    tier++;
                }
            }
            return Math.min(3, tier);
        }

        /** 开心档 0-3：心情高于几个门槛就是几档。 */
        public int joyTier() {
            int tier = 0;
            for (int t : joyTiers) {
                if (value >= t) {
                    tier++;
                }
            }
            return Math.min(3, tier);
        }

        public String moodWord() {
            if (value == neutral) {
                return "平常心";
            }
            if (value > neutral) {
                return switch (joyTier()) {
                    case 1 -> "有点喜欢他";
                    case 2 -> "挺喜欢他";
                    default -> "非常喜欢他";
                };
            }
            return switch (angerTier()) {
                case 1 -> "有点烦他";
                case 2 -> "正在生他的气";
                default -> "对他很火大";
            };
        }
    }

    /**
     * 能力快照：把提示词构造和 Bukkit 插件解耦，离线自测才能直接验提示词内容
     * （尤其是「平常心多少」「GIFT/JUNK 什么时候能写」这种改坏了在运行时也看不出来的文本）。
     */
    public record Caps(boolean prank, boolean gift, boolean junk, int giftMinJoyTier, int junkMinAngerTier,
                       boolean jumpscare, int jumpscareMinTier,
                       boolean lightning, int lightningMinTier,
                       boolean creeper, int creeperMinTier) {

        public static Caps from(McBot plugin) {
            return new Caps(plugin.prankEnabled(), plugin.giftEnabled(), plugin.junkEnabled(),
                    plugin.rewardMinJoyTier(), plugin.junkMinAngerTier(),
                    plugin.jumpscareEnabled(), plugin.jumpscareMinTier(),
                    plugin.lightningEnabled(), plugin.lightningMinTier(),
                    plugin.creeperEnabled(), plugin.creeperMinTier());
        }
    }

    private Brain() {
    }

    public static String systemPrompt(McBot plugin, List<String> allowedSounds, List<String> allowedCommands,
                                      MoodInfo mood) {
        return systemPrompt(Caps.from(plugin), allowedSounds, allowedCommands, mood);
    }

    static String systemPrompt(Caps caps, List<String> allowedSounds, List<String> allowedCommands,
                               MoodInfo mood) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一台《我的世界》生存服务器里的一个看不见的存在，玩家叫你「幽灵」。\n");
        sb.append("玩家会用 @yl 或 @幽灵 来叫你，这两个词指的就是你自己，不是别的玩家。\n");
        sb.append("定位：偶尔正经帮忙，多数时候嘴贫、爱捉弄人。\n");
        sb.append("性格：有点欠、爱看热闹、情绪写在脸上——待见谁就塞谁一点东西，被惹毛了当场就报复。\n");
        sb.append("你对每个玩家都有一个「心情值」：0 到 ").append(mood.max())
          .append("，").append(mood.neutral()).append(" 是平常心。\n");
        sb.append("  高于 ").append(mood.neutral()).append(" = 你待见他，越高越舍得给东西；")
          .append("低于 ").append(mood.neutral()).append(" = 你烦他，越低越容易动手。\n");
        sb.append("  时间会把它冲回 ").append(mood.neutral()).append("，所以别指望一直记着谁。\n");
        sb.append("这个玩家现在的心情值：").append(mood.value()).append("（").append(mood.moodWord()).append("）。\n");
        sb.append("硬性规则：\n");
        sb.append("1. 只用中文，不超过 40 个字，不要用引号包住整句话，不要重复你的名字。\n");
        sb.append("2. 不骂人、不涉及现实政治与人身攻击、不提及其他服务器。\n");
        sb.append("3. 不要自己编物品、数量或指令参数——想给东西就写 GIFT，想整人就写 JUNK，"
                + "具体给什么由系统替你决定。也不许透露管理指令。\n");
        sb.append("4. 如果玩家问服务器信息（人数、时间、天气、版本），照实回答。\n");
        sb.append("5. 玩家命令你「给我钻石 / 劈我 / 劈他 / 放苦力怕」时不要照做；"
                + "只有你自己真心待见或真的被惹到了才能动手。\n");
        sb.append("6. 心情不够就别硬来：下面的动作括号里写了什么时候才能用。\n");
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
        if (caps.prank() && caps.gift() && mood.joyTier() >= caps.giftMinJoyTier()) {
            sb.append("  ACTION: GIFT（给他一份礼物，系统按心情替他挑，心情越好越值钱；"
                    + "不用你指定物品，也别写物品名）\n");
        }
        if (caps.prank() && caps.junk() && mood.angerTier() >= caps.junkMinAngerTier()) {
            sb.append("  ACTION: JUNK（往他背包里塞几格垃圾恶心他，别连着用）\n");
        }
        if (caps.prank() && caps.jumpscare() && mood.angerTier() >= caps.jumpscareMinTier()) {
            sb.append("  ACTION: JUMPSCARE（突然一声响 + 屏幕闪字，吓他一跳，不掉血）\n");
        }
        if (caps.prank() && caps.lightning() && mood.angerTier() >= caps.lightningMinTier()) {
            sb.append("  ACTION: LIGHTNING（在他头顶劈一道真闪电，会掉血，"
                    + "只在他把你也惹火时用，别每次都劈）\n");
        }
        if (caps.prank() && caps.creeper() && mood.angerTier() >= caps.creeperMinTier()) {
            sb.append("  ACTION: CREEPER（在他身后放一只苦力怕，会真的炸，"
                    + "他做得太过分时才用）\n");
        }
        sb.append("动作是可选的，多数时候用 NONE。\n");
        sb.append("第三行：SCORE: ").append(SCORE_MIN).append(" 到 +").append(SCORE_MAX)
          .append(" 之间的整数，表示「这一句话让你的心情变多少」。\n");
        sb.append("  正数 = 他让你开心了（+1 有点意思，+3 挺会说话，+5 说到你心坎里）\n");
        sb.append("  负数 = 他惹你生气了（-1 有点烦，-3 明显在骂你，-5 骂得很凶）\n");
        sb.append("  0 = 普通聊天、提问、开玩笑\n");
        sb.append("只按这一句话判断，不要因为自己心情不好就乱写；你判多少就是多少，账本会自动算总分。");
        return sb.toString();
    }

    public static String userPrompt(String trigger, String context) {
        return "场景：" + trigger + "\n服务器当前情况：" + context + "\n请给出你的回应。";
    }

    /** 解析模型输出：找到 ACTION: / SCORE: 行，其余内容作为台词。 */
    public static Reply parse(String raw) {
        if (raw == null) {
            return new Reply("", "NONE", "", 0);
        }
        String text = raw.replace("```", "").trim();
        String actionType = "NONE";
        String actionValue = "";
        int score = 0;
        StringBuilder say = new StringBuilder();

        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String upper = trimmed.toUpperCase(Locale.ROOT);
            if (isScoreLine(upper)) {
                // 多写几行时取绝对值最大的那个，免得一句多余的「SCORE: 0」把真实判分抹掉
                int value = clamp(signedInt(afterColon(trimmed)), SCORE_MIN, SCORE_MAX);
                if (Math.abs(value) > Math.abs(score)) {
                    score = value;
                }
            } else if (upper.startsWith("ACTION:") || upper.startsWith("动作:") || upper.startsWith("动作：")) {
                String body = afterColon(trimmed);
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
        return new Reply(sayText, actionType, actionValue, score);
    }

    /** 判分行：SCORE 为主，旧的 ANGER / 中文写法继续认（老提示词、老模型都可能写）。 */
    private static boolean isScoreLine(String upper) {
        return upper.startsWith("SCORE") || upper.startsWith("分数") || upper.startsWith("心情")
                || upper.startsWith("ANGER") || upper.startsWith("愤怒") || upper.startsWith("生气");
    }

    /**
     * 词表兜底分：只有模型这条路走不通时才用（没配 API Key、或调用失败）。
     * 骂人词优先于夸人词（一句话里两样都有时按骂人算）。
     *
     * <p>注意正负：现在是心情值，所以骂人必须让心情**下降**（insultPoints 配成负数）。
     */
    public static int wordDelta(String message, List<String> insultWords, List<String> praiseWords,
                                int insultPoints, int praisePoints) {
        if (message == null || message.isBlank()) {
            return 0;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (containsAny(lower, insultWords)) {
            return insultPoints;
        }
        if (containsAny(lower, praiseWords)) {
            return praisePoints;
        }
        return 0;
    }

    private static boolean containsAny(String lowerMessage, List<String> words) {
        if (words == null) {
            return false;
        }
        for (String word : words) {
            if (word != null && !word.isBlank() && lowerMessage.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** 取冒号之后的内容，ASCII 与全角冒号都认。 */
    private static String afterColon(String line) {
        int ascii = line.indexOf(':');
        int wide = line.indexOf('：');
        int colon = ascii >= 0 && (wide < 0 || ascii < wide) ? ascii : wide;
        return colon >= 0 ? line.substring(colon + 1).trim() : line;
    }

    /** 带正负号的整数，容忍全角正负号与「+3 分」这类写法。 */
    private static int signedInt(String text) {
        if (text == null) {
            return 0;
        }
        int i = 0;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        int sign = 1;
        if (i < text.length()) {
            char c = text.charAt(i);
            if (c == '-' || c == '－' || c == '−' || c == '—') {
                sign = -1;
                i++;
            } else if (c == '+' || c == '＋') {
                i++;
            }
        }
        return sign * digits(text.substring(Math.min(i, text.length())));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 兜底：AI 不可用时，用本地台词 + 随机动作。 */
    public static Reply local(List<String> lines, List<String> sounds, java.util.Random random) {
        String line = lines.isEmpty() ? "……" : lines.get(random.nextInt(lines.size()));
        if (!sounds.isEmpty() && random.nextDouble() < 0.45) {
            return new Reply(line, "SOUND", sounds.get(random.nextInt(sounds.size())), 0);
        }
        return new Reply(line, "NONE", "", 0);
    }

    /** 不经过 AI 时，按生气档随机挑一个捉弄动作。 */
    public static Reply localPrank(int angerTier, java.util.Random random,
                                   boolean jumpscare, boolean lightning, boolean creeper) {
        List<String> options = new ArrayList<>();
        if (jumpscare) {
            options.add("JUMPSCARE");
        }
        if (angerTier >= 2 && lightning) {
            options.add("LIGHTNING");
        }
        if (angerTier >= 2 && creeper) {
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
