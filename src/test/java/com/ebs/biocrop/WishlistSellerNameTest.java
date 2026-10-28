package com.ebs.biocrop;

import com.ebs.biocrop.dto.response.WishlistResponse;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductVariant;
import com.ebs.biocrop.entity.Wishlist;
import com.ebs.biocrop.repository.ProductRepository;
import com.ebs.biocrop.repository.ProductVariantLookup;
import com.ebs.biocrop.repository.WishlistRepository;
import com.ebs.biocrop.service.SellerShopNameLookup;
import com.ebs.biocrop.service.impl.WishlistServiceImpl;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WishlistSellerNameTest {

    @Test
    void wishlistUsesCurrentSellerShopNameInsteadOfProductSnapshot() {
        WishlistRepository wishlists = mock(WishlistRepository.class);
        ProductRepository products = mock(ProductRepository.class);
        ProductVariantLookup variants = mock(ProductVariantLookup.class);
        MongoTemplate mongo = mock(MongoTemplate.class);
        SellerShopNameLookup sellerNames = mock(SellerShopNameLookup.class);

        String sellerId = new ObjectId().toHexString();
        String productId = new ObjectId().toHexString();
        String variantId = new ObjectId().toHexString();
        ProductVariant variant = new ProductVariant();
        variant.setId(variantId);
        variant.setLabel("1 L");
        Product product = new Product();
        product.setId(productId);
        product.setSellerId(sellerId);
        product.setSellerName("Outdated Snapshot");
        product.setTitle("Test product");
        product.setStatus("ACTIVE");
        product.setIsAvailable(true);
        product.setImages(List.of());
        product.setVariants(List.of(variant));

        Wishlist wishlist = new Wishlist();
        wishlist.setVariantIds(List.of(variantId));
        wishlist.setVariantProductIds(Map.of(variantId, productId));
        when(wishlists.findByUser("customer-1")).thenReturn(java.util.Optional.of(wishlist));
        when(products.findAllById(List.of(productId))).thenReturn(List.of(product));
        when(sellerNames.findCurrentShopNames(anyCollection())).thenReturn(Map.of(sellerId, "Current Shop Name"));

        WishlistResponse response = new WishlistServiceImpl(wishlists, products, variants, mongo, sellerNames)
                .getWishlist("customer-1");

        assertEquals("Current Shop Name", response.getItems().getFirst().getSellerName());
    }

    @Test
    void fallbackVariantLookupAlsoResolvesCurrentSellerShopName() {
        WishlistRepository wishlists = mock(WishlistRepository.class);
        ProductRepository products = mock(ProductRepository.class);
        ProductVariantLookup variants = mock(ProductVariantLookup.class);
        MongoTemplate mongo = mock(MongoTemplate.class);
        SellerShopNameLookup sellerNames = mock(SellerShopNameLookup.class);
        String sellerId = new ObjectId().toHexString();
        String productId = new ObjectId().toHexString();
        String variantId = new ObjectId().toHexString();
        ProductVariant variant = new ProductVariant();
        variant.setId(variantId);
        Product product = new Product();
        product.setId(productId);
        product.setSellerId(sellerId);
        product.setSellerName("Outdated Snapshot");
        product.setTitle("Test product");
        product.setStatus("ACTIVE");
        product.setIsAvailable(true);
        product.setVariants(List.of(variant));
        Wishlist wishlist = new Wishlist();
        wishlist.setVariantIds(List.of(variantId));
        when(wishlists.findByUser("customer-2")).thenReturn(java.util.Optional.of(wishlist));
        when(products.findAllById(List.of())).thenReturn(List.of());
        when(variants.findProductsContainingVariants(List.of(variantId))).thenReturn(List.of(product));
        when(sellerNames.findCurrentShopNames(anyCollection())).thenReturn(Map.of(sellerId, "Current Shop Name"));

        WishlistResponse response = new WishlistServiceImpl(wishlists, products, variants, mongo, sellerNames)
                .getWishlist("customer-2");

        assertEquals("Current Shop Name", response.getItems().getFirst().getSellerName());
    }
}
