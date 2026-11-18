package com.ebs.biocrop;

import com.ebs.biocrop.controller.TechnicalNameController;
import com.ebs.biocrop.entity.TechnicalName;
import com.ebs.biocrop.repository.TechnicalNameRepository;
import com.ebs.biocrop.service.impl.TechnicalNameServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TechnicalNameControllerTest {

    private TechnicalNameRepository repository;
    private MongoTemplate mongoTemplate;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(TechnicalNameRepository.class);
        mongoTemplate = Mockito.mock(MongoTemplate.class);
        TechnicalNameServiceImpl service = new TechnicalNameServiceImpl(repository, mongoTemplate);
        TechnicalNameController controller = new TechnicalNameController(service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("GET /api/v1/technical-names omits commissionPercentage from public response")
    void publicEndpointOmitsCommission() throws Exception {
        TechnicalName item = new TechnicalName();
        item.setId("tn_1");
        item.setName("Abamectin 1.9% EC");
        item.setLetter("A");
        item.setCommissionPercentage(15.0);
        item.setIsActive(true);

        when(repository.findByIsActiveTrueOrderByNameAsc()).thenReturn(List.of(item));

        mockMvc.perform(get("/api/v1/technical-names"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].name").value("Abamectin 1.9% EC"))
                .andExpect(jsonPath("$.data[0].commissionPercentage").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/admin/technical-names includes commissionPercentage")
    void adminEndpointIncludesCommission() throws Exception {
        TechnicalName item = new TechnicalName();
        item.setId("tn_1");
        item.setName("Acetobacter");
        item.setLetter("A");
        item.setCommissionPercentage(25.0);
        item.setIsActive(true);

        when(repository.findAllByOrderByNameAsc()).thenReturn(List.of(item));

        mockMvc.perform(get("/api/v1/admin/technical-names"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("Acetobacter"))
                .andExpect(jsonPath("$.data[0].commissionPercentage").value(25.0));
    }

    @Test
    @DisplayName("POST /api/v1/admin/technical-names creates entry and normalizes commission")
    void createTechnicalName() throws Exception {
        when(repository.findByNormalizedName("imidacloprid 17.8% sl")).thenReturn(Optional.empty());
        when(repository.save(any(TechnicalName.class))).thenAnswer(inv -> {
            TechnicalName t = inv.getArgument(0);
            t.setId("tn_new");
            return t;
        });

        mockMvc.perform(post("/api/v1/admin/technical-names")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Imidacloprid 17.8% SL\",\"category\":\"Insecticide\",\"commissionPercentage\":15}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value("tn_new"))
                .andExpect(jsonPath("$.data.letter").value("I"))
                .andExpect(jsonPath("$.data.commissionPercentage").value(15.0));
    }
}
