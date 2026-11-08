package com.blueriver.brits.webrtc.entity;

public enum CallStatus {
  QUEUED, RINGING, ACCEPTED, CONNECTED, ENDED, ABANDONED, FAILED;

  public boolean isTerminal() { return this == ENDED || this == ABANDONED || this == FAILED; }
}
