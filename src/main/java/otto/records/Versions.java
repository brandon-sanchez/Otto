package otto.records;

/**
 * The versions of everything that could have changed the answer.
 *
 * @param model the id of the model that wrote the answer, or
 *        {@code none} when no model answered
 */
public record Versions(String data, String code, String prompt, String model) {
}
