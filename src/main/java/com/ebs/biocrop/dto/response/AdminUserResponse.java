package com.ebs.biocrop.dto.response;

import com.ebs.biocrop.entity.Address;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.UserRole;
import java.time.LocalDateTime;
import java.util.List;

/** Safe admin projection; never exposes password or token material. */
public record AdminUserResponse(String id, String phoneNumber, String email, String firstName, String lastName,
        String shopName, String role, Address address, Boolean isVerified, Boolean isProfileComplete,
        String kycStatus, Boolean isKycComplete, String gstNumber, String licenceImage, String shopImage,
        List<Address> shippingAddresses, Boolean isBlocked, Boolean isDeleted,
        LocalDateTime createdAt, LocalDateTime updatedAt,
        String status, String notes, List<String> notesHistory,
        Integer monthlyTarget, String preferredLanguage,
        Long productCodeSequence, Long orderNumberSequence) {
    public static AdminUserResponse from(User u) {
        boolean seller = u.getRole() == UserRole.ROLE_SELLER;
        return new AdminUserResponse(u.getId(), u.getPhoneNumber(), u.getEmail(), u.getFirstName(), u.getLastName(),
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
                seller ? u.getProductCodeSequence() : null, seller ? u.getOrderNumberSequence() : null);
    }
}
