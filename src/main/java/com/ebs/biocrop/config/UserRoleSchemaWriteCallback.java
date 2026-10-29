package com.ebs.biocrop.config;

import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.UserRole;
import org.bson.Document;
import org.springframework.data.mongodb.core.mapping.event.BeforeSaveCallback;
import org.springframework.stereotype.Component;

/** Persists the seller-only payout placeholders without adding seller fields to other roles. */
@Component
public class UserRoleSchemaWriteCallback implements BeforeSaveCallback<User> {

    @Override
    public User onBeforeSave(User user, Document document, String collection) {
        if (!"users".equals(collection)) return user;
        if (user.getRole() == UserRole.ROLE_SELLER) {
            document.put("bankDetails", null);
            document.put("settlementTerms", null);
        } else {
            document.remove("bankDetails");
            document.remove("settlementTerms");
        }
        return user;
    }
}
