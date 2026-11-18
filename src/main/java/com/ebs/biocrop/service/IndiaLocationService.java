package com.ebs.biocrop.service;

import com.ebs.biocrop.exception.AppException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class IndiaLocationService {

    private static final Logger log = LoggerFactory.getLogger(IndiaLocationService.class);
    private static final Pattern PINCODE_PATTERN = Pattern.compile("^[1-9][0-9]{5}$");
    private static final int MAX_PIN_CACHE_SIZE = 5000;

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final Map<String, List<String>> citiesByState = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final List<String> states = new ArrayList<>();
    private final ConcurrentHashMap<String, Map<String, Object>> pincodeCache = new ConcurrentHashMap<>();

    public IndiaLocationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(6000);
        this.restTemplate = new RestTemplate(factory);
    }

    @PostConstruct
    void init() {
        try (InputStream is = new ClassPathResource("data/india_states_cities.json").getInputStream()) {
            Map<String, List<String>> loaded = objectMapper.readValue(is, new TypeReference<>() {});
            loaded.forEach((state, cities) -> {
                if (state != null && !state.isBlank()) {
                    List<String> cleanCities = cities == null
                            ? List.of()
                            : cities.stream().filter(c -> c != null && !c.isBlank()).distinct().sorted().toList();
                    citiesByState.put(state.trim(), cleanCities);
                }
            });
            states.clear();
            states.addAll(citiesByState.keySet());
            log.info("[LOCATIONS] Loaded {} Indian states/UTs from classpath dataset.", states.size());
        } catch (Exception ex) {
            log.error("[LOCATIONS] Failed to load india_states_cities.json: {}", ex.getMessage(), ex);
        }
    }

    public List<String> getStates() {
        return Collections.unmodifiableList(states);
    }

    public List<String> getCities(String state) {
        String normalized = normalizeState(state);
        if (normalized.isEmpty()) return List.of();
        return citiesByState.getOrDefault(normalized, List.of());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> lookupPincode(String pincode) {
        String clean = pincode == null ? "" : pincode.trim();
        if (!PINCODE_PATTERN.matcher(clean).matches()) {
            throw new AppException("PIN code must be a valid 6-digit Indian postal code", HttpStatus.BAD_REQUEST);
        }

        Map<String, Object> cached = pincodeCache.get(clean);
        if (cached != null) {
            return cached;
        }

        try {
            ResponseEntity<List> response = restTemplate.getForEntity(
                    "https://api.postalpincode.in/pincode/" + clean,
                    List.class);
            List<Object> body = response.getBody();
            if (body == null || body.isEmpty() || !(body.get(0) instanceof Map<?, ?> first)) {
                throw new AppException("PIN code not found", HttpStatus.NOT_FOUND);
            }
            if (!"Success".equalsIgnoreCase(String.valueOf(first.get("Status")))) {
                throw new AppException("PIN code not found", HttpStatus.NOT_FOUND);
            }
            Object rawOffices = first.get("PostOffice");
            if (!(rawOffices instanceof List<?> offices) || offices.isEmpty()) {
                throw new AppException("PIN code not found", HttpStatus.NOT_FOUND);
            }

            String resolvedState = "";
            String primaryCity = "";
            Set<String> extraCities = new LinkedHashSet<>();

            for (Object item : offices) {
                if (!(item instanceof Map<?, ?> po)) continue;
                String st = normalizeState(po.get("State") == null ? "" : String.valueOf(po.get("State")));
                if (resolvedState.isEmpty() && !st.isEmpty()) {
                    resolvedState = st;
                }
                String district = po.get("District") == null ? "" : String.valueOf(po.get("District")).trim();
                String block = po.get("Block") == null ? "" : String.valueOf(po.get("Block")).trim();
                if (!district.isEmpty() && !"NA".equalsIgnoreCase(district)) {
                    if (primaryCity.isEmpty()) primaryCity = district;
                    extraCities.add(district);
                }
                if (!block.isEmpty() && !"NA".equalsIgnoreCase(block)) {
                    extraCities.add(block);
                }
            }

            if (resolvedState.isEmpty()) {
                throw new AppException("Unable to resolve state for PIN code " + clean, HttpStatus.NOT_FOUND);
            }
            if (primaryCity.isEmpty() && !extraCities.isEmpty()) {
                primaryCity = extraCities.iterator().next();
            }

            Set<String> mergedCities = new LinkedHashSet<>(getCities(resolvedState));
            mergedCities.addAll(extraCities);
            List<String> sortedCities = mergedCities.stream().sorted().toList();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("pincode", clean);
            result.put("state", resolvedState);
            result.put("primaryCity", primaryCity);
            result.put("cities", sortedCities);

            if (pincodeCache.size() >= MAX_PIN_CACHE_SIZE) {
                pincodeCache.clear();
            }
            Map<String, Object> immutable = Collections.unmodifiableMap(result);
            pincodeCache.put(clean, immutable);
            return immutable;
        } catch (AppException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("[LOCATIONS] India Post lookup failed for pincode={}: {}", clean, ex.getMessage());
            throw new AppException("Unable to look up PIN code right now", HttpStatus.BAD_GATEWAY);
        }
    }

    public String normalizeState(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "";
        for (String known : states) {
            if (known.equalsIgnoreCase(trimmed)) {
                return known;
            }
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.contains("delhi")) return "Delhi";
        if (lower.contains("jammu")) return "Jammu and Kashmir";
        if (lower.contains("andaman")) return "Andaman and Nicobar Islands";
        if (lower.contains("dadra") || lower.contains("daman") || lower.contains("diu")) {
            return "Dadra and Nagar Haveli and Daman and Diu";
        }
        if ("orissa".equals(lower)) return "Odisha";
        if ("pondicherry".equals(lower)) return "Puducherry";
        return trimmed;
    }
}
