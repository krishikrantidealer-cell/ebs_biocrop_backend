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
        String shopName, String sellerCode, String role, Address address, Boolean isVerified, Boolean isProfileComplete,
        String gstNumber, String panNumber, String licenceNo,
        List<String> licenceImage, String gstCertificate, String panCard, String aadhaarCard, String agreement,
        String shopImage,
        List<Address> shippingAddresses, List<Address> warehouseAddresses, Boolean isBlocked, Boolean isDeleted,
        LocalDateTime createdAt, LocalDateTime updatedAt,
        String status, String notes, List<String> notesHistory,
        Integer monthlyTarget, String preferredLanguage,
        Long productCodeSequence, Long orderNumberSequence, Integer whatsappSequence,
        Map<String, Object> bankDetails, Map<String, Object> settlementTerms,
        String pricingModel, Double commissionPercentage, Integer version) {
    /** Source-compatible constructor for callers that build a response fixture. */
    public AdminUserResponse(String id, String phoneNumber, String email, String firstName, String lastName,
            String shopName, String role, Address address, Boolean isVerified, Boolean isProfileComplete,
            String gstNumber, String panNumber, String licenceNo,
            List<Address> shippingAddresses, List<Address> warehouseAddresses, Boolean isBlocked, Boolean isDeleted,
            LocalDateTime createdAt, LocalDateTime updatedAt, String status, String notes,
            List<String> notesHistory, Integer monthlyTarget, String preferredLanguage,
            Long productCodeSequence, Long orderNumberSequence) {
        this(id, phoneNumber, email, null, null, null, firstName, lastName, shopName, null, role, address, isVerified,
                isProfileComplete, gstNumber, panNumber, licenceNo, null, null, null, null, null, null,
                shippingAddresses, warehouseAddresses, isBlocked, isDeleted, createdAt, updatedAt, status, notes,
                notesHistory, monthlyTarget, preferredLanguage, productCodeSequence, orderNumberSequence,
                null, null, null, null, null, null);
    }

    public static AdminUserResponse from(User u) {
        boolean seller = u.getRole() == UserRole.ROLE_SELLER;
        return new AdminUserResponse(u.getId(), u.getPhoneNumber(), u.getEmail(), u.getPasswordHash(),
                u.getPasswordResetTokenHash(), u.getPasswordResetExpiresAt(), u.getFirstName(), u.getLastName(),
                seller ? u.getShopName() : null, seller ? u.getSellerCode() : null,
                u.getRole()==null ? "ROLE_CUSTOMER" : u.getRole().name(), u.getAddress(),
                seller ? null : u.getIsVerified(), u.getIsProfileComplete(),
                seller ? u.getGstNumber() : null,
                seller ? u.getPanNumber() : null,
                seller ? u.getLicenceNo() : null,
                seller ? u.getLicenceImage() : null,
                seller ? u.getGstCertificate() : null,
                seller ? u.getPanCard() : null,
                seller ? u.getAadhaarCard() : null,
                seller ? u.getAgreement() : null,
                seller ? u.getShopImage() : null,
                seller ? List.of() : (u.getShippingAddresses() == null ? List.of() : List.copyOf(u.getShippingAddresses())),
                seller ? (u.getWarehouseAddresses() == null ? List.of() : List.copyOf(u.getWarehouseAddresses())) : List.of(),
                u.getIsBlocked(), u.getIsDeleted(), u.getCreatedAt(), u.getUpdatedAt(),
                u.getStatus(), u.getNotes(),
                seller ? List.of() : (u.getNotesHistory() == null ? List.of() : List.copyOf(u.getNotesHistory())),
                seller ? u.getMonthlyTarget() : null, seller ? u.getPreferredLanguage() : null,
                seller ? u.getProductCodeSequence() : null, seller ? u.getOrderNumberSequence() : null,
                seller ? null : u.getWhatsappSequence(), seller ? u.getBankDetails() : null,
                seller ? u.getSettlementTerms() : null,
                seller ? (u.getPricingModel() == null ? "MARKETPLACE" : u.getPricingModel()) : null,
                seller ? (u.getCommissionPercentage() == null ? 0.0 : u.getCommissionPercentage()) : null,
                u.getVersion());
    }
}
