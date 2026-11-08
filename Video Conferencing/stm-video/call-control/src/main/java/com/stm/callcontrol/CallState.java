package com.stm.callcontrol;

/** The one state machine the dispatcher, orchestrator and kiosk FSM all agree on. */
public enum CallState {
  REQUESTED, QUEUED, RINGING, CONNECTED, ON_HOLD, ENDED, ABANDONED;

  public boolean canGoTo(CallState n) {
    return switch (this) {
      case REQUESTED -> n == QUEUED;
      case QUEUED -> n == RINGING || n == ABANDONED;
      case RINGING -> n == CONNECTED || n == QUEUED || n == ABANDONED;
      case CONNECTED -> n == ON_HOLD || n == ENDED;
      case ON_HOLD -> n == CONNECTED || n == ENDED;
      case ENDED, ABANDONED -> false;
    };
  }
}
