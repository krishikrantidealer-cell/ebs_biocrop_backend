package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.service.EmailDeliveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;

@Service
public class SmtpEmailDeliveryService implements EmailDeliveryService {
    private static final Logger log = LoggerFactory.getLogger(SmtpEmailDeliveryService.class);
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final Environment environment;
    @Value("${app.mail.from:no-reply@localhost}") private String from;
    @Value("${app.mail.console-delivery.enabled:false}") private boolean consoleDelivery;

    public SmtpEmailDeliveryService(ObjectProvider<JavaMailSender> mailSenderProvider, Environment environment) {
        this.mailSenderProvider = mailSenderProvider;
        this.environment = environment;
    }

    @Override
    public void send(String to, String subject, String body) {
        if (environment.acceptsProfiles(Profiles.of("dev")) && consoleDelivery) {
            log.info("Development email to [{}], subject [{}]: {}", to, subject, body);
            return;
        }
        try {
            JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
            if (mailSender == null) throw new IllegalStateException("SMTP is not configured");
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
        } catch (Exception exception) {
            log.error("Email delivery failed for masked address [{}]", mask(to));
            throw new AppException("Email delivery is temporarily unavailable. Please retry shortly.", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private String mask(String email) {
        int at = email == null ? -1 : email.indexOf('@');
        return at <= 1 ? "***" : email.substring(0, 1) + "***" + email.substring(at);
    }
}
