package com.stm.middleware.backend.sim;

import com.stm.middleware.backend.Messaging;
import com.stm.middleware.stm.core.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** SIMULATED SMS gateway: nothing is sent; the text is logged and kept in sms_messages (portal: SMS outbox). */
@Service
@Profile("!prod")
public class SimulatedMessaging implements Messaging {
    private static final Logger log = LoggerFactory.getLogger(SimulatedMessaging.class);

    @Override
    public String sendSms(String mobile, String text) {
        String masked = mobile.length() > 6 ? mobile.substring(0, 5) + "•••" + mobile.substring(mobile.length() - 3) : mobile;
        log.info("SIMULATED SMS to {}: {}", masked, text);
        return Ids.next("sim");
    }
}
