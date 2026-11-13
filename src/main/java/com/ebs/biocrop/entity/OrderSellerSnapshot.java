package com.ebs.biocrop.entity;

import org.springframework.data.mongodb.core.mapping.Field;

/** Historical seller business/contact details needed to identify the fulfillment owner. */
public class OrderSellerSnapshot {
    @Field(write = Field.Write.ALWAYS) private String legalName;
    @Field(write = Field.Write.ALWAYS) private String tradeName;
    @Field(write = Field.Write.ALWAYS) private String gstNumber;
    @Field(write = Field.Write.ALWAYS) private String contactPhone;
    @Field(write = Field.Write.ALWAYS) private String dispatchPincode;
    @Field(write = Field.Write.ALWAYS) private String sellerCode;

    public String getLegalName() { return legalName; }
    public void setLegalName(String value) { legalName = value; }
    public String getTradeName() { return tradeName; }
    public void setTradeName(String value) { tradeName = value; }
    public String getGstNumber() { return gstNumber; }
    public void setGstNumber(String value) { gstNumber = value; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String value) { contactPhone = value; }
    public String getDispatchPincode() { return dispatchPincode; }
    public void setDispatchPincode(String value) { dispatchPincode = value; }
    public String getSellerCode() { return sellerCode; }
    public void setSellerCode(String value) { sellerCode = value; }

    @org.springframework.data.annotation.Transient public String getDisplayName() { return tradeName; }
    public void setDisplayName(String value) { tradeName = value; }
    @org.springframework.data.annotation.Transient public String getPhoneNumber() { return contactPhone; }
    public void setPhoneNumber(String value) { contactPhone = value; }
    @org.springframework.data.annotation.Transient public String getEmail() { return null; }
    public void setEmail(String ignored) { }
}
