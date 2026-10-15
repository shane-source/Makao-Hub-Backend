package com.makaohub.backend.auth.service;

import com.makaohub.backend.auth.config.EmailOtpProperties;
import com.makaohub.backend.auth.domain.AuthOtpChallengePurpose;
import com.makaohub.backend.auth.exception.OtpProviderException;
import org.springframework.boot.mail.autoconfigure.MailProperties;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailOtpClient {
    private final JavaMailSender sender;
    private final String from;

    public EmailOtpClient(JavaMailSender sender, MailProperties mail, EmailOtpProperties otp) {
        if (mail.getHost() == null || !mail.getHost().matches("[A-Za-z0-9.-]+")
                || mail.getPort() == null || mail.getPort() < 1 || mail.getPort() > 65535
                || !EmailOtpProperties.validEmail(mail.getUsername())
                || mail.getPassword() == null || mail.getPassword().isBlank()
                || mail.getPassword().contains("\r") || mail.getPassword().contains("\n")
                || !"smtp".equals(mail.getProtocol()) || mail.getJndiName() != null) {
            throw new IllegalArgumentException("Configure SMTP_HOST, SMTP_PORT, SMTP_USERNAME and SMTP_PASSWORD for authenticated STARTTLS email.");
        }
        for (String setting : new String[]{"mail.smtp.auth", "mail.smtp.starttls.enable",
                "mail.smtp.starttls.required", "mail.smtp.ssl.checkserveridentity"}) {
            if (!"true".equals(mail.getProperties().get(setting))) {
                throw new IllegalArgumentException("SMTP authentication and verified STARTTLS must remain enabled.");
            }
        }
        if (!"false".equals(mail.getProperties().get("mail.debug"))
                || mail.getProperties().containsKey("mail.smtp.ssl.trust")) {
            throw new IllegalArgumentException("SMTP debug and custom certificate trust are not allowed for OTP email.");
        }
        this.sender = sender;
        this.from = otp.fromEmail();
    }

    public void sendCode(String email, String code, AuthOtpChallengePurpose purpose) {
        if (!EmailOtpProperties.validEmail(email) || code == null || !code.matches("[0-9]{6}") || purpose == null) {
            throw new IllegalArgumentException("Invalid OTP email request.");
        }
        String action = switch (purpose) {
            case REGISTRATION -> "complete your registration";
            case LOGIN -> "sign in";
            case PASSWORD_RESET -> "reset your password";
        };
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("Makao Hub verification code");
        message.setText("Your Makao Hub code to " + action + " is " + code
                + ".\nIt expires in five minutes and can be used once. Do not share it."
                + "\nIf you did not request this code, ignore this email.");
        try {
            // No automatic retry: a timeout may hide SMTP acceptance.
            sender.send(message);
        } catch (MailAuthenticationException failure) {
            throw new OtpProviderException(OtpProviderException.Failure.CONFIGURATION);
        } catch (MailException failure) {
            // Provider exceptions may contain recipient addresses, credentials or message content.
            throw new OtpProviderException(OtpProviderException.Failure.UNAVAILABLE);
        }
    }
}
