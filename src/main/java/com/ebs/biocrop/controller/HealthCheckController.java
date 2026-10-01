package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Health")
public class HealthCheckController {

    @GetMapping("/health")
    @Operation(summary = "Health check")
    public ResponseEntity<ApiResponse<Map<String, Object>>> healthCheck() {
        Map<String, Object> status = Map.of(
                "status", "UP",
                "service", "ebs_biocrop_web",
                "database", "MongoDB Atlas (seller_hub)",
                "collections", "users, products",
                "security", "JWT + Spring Security 6"
        );
        return ResponseEntity.ok(ApiResponse.ok("EBS Biocrop Web API is healthy and operational", status));
    }
}
