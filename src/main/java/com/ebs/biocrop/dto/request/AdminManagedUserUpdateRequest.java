package com.ebs.biocrop.dto.request;

import com.ebs.biocrop.entity.Address;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/** Partial admin profile update. Identity, role, auth flags, and derived KYC flags are not writable here. */
public class AdminManagedUserUpdateRequest {
    @Email @Size(max = 254) private String email;
    @Size(max = 50) private String firstName;
    @Size(max = 50) private String lastName;
    @Size(max = 120) private String shopName;
    @Valid private Address address;
    @Size(max = 32) private String gstNumber;
    @Size(max = 2048) private String licenceImage;
    @Size(max = 2048) private String shopImage;
    @Size(max = 100) private String status;
    @Size(max = 2000) private String notes;
    private Integer monthlyTarget;
    @Size(max = 10) private String preferredLanguage;

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
    private static String trim(String value) { return value == null ? null : value.trim(); }
}
