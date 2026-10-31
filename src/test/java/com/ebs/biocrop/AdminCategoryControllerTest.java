package com.ebs.biocrop;

import com.ebs.biocrop.dto.request.DeleteCategoriesRequest;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.repository.CategoryRepository;
import com.ebs.biocrop.repository.ProductRepository;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.security.user.CustomUserDetails;
import com.ebs.biocrop.service.CategoryService;
import com.ebs.biocrop.service.GcsImageStorageService;
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
class AdminCategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CategoryService categoryService;

    @MockitoBean
    private CategoryRepository categoryRepository;

    @MockitoBean
    private ProductRepository productRepository;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private GcsImageStorageService gcsImageStorageService;

    @MockitoBean
    private RateLimitService rateLimitService;

    private CustomUserDetails createAdminUser() {
        User u = new User();
        u.setId(new ObjectId().toHexString());
        u.setRole(UserRole.ROLE_ADMIN);
        return CustomUserDetails.build(u);
    }

    @Test
    @DisplayName("Permanently delete single category via path parameter")
    void permanentlyDeleteSingleCategory_viaPath() throws Exception {
        CustomUserDetails admin = createAdminUser();
        String catId = new ObjectId().toHexString();

        when(categoryService.permanentlyDeleteMany(eq(List.of(catId))))
                .thenReturn(List.of(catId));

        mockMvc.perform(delete("/api/v1/admin/categories/" + catId)
                        .with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Category permanently deleted"))
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.items[0]").value(catId));

        verify(categoryService).permanentlyDeleteMany(eq(List.of(catId)));
    }

    @Test
    @DisplayName("Permanently delete multiple categories via body")
    void permanentlyDeleteMultipleCategories_viaBody() throws Exception {
        CustomUserDetails admin = createAdminUser();
        String cat1 = new ObjectId().toHexString();
        String cat2 = new ObjectId().toHexString();
        DeleteCategoriesRequest request = new DeleteCategoriesRequest(List.of(cat1, cat2));

        when(categoryService.permanentlyDeleteMany(eq(List.of(cat1, cat2))))
                .thenReturn(List.of(cat1, cat2));

        mockMvc.perform(delete("/api/v1/admin/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Categories permanently deleted"))
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.items[0]").value(cat1))
                .andExpect(jsonPath("$.data.items[1]").value(cat2));

        verify(categoryService).permanentlyDeleteMany(eq(List.of(cat1, cat2)));
    }

    @Test
    @DisplayName("Permanently delete categories via /permanent endpoint")
    void permanentlyDeleteCategories_viaPermanentEndpoint() throws Exception {
        CustomUserDetails admin = createAdminUser();
        String cat1 = new ObjectId().toHexString();
        DeleteCategoriesRequest request = new DeleteCategoriesRequest(List.of(cat1));

        when(categoryService.permanentlyDeleteMany(eq(List.of(cat1))))
                .thenReturn(List.of(cat1));

        mockMvc.perform(delete("/api/v1/admin/categories/permanent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Category permanently deleted"))
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.items[0]").value(cat1));

        verify(categoryService).permanentlyDeleteMany(eq(List.of(cat1)));
    }
}
