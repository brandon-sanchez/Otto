package otto.sleeper;

import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The statuses Sleeper's transaction vocabulary carries, and which of
 * them Otto reads as something that happened.
 *
 * Only {@link #COMPLETE} is news. Every other status names a
 * transaction that either has not happened yet or never will, and each
 * says below why it is refused - so the rule is a stated one rather
 * than something inferred from the shape of a string comparison.
 *
 * A scan of 284,671 live transaction rows saw only {@code complete} and
 * {@code failed} on the public endpoint; {@code pending} exists in
 * Sleeper's product but is not served there. The rest are recorded here
 * anyway, because the vocabulary is Sleeper's and a value it stops
 * withholding must land on a stated rule rather than on an accident.
 */
public enum TransactionStatus {

    /** It happened. This is the only status the Alert path reads. */
    COMPLETE(true),

    /** An offer nobody has accepted, so no roster has changed. */
    PROPOSED(false),

    /**
     * Accepted and inside the commissioner review window. Sleeper's app
     * shows this state; the documented endpoint does not serve it.
     */
    PENDING(false),

    /** Withdrawn before it completed, so no roster has changed. */
    CANCELLED(false),

    /** A waiver claim that lost, or a move the league refused. It never happened. */
    FAILED(false),

    /** Turned down by the other side or by the league, so nothing moved. */
    REJECTED(false);

    private final boolean accepted;

    TransactionStatus(boolean accepted) {
        this.accepted = accepted;
    }

    /** True when this status means the transaction actually happened. */
    public boolean accepted() {
        return accepted;
    }

    /**
     * The status Sleeper published, or empty when it published a value
     * this vocabulary does not hold. The caller drops an unknown status
     * and says so out loud: a new Sleeper value must be findable rather
     * than read as either news or silence.
     */
    public static Optional<TransactionStatus> of(String status) {
        if (status == null) {
            return Optional.empty();
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        return Stream.of(values())
                .filter(candidate -> candidate.name().toLowerCase(Locale.ROOT).equals(normalized))
                .findFirst();
    }
}
