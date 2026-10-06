package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.BlogFacetResponse;
import com.ebs.biocrop.dto.response.BlogPublicResponse;
import com.ebs.biocrop.dto.response.PagedResponse;
import com.ebs.biocrop.entity.Blog;
import com.ebs.biocrop.service.BlogService;
import com.ebs.biocrop.service.RedisJsonCache;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.time.Duration;

@RestController
@RequestMapping("/api/v1/blogs")
@Tag(name = "Blogs", description = "Public published blog content and browse facets.")
public class BlogController {

    private final BlogService blogService;
    private final RedisJsonCache redisCache;
    private final TypeFactory typeFactory;

    public BlogController(BlogService blogService, RedisJsonCache redisCache, ObjectMapper objectMapper) {
        this.blogService = blogService;
        this.redisCache = redisCache;
        this.typeFactory = objectMapper.getTypeFactory();
    }

    @GetMapping
    @Operation(summary = "List published blogs")
    public ResponseEntity<ApiResponse<PagedResponse<BlogPublicResponse>>> listBlogs(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) Boolean featured,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String key = "category=" + category + "|tag=" + tag + "|featured=" + featured
                + "|page=" + page + "|size=" + size;
        JavaType responseType = typeFactory.constructParametricType(PagedResponse.class, BlogPublicResponse.class);
        PagedResponse<BlogPublicResponse> response = redisCache.getOrLoad(
                "public-blogs", key, responseType, Duration.ofMinutes(5),
                () -> PagedResponse.from(blogService.listPublicBlogs(category, tag, featured, page, size), BlogPublicResponse::from));
        return ResponseEntity.ok(ApiResponse.ok("Published blogs retrieved successfully", response));
    }

    @GetMapping("/categories")
    @Operation(summary = "List blog categories")
    public ResponseEntity<ApiResponse<List<BlogFacetResponse>>> categories() {
        JavaType responseType = typeFactory.constructCollectionType(List.class, BlogFacetResponse.class);
        List<BlogFacetResponse> response = redisCache.getOrLoad("public-blogs", "categories", responseType,
                Duration.ofMinutes(5), blogService::publicCategories);
        return ResponseEntity.ok(ApiResponse.ok("Published blog categories retrieved successfully", response));
    }

    @GetMapping("/tags")
    @Operation(summary = "List blog tags")
    public ResponseEntity<ApiResponse<List<BlogFacetResponse>>> tags() {
        JavaType responseType = typeFactory.constructCollectionType(List.class, BlogFacetResponse.class);
        List<BlogFacetResponse> response = redisCache.getOrLoad("public-blogs", "tags", responseType,
                Duration.ofMinutes(5), blogService::publicTags);
        return ResponseEntity.ok(ApiResponse.ok("Published blog tags retrieved successfully", response));
    }

    @GetMapping("/{slug}")
    @Operation(summary = "Get a published blog by slug")
    public ResponseEntity<ApiResponse<BlogPublicResponse>> getBlog(@PathVariable String slug) {
        BlogPublicResponse response = redisCache.getOrLoad("public-blogs", "slug=" + slug,
                typeFactory.constructType(BlogPublicResponse.class), Duration.ofMinutes(5),
                () -> BlogPublicResponse.from(blogService.getPublicBlog(slug)));
        return ResponseEntity.ok(ApiResponse.ok("Published blog retrieved successfully", response));
    }
}
