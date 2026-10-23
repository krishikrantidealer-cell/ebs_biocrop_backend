package com.ebs.biocrop.dto.response;

import com.ebs.biocrop.entity.Address;
import com.ebs.biocrop.entity.User;
import java.time.LocalDateTime;
import java.util.List;

/** Safe admin projection; never exposes password or token material. */
public record AdminUserResponse(String id, String phoneNumber, String email, String firstName, String lastName,
        String shopName, String role, Address address, Boolean isVerified, Boolean isProfileComplete,
        String kycStatus, Boolean isKycComplete, String gstNumber, String licenceImage, String shopImage,
        List<Address> shippingAddresses, Boolean isBlocked, Boolean isDeleted,
        LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static AdminUserResponse from(User u) {
        return new AdminUserResponse(u.getId(), u.getPhoneNumber(), u.getEmail(), u.getFirstName(), u.getLastName(),
                u.getShopName(), u.getRole()==null ? "ROLE_CUSTOMER" : u.getRole().name(), u.getAddress(),
                u.getIsVerified(), u.getIsProfileComplete(), u.getKycStatus(), u.getIsKycComplete(),
                u.getGstNumber(), u.getLicenceImage(), u.getShopImage(), u.getShippingAddresses(),
                u.getIsBlocked(), u.getIsDeleted(), u.getCreatedAt(), u.getUpdatedAt());
    }
}
