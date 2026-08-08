package otto.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

import com.fasterxml.jackson.databind.JavaType;
import org.springframework.stereotype.Component;

import otto.OttoProperties;

/**
 * Local document storage: one JSON file per named document under the
 * configured storage directory. Writes go to a temp file first and
 * move into place, so a crash mid-write never leaves a torn document.
 * This class is the storage seam: a cloud object-store adapter can
 * replace it without touching callers.
 */
@Component
public class JsonStore {

    private final Path dir;

    public JsonStore(OttoProperties properties) {
        this.dir = Path.of(properties.storageDir());
    }

    public <T> Optional<T> read(String name, Class<T> type) {
        return read(name, OttoJson.MAPPER.constructType(type));
    }

    public <T> Optional<T> read(String name, JavaType type) {
        Path file = fileFor(name);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(OttoJson.MAPPER.readValue(file.toFile(), type));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read stored document " + name, e);
        }
    }

    public void write(String name, Object value) {
        Path file = fileFor(name);
        try {
            Files.createDirectories(dir);
            Path temp = Files.createTempFile(dir, name, ".tmp");
            OttoJson.MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), value);
            try {
                Files.move(temp, file,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write stored document " + name, e);
        }
    }

    private Path fileFor(String name) {
        return dir.resolve(name + ".json");
    }
}
