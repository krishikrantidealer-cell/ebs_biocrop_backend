package com.ebs.biocrop.entity;

import com.ebs.biocrop.entity.enums.UserRole;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "users")
public class User {

    @Id
    private String id;

    @Indexed(unique = true)
    private String phoneNumber;

    private String firstName;
    private String lastName;
    private String shopName;
    private String source;
    
    private String assignedAgent; // ObjectId
    private Boolean isVerified = false;
    private Boolean isProfileComplete = false;
    private String addressType;
    private UserRole role = UserRole.ROLE_CUSTOMER;
    private String kycStatus;
    private Boolean isKycComplete = false;
    private List<Address> shippingAddresses = new ArrayList<>();
    
    private String fcmToken;
    private Address address;
    
    private Boolean isBlocked = false;
    private String notes;
    private List<String> notesHistory = new ArrayList<>();
    private String status;
    private String userType;
    private Integer whatsappSequence = 0;
    
    private Boolean isDeleted = false;
    private Integer monthlyTarget;
    private String preferredLanguage;
    private String gstNumber;
    private String licenceImage;
    private String shopImage;
    private Integer kycReminderCount;

    private LocalDateTime assignedAt;
    private LocalDateTime lastMarketingNotificationSentAt;
    private String lastMarketingSegment;
    private LocalDateTime last530PMSentAt;
    private LocalDateTime last8PMSentAt;
    private LocalDateTime lastWinBackSentAt;
    private LocalDateTime lastFirstOrderSentAt;
    private LocalDateTime lastKycReminderSentAt;

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
    }

    // Getters and Setters

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getShopName() { return shopName; }
    public void setShopName(String shopName) { this.shopName = shopName; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getAssignedAgent() { return assignedAgent; }
    public void setAssignedAgent(String assignedAgent) { this.assignedAgent = assignedAgent; }

    public Boolean getIsVerified() { return isVerified; }
    public void setIsVerified(Boolean isVerified) { this.isVerified = isVerified; }

    public Boolean getIsProfileComplete() { return isProfileComplete; }
    public void setIsProfileComplete(Boolean isProfileComplete) { this.isProfileComplete = isProfileComplete; }

    public boolean hasCompleteProfile() {
        return hasText(firstName)
                && hasText(lastName)
                && address != null
                && hasText(address.getVillageArea())
                && (hasText(address.getAddress2()) || hasText(address.getAddressLine2()))
                && hasText(address.getCityTehsil())
                && hasText(address.getState())
                && hasText(address.getPincode());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public String getAddressType() { return addressType; }
    public void setAddressType(String addressType) { this.addressType = addressType; }

    public UserRole getRole() { return role; }
    public void setRole(UserRole role) { this.role = role != null ? role : UserRole.ROLE_CUSTOMER; }

    public String getKycStatus() { return kycStatus; }
    public void setKycStatus(String kycStatus) { this.kycStatus = kycStatus; }

    public Boolean getIsKycComplete() { return isKycComplete; }
    public void setIsKycComplete(Boolean isKycComplete) { this.isKycComplete = isKycComplete; }

    public List<Address> getShippingAddresses() { return shippingAddresses; }
    public void setShippingAddresses(List<Address> shippingAddresses) { this.shippingAddresses = shippingAddresses; }

    public String getFcmToken() { return fcmToken; }
    public void setFcmToken(String fcmToken) { this.fcmToken = fcmToken; }

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

    public String getUserType() { return userType; }
    public void setUserType(String userType) { this.userType = userType; }

    public Integer getWhatsappSequence() { return whatsappSequence; }
    public void setWhatsappSequence(Integer whatsappSequence) { this.whatsappSequence = whatsappSequence; }

    public Boolean getIsDeleted() { return isDeleted; }
    public void setIsDeleted(Boolean isDeleted) { this.isDeleted = isDeleted; }

    public Integer getMonthlyTarget() { return monthlyTarget; }
    public void setMonthlyTarget(Integer monthlyTarget) { this.monthlyTarget = monthlyTarget; }

    public String getPreferredLanguage() { return preferredLanguage; }
    public void setPreferredLanguage(String preferredLanguage) { this.preferredLanguage = preferredLanguage; }

    public String getGstNumber() { return gstNumber; }
    public void setGstNumber(String gstNumber) { this.gstNumber = gstNumber; }

    public String getLicenceImage() { return licenceImage; }
    public void setLicenceImage(String licenceImage) { this.licenceImage = licenceImage; }

    public String getShopImage() { return shopImage; }
    public void setShopImage(String shopImage) { this.shopImage = shopImage; }

    public Integer getKycReminderCount() { return kycReminderCount; }
    public void setKycReminderCount(Integer kycReminderCount) { this.kycReminderCount = kycReminderCount; }

    public LocalDateTime getAssignedAt() { return assignedAt; }
    public void setAssignedAt(LocalDateTime assignedAt) { this.assignedAt = assignedAt; }

    public LocalDateTime getLastMarketingNotificationSentAt() { return lastMarketingNotificationSentAt; }
    public void setLastMarketingNotificationSentAt(LocalDateTime lastMarketingNotificationSentAt) { this.lastMarketingNotificationSentAt = lastMarketingNotificationSentAt; }

    public String getLastMarketingSegment() { return lastMarketingSegment; }
    public void setLastMarketingSegment(String lastMarketingSegment) { this.lastMarketingSegment = lastMarketingSegment; }

    public LocalDateTime getLast530PMSentAt() { return last530PMSentAt; }
    public void setLast530PMSentAt(LocalDateTime last530PMSentAt) { this.last530PMSentAt = last530PMSentAt; }

    public LocalDateTime getLast8PMSentAt() { return last8PMSentAt; }
    public void setLast8PMSentAt(LocalDateTime last8PMSentAt) { this.last8PMSentAt = last8PMSentAt; }

    public LocalDateTime getLastWinBackSentAt() { return lastWinBackSentAt; }
    public void setLastWinBackSentAt(LocalDateTime lastWinBackSentAt) { this.lastWinBackSentAt = lastWinBackSentAt; }

    public LocalDateTime getLastFirstOrderSentAt() { return lastFirstOrderSentAt; }
    public void setLastFirstOrderSentAt(LocalDateTime lastFirstOrderSentAt) { this.lastFirstOrderSentAt = lastFirstOrderSentAt; }

    public LocalDateTime getLastKycReminderSentAt() { return lastKycReminderSentAt; }
    public void setLastKycReminderSentAt(LocalDateTime lastKycReminderSentAt) { this.lastKycReminderSentAt = lastKycReminderSentAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
