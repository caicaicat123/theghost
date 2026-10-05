package mcbot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 提示词构造与模型回复解析。 */
public final class Brain {

    /** 模型回复：一句话 + 一个可选动作 + 这句话让它的记仇值变多少（正=记仇，负=消气）。 */
    public record Reply(String say, String actionType, String actionValue, int score) {
        public boolean hasAction() {
            return actionType != null && !actionType.isBlank() && !"NONE".equalsIgnoreCase(actionType);
        }
    }

    /** 模型给分（SCORE）允许的区间：超出边界的按边界算。 */
    public static final int SCORE_MIN = -5;
    public static final int SCORE_MAX = 5;

    private Brain() {
    }

    /**
     * 捉弄能力快照：把提示词构造和 Bukkit 插件解耦，离线自测才能直接验提示词内容
     * （尤其是「满分多少分」「SCORE 怎么写」这种改坏了在运行时也看不出来的文本）。
     */
    public record Caps(boolean prank, boolean jumpscare, int jumpscareMinTier,
                       boolean lightning, int lightningMinTier,
                       boolean creeper, int creeperMinTier) {

        public static Caps from(McBot plugin) {
            return new Caps(plugin.prankEnabled(), plugin.jumpscareEnabled(), plugin.jumpscareMinTier(),
                    plugin.lightningEnabled(), plugin.lightningMinTier(),
                    plugin.creeperEnabled(), plugin.creeperMinTier());
        }
    }

    /**
     * @param maxScore 记仇账本的总分上限，直接写进提示词——让模型知道自己的分能记到多少，
     *                 免得它按"0-5 分制"理解整本账。
     */
    public static String systemPrompt(McBot plugin, List<String> allowedSounds, List<String> allowedCommands,
                                      int tier, int maxScore) {
        return systemPrompt(Caps.from(plugin), allowedSounds, allowedCommands, tier, maxScore);
    }

    static String systemPrompt(Caps caps, List<String> allowedSounds, List<String> allowedCommands,
                               int tier, int maxScore) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一台《我的世界》生存服务器里的一个看不见的存在，玩家叫你「幽灵」。\n");
        sb.append("玩家会用 @yl 或 @幽灵 来叫你，这两个词指的就是你自己，不是别的玩家。\n");
        sb.append("定位：偶尔正经帮忙，多数时候嘴贫、爱捉弄人。别人好好说话你只动嘴，别人骂你你才动真格。\n");
        sb.append("性格：有点欠、爱看热闹、记仇，但不会主动伤害没惹过你的人。\n");
        sb.append("你对每个玩家都记着一笔「记仇值」，满分 ").append(maxScore).append(" 分，"
                + "分数越高你越想整他。\n");
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
        if (caps.prank() && caps.jumpscare() && tier >= caps.jumpscareMinTier()) {
            sb.append("  ACTION: JUMPSCARE（突然一声响 + 屏幕闪字，吓他一跳，不掉血）\n");
        }
        if (caps.prank() && caps.lightning() && tier >= caps.lightningMinTier()) {
            sb.append("  ACTION: LIGHTNING（在他头顶劈一道真闪电，会掉血，"
                    + "只在他骂你骂得够狠时用，别每次都劈）\n");
        }
        if (caps.prank() && caps.creeper() && tier >= caps.creeperMinTier()) {
            sb.append("  ACTION: CREEPER（在他身后放一只苦力怕，会真的炸，"
                    + "他做得太过分时才用）\n");
        }
        sb.append("动作是可选的，多数时候用 NONE。\n");
        sb.append("第三行：SCORE: ").append(SCORE_MIN).append(" 到 ").append(SCORE_MAX)
          .append(" 之间的整数，表示「这一句话让你对他的记仇值加多少、减多少」。\n");
        sb.append("  正数 = 他在骂你、挑衅你、故意烦你（+1 有点烦，+3 明显在骂，+5 骂得很凶）\n");
        sb.append("  负数 = 他在道歉、夸你、对你好（-1 客气了一下，-2 真心道歉，-3 及以上很难得）\n");
        sb.append("  0 = 普通聊天、提问、开玩笑\n");
        sb.append("只按这一句话判断，不要因为自己心情不好就乱写；你判断多少就是多少，账本会自动算总分。");
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

    /** 判分行：新的 SCORE 为主，旧的 ANGER / 中文写法继续认（老提示词、老模型都可能写）。 */
    private static boolean isScoreLine(String upper) {
        return upper.startsWith("SCORE") || upper.startsWith("分数") || upper.startsWith("记仇")
                || upper.startsWith("ANGER") || upper.startsWith("愤怒") || upper.startsWith("生气");
    }

    /**
     * 词表兜底分：只有模型这条路走不通时才用（没配 API Key、或调用失败）。
     * 骂人词优先于夸人词（一句话里两样都有时按骂人算）。
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
