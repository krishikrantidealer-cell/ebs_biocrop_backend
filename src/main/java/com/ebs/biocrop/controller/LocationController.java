package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.service.IndiaLocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/locations")
@Tag(name = "Locations", description = "Indian states, cities, and cached PIN code lookup")
public class LocationController {

    private final IndiaLocationService locationService;

    public LocationController(IndiaLocationService locationService) {
        this.locationService = locationService;
    }

    @GetMapping("/india/states")
    @Operation(summary = "List all Indian States and Union Territories")
    public ResponseEntity<ApiResponse<List<String>>> getIndianStates() {
        return ResponseEntity.ok(ApiResponse.ok("Indian states retrieved", locationService.getStates()));
    }

    @GetMapping("/india/cities")
    @Operation(summary = "List all cities/towns for a given Indian State or UT")
    public ResponseEntity<ApiResponse<List<String>>> getIndianCities(@RequestParam String state) {
        return ResponseEntity.ok(ApiResponse.ok("Indian cities retrieved", locationService.getCities(state)));
    }

    @GetMapping("/pincode/{pincode}")
    @Operation(summary = "Resolve Indian PIN code to State and City/District")
    public ResponseEntity<ApiResponse<Map<String, Object>>> lookupPincode(@PathVariable String pincode) {
        return ResponseEntity.ok(ApiResponse.ok("PIN code resolved", locationService.lookupPincode(pincode)));
    }
}
