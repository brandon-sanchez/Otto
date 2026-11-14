package otto.alerts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import otto.decisions.UsageDrop;
import otto.storage.JsonStore;

/**
 * What Otto keeps between Checks about role slips: the latest usage
 * scan, which slips are settled, the last advice sent about each
 * player, and the advice held for the next waiver board. One document,
 * written only by the Check.
 */
@Component
public class RoleSlipLedger {

    private static final String DOCUMENT = "role-slips";

    /**
     * How many settled slip keys the ledger remembers, newest last. A
     * key names a week or a story, so an evicted key no longer comes back.
     */
    static final int MAX_SETTLED = 200;

    private final JsonStore store;

    public RoleSlipLedger(JsonStore store) {
        this.store = store;
    }

    /**
     * Advice about a role slip that waits for the next waiver board
     * because sending it alone would gain the user nothing.
     *
     * @param runId the Decision Record the advice came from
     * @param message the checked text the board carries
     */
    public record Held(String runId, String playerId, String message) {
    }

    /**
     * The stored document.
     *
     * @param usageScannedAt when the usage drops were last computed; null before the first scan
     * @param usageDrops each rostered player's current drop, by Sleeper id
     * @param settled slip keys whose advice was decided and not sent alone
     * @param lastAdvice the signature of the last advice sent about each player
     * @param held the advice waiting for the next waiver board, oldest first
     */
    record Document(Instant usageScannedAt, Map<String, UsageDrop> usageDrops,
            List<String> settled, Map<String, String> lastAdvice, List<Held> held) {

        Document {
            usageDrops = usageDrops == null ? Map.of() : Map.copyOf(usageDrops);
            settled = settled == null ? List.of() : List.copyOf(settled);
            lastAdvice = lastAdvice == null ? Map.of() : Map.copyOf(lastAdvice);
            held = held == null ? List.of() : List.copyOf(held);
        }

        static final Document EMPTY = new Document(null, Map.of(), List.of(), Map.of(),
                List.of());
    }

    Document read() {
        return store.read(DOCUMENT, Document.class).orElse(Document.EMPTY);
    }

    void scanned(Instant at, Map<String, UsageDrop> drops) {
        Document held = read();
        store.write(DOCUMENT, new Document(at, drops, held.settled(), held.lastAdvice(),
                held.held()));
    }

    boolean settled(String key) {
        return read().settled().contains(key);
    }

    void settle(String key) {
        Document held = read();
        Set<String> settled = new LinkedHashSet<>(held.settled());
        settled.add(key);
        List<String> kept = new ArrayList<>(settled);
        store.write(DOCUMENT, new Document(held.usageScannedAt(), held.usageDrops(),
                kept.subList(Math.max(0, kept.size() - MAX_SETTLED), kept.size()),
                held.lastAdvice(), held.held()));
    }

    Optional<String> lastAdvice(String playerId) {
        return Optional.ofNullable(read().lastAdvice().get(playerId));
    }

    void advised(String playerId, String signature) {
        Document held = read();
        Map<String, String> last = new LinkedHashMap<>(held.lastAdvice());
        last.put(playerId, signature);
        store.write(DOCUMENT, new Document(held.usageScannedAt(), held.usageDrops(),
                held.settled(), last, held.held()));
    }

    void hold(Held advice) {
        Document document = read();
        List<Held> held = new ArrayList<>(document.held());
        held.removeIf(earlier -> earlier.playerId().equals(advice.playerId()));
        held.add(advice);
        store.write(DOCUMENT, new Document(document.usageScannedAt(), document.usageDrops(),
                document.settled(), document.lastAdvice(), held));
    }

    /** Returns the advice waiting for the next waiver board, oldest first. */
    public List<Held> heldForBoard() {
        return read().held();
    }

    /**
     * Forgets held advice once a board carrying it was delivered.
     *
     * <p>Side effects: one document write.
     *
     * @param runIds the run ids of the held advice the board carried
     */
    public void deliveredInBoard(List<String> runIds) {
        Document document = read();
        List<Held> held = document.held().stream()
                .filter(advice -> !runIds.contains(advice.runId()))
                .toList();
        store.write(DOCUMENT, new Document(document.usageScannedAt(), document.usageDrops(),
                document.settled(), document.lastAdvice(), held));
    }
}
