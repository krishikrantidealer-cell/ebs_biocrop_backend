package com.ebs.biocrop.service;

import com.ebs.biocrop.dto.response.ShiprocketOrderResult;
import com.ebs.biocrop.dto.response.ShiprocketServiceabilityResponse;
import com.ebs.biocrop.entity.Order;

/**
 * Service for Shiprocket logistics, order dispatching, AWB allocation, and tracking.
 */
public interface ShiprocketService {

    /**
     * Checks if a destination postal pincode is serviceable by Shiprocket couriers.
     */
    ShiprocketServiceabilityResponse checkServiceability(String deliveryPincode, double weightKg, boolean isCod);

    /**
     * Creates an adhoc fulfillment order and shipment in Shiprocket, then allocates courier & AWB.
     */
    ShiprocketOrderResult createOrderAndShipment(Order order);

    /**
     * Retrieves tracking activities for a given AWB or order.
     */
    String trackShipment(String awbCode);

    /**
     * Returns true if Shiprocket credentials are fully configured.
     */
    boolean isConfigured();
}
