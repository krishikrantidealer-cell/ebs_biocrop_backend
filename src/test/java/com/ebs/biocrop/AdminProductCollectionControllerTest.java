package com.ebs.biocrop;

import com.ebs.biocrop.dto.request.DeleteCollectionsRequest;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.repository.ProductCollectionRepository;
import com.ebs.biocrop.repository.ProductRepository;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.security.user.CustomUserDetails;
import com.ebs.biocrop.service.ProductCollectionService;
import com.ebs.biocrop.service.RateLimitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminProductCollectionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProductCollectionService productCollectionService;

    @MockitoBean
    private ProductCollectionRepository productCollectionRepository;

    @MockitoBean
    private ProductRepository productRepository;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private RateLimitService rateLimitService;

    private CustomUserDetails createAdminUser() {
        User u = new User();
        u.setId(new ObjectId().toHexString());
        u.setRole(UserRole.ROLE_ADMIN);
        return CustomUserDetails.build(u);
    }

    @Test
    @DisplayName("Permanently delete single collection via path parameter")
    void permanentlyDeleteSingleCollection_viaPath() throws Exception {
        CustomUserDetails admin = createAdminUser();
        String colId = new ObjectId().toHexString();

        when(productCollectionService.permanentlyDeleteMany(eq(List.of(colId))))
                .thenReturn(List.of(colId));

        mockMvc.perform(delete("/api/v1/admin/collections/" + colId)
                        .with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Collection permanently deleted"))
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.items[0]").value(colId));

        verify(productCollectionService).permanentlyDeleteMany(eq(List.of(colId)));
    }

    @Test
    @DisplayName("Permanently delete multiple collections via body")
    void permanentlyDeleteMultipleCollections_viaBody() throws Exception {
        CustomUserDetails admin = createAdminUser();
        String col1 = new ObjectId().toHexString();
        String col2 = new ObjectId().toHexString();
        DeleteCollectionsRequest request = new DeleteCollectionsRequest(List.of(col1, col2));

        when(productCollectionService.permanentlyDeleteMany(eq(List.of(col1, col2))))
                .thenReturn(List.of(col1, col2));

        mockMvc.perform(delete("/api/v1/admin/collections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Collections permanently deleted"))
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.items[0]").value(col1))
                .andExpect(jsonPath("$.data.items[1]").value(col2));

        verify(productCollectionService).permanentlyDeleteMany(eq(List.of(col1, col2)));
    }

    @Test
    @DisplayName("Permanently delete multiple collections via /permanent endpoint")
    void permanentlyDeleteMultipleCollections_viaPermanentEndpoint() throws Exception {
        CustomUserDetails admin = createAdminUser();
        String col1 = new ObjectId().toHexString();
        DeleteCollectionsRequest request = new DeleteCollectionsRequest(List.of(col1));

        when(productCollectionService.permanentlyDeleteMany(eq(List.of(col1))))
                .thenReturn(List.of(col1));

        mockMvc.perform(delete("/api/v1/admin/collections/permanent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Collection permanently deleted"))
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.items[0]").value(col1));

        verify(productCollectionService).permanentlyDeleteMany(eq(List.of(col1)));
    }

    @Test
    @DisplayName("Permanently delete collections rejects when no IDs provided")
    void permanentlyDeleteCollections_rejectsEmpty() throws Exception {
        CustomUserDetails admin = createAdminUser();

        mockMvc.perform(delete("/api/v1/admin/collections")
                        .with(user(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("At least one collection ID is required for permanent deletion"));
    }
}
