package com.ebs.biocrop.entity;

/** Address copied into the order so later profile edits do not alter order records. */
public class OrderAddressSnapshot {
    private String name;
    private String phoneNumber;
    private String villageArea;
    private String addressLine2;
    private String cityTehsil;
    private String state;
    private String pincode;

    public OrderAddressSnapshot() { }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public String getVillageArea() { return villageArea; }
    public void setVillageArea(String villageArea) { this.villageArea = villageArea; }
    public String getAddressLine2() { return addressLine2; }
    public void setAddressLine2(String addressLine2) { this.addressLine2 = addressLine2; }
    public String getCityTehsil() { return cityTehsil; }
    public void setCityTehsil(String cityTehsil) { this.cityTehsil = cityTehsil; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getPincode() { return pincode; }
    public void setPincode(String pincode) { this.pincode = pincode; }
}
