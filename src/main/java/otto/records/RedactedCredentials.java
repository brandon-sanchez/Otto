package otto.records;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Every credential the app holds, which {@link DecisionRecords} replaces
 * inside a record's string values before it is written.
 *
 * @param values the credentials, longest first so one that contains
 *        another is replaced whole; nulls, blanks and the "unset"
 *        placeholder are dropped on construction
 */
public record RedactedCredentials(List<String> values) {

    private static final String REDACTED = "[redacted]";

    /** The api-key default when no key is configured; not a credential. */
    private static final String UNSET = "unset";

    public RedactedCredentials {
        values = values.stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank() && !value.equals(UNSET))
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    /** Returns the text with every credential replaced by a marker. */
    String redact(String text) {
        String redacted = text;
        for (String credential : values) {
            redacted = redacted.replace(credential, REDACTED);
        }
        return redacted;
    }
}
