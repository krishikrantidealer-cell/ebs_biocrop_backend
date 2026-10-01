package com.ebs.biocrop.service;

public interface EmailDeliveryService {
    void send(String to, String subject, String body);
}
