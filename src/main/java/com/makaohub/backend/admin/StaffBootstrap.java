package com.makaohub.backend.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("staff-bootstrap")
public class StaffBootstrap implements ApplicationRunner {
    private static final Logger log=LoggerFactory.getLogger(StaffBootstrap.class);
    private final StaffInvitationService invitations;
    private final String email;
    public StaffBootstrap(StaffInvitationService invitations,@Value("${STAFF_BOOTSTRAP_EMAIL:}") String email) {
        this.invitations=invitations; this.email=email;
    }
    @Override public void run(ApplicationArguments args) {
        switch (args.containsOption("resend-staff-invitation")
                ? invitations.resendBootstrap(email) : invitations.bootstrap(email)) {
            case INVITATION_SENT -> log.info("Staff invitation submitted to SMTP. Check the configured staff mailbox and accept the invitation; disable the staff-bootstrap profile after setup.");
            case INVITATION_RESENT -> log.info("Replacement staff invitation submitted to SMTP. Use the newest invitation email; the previous code is invalid. Disable the staff-bootstrap profile after setup.");
            case INVITATION_PENDING -> log.info("Staff bootstrap skipped: an unexpired invitation is pending. Use the existing invitation email; no new code was sent. Disable the staff-bootstrap profile for normal startup.");
            case STAFF_EXISTS -> log.info("Staff bootstrap skipped: a staff account already exists. Sign in with the existing staff account; no new code was sent. Disable the staff-bootstrap profile for normal startup.");
        }
    }
}
