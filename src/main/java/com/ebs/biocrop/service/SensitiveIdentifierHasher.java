package com.ebs.biocrop.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** HMACs low-entropy identifiers before they are included in Redis key names. */
@Service
public class SensitiveIdentifierHasher {
    private static final Logger log = LoggerFactory.getLogger(SensitiveIdentifierHasher.class);
    private static final String ALGORITHM = "HmacSHA256";

    private final Environment environment;
    private final String secret;

    public SensitiveIdentifierHasher(Environment environment,
                                     @Value("${app.redis.key-hmac-secret:}") String secret) {
        this.environment = environment;
        this.secret = secret == null ? "" : secret.trim();
    }

    @PostConstruct
    public void validateConfiguration() {
        if (secret.isEmpty()) {
            if (!environment.acceptsProfiles(Profiles.of("dev"))) {
                throw new IllegalStateException("REDIS_KEY_HMAC_SECRET must be configured outside the dev profile.");
            }
            log.warn("REDIS_KEY_HMAC_SECRET is not set; development Redis identifiers use unkeyed SHA-256.");
            return;
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("REDIS_KEY_HMAC_SECRET must contain at least 32 UTF-8 bytes.");
        }
    }

    /** Returns a versioned digest so key-format changes can be migrated deliberately. */
    public String hash(String value) {
        byte[] input = value.trim().getBytes(StandardCharsets.UTF_8);
        try {
            if (!secret.isEmpty()) {
                Mac mac = Mac.getInstance(ALGORITHM);
                mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
                return "h1:" + HexFormat.of().formatHex(mac.doFinal(input));
            }
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash sensitive Redis identifier", exception);
        }
    }
}
