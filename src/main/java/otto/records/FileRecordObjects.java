package otto.records;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import otto.OttoProperties;

/**
 * The laptop's Decision Records: one file per key under
 * {@code <storageDir>/decision-records}. A file is written whole to a
 * temp file and then hard-linked into place. Creating a link fails if
 * the name exists, atomically, so of two writers racing for a key
 * exactly one wins, and a crash mid-write never leaves a torn record
 * that every retry would then disagree with.
 */
@Component
@Profile("!aws")
public class FileRecordObjects implements RecordObjects {

    private final Path dir;

    @Autowired
    public FileRecordObjects(OttoProperties properties) {
        this(Path.of(properties.storageDir(), "decision-records"));
    }

    public FileRecordObjects(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
    }

    @Override
    public boolean putIfAbsent(String key, byte[] bytes) {
        Path file = dir.resolve(key);
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "record-", ".tmp");
            try {
                Files.write(temp, bytes);
                Files.createLink(file, temp);
                return true;
            } catch (FileAlreadyExistsException e) {
                return false;
            } finally {
                Files.delete(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write Decision Record object " + key, e);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        try {
            return Optional.of(Files.readAllBytes(dir.resolve(key)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read Decision Record object " + key, e);
        }
    }

    @Override
    public void deleteAll(String prefix) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            List<Path> matching = files
                    .filter(Files::isRegularFile)
                    .filter(file -> keyOf(file).startsWith(prefix))
                    .toList();
            for (Path file : matching) {
                Files.delete(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete Decision Records under " + prefix, e);
        }
    }

    private String keyOf(Path file) {
        return dir.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
    }
}
