package otto.research;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One address per article. Outlets and feeds link the same page with
 * and without {@code www.}, over http and https, with tracking
 * parameters and page anchors, and each spelling would otherwise read
 * as a different article.
 */
final class CanonicalUrl {

    private static final Set<String> TRACKING_PARAMETERS = Set.of(
            "fbclid", "gclid", "mc_cid", "mc_eid", "ref", "cmpid", "src", "ocid");

    private CanonicalUrl() {
    }

    /**
     * Returns the canonical spelling of an article address: https, a
     * lowercase host without {@code www.} or {@code m.}, no anchor, no
     * trailing slash, and the query without tracking parameters, the
     * rest sorted.
     *
     * @return null for a blank address; an address that does not parse
     *         is kept as given, trimmed, rather than dropped
     */
    static String of(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmed = url.trim();
        try {
            URI uri = new URI(trimmed);
            if (uri.getHost() == null) {
                return trimmed;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT)
                    .replaceFirst("^(www|m)\\.", "");
            String path = uri.getRawPath() == null ? "" : uri.getRawPath()
                    .replaceFirst("/+$", "");
            String query = uri.getRawQuery() == null ? "" : Arrays.stream(
                            uri.getRawQuery().split("&"))
                    .filter(parameter -> !parameter.isEmpty())
                    .filter(parameter -> !tracking(parameter))
                    .sorted()
                    .collect(Collectors.joining("&"));
            return "https://" + host + path + (query.isEmpty() ? "" : "?" + query);
        } catch (URISyntaxException e) {
            return trimmed;
        }
    }

    private static boolean tracking(String parameter) {
        String name = parameter.split("=", 2)[0].toLowerCase(Locale.ROOT);
        return name.startsWith("utm_") || TRACKING_PARAMETERS.contains(name);
    }
}
