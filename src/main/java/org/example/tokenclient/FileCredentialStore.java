package org.example.tokenclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.UUID;

/** Owner-only files on POSIX; Windows inherits the user's profile directory ACL. */
final class FileCredentialStore implements CredentialStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path directory;

    FileCredentialStore(Path directory) { this.directory = directory.toAbsolutePath(); }

    String deviceId() throws IOException {
        Path file = directory.resolve("device-id");
        if (Files.exists(file)) {
            String id = Files.readString(file).strip();
            if (TokenInput.validDeviceId(id)) return id;
        }
        String id = UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT);
        write(file, id);
        return id;
    }

    @Override public Optional<AuthService.Session> load() throws IOException {
        Path file = directory.resolve("session.json");
        if (!Files.exists(file)) return Optional.empty();
        if (Files.size(file) > 16384) throw new IOException("本地凭据损坏");
        try {
            var data = JSON.readTree(Files.readString(file));
            return Optional.of(new AuthService.Session(data.path("account").asText(), data.path("token").asText(),
                    data.path("deviceId").asText("")));
        } catch (RuntimeException error) {
            throw new IOException("本地凭据损坏");
        }
    }

    @Override public void save(AuthService.Session session) throws IOException {
        write(directory.resolve("session.json"), JSON.writeValueAsString(session));
    }

    @Override public void clear() throws IOException { Files.deleteIfExists(directory.resolve("session.json")); }

    private void write(Path file, String value) throws IOException {
        Files.createDirectories(directory);
        boolean posix = Files.getFileStore(directory).supportsFileAttributeView("posix");
        if (posix) Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        Path temp = posix
                ? Files.createTempFile(directory, ".credentials-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(directory, ".credentials-", ".tmp");
        try {
            Files.writeString(temp, value);
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
