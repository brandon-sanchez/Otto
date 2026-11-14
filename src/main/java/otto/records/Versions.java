package otto.records;

/** The versions of everything that could have changed the answer. */
public record Versions(String data, String code, String prompt, String model) {
}
