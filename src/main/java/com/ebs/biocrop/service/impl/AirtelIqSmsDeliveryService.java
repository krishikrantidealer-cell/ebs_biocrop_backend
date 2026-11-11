package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.service.SmsDeliveryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AirtelIqSmsDeliveryService implements SmsDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(AirtelIqSmsDeliveryService.class);

    private final Environment environment;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${app.sms.airtel-iq.enabled:true}")
    private boolean enabled = true;

    @Value("${app.sms.airtel-iq.url:https://iqsms.airtel.in/api/v1/send-sms}")
    private String apiUrl = "https://iqsms.airtel.in/api/v1/send-sms";

    @Value("${app.sms.airtel-iq.customer-id:60bcc9cf-5bbe-40f5-a5ea-f8cc875bc1ea}")
    private String customerId = "60bcc9cf-5bbe-40f5-a5ea-f8cc875bc1ea";

    @Value("${app.sms.airtel-iq.username:3e6ecb5e_f47d_482a_bd24_226521e128cf}")
    private String username = "3e6ecb5e_f47d_482a_bd24_226521e128cf";

    @Value("${app.sms.airtel-iq.password:4lC0T2bLD4}")
    private String password = "4lC0T2bLD4";

    @Value("${app.sms.airtel-iq.source-address:KRORCS}")
    private String sourceAddress = "KRORCS";

    @Value("${app.sms.airtel-iq.dlt-template-id:1007069945111743122}")
    private String dltTemplateId = "1007069945111743122";

    @Value("${app.sms.airtel-iq.entity-id:1001392915934347308}")
    private String entityId = "1001392915934347308";

    @Value("${app.sms.airtel-iq.message-template:{otp} is your login OTP for Krishikranti Organics. This OTP is valid for 10 minutes. Do not share this OTP with anyone.}")
    private String messageTemplate = "{otp} is your login OTP for Krishikranti Organics. This OTP is valid for 10 minutes. Do not share this OTP with anyone.";

    @Value("${app.sms.airtel-iq.test-numbers:9999999999}")
    private String testNumbers = "9999999999";

    public AirtelIqSmsDeliveryService(Environment environment, ObjectMapper objectMapper) {
        this.environment = environment;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public boolean isConfigured() {
        return enabled
                && customerId != null && !customerId.isBlank()
                && username != null && !username.isBlank()
                && password != null && !password.isBlank()
                && sourceAddress != null && !sourceAddress.isBlank()
                && dltTemplateId != null && !dltTemplateId.isBlank()
                && entityId != null && !entityId.isBlank();
    }

    @Override
    public void sendOtp(String rawPhoneNumber, String otp) {
        String cleaned = rawPhoneNumber.replaceAll("\\D", "");
        String formattedPhone = cleaned.length() == 10 ? "91" + cleaned : cleaned;

        // Check if phone number is whitelisted for testing without hitting Airtel IQ balance
        List<String> whitelist = testNumbers != null
                ? Arrays.stream(testNumbers.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList()
                : List.of();

        if (whitelist.contains(cleaned) || whitelist.contains(formattedPhone)) {
            log.info("[SMS-WHITELIST] Bypassing Airtel IQ provider for test number [{}] with OTP [{}]",
                    maskPhone(rawPhoneNumber), otp);
            return;
        }

        if (!isConfigured()) {
            if (environment.acceptsProfiles(Profiles.of("dev"))) {
                log.warn("[SMS-DEV-STUB] Airtel IQ is not fully configured. Development OTP [{}] for phone [{}]",
                        otp, maskPhone(rawPhoneNumber));
                return;
            }
            throw new AppException("SMS service is not properly configured.", HttpStatus.SERVICE_UNAVAILABLE);
        }

        String message = messageTemplate.replace("{otp}", otp);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customerId", customerId);
        payload.put("destinationAddress", formattedPhone);
        payload.put("message", message);
        payload.put("sourceAddress", sourceAddress);
        payload.put("dltTemplateId", dltTemplateId);
        payload.put("entityId", entityId);
        payload.put("messageType", "SERVICE_IMPLICIT");
        payload.put("otp", true);

        try {
            String jsonPayload = objectMapper.writeValueAsString(payload);
            String credentials = username + ":" + password;
            String encodedAuth = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Basic " + encodedAuth)
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                    .build();

            log.info("Dispatching Airtel IQ OTP SMS to [{}] with template ID [{}]...", maskPhone(formattedPhone), dltTemplateId);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("Airtel IQ SMS successfully delivered to [{}]. Status: {}, Response: {}",
                        maskPhone(formattedPhone), response.statusCode(), response.body());
            } else {
                log.error("Airtel IQ SMS API returned non-2xx status code [{}] for phone [{}]. Response: {}",
                        response.statusCode(), maskPhone(formattedPhone), response.body());
                if (environment.acceptsProfiles(Profiles.of("dev"))) {
                    log.warn("[DEV-FALLBACK] Airtel IQ call failed with status {}, falling back to console delivery for OTP [{}]",
                            response.statusCode(), otp);
                    return;
                }
                throw new AppException("Failed to deliver OTP SMS via Airtel IQ. Please try again later.", HttpStatus.BAD_GATEWAY);
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.error("Exception occurred while sending SMS via Airtel IQ to [{}]: {}", maskPhone(formattedPhone), e.getMessage(), e);
            if (environment.acceptsProfiles(Profiles.of("dev"))) {
                log.warn("[DEV-FALLBACK] Exception connecting to Airtel IQ, fallback enabled in dev profile for OTP [{}]", otp);
                return;
            }
            throw new AppException("Failed to deliver OTP SMS. Please try again later.", HttpStatus.BAD_GATEWAY);
        }
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return "****";
        int len = phone.length();
        return phone.substring(0, 2) + "*".repeat(len - 4) + phone.substring(len - 2);
    }
}
