package otto.format;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes and reads the subset of HTML that Telegram's HTML parse mode
 * accepts. Code that lays out a message escapes every name it inserts
 * with {@link #escape} and bolds with {@link #bold}; whatever reaches the
 * chat passes through {@link #normalize} last, so model text, a stray
 * Markdown mark or an unescaped {@code &} can never show as symbols or
 * make Telegram refuse the message.
 *
 * @see <a href="https://core.telegram.org/bots/api#html-style">Telegram HTML style</a>
 */
public final class TelegramHtml {

    /** The tags Telegram renders, and so the only ones a sent message keeps. */
    private static final Set<String> SUPPORTED = Set.of(
            "b", "i", "u", "s", "code", "pre", "a", "blockquote");

    /** Standard tags Telegram also accepts under another name. */
    private static final Map<String, String> ALIASES = Map.of(
            "strong", "b", "em", "i", "ins", "u", "strike", "s", "del", "s");

    /** Layout tags a model may write that carry no meaning in a chat message. */
    private static final Set<String> DROPPED = Set.of(
            "p", "div", "span", "ul", "ol", "li", "h1", "h2", "h3", "h4", "h5", "h6");

    private static final Pattern TAG = Pattern.compile(
            "<(/?)([a-zA-Z][a-zA-Z0-9]*)((?:\\s[^<>\\n]*)?)/?>");
    private static final Pattern ANY_TAG = Pattern.compile("<[^<>]*>");
    private static final Pattern ENTITY = Pattern.compile(
            "&(?:lt|gt|amp|quot|#\\d{1,7}|#x[0-9a-fA-F]{1,6});");
    private static final Pattern HREF = Pattern.compile("\\s+href=\"([^\"<>]*)\"\\s*");
    private static final Pattern CODE_LANGUAGE = Pattern.compile(
            "\\s+class=\"language-[\\w+#-]+\"\\s*");
    private static final Pattern EXPANDABLE = Pattern.compile("\\s+expandable\\s*");

    private static final Pattern CODE_FENCE = Pattern.compile("(?m)^[ \\t]*```[\\w+-]*[ \\t]*\\n?");
    private static final Pattern HEADING_MARKS = Pattern.compile("(?m)^#{1,6}[ \\t]+");
    private static final Pattern STAR_BULLET = Pattern.compile("(?m)^([ \\t]*)\\*[ \\t]+");
    private static final Pattern STAR_BOLD = Pattern.compile("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*");
    private static final Pattern UNDERSCORE_BOLD = Pattern.compile(
            "(?<!\\w)__(?=\\S)(.+?)(?<=\\S)__(?!\\w)");
    private static final Pattern STRAY_EMPHASIS = Pattern.compile(
            "\\*\\*|(?<!\\w)__|__(?!\\w)");
    private static final Pattern BACKTICKS = Pattern.compile("`([^`\\n]+)`");

    /**
     * Emoji, arrows and the joiners that build them, and nothing that can
     * carry a fact such as a degree sign. Facts are compared without
     * them, so a coloured dot never counts as part of a name or number.
     */
    private static final Pattern EMOJI = Pattern.compile("[\\x{1F000}-\\x{1FAFF}"
            + "\\x{2190}-\\x{21FF}\\x{2300}-\\x{23FF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}"
            + "\\x{FE0F}\\x{200D}]+");
    private static final Pattern LEADING_SPACES = Pattern.compile("(?m)^[ \\t]+");
    private static final Pattern REPEATED_SPACES = Pattern.compile(" {2,}");

    private TelegramHtml() {
    }

    /** Returns plain text with the three characters HTML reserves escaped. */
    public static String escape(String plain) {
        return plain.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Returns plain text, escaped, in bold. */
    public static String bold(String plain) {
        return "<b>" + escape(plain) + "</b>";
    }

    /**
     * Returns already-formatted text inside a quote the reader taps to
     * open, so long reasoning stays out of the way.
     */
    public static String expandable(String html) {
        return "<blockquote expandable>" + html + "</blockquote>";
    }

    /**
     * Returns text as HTML Telegram accepts. Markdown bold
     * ({@code **x**}, {@code __x__}) becomes {@code <b>x</b>}; heading
     * marks, code fences and backticks go; a leading {@code * } bullet
     * becomes {@code • }. Supported tags are kept and balanced, standard
     * aliases such as {@code <strong>} are renamed, layout tags such as
     * {@code <p>} are dropped, and every other {@code <}, {@code >} or
     * {@code &} that is not an entity is escaped. Normalising its own
     * output changes nothing.
     */
    public static String normalize(String text) {
        return sanitized(withoutMarkdown(text.replace("\r\n", "\n")));
    }

    /**
     * Returns what the reader sees: the text with its tags removed and
     * its entities decoded. Sending this without a parse mode shows the
     * same words as the formatted message.
     */
    public static String visible(String html) {
        String text = ANY_TAG.matcher(html).replaceAll("");
        Matcher entity = ENTITY.matcher(text);
        StringBuilder decoded = new StringBuilder();
        while (entity.find()) {
            entity.appendReplacement(decoded, Matcher.quoteReplacement(decode(entity.group())));
        }
        entity.appendTail(decoded);
        return decoded.toString();
    }

    /**
     * Returns the words of a message as a fact check reads them: the
     * {@link #visible} text of its normalised form, without emoji.
     */
    public static String words(String text) {
        String words = EMOJI.matcher(visible(normalize(text))).replaceAll(" ");
        words = LEADING_SPACES.matcher(words).replaceAll("");
        return REPEATED_SPACES.matcher(words).replaceAll(" ");
    }

    private static String withoutMarkdown(String text) {
        String plain = CODE_FENCE.matcher(text).replaceAll("");
        plain = HEADING_MARKS.matcher(plain).replaceAll("");
        plain = STAR_BULLET.matcher(plain).replaceAll("$1• ");
        plain = STAR_BOLD.matcher(plain).replaceAll("<b>$1</b>");
        plain = UNDERSCORE_BOLD.matcher(plain).replaceAll("<b>$1</b>");
        plain = STRAY_EMPHASIS.matcher(plain).replaceAll("");
        return BACKTICKS.matcher(plain).replaceAll("$1");
    }

    /** An opening tag still waiting for its close: its name and its text. */
    private record Open(String name, String tag) {
    }

    private static String sanitized(String text) {
        StringBuilder html = new StringBuilder(text.length());
        Deque<Open> open = new ArrayDeque<>();
        Matcher tag = TAG.matcher(text);
        Matcher entity = ENTITY.matcher(text);
        int at = 0;
        while (at < text.length()) {
            char next = text.charAt(at);
            if (next == '<' && tag.region(at, text.length()).lookingAt()) {
                if (!keepTag(tag, html, open)) {
                    html.append(escape(tag.group()));
                }
                at = tag.end();
            } else if (next == '&' && entity.region(at, text.length()).lookingAt()
                    && !decode(entity.group()).isEmpty()) {
                html.append(entity.group());
                at = entity.end();
            } else {
                html.append(switch (next) {
                    case '<' -> "&lt;";
                    case '>' -> "&gt;";
                    case '&' -> "&amp;";
                    default -> String.valueOf(next);
                });
                at++;
            }
        }
        while (!open.isEmpty()) {
            html.append("</").append(open.pop().name()).append('>');
        }
        return html.toString();
    }

    /**
     * Writes one tag the message keeps, or drops it, and tracks what is
     * open. A close with no matching open tag is dropped; one that
     * matches an outer tag first closes the tags opened inside it.
     *
     * @return false when the tag is one to show as text instead
     */
    private static boolean keepTag(Matcher tag, StringBuilder html, Deque<Open> open) {
        String raw = tag.group(2).toLowerCase();
        String name = ALIASES.getOrDefault(raw, raw);
        if (raw.equals("br")) {
            html.append('\n');
            return true;
        }
        if (DROPPED.contains(name)) {
            return true;
        }
        if (tag.group().endsWith("/>")) {
            return true;
        }
        if (!SUPPORTED.contains(name)) {
            return false;
        }
        boolean closing = !tag.group(1).isEmpty();
        if (closing) {
            if (open.stream().anyMatch(each -> each.name().equals(name))) {
                Open closed;
                do {
                    closed = open.pop();
                    html.append("</").append(closed.name()).append('>');
                } while (!closed.name().equals(name));
            }
            return true;
        }
        String attributes = attributes(name, tag.group(3));
        if (attributes == null) {
            return false;
        }
        String opening = "<" + name + attributes + ">";
        open.push(new Open(name, opening));
        html.append(opening);
        return true;
    }

    /** The tag's attributes as Telegram accepts them, or null for ones it refuses. */
    private static String attributes(String name, String written) {
        if (written.isBlank()) {
            return name.equals("a") ? null : "";
        }
        return switch (name) {
            case "a" -> {
                Matcher href = HREF.matcher(written);
                yield href.matches() ? " href=\"" + href.group(1) + "\"" : null;
            }
            case "blockquote" -> EXPANDABLE.matcher(written).matches() ? " expandable" : null;
            case "code" -> CODE_LANGUAGE.matcher(written).matches() ? written.stripTrailing() : null;
            default -> null;
        };
    }

    private static String decode(String entity) {
        return switch (entity) {
            case "&lt;" -> "<";
            case "&gt;" -> ">";
            case "&amp;" -> "&";
            case "&quot;" -> "\"";
            default -> {
                boolean hex = entity.startsWith("&#x");
                int codePoint = Integer.parseInt(
                        entity.substring(hex ? 3 : 2, entity.length() - 1), hex ? 16 : 10);
                boolean character = Character.isValidCodePoint(codePoint) && codePoint != 0
                        && (codePoint < Character.MIN_SURROGATE
                                || codePoint > Character.MAX_SURROGATE);
                yield character ? Character.toString(codePoint) : "";
            }
        };
    }
}
