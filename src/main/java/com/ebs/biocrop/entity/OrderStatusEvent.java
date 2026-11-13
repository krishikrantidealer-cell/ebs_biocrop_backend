package com.ebs.biocrop.entity;

import org.springframework.data.mongodb.core.mapping.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Concise audit entry for a whole-order or seller/item lifecycle transition. */
public class OrderStatusEvent {
    @Field(write = Field.Write.ALWAYS) private String status;
    @Field(write = Field.Write.ALWAYS) private String sellerFulfillmentId;
    @Field(write = Field.Write.ALWAYS) private List<String> itemOrderIds = new ArrayList<>();
    @Field(write = Field.Write.ALWAYS) private LocalDateTime occurredAt;
    @Field(write = Field.Write.ALWAYS) private String actor;
    @Field(write = Field.Write.ALWAYS) private String note;

    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getSellerFulfillmentId() { return sellerFulfillmentId; }
    public void setSellerFulfillmentId(String value) { sellerFulfillmentId = value; }
    public List<String> getItemOrderIds() { return itemOrderIds; }
    public void setItemOrderIds(List<String> values) { itemOrderIds = values == null ? new ArrayList<>() : values; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime value) { occurredAt = value; }
    public String getActor() { return actor; }
    public void setActor(String value) { actor = value; }
    public String getNote() { return note; }
    public void setNote(String value) { note = value; }
    @org.springframework.data.annotation.Transient public LocalDateTime getChangedAt() { return occurredAt; }
    public void setChangedAt(LocalDateTime value) { occurredAt = value; }
    @org.springframework.data.annotation.Transient public String getChangedBy() { return actor; }
    public void setChangedBy(String value) { actor = value; }
}
