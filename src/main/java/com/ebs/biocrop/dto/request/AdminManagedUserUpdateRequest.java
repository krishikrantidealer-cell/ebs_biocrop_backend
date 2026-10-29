package com.ebs.biocrop.dto.request;

import com.ebs.biocrop.entity.Address;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/** Partial admin profile update. Identity, role, auth flags, and derived KYC flags are not writable here. */
public class AdminManagedUserUpdateRequest {
    @Email @Size(max = 254) private String email;
    @Size(max = 50) private String firstName;
    @Size(max = 50) private String lastName;
    @Size(max = 120) private String shopName;
    @Valid private Address address;
    @Valid private List<Address> shippingAddresses;
    @Size(max = 32) private String gstNumber;
    @Size(max = 2048) private String licenceImage;
    @Size(max = 2048) private String shopImage;
    @Size(max = 100) private String status;
    @Size(max = 2000) private String notes;
    private Integer monthlyTarget;
    @Size(max = 10) private String preferredLanguage;
    @Pattern(regexp = "^[0-9]{10}$") private String phoneNumber;
    private Map<String, Object> bankDetails;
    private Map<String, Object> settlementTerms;
    private Boolean isBlocked;
    private Boolean isDeleted;
    private Boolean isVerified;
    private List<String> notesHistory;

    public String getEmail() { return email; }
    public void setEmail(String value) { email = value == null ? null : value.trim().toLowerCase(java.util.Locale.ROOT); }
    public String getFirstName() { return firstName; }
    public void setFirstName(String value) { firstName = trim(value); }
    public String getLastName() { return lastName; }
    public void setLastName(String value) { lastName = trim(value); }
    public String getShopName() { return shopName; }
    public void setShopName(String value) { shopName = trim(value); }
    public Address getAddress() { return address; }
    public void setAddress(Address value) { address = value; }
    public List<Address> getShippingAddresses() { return shippingAddresses; }
    public void setShippingAddresses(List<Address> value) { shippingAddresses = value; }
    public String getGstNumber() { return gstNumber; }
    public void setGstNumber(String value) { gstNumber = trim(value); }
    public String getLicenceImage() { return licenceImage; }
    public void setLicenceImage(String value) { licenceImage = trim(value); }
    public String getShopImage() { return shopImage; }
    public void setShopImage(String value) { shopImage = trim(value); }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = trim(value); }
    public String getNotes() { return notes; }
    public void setNotes(String value) { notes = trim(value); }
    public Integer getMonthlyTarget() { return monthlyTarget; }
    public void setMonthlyTarget(Integer value) { monthlyTarget = value; }
    public String getPreferredLanguage() { return preferredLanguage; }
    public void setPreferredLanguage(String value) { preferredLanguage = trim(value); }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String value) { phoneNumber = value == null ? null : value.trim(); }
    public Map<String, Object> getBankDetails() { return bankDetails; }
    public void setBankDetails(Map<String, Object> value) { bankDetails = value; }
    public Map<String, Object> getSettlementTerms() { return settlementTerms; }
    public void setSettlementTerms(Map<String, Object> value) { settlementTerms = value; }
    public Boolean getIsBlocked() { return isBlocked; }
    public void setIsBlocked(Boolean value) { isBlocked = value; }
    public Boolean getIsDeleted() { return isDeleted; }
    public void setIsDeleted(Boolean value) { isDeleted = value; }
    public Boolean getIsVerified() { return isVerified; }
    public void setIsVerified(Boolean value) { isVerified = value; }
    public List<String> getNotesHistory() { return notesHistory; }
    public void setNotesHistory(List<String> value) { notesHistory = value; }
    private static String trim(String value) { return value == null ? null : value.trim(); }
}
