package otto.research;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

/**
 * A team's news feed ({@code /rss/news}), read for the posts that carry
 * official injury, status, inactive and roster news. Only each post's
 * identity, link, time, title and tags are read; the article itself is
 * never kept.
 */
final class TeamFeed {

    private static final Pattern WEEK_TAG = Pattern.compile("\\(\\d{4}-((?:PRE|REG|POST)-\\d+)\\)");

    private static final Pattern TRANSACTION_TITLE = Pattern.compile("\\b(activates?|activated"
            + "|placed on|places|waives?|waived|signs?|signed|releases?|released|elevates?"
            + "|elevated|claims?|claimed|roster moves?|designated to return)\\b");

    private TeamFeed() {
    }

    /** What kind of official news a post carries. */
    enum Kind {

        /** A practice report or game status report; the injury page holds its facts. */
        INJURY_REPORT,

        /** The players declared inactive for a game; the post itself lists them. */
        INACTIVES,

        /** A roster move, which can change the injury page too. */
        TRANSACTION
    }

    /**
     * One post in the feed.
     *
     * @param guid the feed's id for the post, or its link when the feed gives none
     * @param link the article address as the feed gives it
     * @param keywords the post's tags, such as {@code Injury Report}, in feed order
     */
    record Post(String guid, String title, String link, Instant published,
            List<String> keywords) {

        Post {
            keywords = List.copyOf(keywords);
        }

        /** Identifies this version of the post: an edit that re-dates it is a new version. */
        String versionKey() {
            return guid + "@" + published;
        }

        /** Returns the kind of official news this post carries, empty for any other story. */
        Optional<Kind> kind() {
            String lowerTitle = title.toLowerCase(Locale.ROOT);
            if (tagged("inactives") || lowerTitle.contains("inactive")) {
                return Optional.of(Kind.INACTIVES);
            }
            if (tagged("injury report") || lowerTitle.contains("injury report")) {
                return Optional.of(Kind.INJURY_REPORT);
            }
            if (tagged("transactions") || TRANSACTION_TITLE.matcher(lowerTitle).find()) {
                return Optional.of(Kind.TRANSACTION);
            }
            return Optional.empty();
        }

        /** Returns the game week the post's tags name, such as {@code REG-10}. */
        Optional<String> week() {
            for (String keyword : keywords) {
                Matcher tag = WEEK_TAG.matcher(keyword);
                if (tag.find()) {
                    return Optional.of(tag.group(1));
                }
            }
            return Optional.empty();
        }

        private boolean tagged(String keyword) {
            return keywords.stream().anyMatch(tag -> tag.equalsIgnoreCase(keyword));
        }
    }

    /**
     * Returns the feed's posts in feed order.
     *
     * @throws LayoutChanged when the body is not an RSS channel, or a
     *         post lacks a title, a link or a readable publication time
     */
    static List<Post> parse(String xml) {
        Document feed = Jsoup.parse(xml, "", Parser.xmlParser());
        Element channel = feed.selectFirst("rss > channel");
        if (channel == null) {
            throw new LayoutChanged("feed has no RSS channel");
        }
        List<Post> posts = new ArrayList<>();
        for (Element item : channel.select("> item")) {
            String title = text(item, "title");
            String link = text(item, "link");
            String published = text(item, "pubDate");
            if (title.isEmpty() || link.isEmpty() || published.isEmpty()) {
                throw new LayoutChanged("feed post without a title, link or pubDate");
            }
            String guid = text(item, "guid");
            posts.add(new Post(guid.isEmpty() ? link : guid, title, link, instant(published),
                    keywords(text(item, "media|keywords"))));
        }
        return posts;
    }

    private static String text(Element item, String tag) {
        Element element = item.selectFirst("> " + tag);
        return element == null ? "" : element.text().trim();
    }

    private static Instant instant(String published) {
        try {
            return ZonedDateTime.parse(published, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant();
        } catch (DateTimeParseException e) {
            throw new LayoutChanged("feed pubDate \"%s\" is not an RFC 1123 time"
                    .formatted(published));
        }
    }

    private static List<String> keywords(String tags) {
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .toList();
    }
}
