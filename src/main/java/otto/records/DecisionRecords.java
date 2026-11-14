package otto.records;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import otto.aws.SsmSecrets;
import otto.storage.OttoJson;

/**
 * Every Decision Record Otto keeps, and every rule about how it is kept.
 *
 * <p>Records are stored per user and per league, under Hive-style keys
 * so Athena can partition on them later:
 * {@code records/user=<userId>/league=<leagueId>/run=<runId>.json}.
 * Everything one user owns sits under one prefix, so deleting an
 * account is one prefix per object kind and cannot miss a league.
 *
 * <p>Copied article text expires sooner than the facts around it. It is
 * lifted out of the record and stored beside it under
 * {@code article-text/}, which the bucket expires on its own clock. A
 * record whose text has expired still reads with every fact, reference
 * and timestamp; only {@link Evidence#sourceText()} comes back empty.
 *
 * <p>Credentials are never stored. A model request can carry the key
 * it was sent with, and an error body can quote a bot token, so every
 * credential the app holds is replaced inside the record's string
 * values before anything is written. Property names, numbers and the
 * record's structure are never touched, so a credential short enough
 * to occur by chance cannot reshape a record that is then kept for
 * years. Unrelated chat needs no such pass: {@link DecisionRecord} has
 * no field that could hold it.
 *
 * <p>A record is immutable once written. A run that retries its save
 * under the same run id writes the same bytes and converges; a
 * different record under a run id already used is refused, never
 * written over. A retry under a new run id stores a second record, so
 * a caller that may be retried across invocations derives its run id
 * from a stable identity of the run.
 */
@Component
public class DecisionRecords {

    private static final String RECORDS = "records";
    private static final String ARTICLE_TEXT = "article-text";

    /** Ids become key segments, so nothing in one may reshape a key. */
    private static final Pattern KEY_SEGMENT = Pattern.compile("[A-Za-z0-9_-]+");

    private static final String REDACTED = "[redacted]";

    /** The api-key default when no key is configured; not a credential. */
    private static final String UNSET = "unset";

    /** The credentials the laptop holds; it reads them from its environment, not SSM. */
    private static final List<String> NAMED_CREDENTIALS = List.of(
            "otto.telegram.bot-token",
            "otto.telegram.webhook-secret",
            "spring.ai.openai.api-key");

    private static final TypeReference<DecisionRecord> RECORD_TYPE = new TypeReference<>() {
    };

    private static final TypeReference<Map<String, String>> ARTICLE_TEXT_TYPE =
            new TypeReference<>() {
            };

    private final RecordObjects objects;

    /** Longest first, so a credential that contains another is replaced whole. */
    private final List<String> credentials;

    /**
     * Every value read from SSM counts as a credential, so a secret
     * added to the parameter path later is kept out of records with no
     * change here.
     */
    @Autowired
    public DecisionRecords(RecordObjects objects, Environment environment) {
        this(objects, credentials(environment));
    }

