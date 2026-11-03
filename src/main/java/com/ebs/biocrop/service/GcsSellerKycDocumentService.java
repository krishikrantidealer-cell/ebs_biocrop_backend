package com.ebs.biocrop.service;

import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.repository.UserRepository;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Stores seller KYC files privately in the dedicated seller data bucket. */
@Service
public class GcsSellerKycDocumentService {
    private static final Logger log = LoggerFactory.getLogger(GcsSellerKycDocumentService.class);
    private static final long MAX_FILE_SIZE = 8L * 1024 * 1024;
    private static final int MAX_LICENCE_FILES = 10;

    private final Storage storage;
    private final String bucketName;
    private final UserRepository users;

    public GcsSellerKycDocumentService(
            Storage storage,
            UserRepository users,
            @Value("${app.gcs.sellerdata-bucket-name:sellerhub-prod-sellerdata-media-us}") String bucketName) {
        this.storage = storage;
        this.users = users;
        this.bucketName = bucketName;
    }

    public User upload(String sellerId, List<MultipartFile> licenceFiles,
                       MultipartFile gstCertificate, MultipartFile panCard, MultipartFile aadhaarCard,
                       boolean replaceLicenceFiles) {
        User seller = findSeller(sellerId);
        List<MultipartFile> licenses = nonEmpty(licenceFiles);
        // A replacement flag applies only when licence files are part of this request.
        // Updating a GST/PAN/Aadhaar file alone must not clear the existing licence list.
        boolean replaceLicences = replaceLicenceFiles && !licenses.isEmpty();
        int currentCount = replaceLicences || seller.getLicenceImage() == null ? 0 : seller.getLicenceImage().size();
        if (currentCount + licenses.size() > MAX_LICENCE_FILES) {
            throw badRequest("A seller can have at most 10 licence or certificate files");
        }
        MultipartFile gst = nonEmpty(gstCertificate);
        MultipartFile pan = nonEmpty(panCard);
        MultipartFile aadhaar = nonEmpty(aadhaarCard);
        if (licenses.isEmpty() && gst == null && pan == null && aadhaar == null) {
            throw badRequest("At least one seller KYC document must be uploaded");
        }

        List<UploadFile> files = new ArrayList<>();
        for (MultipartFile file : licenses) files.add(validate(file, "licence"));
        if (gst != null) files.add(validate(gst, "gst-certificate"));
        if (pan != null) files.add(validate(pan, "pan-card"));
        if (aadhaar != null) files.add(validate(aadhaar, "aadhaar-card"));

        List<String> uploaded = new ArrayList<>();
        List<String> replaced = new ArrayList<>();
        try {
            List<String> updatedLicences = new ArrayList<>(replaceLicences || seller.getLicenceImage() == null
                    ? List.of() : seller.getLicenceImage());
            if (replaceLicences && seller.getLicenceImage() != null) {
                seller.getLicenceImage().forEach(uri -> rememberOld(replaced, uri, sellerId));
            }
            for (UploadFile file : files) {
                String objectName = "Sellers/" + sellerId + "/kyc/" + file.documentType() + "/"
                        + UUID.randomUUID() + "." + file.extension();
                storage.create(BlobInfo.newBuilder(bucketName, objectName)
                                .setContentType(file.contentType()).build(),
                        file.bytes(), Storage.BlobTargetOption.doesNotExist());
                uploaded.add(objectName);
                String storageUri = "gs://" + bucketName + "/" + objectName;
                switch (file.documentType()) {
                    case "licence" -> updatedLicences.add(storageUri);
                    case "gst-certificate" -> {
                        rememberOld(replaced, seller.getGstCertificate(), sellerId);
                        seller.setGstCertificate(storageUri);
                    }
                    case "pan-card" -> {
                        rememberOld(replaced, seller.getPanCard(), sellerId);
                        seller.setPanCard(storageUri);
                    }
                    case "aadhaar-card" -> {
                        rememberOld(replaced, seller.getAadhaarCard(), sellerId);
                        seller.setAadhaarCard(storageUri);
                    }
                    default -> throw new IllegalStateException("Unexpected KYC document type");
                }
            }
            seller.setLicenceImage(updatedLicences);
            seller.setUpdatedAt(LocalDateTime.now());
            User saved = users.save(seller);
            replaced.forEach(this::deleteObjectSafely);
            return saved;
        } catch (RuntimeException exception) {
            cleanup(uploaded);
            throw exception;
        }
    }

