package com.ebs.biocrop.entity;

/** Address copied into the order so later profile edits do not alter order records. */
public class OrderAddressSnapshot {
    private String name;
    private String phoneNumber;
    private String villageArea;
    private String address2;
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
    public String getAddress2() { return address2; }
    public void setAddress2(String address2) { this.address2 = address2; }
    public String getCityTehsil() { return cityTehsil; }
    public void setCityTehsil(String cityTehsil) { this.cityTehsil = cityTehsil; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getPincode() { return pincode; }
    public void setPincode(String pincode) { this.pincode = pincode; }
}
