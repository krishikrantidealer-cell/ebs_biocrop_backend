package com.ebs.biocrop.service;

import java.util.Collection;
import java.util.Map;

/** Resolves current seller display names in one batch for catalog responses. */
public interface SellerShopNameLookup {
    Map<String, String> findCurrentShopNames(Collection<String> sellerIds);
}
