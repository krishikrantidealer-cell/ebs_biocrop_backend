package com.ebs.biocrop.entity;

import java.time.LocalDateTime;

/** Audit entry for an order status change; status vocabulary remains configurable. */
public class OrderStatusEvent {
    private String status;
    private LocalDateTime changedAt;
    private String changedBy;
    private String note;

    public OrderStatusEvent() { }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getChangedAt() { return changedAt; }
    public void setChangedAt(LocalDateTime changedAt) { this.changedAt = changedAt; }
    public String getChangedBy() { return changedBy; }
    public void setChangedBy(String changedBy) { this.changedBy = changedBy; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
