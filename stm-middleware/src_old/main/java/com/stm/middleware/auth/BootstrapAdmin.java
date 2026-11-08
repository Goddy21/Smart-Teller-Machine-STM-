package com.stm.middleware.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * First start with an empty portal_users table: creates "admin". If stm.portal-auth.bootstrap-admin-password is not set,
 * a random password is generated and printed ONCE below. The admin must change it at first sign-in, then create the
 * real accounts on the Users page.
 */
@Component
public class BootstrapAdmin implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);
    private final PortalAuthService auth;
    private final PortalAuthProperties props;

    public BootstrapAdmin(PortalAuthService auth, PortalAuthProperties props) {
        this.auth = auth;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        String given = props.bootstrapAdminPassword();
        String pw = auth.bootstrapAdmin(given);
        if (pw == null) return;
        if (given != null && !given.isBlank()) {
            log.warn("Portal: created the first account 'admin' with the configured bootstrap password. Change it at first sign-in.");
        } else {
            log.warn("""

                    =====================================================================
                      STM portal: first administrator account created
                        username: admin
                        password: {}
                      Shown once. You must change it at first sign-in.
                    =====================================================================""", pw);
        }
    }
}
