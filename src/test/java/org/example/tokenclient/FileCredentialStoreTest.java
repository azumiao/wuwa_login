package org.example.tokenclient;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FileCredentialStoreTest {
    @TempDir Path directory;

    @Test void reopenRestoresCredentialsAndLogoutPreservesDeviceIdentity() throws Exception {
        var store = new FileCredentialStore(directory.resolve("credentials"));
        String device = store.deviceId();
        var session = new AuthService.Session("138****5678", KuroAuthServiceTest.token("123", null));
        store.save(session);
        var reopened = new FileCredentialStore(directory.resolve("credentials"));
        assertEquals(session, reopened.load().orElseThrow());
        assertEquals(device, reopened.deviceId());
        reopened.clear();
        assertTrue(store.load().isEmpty());
        assertEquals(device, reopened.deviceId());
        assertFalse(session.toString().contains(session.token()));
    }

    @Test void corruptedOrOversizedCredentialsNeverBecomeASession() throws Exception {
        var store = new FileCredentialStore(directory);
        Files.writeString(directory.resolve("session.json"), "{broken");
        assertThrows(IOException.class, store::load);
        Files.writeString(directory.resolve("session.json"), "{\"account\":\"\",\"token\":\"\"}");
        assertThrows(IOException.class, store::load);
        Files.writeString(directory.resolve("session.json"), "x".repeat(16385));
        assertThrows(IOException.class, store::load);
    }

    @Test void credentialFilesAreOwnerOnlyOnPosix() throws Exception {
        var store = new FileCredentialStore(directory);
        store.deviceId();
        store.save(new AuthService.Session("账号", "test-token"));
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(directory.resolve("session.json")));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(directory.resolve("device-id")));
        }
        try (var files = Files.list(directory)) { assertEquals(2, files.count()); }
    }
}
