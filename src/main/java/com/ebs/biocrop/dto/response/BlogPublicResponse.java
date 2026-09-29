package com.ebs.biocrop.dto.response;

import com.ebs.biocrop.entity.Blog;

import java.time.Instant;
import java.util.List;

public record BlogPublicResponse(
        String id,
        String title,
        String slug,
        String excerpt,
        Blog.BlogContent content,
        Blog.BlogCategory category,
        List<String> tags,
        Blog.FeaturedImage featuredImage,
        Blog.BlogSeo seo,
        List<String> relatedBlogIds,
        List<RelatedProduct> relatedProducts,
        Integer wordCount,
        Integer readingTimeMinutes,
        Boolean isFeatured,
        Instant publishedAt) {

    public static BlogPublicResponse from(Blog blog) {
        return new BlogPublicResponse(
                blog.getId(), blog.getTitle(), blog.getSlug(), blog.getExcerpt(), blog.getContent(),
                blog.getCategory(), safeList(blog.getTags()), blog.getFeaturedImage(), blog.getSeo(),
                safeList(blog.getRelatedBlogIds()).stream().map(Object::toString).toList(),
                safeList(blog.getRelatedProducts()).stream()
                        .map(product -> new RelatedProduct(
                                product.getProductId() == null ? null : product.getProductId().toHexString(), product.getTitle()))
                        .toList(),
                blog.getWordCount(), blog.getReadingTimeMinutes(), blog.getIsFeatured(), blog.getPublishedAt());
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    public record RelatedProduct(String productId, String title) { }
}
