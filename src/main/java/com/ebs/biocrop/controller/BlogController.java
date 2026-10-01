package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.BlogFacetResponse;
import com.ebs.biocrop.dto.response.BlogPublicResponse;
import com.ebs.biocrop.dto.response.PagedResponse;
import com.ebs.biocrop.entity.Blog;
import com.ebs.biocrop.service.BlogService;
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

@RestController
@RequestMapping("/api/v1/blogs")
@Tag(name = "Blogs", description = "Public published blog content and browse facets.")
public class BlogController {

    private final BlogService blogService;

    public BlogController(BlogService blogService) {
        this.blogService = blogService;
    }

    @GetMapping
    @Operation(summary = "List published blogs")
    public ResponseEntity<ApiResponse<PagedResponse<BlogPublicResponse>>> listBlogs(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) Boolean featured,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Blog> blogs = blogService.listPublicBlogs(category, tag, featured, page, size);
        return ResponseEntity.ok(ApiResponse.ok("Published blogs retrieved successfully",
                PagedResponse.from(blogs, BlogPublicResponse::from)));
    }

    @GetMapping("/categories")
    @Operation(summary = "List blog categories")
    public ResponseEntity<ApiResponse<List<BlogFacetResponse>>> categories() {
        return ResponseEntity.ok(ApiResponse.ok("Published blog categories retrieved successfully",
                blogService.publicCategories()));
    }

    @GetMapping("/tags")
    @Operation(summary = "List blog tags")
    public ResponseEntity<ApiResponse<List<BlogFacetResponse>>> tags() {
        return ResponseEntity.ok(ApiResponse.ok("Published blog tags retrieved successfully",
                blogService.publicTags()));
    }

    @GetMapping("/{slug}")
    @Operation(summary = "Get a published blog by slug")
    public ResponseEntity<ApiResponse<BlogPublicResponse>> getBlog(@PathVariable String slug) {
        return ResponseEntity.ok(ApiResponse.ok("Published blog retrieved successfully",
                BlogPublicResponse.from(blogService.getPublicBlog(slug))));
    }
}
