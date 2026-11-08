package com.stm.middleware.stm.core;

/** A kiosk signed in with a device token (D-1). deviceId = the terminal code, e.g. STM-NBO-MOI-03. */
public record DevicePrincipal(long terminalId, String deviceId, String branchCode) {
}
