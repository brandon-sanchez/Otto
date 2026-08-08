package otto.directory;

public record DirectoryPlayer(
        String playerId,
        String fullName,
        String position,
        String team,
        PlayerHealth health) {
}
