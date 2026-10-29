package com.ebs.biocrop.config;

import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.UserRole;
import org.bson.Document;
import org.springframework.data.mongodb.core.mapping.event.BeforeSaveCallback;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persists role-specific user schema fields with stable defaults and explicit seller payout subfields. */
@Component
public class UserRoleSchemaWriteCallback implements BeforeSaveCallback<User> {

    @Override
    public User onBeforeSave(User user, Document document, String collection) {
        if (!"users".equals(collection)) return user;
        if (user.getRole() == UserRole.ROLE_SELLER) {
            putMissing(document, "passwordHash");
            putMissing(document, "passwordResetTokenHash");
            putMissing(document, "passwordResetExpiresAt");
            putMissing(document, "phoneNumber");
            putMissing(document, "email");
            putMissing(document, "firstName");
            putMissing(document, "lastName");
            putMissing(document, "shopName");
            putMissing(document, "role");
            putMissing(document, "status");
            putMissing(document, "isBlocked");
            putMissing(document, "isDeleted");
            putMissing(document, "gstNumber");
            putMissing(document, "licenceImage");
            putMissing(document, "shopImage");
            putMissing(document, "kycStatus");
            putMissing(document, "isKycComplete");
            putMissing(document, "isVerified");
            putMissing(document, "isProfileComplete");
            putMissing(document, "productCodeSequence");
            putMissing(document, "orderNumberSequence");
            putMissing(document, "monthlyTarget");
            putMissing(document, "preferredLanguage");
            putMissing(document, "notes");
            putMissing(document, "notesHistory");
            putMissing(document, "address");
            putMissing(document, "shippingAddresses");
            putMissing(document, "whatsappSequence");
            putMissing(document, "createdAt");
            putMissing(document, "updatedAt");
            document.put("bankDetails", normalize(document.get("bankDetails"), bankDefaults()));
            document.put("settlementTerms", normalize(document.get("settlementTerms"), settlementDefaults()));
        } else if (user.getRole() == UserRole.ROLE_CUSTOMER) {
            putMissing(document, "phoneNumber");
            putMissing(document, "email");
            putMissing(document, "passwordHash");
            putMissing(document, "firstName");
            putMissing(document, "lastName");
            putMissing(document, "role");
            putMissing(document, "isVerified");
            putMissing(document, "isProfileComplete");
            putMissing(document, "isKycComplete");
            putMissing(document, "shippingAddresses");
            putMissing(document, "address");
            putMissing(document, "isBlocked");
            putMissing(document, "isDeleted");
            putMissing(document, "status");
            putMissing(document, "whatsappSequence");
            putMissing(document, "notes");
            putMissing(document, "notesHistory");
            putMissing(document, "createdAt");
            putMissing(document, "updatedAt");
            document.remove("bankDetails");
            document.remove("settlementTerms");
        } else {
            document.remove("bankDetails");
            document.remove("settlementTerms");
        }
        return user;
    }

    private void putMissing(Document document, String field) {
        if (!document.containsKey(field)) document.put(field, null);
    }

    private Map<String, Object> bankDefaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("accountHolderName", null);
        defaults.put("bankName", null);
        defaults.put("accountNumber", null);
        defaults.put("ifscCode", null);
        defaults.put("branch", null);
        defaults.put("upiId", null);
        defaults.put("isVerified", false);
        return defaults;
    }

    private Map<String, Object> settlementDefaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("commissionPercentage", 0);
        defaults.put("settlementCycle", null);
        defaults.put("payoutStatus", null);
        return defaults;
    }

    private Map<String, Object> normalize(Object value, Map<String, Object> defaults) {
        if (value instanceof Map<?, ?> supplied) {
            supplied.forEach((key, fieldValue) -> {
                String name = String.valueOf(key);
                if (fieldValue != null || !defaults.containsKey(name) || defaults.get(name) == null) {
                    defaults.put(name, fieldValue);
                }
            });
        }
        return defaults;
    }
}
