package com.ebs.biocrop.entity;

import com.ebs.biocrop.entity.enums.UserRole;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Document(collection = "users")
public class User {

    @Id
    private String id;

    @Indexed(unique = true, name = "uniq_user_phone")
    private String phoneNumber;

    private String email;
    private String passwordHash;
    private String passwordResetTokenHash;
    private LocalDateTime passwordResetExpiresAt;

    private String firstName;
    private String lastName;
    private String shopName;
    /** Atomic per-seller counter used to issue stable seller product codes. */
    private Long productCodeSequence;
    /** Atomic per-seller counter used to issue seller order numbers. */
    private Long orderNumberSequence;
    private Boolean isVerified = false;
    private Boolean isProfileComplete = false;
    private UserRole role = UserRole.ROLE_CUSTOMER;
    private String kycStatus;
    private Boolean isKycComplete = false;
    private List<Address> shippingAddresses = new ArrayList<>();
    
    private Address address;
    
    private Boolean isBlocked = false;
    private String notes;
    private List<String> notesHistory = new ArrayList<>();
    private String status;
    private Integer whatsappSequence = 0;
    
    private Boolean isDeleted = false;
    private Integer monthlyTarget;
    private String preferredLanguage;
    private String gstNumber;
    private String licenceImage;
    private String shopImage;
    // Reserved seller-schema placeholders; payout data is intentionally not collected or exposed yet.
    private Map<String, Object> bankDetails;
    private Map<String, Object> settlementTerms;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Version
    private Integer version;

    public User() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public User(String phoneNumber, UserRole role) {
        this();
        this.phoneNumber = phoneNumber;
        this.role = role != null ? role : UserRole.ROLE_CUSTOMER;
        if (this.role == UserRole.ROLE_CUSTOMER || this.role == UserRole.ROLE_SELLER) {
            this.status = "ACTIVE";
        }
        if (this.role == UserRole.ROLE_SELLER) {
            this.productCodeSequence = 0L;
            this.orderNumberSequence = 0L;
        } else if (this.role == UserRole.ROLE_ADMIN) {
            this.isVerified = null;
            this.isProfileComplete = true;
            this.isBlocked = null;
            this.isDeleted = null;
            this.whatsappSequence = null;
        }
    }

    public static User newCustomer(String phoneNumber) {
        return new User(phoneNumber, UserRole.ROLE_CUSTOMER);
    }

    public static User newSeller(String phoneNumber, String email) {
        User seller = new User(phoneNumber, UserRole.ROLE_SELLER);
        seller.setEmail(email);
        return seller;
    }

    public static User newAdmin(String phoneNumber, String email) {
        User admin = new User(phoneNumber, UserRole.ROLE_ADMIN);
        admin.setEmail(email);
        return admin;
    }

