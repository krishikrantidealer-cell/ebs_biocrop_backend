package com.ebs.biocrop.dto.request;

import jakarta.validation.constraints.NotNull;

/** Admin decision for a seller's submitted KYC documents. */
public record SellerKycReviewRequest(@NotNull Boolean approved) {
}
