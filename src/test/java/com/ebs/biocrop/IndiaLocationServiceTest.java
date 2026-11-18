package com.ebs.biocrop;

import com.ebs.biocrop.service.IndiaLocationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndiaLocationServiceTest {

    @Test
    void loadsAll36IndianStatesAndUtsWithDeduplicatedUrbanCities() {
        IndiaLocationService service = new IndiaLocationService(new ObjectMapper());
        ReflectionTestUtils.invokeMethod(service, "init");

        List<String> states = service.getStates();
        assertEquals(36, states.size(), "Expected 28 Indian states + 8 Union Territories = 36");

        for (String state : states) {
            List<String> cities = service.getCities(state);
            assertFalse(cities.isEmpty(), "Expected non-empty urban cities for " + state);
            Set<String> uniqueLower = new HashSet<>();
            Set<String> uniqueCanonical = new HashSet<>();
            for (String city : cities) {
                assertTrue(uniqueLower.add(city.toLowerCase()), "Duplicate city in " + state + ": " + city);
                String canonical = city.toLowerCase().replaceAll("[^a-z0-9]", "");
                assertTrue(uniqueCanonical.add(canonical), "Near-duplicate city in " + state + ": " + city);
            }
        }

        assertTrue(service.getCities("Gujarat").contains("Ahmedabad"));
        assertTrue(service.getCities("Madhya Pradesh").contains("Indore"));
        assertEquals("Dadra and Nagar Haveli and Daman and Diu", service.normalizeState("Dadra and Nagar Haveli"));
    }
}
