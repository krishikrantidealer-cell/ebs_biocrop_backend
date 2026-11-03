package com.ebs.biocrop;

import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.service.GcsSellerKycDocumentService;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GcsSellerKycDocumentServiceTest {
    @Mock private Storage storage;
    @Mock private UserRepository users;
    private GcsSellerKycDocumentService service;
    private User seller;

    @BeforeEach
    void setUp() {
        service = new GcsSellerKycDocumentService(storage, users, "sellerhub-prod-sellerdata-media-us");
        seller = User.newSeller("9823012345", "seller@example.com");
        seller.setId("seller-123");
        seller.setGstNumber("27AAPFU0939F1ZV");
        when(users.findById("seller-123")).thenReturn(Optional.of(seller));
    }

    @Test
    void uploadsDocumentsPrivatelyAndStoresDedicatedBucketUrisInSeller() {
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var licence = pdf("licenceImage", "licence.pdf");
        var gst = pdf("gstCertificate", "gst.pdf");
        var pan = pdf("panCard", "pan.pdf");
        var aadhaar = pdf("aadhaarCard", "aadhaar.pdf");

        User saved = service.upload("seller-123", List.of(licence), gst, pan, aadhaar, false);

        assertEquals(1, saved.getLicenceImage().size());
        assertTrue(saved.getLicenceImage().getFirst().startsWith(
                "gs://sellerhub-prod-sellerdata-media-us/Sellers/seller-123/kyc/licence/"));
        assertTrue(saved.getGstCertificate().contains("/kyc/gst-certificate/"));
        assertTrue(saved.getPanCard().contains("/kyc/pan-card/"));
        assertTrue(saved.getAadhaarCard().contains("/kyc/aadhaar-card/"));
        assertTrue(saved.hasRequiredSellerKycInformation());
        verify(storage, times(4)).create(any(BlobInfo.class), any(byte[].class), any(Storage.BlobTargetOption[].class));
    }

    @Test
    void rejectsEleventhLicenceFileWithoutUploading() {
        seller.setLicenceImage(java.util.stream.IntStream.range(0, 10)
                .mapToObj(index -> "gs://sellerhub-prod-sellerdata-media-us/Sellers/seller-123/kyc/licence/" + index + ".pdf")
                .toList());

        assertThrows(RuntimeException.class, () -> service.upload("seller-123", List.of(pdf("licenceImage", "extra.pdf")), null, null, null, false));
        verify(storage, never()).create(any(BlobInfo.class), any(byte[].class), any(Storage.BlobTargetOption[].class));
    }

    @Test
    void rejectsOversizedOrUnsupportedFileBeforeGcsWrite() {
        byte[] tooLarge = new byte[8 * 1024 * 1024 + 1];
        MockMultipartFile oversized = new MockMultipartFile("panCard", "pan.pdf", "application/pdf", tooLarge);

        assertThrows(RuntimeException.class, () -> service.upload("seller-123", null, null, oversized, null, false));
        MockMultipartFile fakePdf = new MockMultipartFile("panCard", "pan.pdf", "application/pdf", "not a PDF".getBytes());
        assertThrows(RuntimeException.class, () -> service.upload("seller-123", null, null, fakePdf, null, false));
        verify(storage, never()).create(any(BlobInfo.class), any(byte[].class), any(Storage.BlobTargetOption[].class));
    }

    private MockMultipartFile pdf(String fieldName, String filename) {
        return new MockMultipartFile(fieldName, filename, "application/pdf", "%PDF-1.7\nfixture".getBytes());
    }
}
