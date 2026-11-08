package com.blueriver.brits.webrtc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/** Wire format for the signaling socket. sdp, candidate and meta are opaque to the server and relayed untouched. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SignalingMessage(String type, String callId, JsonNode sdp, JsonNode candidate, JsonNode meta,
                               Boolean available, String reason, Integer position, String terminalId, String txId) {

  public static SignalingMessage simple(String type, String callId) {
    return new SignalingMessage(type, callId, null, null, null, null, null, null, null, null);
  }
  public static SignalingMessage withReason(String type, String callId, String reason) {
    return new SignalingMessage(type, callId, null, null, null, null, reason, null, null, null);
  }
  public static SignalingMessage queued(String callId, int position) {
    return new SignalingMessage("CALL_QUEUED", callId, null, null, null, null, null, position, null, null);
  }
  public static SignalingMessage incoming(String callId, String terminalId, String txId) {
    return new SignalingMessage("CALL_INCOMING", callId, null, null, null, null, null, null, terminalId, txId);
  }
  /** Only these fields are forwarded between peers; anything else a client adds is dropped. */
  public SignalingMessage relayCopy() {
    return new SignalingMessage(type, callId, sdp, candidate, meta, null, null, null, null, null);
  }
}
