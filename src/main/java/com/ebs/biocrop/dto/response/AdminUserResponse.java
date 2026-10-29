package com.ebs.biocrop.dto.response;

import com.ebs.biocrop.entity.Address;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.UserRole;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Admin-only projection of persisted user fields. */
public record AdminUserResponse(String id, String phoneNumber, String email, String passwordHash,
        String passwordResetTokenHash, LocalDateTime passwordResetExpiresAt,
        String firstName, String lastName,
        String shopName, String role, Address address, Boolean isVerified, Boolean isProfileComplete,
        String kycStatus, Boolean isKycComplete, String gstNumber, String licenceImage, String shopImage,
        List<Address> shippingAddresses, Boolean isBlocked, Boolean isDeleted,
        LocalDateTime createdAt, LocalDateTime updatedAt,
        String status, String notes, List<String> notesHistory,
        Integer monthlyTarget, String preferredLanguage,
        Long productCodeSequence, Long orderNumberSequence, Integer whatsappSequence,
        Map<String, Object> bankDetails, Map<String, Object> settlementTerms, Integer version) {
    /** Source-compatible constructor for callers that build a response fixture. */
    public AdminUserResponse(String id, String phoneNumber, String email, String firstName, String lastName,
            String shopName, String role, Address address, Boolean isVerified, Boolean isProfileComplete,
            String kycStatus, Boolean isKycComplete, String gstNumber, String licenceImage, String shopImage,
            List<Address> shippingAddresses, Boolean isBlocked, Boolean isDeleted,
            LocalDateTime createdAt, LocalDateTime updatedAt, String status, String notes,
            List<String> notesHistory, Integer monthlyTarget, String preferredLanguage,
            Long productCodeSequence, Long orderNumberSequence) {
        this(id, phoneNumber, email, null, null, null, firstName, lastName, shopName, role, address, isVerified,
                isProfileComplete, kycStatus, isKycComplete, gstNumber, licenceImage, shopImage,
                shippingAddresses, isBlocked, isDeleted, createdAt, updatedAt, status, notes,
                notesHistory, monthlyTarget, preferredLanguage, productCodeSequence, orderNumberSequence,
                null, null, null, null);
    }

    public static AdminUserResponse from(User u) {
        boolean seller = u.getRole() == UserRole.ROLE_SELLER;
        return new AdminUserResponse(u.getId(), u.getPhoneNumber(), u.getEmail(), u.getPasswordHash(),
                u.getPasswordResetTokenHash(), u.getPasswordResetExpiresAt(), u.getFirstName(), u.getLastName(),
                seller ? u.getShopName() : null,
                u.getRole()==null ? "ROLE_CUSTOMER" : u.getRole().name(), u.getAddress(),
                u.getIsVerified(), u.getIsProfileComplete(), seller ? u.getKycStatus() : null,
                seller ? u.getIsKycComplete() : false,
                seller ? u.getGstNumber() : null, seller ? u.getLicenceImage() : null,
                seller ? u.getShopImage() : null,
                u.getShippingAddresses() == null ? List.of() : List.copyOf(u.getShippingAddresses()),
                u.getIsBlocked(), u.getIsDeleted(), u.getCreatedAt(), u.getUpdatedAt(),
                u.getStatus(), u.getNotes(),
                u.getNotesHistory() == null ? List.of() : List.copyOf(u.getNotesHistory()),
                seller ? u.getMonthlyTarget() : null, seller ? u.getPreferredLanguage() : null,
                seller ? u.getProductCodeSequence() : null, seller ? u.getOrderNumberSequence() : null,
                u.getWhatsappSequence(), seller ? u.getBankDetails() : null,
                seller ? u.getSettlementTerms() : null, u.getVersion());
    }
}
