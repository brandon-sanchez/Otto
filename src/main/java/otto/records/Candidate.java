package otto.records;

/**
 * One player the run considered, and why he was kept in the pool or
 * left out of it.
 */
public record Candidate(String playerId, String name, boolean included, String reason) {
}
