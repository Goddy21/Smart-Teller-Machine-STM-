package com.stm.middleware.stm.core;

import java.time.OffsetDateTime;

public record StmSession(String id, long terminalId, String language, String status, String flow, String customerId,
                         OffsetDateTime startedAt, OffsetDateTime expiresAt, OffsetDateTime endedAt) {
    public boolean active() {
        return "ACTIVE".equals(status);
    }
}
