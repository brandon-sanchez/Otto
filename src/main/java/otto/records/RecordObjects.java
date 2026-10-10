package otto.records;

import java.util.Optional;

/**
 * Where the bytes of Decision Records live. {@link DecisionRecords}
 * owns every rule about what is stored and under which key; an
 * implementation only keeps bytes - a directory on the laptop, a
 * bucket in the deployed assistant.
 */
public interface RecordObjects {

    /**
     * Stores the bytes under a key that holds nothing yet.
     *
     * @return false, and nothing written, when the key already holds an
     *         object
     */
    boolean putIfAbsent(String key, byte[] bytes);

    /** The bytes under a key, or empty when it holds nothing. */
    Optional<byte[]> get(String key);

    /** Removes every object, and every stored version of one, under a key prefix. */
    void deleteAll(String prefix);
}
