package com.ebs.biocrop.service;

import com.ebs.biocrop.exception.AppException;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

@Service
public class ProductImageVariantService {

    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_IMAGE_DIMENSION = 10_000;
    private static final long MAX_IMAGE_PIXELS = 20_000_000L;

    private static final int MID_MAX_DIMENSION = 1200;
    private static final int LOW_MAX_DIMENSION = 400;

    public void validateImage(byte[] imageBytes) {
        validateImageSize(imageBytes);
        try {
            inspect(imageBytes);
        } catch (IOException exception) {
            throw badRequest("Image could not be decoded");
        }
    }

    public Variants createVariants(byte[] originalBytes) {
        validateImageSize(originalBytes);

        try {
            ImageInfo info = inspect(originalBytes);

            return new Variants(
                    originalBytes,
                    resize(originalBytes, info, MID_MAX_DIMENSION, 0.82),
                    resize(originalBytes, info, LOW_MAX_DIMENSION, 0.65),
                    info.extension(),
                    info.contentType());
        } catch (IOException exception) {
            throw badRequest("Image could not be decoded");
        }
    }

    private void validateImageSize(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw badRequest("Image file is empty");
        }
        if (imageBytes.length > MAX_IMAGE_BYTES) {
            throw badRequest("Image must be 8 MB or smaller");
        }
    }

    private ImageInfo inspect(byte[] bytes) throws IOException {
        try (ImageInputStream input =
                     ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw badRequest("File is not a supported image");
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw badRequest("Only JPEG and PNG images are supported");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);

                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                String extension;
                String contentType;

                if (format.equals("jpeg") || format.equals("jpg")) {
                    extension = "jpg";
                    contentType = "image/jpeg";
                } else if (format.equals("png")) {
                    extension = "png";
                    contentType = "image/png";
                } else {
                    throw badRequest("Only JPEG and PNG images are supported");
                }

                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long) width * height;

                if (width < 1 || height < 1
                        || width > MAX_IMAGE_DIMENSION
                        || height > MAX_IMAGE_DIMENSION
                        || pixels > MAX_IMAGE_PIXELS) {
                    throw badRequest("Image dimensions exceed the allowed limit");
                }

                return new ImageInfo(width, height, extension, contentType);
            } finally {
                reader.dispose();
            }
        }
    }

    private byte[] resize(
            byte[] source,
            ImageInfo info,
            int maxDimension,
            double quality) throws IOException {

        double scale = Math.min(
                1.0,
                (double) maxDimension / Math.max(info.width(), info.height()));

        int targetWidth = Math.max(1, (int) Math.round(info.width() * scale));
        int targetHeight = Math.max(1, (int) Math.round(info.height() * scale));

        ByteArrayOutputStream output = new ByteArrayOutputStream();

        Thumbnails.of(new ByteArrayInputStream(source))
                .size(targetWidth, targetHeight)
                .keepAspectRatio(true)
                .outputFormat(info.extension())
                .outputQuality(quality)
                .useExifOrientation(true)
                .toOutputStream(output);

        return output.toByteArray();
    }

    private AppException badRequest(String message) {
        return new AppException(message, HttpStatus.BAD_REQUEST);
    }

    private record ImageInfo(
            int width,
            int height,
            String extension,
            String contentType) {
    }

    public record Variants(
            byte[] originalBytes,
            byte[] midBytes,
            byte[] lowBytes,
            String extension,
            String contentType) {
    }
}