    /** @param credentials every secret value the app holds; nulls and blanks are ignored */
    public DecisionRecords(RecordObjects objects, Collection<String> credentials) {
        this.objects = objects;
        this.credentials = credentials.stream()
                .filter(value -> value != null && !value.isBlank() && !value.equals(UNSET))
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    /**
     * Stores a record. The article text goes first: a record must never
     * be stored pointing at text that a failed save left unwritten.
     *
     * @throws IllegalStateException when this run id already holds a
     *         different record
     */
    public void save(DecisionRecord record) {
        Map<String, String> articleText = new LinkedHashMap<>();
        record.evidence().forEach(evidence -> evidence.sourceText()
                .ifPresent(text -> articleText.put(evidence.id(), text)));
        DecisionRecord facts = record.withEvidence(record.evidence().stream()
                .map(evidence -> evidence.withSourceText(Optional.empty()))
                .toList());

        if (!articleText.isEmpty()) {
            writeOnce(key(ARTICLE_TEXT, record.userId(), record.leagueId(), record.runId()),
                    articleText);
        }
        writeOnce(key(RECORDS, record.userId(), record.leagueId(), record.runId()), facts);
    }

    /**
     * One record, with whatever article text has not yet expired, or
     * empty when no record was stored under this run id.
     */
    public Optional<DecisionRecord> read(String userId, String leagueId, String runId) {
        return objects.get(key(RECORDS, userId, leagueId, runId)).map(bytes -> {
            DecisionRecord facts = parse(bytes, RECORD_TYPE);
            Map<String, String> articleText = objects
                    .get(key(ARTICLE_TEXT, userId, leagueId, runId))
                    .map(text -> parse(text, ARTICLE_TEXT_TYPE))
                    .orElse(Map.of());
            return facts.withEvidence(facts.evidence().stream()
                    .map(evidence -> evidence.withSourceText(
                            Optional.ofNullable(articleText.get(evidence.id()))))
                    .toList());
        });
    }

    /**
     * Deletes every record and every article text one user has, in every
     * league. The bucket holds objects under governance-mode retention,
     * so whatever calls this in the deployed assistant needs
     * {@code s3:ListBucketVersions}, {@code s3:DeleteObjectVersion} and
     * {@code s3:BypassGovernanceRetention} on the records bucket. No
     * function holds them today. The caller must stop new runs for the
     * user before deleting, or a save already in flight can recreate a
     * record.
     */
    public void deleteUser(String userId) {
        objects.deleteAll(userPrefix(RECORDS, userId));
        objects.deleteAll(userPrefix(ARTICLE_TEXT, userId));
    }

    private void writeOnce(String key, Object value) {
        byte[] bytes = json(redacted(OttoJson.MAPPER.valueToTree(value)));
        if (objects.putIfAbsent(key, bytes)) {
            return;
        }
        byte[] stored = objects.get(key).orElseThrow(() -> new IllegalStateException(
                "Decision Record object " + key + " was refused as present but cannot be read"));
        if (!Arrays.equals(stored, bytes)) {
            throw new IllegalStateException("Decision Record object " + key
                    + " already holds a different record; a run id is never rewritten");
        }
    }

    private JsonNode redacted(JsonNode node) {
        if (node.isTextual()) {
            String text = node.textValue();
            for (String credential : credentials) {
                text = text.replace(credential, REDACTED);
            }
            return TextNode.valueOf(text);
        }
        if (node.isObject()) {
            ObjectNode copy = OttoJson.MAPPER.createObjectNode();
            node.properties().forEach(property ->
                    copy.set(property.getKey(), redacted(property.getValue())));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = OttoJson.MAPPER.createArrayNode();
            node.forEach(element -> copy.add(redacted(element)));
            return copy;
        }
        return node;
    }

    private static List<String> credentials(Environment environment) {
        List<String> values = new ArrayList<>();
        NAMED_CREDENTIALS.forEach(name -> values.add(environment.getProperty(name)));
        if (environment instanceof ConfigurableEnvironment configurable
                && configurable.getPropertySources().get(SsmSecrets.SOURCE_NAME)
                        instanceof EnumerablePropertySource<?> ssm) {
            for (String name : ssm.getPropertyNames()) {
                values.add(Objects.toString(ssm.getProperty(name), null));
            }
        }
        return values;
    }

    private static String key(String root, String userId, String leagueId, String runId) {
        return userPrefix(root, userId) + "league=" + segment(leagueId)
                + "/run=" + segment(runId) + ".json";
    }

    /**
     * Ends in a slash, so one user's prefix never matches another user
     * whose id merely starts the same way.
     */
    private static String userPrefix(String root, String userId) {
        return root + "/user=" + segment(userId) + "/";
    }

    private static String segment(String id) {
        if (id == null || !KEY_SEGMENT.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "Not usable in a Decision Record key: " + id);
        }
        return id;
    }

    private static byte[] json(Object value) {
        try {
            return OttoJson.MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write a Decision Record", e);
        }
    }

    private static <T> T parse(byte[] json, TypeReference<T> type) {
        try {
            return OttoJson.MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read a stored Decision Record", e);
        }
    }
}
