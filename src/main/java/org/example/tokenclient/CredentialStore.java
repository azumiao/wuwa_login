package org.example.tokenclient;

import java.io.IOException;
import java.util.Optional;

interface CredentialStore {
    Optional<AuthService.Session> load() throws IOException;
    void save(AuthService.Session session) throws IOException;
    void clear() throws IOException;

    CredentialStore NONE = new CredentialStore() {
        public Optional<AuthService.Session> load() { return Optional.empty(); }
        public void save(AuthService.Session session) {}
        public void clear() {}
    };
}
