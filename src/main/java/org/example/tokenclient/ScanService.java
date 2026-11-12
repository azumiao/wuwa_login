package org.example.tokenclient;

import java.util.List;
import java.util.concurrent.CompletionStage;

interface ScanService {
    enum Status { SUCCESS, SMS_REQUIRED, INVALID_TOKEN, EXPIRED, FAILURE }
    record Role(String id, String name) {
        @Override public String toString() { return name; }
    }
    record ScanResult(Status status, String message, List<Role> roles) {
        ScanResult(Status status, String message) { this(status, message, List.of()); }
    }
    CompletionStage<ScanResult> roles(AuthService.Session session, String qrCode);
    CompletionStage<ScanResult> scan(AuthService.Session session, String qrCode, String roleId, String sms);
    CompletionStage<AuthService.CodeResult> sendScanCode(AuthService.Session session, String proof);
}
