package com.ebs.biocrop.config;

import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Seeds a single temporary sales agent account on startup if no sales agent exists yet,
 * so the team can test the Sales Agent login flow immediately and replace/update it later.
 */
@Component
public class SalesAgentBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SalesAgentBootstrapRunner.class);
    private static final String TEMP_EMAIL = "sales@ebskrishidukaan.com";
    private static final String TEMP_PASSWORD = "SalesTemp@2026";

    private final MongoTemplate mongoTemplate;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public SalesAgentBootstrapRunner(
            MongoTemplate mongoTemplate,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder) {
        this.mongoTemplate = mongoTemplate;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean anySalesAgentExists = mongoTemplate.exists(
                Query.query(Criteria.where("role").is(UserRole.ROLE_SALES_AGENT.name())
                        .and("isDeleted").ne(true)),
                User.class);
        if (anySalesAgentExists || userRepository.findByEmailIgnoreCase(TEMP_EMAIL).isPresent()) {
            return;
        }

        String phone = "+910000099901";
        int suffix = 1;
        while (userRepository.findByPhoneNumber(phone).isPresent()) {
            suffix++;
            phone = "+9100000999" + String.format("%02d", suffix);
        }

        User agent = new User(phone, UserRole.ROLE_SALES_AGENT);
        agent.setEmail(TEMP_EMAIL);
        agent.setFirstName("Sales");
        agent.setLastName("Agent");
        agent.setPasswordHash(passwordEncoder.encode(TEMP_PASSWORD));
        agent.setPermissions(Set.of(
                "DASHBOARD_VIEW",
                "ORDERS_VIEW",
                "ORDERS_CREATE",
                "USERS_CUSTOMERS_VIEW",
                "USERS_CUSTOMERS_MANAGE"
        ));
        agent.setCreatedAt(LocalDateTime.now());
        agent.setUpdatedAt(LocalDateTime.now());
        userRepository.save(agent);
        log.info("[BOOTSTRAP] Seeded temporary ROLE_SALES_AGENT account: {}", TEMP_EMAIL);
    }
}
