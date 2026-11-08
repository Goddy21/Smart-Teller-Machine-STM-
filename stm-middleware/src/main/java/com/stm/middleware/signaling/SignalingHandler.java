package com.stm.middleware.signaling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stm.middleware.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebRTC signaling broker + FIFO call dispatcher. Media never touches this service (peer-to-peer / TURN / SFU).
 *
 * Kiosk  -> server : CALL_REQUEST{sessionId?} | CALL_CANCEL | OFFER | ANSWER | ICE | HANGUP
 * Teller -> server : READY | OFFER | ANSWER | ICE | HANGUP
 * Server -> clients: QUEUED{position} | CALL_ASSIGNED{callId,...,sessionId?} | CALL_ENDED | COMMAND{command}
 *                    | SESSION_UPDATED{sessionId,step,status} | ERROR{code}
 * OFFER/ANSWER/ICE are relayed verbatim to the call peer.
 * The queue is in-memory: run ONE instance, or move the queue to Redis/DB before scaling out.
 */
@Component
public class SignalingHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(SignalingHandler.class);

    private final ObjectMapper mapper;
    private final AuditService audit;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> kioskSessionByCode = new ConcurrentHashMap<>();
    private final Map<String, String> peers = new ConcurrentHashMap<>();
    private final Map<String, String> journeySessionByKiosk = new ConcurrentHashMap<>();
    private final Deque<String> queue = new ArrayDeque<>();          // guarded by this
    private final Set<String> idleTellers = new LinkedHashSet<>();   // guarded by this

    public SignalingHandler(ObjectMapper mapper, AuditService audit) {
        this.mapper = mapper;
        this.audit = audit;
    }

    private record Assignment(String kioskSid, String tellerSid, String callId) {}

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) {
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, 5000, 64 * 1024);
        sessions.put(raw.getId(), session);
        if ("KIOSK".equals(role(raw))) kioskSessionByCode.put(name(raw), raw.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) throws Exception {
        JsonNode node = mapper.readTree(message.getPayload());
        String type = node.path("type").asText("");
        String sid = raw.getId();
        boolean kiosk = "KIOSK".equals(role(raw));

        switch (type) {
            case "CALL_REQUEST" -> {
                if (kiosk) requestCall(sid, node.hasNonNull("sessionId") ? node.get("sessionId").asText() : null);
                else error(sid, "KIOSK_ONLY");
            }
            case "CALL_CANCEL" -> { if (kiosk) cancelCall(sid); else error(sid, "KIOSK_ONLY"); }
            case "READY" -> { if (!kiosk) ready(sid); else error(sid, "TELLER_ONLY"); }
            case "OFFER", "ANSWER", "ICE" -> {
                String peer = peers.get(sid);
                if (peer == null) error(sid, "NOT_IN_CALL"); else send(peer, message.getPayload());
            }
            case "HANGUP" -> endCall(sid);
            default -> error(sid, "UNKNOWN_TYPE");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        String sid = raw.getId();
        sessions.remove(sid);
        if ("KIOSK".equals(role(raw))) kioskSessionByCode.remove(name(raw), sid);
        synchronized (this) {
            queue.remove(sid);
            idleTellers.remove(sid);
        }
        endCall(sid);
        journeySessionByKiosk.remove(sid);
        broadcastQueuePositions();
    }

    /** Remote commands to a kiosk (REBOOT, LOCK, UNLOCK, REFRESH_CONFIG). */
    public boolean sendCommand(String terminalCode, String command) {
        return push(terminalCode, "COMMAND", Map.of("command", command));
    }

    /** Pushes an event to a connected kiosk, e.g. SESSION_UPDATED after a teller decision. */
    public boolean push(String terminalCode, String type, Map<String, Object> data) {
        String sid = kioskSessionByCode.get(terminalCode);
        if (sid == null || !sessions.containsKey(sid)) return false;
        ObjectNode n = mapper.createObjectNode().put("type", type);
        data.forEach((k, v) -> n.putPOJO(k, v));
        send(sid, n.toString());
        return true;
    }

    // ----------------------------------------------------------------- dispatch

    private void requestCall(String kioskSid, String journeySessionId) {
        List<Assignment> assigned;
        synchronized (this) {
            if (peers.containsKey(kioskSid) || queue.contains(kioskSid)) { error(kioskSid, "ALREADY_QUEUED_OR_IN_CALL"); return; }
            if (journeySessionId != null) journeySessionByKiosk.put(kioskSid, journeySessionId);
            queue.addLast(kioskSid);
            assigned = dispatch();
        }
        afterDispatch(assigned);
    }

    private void cancelCall(String kioskSid) {
        synchronized (this) { queue.remove(kioskSid); }
        endCall(kioskSid);
        broadcastQueuePositions();
    }

    private void ready(String tellerSid) {
        List<Assignment> assigned;
        synchronized (this) {
            if (peers.containsKey(tellerSid)) { error(tellerSid, "ALREADY_IN_CALL"); return; }
            idleTellers.add(tellerSid);
            assigned = dispatch();
        }
        afterDispatch(assigned);
    }

    /** Pairs queued kiosks (FIFO) with idle tellers. Caller must hold the lock. */
    private List<Assignment> dispatch() {
        List<Assignment> out = new ArrayList<>();
        while (!queue.isEmpty() && !idleTellers.isEmpty()) {
            String k = queue.pollFirst();
            String t = idleTellers.iterator().next();
            idleTellers.remove(t);
            peers.put(k, t);
            peers.put(t, k);
            out.add(new Assignment(k, t, UUID.randomUUID().toString()));
        }
        return out;
    }

    private void afterDispatch(List<Assignment> assigned) {
        for (Assignment a : assigned) {
            WebSocketSession k = sessions.get(a.kioskSid());
            WebSocketSession t = sessions.get(a.tellerSid());
            String kioskCode = k != null ? name(k) : "?";
            String tellerId = t != null ? name(t) : "?";
            String journeySession = journeySessionByKiosk.get(a.kioskSid());

            ObjectNode toKiosk = mapper.createObjectNode().put("type", "CALL_ASSIGNED")
                    .put("callId", a.callId()).put("tellerId", tellerId);
            ObjectNode toTeller = mapper.createObjectNode().put("type", "CALL_ASSIGNED")
                    .put("callId", a.callId()).put("terminalCode", kioskCode);
            if (journeySession != null) toTeller.put("sessionId", journeySession);
            send(a.kioskSid(), toKiosk.toString());
            send(a.tellerSid(), toTeller.toString());

            try {
                Long terminalId = k != null ? (Long) k.getAttributes().get("terminalId") : null;
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("terminalCode", kioskCode);
                payload.put("tellerId", tellerId);
                if (journeySession != null) payload.put("sessionId", journeySession);
                audit.record("CALL_ASSIGNED", a.callId(), terminalId, payload);
            } catch (Exception e) {
                log.warn("Could not audit call {}: {}", a.callId(), e.toString());
            }
        }
        broadcastQueuePositions();
    }

    private void endCall(String sid) {
        String peer = peers.remove(sid);
        if (peer != null) {
            peers.remove(peer);
            send(peer, mapper.createObjectNode().put("type", "CALL_ENDED").toString());
        }
    }

    private void broadcastQueuePositions() {
        List<String> snapshot;
        synchronized (this) { snapshot = new ArrayList<>(queue); }
        for (int i = 0; i < snapshot.size(); i++) {
            send(snapshot.get(i), mapper.createObjectNode().put("type", "QUEUED").put("position", i + 1).toString());
        }
    }

    // ----------------------------------------------------------------- helpers

    private void error(String sid, String code) {
        send(sid, mapper.createObjectNode().put("type", "ERROR").put("code", code).toString());
    }

    private void send(String sid, String json) {
        WebSocketSession s = sessions.get(sid);
        if (s == null || !s.isOpen()) return;
        try {
            s.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.warn("WS send failed to {}: {}", sid, e.toString());
        }
    }

    private static String role(WebSocketSession s) { return (String) s.getAttributes().get("role"); }
    private static String name(WebSocketSession s) { return (String) s.getAttributes().get("name"); }
}
