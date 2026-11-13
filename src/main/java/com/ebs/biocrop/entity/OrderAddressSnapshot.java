package com.ebs.biocrop.entity;

import org.springframework.data.mongodb.core.mapping.Field;

/** Address copied into the order so later profile edits do not alter order records. */
public class OrderAddressSnapshot {
    @Field(write = Field.Write.ALWAYS) private String name;
    @Field(write = Field.Write.ALWAYS) private String phoneNumber;
    @Field(write = Field.Write.ALWAYS) private String villageArea;
    @Field(write = Field.Write.ALWAYS) private String address2;
    @Field(write = Field.Write.ALWAYS) private String cityTehsil;
    @Field(write = Field.Write.ALWAYS) private String district;
    @Field(write = Field.Write.ALWAYS) private String state;
    @Field(write = Field.Write.ALWAYS) private String pincode;
    @Field(write = Field.Write.ALWAYS) private String country = "India";

    public OrderAddressSnapshot() { }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public String getVillageArea() { return villageArea; }
    public void setVillageArea(String villageArea) { this.villageArea = villageArea; }
    public String getAddress2() { return address2; }
    public void setAddress2(String address2) { this.address2 = address2; }
    public String getCityTehsil() { return cityTehsil; }
    public void setCityTehsil(String cityTehsil) { this.cityTehsil = cityTehsil; }
    public String getDistrict() { return district; }
    public void setDistrict(String district) { this.district = district; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getPincode() { return pincode; }
    public void setPincode(String pincode) { this.pincode = pincode; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country == null || country.isBlank() ? "India" : country.trim(); }
}