    public StoredDocument download(String sellerId, String documentType, int index) {
        User seller = findSeller(sellerId);
        String storageUri = switch (documentType.toLowerCase(Locale.ROOT)) {
            case "licence" -> seller.getLicenceImage() != null && index >= 0 && index < seller.getLicenceImage().size()
                    ? seller.getLicenceImage().get(index) : null;
            case "gst-certificate" -> index == 0 ? seller.getGstCertificate() : null;
            case "pan-card" -> index == 0 ? seller.getPanCard() : null;
            case "aadhaar-card" -> index == 0 ? seller.getAadhaarCard() : null;
            default -> throw badRequest("Unsupported seller KYC document type");
        };
        String objectName = objectName(storageUri, sellerId);
        Blob blob = storage.get(bucketName, objectName);
        if (blob == null) throw new AppException("Seller KYC document not found in storage", HttpStatus.NOT_FOUND);
        String contentType = blob.getContentType();
        if (contentType == null || !List.of("application/pdf", "image/jpeg", "image/png", "image/webp").contains(contentType)) {
            throw new AppException("Stored seller KYC document has an unsupported content type", HttpStatus.CONFLICT);
        }
        String extension = objectName.substring(objectName.lastIndexOf('.') + 1);
        return new StoredDocument(blob.getContent(), contentType,
                "seller-" + documentType + "-" + index + "." + extension);
    }

    /** Deletes only this seller's KYC objects; product media uses a separate prefix. */
    public int deleteAllForSeller(String sellerId) {
        if (sellerId == null || !sellerId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw badRequest("Seller ID is invalid");
        }
        String prefix = "Sellers/" + sellerId + "/kyc/";
        int deleted = 0;
        try {
            for (Blob blob : storage.list(bucketName, Storage.BlobListOption.prefix(prefix)).iterateAll()) {
                String objectName = blob == null ? null : blob.getName();
                if (objectName == null || !objectName.startsWith(prefix)) continue;
                if (storage.delete(bucketName, objectName)) {
                    deleted++;
                } else {
                    log.warn("GCS did not delete seller KYC object {} from bucket {}", objectName, bucketName);
                }
            }
        } catch (RuntimeException exception) {
            // User deletion has already committed when this method is called. Keep the
            // API result stable and leave an actionable log if storage cleanup fails.
            log.error("Could not finish GCS KYC cleanup for seller {} in bucket {}", sellerId, bucketName, exception);
        }
        return deleted;
    }

    private UploadFile validate(MultipartFile file, String type) {
        if (file.getSize() <= 0 || file.getSize() > MAX_FILE_SIZE) {
            throw badRequest("Each seller KYC file must be between 1 byte and 8 MB");
        }
        try {
            byte[] bytes = file.getBytes();
            String[] detected = detectType(bytes);
            if (detected == null) throw badRequest("Only JPEG, PNG, WEBP, or PDF files are allowed");
            return new UploadFile(type, detected[0], detected[1], bytes);
        } catch (IOException exception) {
            throw new AppException("Could not read a seller KYC document", HttpStatus.BAD_REQUEST);
        }
    }

    private String[] detectType(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff)
            return new String[]{"image/jpeg", "jpg"};
        if (bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G')
            return new String[]{"image/png", "png"};
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P')
            return new String[]{"image/webp", "webp"};
        if (bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-')
            return new String[]{"application/pdf", "pdf"};
        return null;
    }

    private User findSeller(String sellerId) {
        if (sellerId == null || !sellerId.matches("[A-Za-z0-9_-]{1,128}")) throw badRequest("Seller ID is invalid");
        User user = users.findById(sellerId).orElseThrow(() -> new AppException("Seller not found", HttpStatus.NOT_FOUND));
        if (user.getRole() != UserRole.ROLE_SELLER) throw new AppException("KYC documents are only available for sellers", HttpStatus.CONFLICT);
        return user;
    }

    private MultipartFile nonEmpty(MultipartFile file) { return file == null || file.isEmpty() ? null : file; }
    private List<MultipartFile> nonEmpty(List<MultipartFile> files) {
        return files == null ? List.of() : files.stream().filter(file -> file != null && !file.isEmpty()).toList();
    }
    private void rememberOld(List<String> replaced, String uri, String sellerId) {
        if (uri == null) return;
        try { replaced.add(objectName(uri, sellerId)); } catch (RuntimeException ignored) { }
    }
    private String objectName(String uri, String sellerId) {
        String prefix = "gs://" + bucketName + "/";
        if (uri == null || !uri.startsWith(prefix)) throw new AppException("Seller document reference is invalid", HttpStatus.NOT_FOUND);
        String name = uri.substring(prefix.length());
        if (!name.startsWith("Sellers/" + sellerId + "/kyc/") || name.contains(".."))
            throw new AppException("Seller document reference is invalid", HttpStatus.NOT_FOUND);
        return name;
    }
    private void cleanup(List<String> names) { names.forEach(this::deleteObjectSafely); }
    private void deleteObjectSafely(String name) {
        try { storage.delete(bucketName, name); } catch (RuntimeException ignored) { }
    }
    private AppException badRequest(String message) { return new AppException(message, HttpStatus.BAD_REQUEST); }

    private record UploadFile(String documentType, String contentType, String extension, byte[] bytes) { }
    public record StoredDocument(byte[] bytes, String contentType, String filename) { }
}
