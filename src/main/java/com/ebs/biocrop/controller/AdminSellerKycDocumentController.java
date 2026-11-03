package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.response.AdminUserResponse;
import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.security.user.CustomUserDetails;
import com.ebs.biocrop.service.GcsSellerKycDocumentService;
import com.ebs.biocrop.service.RateLimitGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/users/{userId}/kyc-documents")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin seller onboarding")
@SecurityRequirement(name = "bearerAuth")
public class AdminSellerKycDocumentController {
    private final GcsSellerKycDocumentService documents;
    private final RateLimitGuard rateLimitGuard;

    public AdminSellerKycDocumentController(GcsSellerKycDocumentService documents, RateLimitGuard rateLimitGuard) {
        this.documents = documents;
        this.rateLimitGuard = rateLimitGuard;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload private seller KYC documents to GCS",
            description = "Upload licenceImage files (up to 10 total) and/or one gstCertificate, panCard, or aadhaarCard. Each file must be at most 8 MB and a JPEG, PNG, WEBP, or PDF.")
    public ResponseEntity<ApiResponse<AdminUserResponse>> uploadDocuments(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String userId,
            @RequestParam(name = "licenceImage", required = false) List<MultipartFile> licenceFiles,
            @RequestParam(name = "gstCertificate", required = false) MultipartFile gstCertificate,
            @RequestParam(name = "panCard", required = false) MultipartFile panCard,
            @RequestParam(name = "aadhaarCard", required = false) MultipartFile aadhaarCard,
            @RequestParam(name = "replaceLicenceFiles", defaultValue = "false") boolean replaceLicenceFiles) {
        rateLimitGuard.enforce("admin-user-write", admin.getId(), 20, Duration.ofMinutes(1));
        User saved = documents.upload(userId, licenceFiles, gstCertificate, panCard, aadhaarCard, replaceLicenceFiles);
        return ResponseEntity.ok(ApiResponse.ok("Seller KYC documents uploaded to private storage", AdminUserResponse.from(saved)));
    }

    @org.springframework.web.bind.annotation.GetMapping("/{documentType}/{index}")
    @Operation(summary = "Download a seller KYC document through the admin API")
    public ResponseEntity<byte[]> downloadDocument(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String userId,
            @PathVariable String documentType,
            @PathVariable int index) {
        rateLimitGuard.enforce("admin-user-read", admin.getId(), 60, Duration.ofMinutes(1));
        GcsSellerKycDocumentService.StoredDocument file = documents.download(userId, documentType, index);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(file.bytes());
    }
}
