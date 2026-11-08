package com.stm.middleware.journey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Closes idle sessions and removes personal data of sessions that never produced an account. */
@Component
public class SessionReaper {
    private static final Logger log = LoggerFactory.getLogger(SessionReaper.class);

    private final SessionRepository sessions;
    private final SessionService service;
    private final int idleSeconds;
    private final int retentionHours;

    public SessionReaper(SessionRepository sessions, SessionService service,
                         @Value("${stm.session.idle-seconds:600}") int idleSeconds,
                         @Value("${stm.session.abandoned-retention-hours:24}") int retentionHours) {
        this.sessions = sessions;
        this.service = service;
        this.idleSeconds = idleSeconds;
        this.retentionHours = retentionHours;
    }

    @Scheduled(fixedDelayString = "${stm.session.reap-interval-ms:30000}")
    public void run() {
        for (KioskSession s : sessions.findIdleInProgress(idleSeconds)) {
            try {
                service.expire(s);
            } catch (Exception e) {
                log.warn("Could not expire session {}: {}", s.id(), e.toString());
            }
        }
        for (KioskSession s : sessions.findScrubCandidates(retentionHours)) {
            try {
                service.scrub(s);
            } catch (Exception e) {
                log.warn("Could not scrub session {}: {}", s.id(), e.toString());
            }
        }
    }
}
