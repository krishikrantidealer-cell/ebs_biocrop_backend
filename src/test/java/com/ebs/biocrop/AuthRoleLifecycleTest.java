package com.ebs.biocrop;

import com.ebs.biocrop.dto.request.OtpVerifyRequest;
import com.ebs.biocrop.dto.request.SellerEmailRegistrationRequest;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.security.jwt.JwtTokenProvider;
import com.ebs.biocrop.security.user.CustomUserDetails;
import com.ebs.biocrop.service.EmailDeliveryService;
import com.ebs.biocrop.service.EmailOtpService;
import com.ebs.biocrop.service.OtpService;
import com.ebs.biocrop.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthRoleLifecycleTest {
    @Mock private OtpService otpService;
    @Mock private UserRepository userRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private EmailOtpService emailOtpService;
    @Mock private EmailDeliveryService emailDeliveryService;
    @Mock private PasswordEncoder passwordEncoder;

    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(otpService, userRepository, jwtTokenProvider,
                emailOtpService, emailDeliveryService, passwordEncoder);
        lenient().when(jwtTokenProvider.generateAccessToken(anyString(), anyString(), anyString())).thenReturn("access");
        lenient().when(jwtTokenProvider.generateRefreshToken(anyString(), anyString())).thenReturn("refresh");
        lenient().when(jwtTokenProvider.getExpirationMs()).thenReturn(86_400_000L);
    }

    @Test
    void firstPhoneOtpCanRegisterSellerAndMarksPhoneVerified() {
        when(userRepository.findByPhoneNumber("9876543210")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId("seller-id");
            return saved;
        });
        OtpVerifyRequest request = new OtpVerifyRequest("9876543210", "123456");
        request.setRole("SELLER");

        var response = service.verifyOtpAndLogin(request);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertEquals(UserRole.ROLE_SELLER, saved.getValue().getRole());
        assertEquals("ACTIVE", saved.getValue().getStatus());
        assertTrue(saved.getValue().getIsVerified());
        assertFalse(saved.getValue().getIsProfileComplete());
        assertEquals("ROLE_SELLER", response.getRole());
    }

    @Test
    void firstPhoneOtpDefaultsToCustomerAndMarksPhoneVerified() {
        when(userRepository.findByPhoneNumber("9876543216")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId("customer-id");
            return saved;
        });

        var response = service.verifyOtpAndLogin(new OtpVerifyRequest("9876543216", "123456"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertEquals(UserRole.ROLE_CUSTOMER, saved.getValue().getRole());
        assertEquals("ACTIVE", saved.getValue().getStatus());
        assertTrue(saved.getValue().getIsVerified());
        assertEquals("ROLE_CUSTOMER", response.getRole());
    }

    @Test
    void sellerCanRegisterWithEmailOtpAndIsMarkedVerified() {
        when(userRepository.existsByPhoneNumber("9876543211")).thenReturn(false);
        when(userRepository.findByEmailIgnoreCase("seller@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId("seller-id");
            return saved;
        });
        SellerEmailRegistrationRequest request = new SellerEmailRegistrationRequest();
        request.setEmail("SELLER@example.com");
        request.setPhoneNumber("9876543211");
        request.setOtp("123456");

        var response = service.registerSellerWithEmailOtp(request);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertEquals(UserRole.ROLE_SELLER, saved.getValue().getRole());
        assertEquals("ACTIVE", saved.getValue().getStatus());
        assertEquals("seller@example.com", saved.getValue().getEmail());
        assertTrue(saved.getValue().getIsVerified());
        assertEquals("ROLE_SELLER", response.getRole());
    }

    @Test
    void blockedUserCannotLoginByPhoneOtp() {
        User blocked = User.newCustomer("9876543212");
        blocked.setIsBlocked(true);
        when(userRepository.findByPhoneNumber("9876543212")).thenReturn(Optional.of(blocked));

        AppException error = assertThrows(AppException.class, () ->
                service.verifyOtpAndLogin(new OtpVerifyRequest("9876543212", "123456")));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void customerCannotUseEmailOtpOrPasswordLogin() {
        User customer = User.newCustomer("9876543213");
        customer.setEmail("customer@example.com");
        when(userRepository.findByEmailIgnoreCase("customer@example.com")).thenReturn(Optional.of(customer));

        assertThrows(AppException.class,
                () -> service.verifyEmailOtpAndLogin("customer@example.com", "123456"));
        assertThrows(AppException.class,
                () -> service.loginWithPassword("customer@example.com", "secret"));
        verify(jwtTokenProvider, never()).generateAccessToken(anyString(), anyString(), anyString());
    }

    @Test
    void blockedAndSoftDeletedUsersAreDisabledForExistingAccessTokens() {
        User blocked = User.newSeller("9876543214", "blocked@example.com");
        blocked.setIsBlocked(true);
        User deleted = User.newCustomer("9876543215");
        deleted.setIsDeleted(true);

        assertFalse(CustomUserDetails.build(blocked).isEnabled());
        assertFalse(CustomUserDetails.build(deleted).isEnabled());
    }
}
