package com.ebs.biocrop.dto.response;

import java.util.List;

public record PlacedOrdersResponse(String checkoutGroupId, List<OrderResponse> orders) { }