    // Getters and Setters

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getPasswordResetTokenHash() { return passwordResetTokenHash; }
    public void setPasswordResetTokenHash(String passwordResetTokenHash) { this.passwordResetTokenHash = passwordResetTokenHash; }
    public LocalDateTime getPasswordResetExpiresAt() { return passwordResetExpiresAt; }
    public void setPasswordResetExpiresAt(LocalDateTime passwordResetExpiresAt) { this.passwordResetExpiresAt = passwordResetExpiresAt; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getShopName() { return shopName; }
    public void setShopName(String shopName) { this.shopName = shopName; }
    public Long getProductCodeSequence() { return productCodeSequence; }
    public void setProductCodeSequence(Long productCodeSequence) { this.productCodeSequence = productCodeSequence; }
    public Long getOrderNumberSequence() { return orderNumberSequence; }
    public void setOrderNumberSequence(Long orderNumberSequence) { this.orderNumberSequence = orderNumberSequence; }

    public Boolean getIsVerified() { return isVerified; }
    public void setIsVerified(Boolean isVerified) { this.isVerified = isVerified; }

    public Boolean getIsProfileComplete() { return isProfileComplete; }
    public void setIsProfileComplete(Boolean isProfileComplete) { this.isProfileComplete = isProfileComplete; }

    public boolean hasCompleteProfile() {
        if (!hasText(firstName) || !hasText(lastName) || !hasCompleteAddress()) return false;
        return getRole() != UserRole.ROLE_SELLER || hasText(shopName);
    }

    private boolean hasCompleteAddress() {
        return address != null
                && hasText(address.getVillageArea())
                && hasText(address.getAddress2())
                && hasText(address.getCityTehsil())
                && hasText(address.getState())
                && address.getPincode() != null
                && address.getPincode().matches("^[0-9]{6}$");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public UserRole getRole() { return role; }
    public void setRole(UserRole role) {
        this.role = role != null ? role : UserRole.ROLE_CUSTOMER;
        if (this.role == UserRole.ROLE_SELLER) {
            if (status == null || status.isBlank()) status = "ACTIVE";
            if (productCodeSequence == null) productCodeSequence = 0L;
            if (orderNumberSequence == null) orderNumberSequence = 0L;
            if (isVerified == null) isVerified = false;
            if (isBlocked == null) isBlocked = false;
            if (isDeleted == null) isDeleted = false;
            if (whatsappSequence == null) whatsappSequence = 0;
        } else if (this.role == UserRole.ROLE_ADMIN) {
            status = null;
            isVerified = null;
            isProfileComplete = hasCompleteProfile();
            isBlocked = null;
            isDeleted = null;
            whatsappSequence = null;
            productCodeSequence = null;
            orderNumberSequence = null;
        } else {
            if (status == null || status.isBlank()) status = "ACTIVE";
            if (isVerified == null) isVerified = false;
            if (isBlocked == null) isBlocked = false;
            if (isDeleted == null) isDeleted = false;
            if (whatsappSequence == null) whatsappSequence = 0;
            isProfileComplete = hasCompleteProfile();
        }
    }

    public String getKycStatus() { return kycStatus; }
    public void setKycStatus(String kycStatus) {
        boolean approved = role == UserRole.ROLE_SELLER
                && "APPROVED".equalsIgnoreCase(kycStatus)
                && hasRequiredSellerKycInformation();
        this.kycStatus = approved ? "APPROVED" : null;
        this.isKycComplete = approved;
    }

    public Boolean getIsKycComplete() { return isKycComplete; }
    public void setIsKycComplete(Boolean isKycComplete) {
        this.isKycComplete = role == UserRole.ROLE_SELLER
                && Boolean.TRUE.equals(isKycComplete)
                && "APPROVED".equalsIgnoreCase(kycStatus);
    }

    /** Seller KYC information is ready for review when all required details are present. */
    public boolean hasRequiredSellerKycInformation() {
        return hasText(gstNumber) && hasText(licenceImage) && hasText(shopImage);
    }

    private void invalidateSellerKycApprovalIfChanged(String current, String replacement) {
        if (role == UserRole.ROLE_SELLER && !java.util.Objects.equals(current, replacement)) {
            this.kycStatus = null;
            this.isKycComplete = false;
        }
    }

    public List<Address> getShippingAddresses() { return shippingAddresses; }
    public void setShippingAddresses(List<Address> shippingAddresses) { this.shippingAddresses = shippingAddresses; }

    public Address getAddress() { return address; }
    public void setAddress(Address address) { this.address = address; }

    public Boolean getIsBlocked() { return isBlocked; }
    public void setIsBlocked(Boolean isBlocked) { this.isBlocked = isBlocked; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public List<String> getNotesHistory() { return notesHistory; }
    public void setNotesHistory(List<String> notesHistory) { this.notesHistory = notesHistory; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getWhatsappSequence() { return whatsappSequence; }
    public void setWhatsappSequence(Integer whatsappSequence) { this.whatsappSequence = whatsappSequence; }
    public Map<String, Object> getBankDetails() { return bankDetails; }
    public void setBankDetails(Map<String, Object> bankDetails) { this.bankDetails = bankDetails; }
    public Map<String, Object> getSettlementTerms() { return settlementTerms; }
    public void setSettlementTerms(Map<String, Object> settlementTerms) { this.settlementTerms = settlementTerms; }

    public Boolean getIsDeleted() { return isDeleted; }
    public void setIsDeleted(Boolean isDeleted) { this.isDeleted = isDeleted; }

    public Integer getMonthlyTarget() { return monthlyTarget; }
    public void setMonthlyTarget(Integer monthlyTarget) { this.monthlyTarget = monthlyTarget; }

    public String getPreferredLanguage() { return preferredLanguage; }
    public void setPreferredLanguage(String preferredLanguage) { this.preferredLanguage = preferredLanguage; }

    public String getGstNumber() { return gstNumber; }
    public void setGstNumber(String gstNumber) {
        invalidateSellerKycApprovalIfChanged(this.gstNumber, gstNumber);
        this.gstNumber = gstNumber;
    }

    public String getLicenceImage() { return licenceImage; }
    public void setLicenceImage(String licenceImage) {
        invalidateSellerKycApprovalIfChanged(this.licenceImage, licenceImage);
        this.licenceImage = licenceImage;
    }

    public String getShopImage() { return shopImage; }
    public void setShopImage(String shopImage) {
        invalidateSellerKycApprovalIfChanged(this.shopImage, shopImage);
        this.shopImage = shopImage;
    }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
