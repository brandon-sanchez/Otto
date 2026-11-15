package otto.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;

/**
 * Who holds a named lease, and until when. A lease lets one of several
 * processes that share a store do a piece of work the others then leave
 * alone; see {@link DocumentBackend#lease}.
 *
 * @param holder an id unique to one process
 * @param until when the lease ends if its holder does not release it
 *        first; a lease past it is free to take
 */
public record Lease(String holder, Instant until) {

    /** Returns true when this lease keeps a different holder out at {@code now}. */
    public boolean keepsOut(String other, Instant now) {
        return !holder.equals(other) && until.isAfter(now);
    }

    /** Returns the bytes a backend stores for this lease. */
    public byte[] bytes() {
        try {
            return OttoJson.MAPPER.writeValueAsBytes(this);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write a lease", e);
        }
    }

    /** Reads a lease back from its stored bytes. */
    public static Lease parse(byte[] bytes) {
        try {
            return OttoJson.MAPPER.readValue(bytes, Lease.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read a stored lease", e);
        }
    }
}
