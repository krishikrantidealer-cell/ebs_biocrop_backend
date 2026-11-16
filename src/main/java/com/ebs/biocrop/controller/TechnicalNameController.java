package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.service.TechnicalNameService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Technical Names", description = "Dynamic technical names master catalog and commission rules")
public class TechnicalNameController {

    private final TechnicalNameService technicalNameService;

    public TechnicalNameController(TechnicalNameService technicalNameService) {
        this.technicalNameService = technicalNameService;
    }

    @GetMapping("/technical-names")
    @Operation(summary = "List active technical names for storefront search (public, no commission exposed)")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listPublic(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String letter) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Technical names fetched",
                technicalNameService.listPublic(search, category, letter)));
    }

    @GetMapping("/seller/technical-names")
    @Operation(summary = "List active technical names with default commission for Seller Hub product forms")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listForSellers(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String letter) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Technical names fetched",
                technicalNameService.listForPanel(search, category, letter, true)));
    }

    @GetMapping("/admin/technical-names")
    @Operation(summary = "List all technical names with commissions for Admin management")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listForAdmin(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String letter,
            @RequestParam(defaultValue = "false") boolean activeOnly) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Technical names fetched",
                technicalNameService.listForPanel(search, category, letter, activeOnly)));
    }

    @PostMapping("/admin/technical-names")
    @Operation(summary = "Create a new technical name and default commission percentage")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody Map<String, Object> body) {
        String name = asString(body.get("name"));
        String category = asString(body.get("category"));
        Double commission = asDouble(body.get("commissionPercentage"));
        Boolean isActive = body.get("isActive") instanceof Boolean b ? b : Boolean.TRUE;
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Technical name created", technicalNameService.create(name, category, commission, isActive)));
    }

    @PutMapping("/admin/technical-names/{id}")
    @Operation(summary = "Update a technical name or its commission percentage")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        String name = asString(body.get("name"));
        String category = asString(body.get("category"));
        Double commission = asDouble(body.get("commissionPercentage"));
        Boolean isActive = body.get("isActive") instanceof Boolean b ? b : null;
        boolean syncProducts = Boolean.TRUE.equals(body.get("syncProducts"));
        return ResponseEntity.ok(ApiResponse.ok(
                "Technical name updated",
                technicalNameService.update(id, name, category, commission, isActive, syncProducts)));
    }

    @DeleteMapping("/admin/technical-names/{id}")
    @Operation(summary = "Delete a technical name entry")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) {
        technicalNameService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("Technical name deleted", null));
    }

    @PostMapping("/admin/technical-names/import")
    @Operation(summary = "Bulk import or update technical names from CSV text")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importCsv(@RequestBody Map<String, Object> body) {
        String csv = asString(body.get("csv"));
        return ResponseEntity.ok(ApiResponse.ok("Technical names imported", technicalNameService.importFromCsv(csv)));
    }

    private static String asString(Object val) {
        return val == null ? null : String.valueOf(val).trim();
    }

    private static Double asDouble(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(val).replace("%", "").trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
