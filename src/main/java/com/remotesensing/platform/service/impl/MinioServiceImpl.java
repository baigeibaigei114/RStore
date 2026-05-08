package com.remotesensing.platform.service.impl;

import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.config.MinioProperties;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.service.MinioService;
import com.remotesensing.platform.vo.FilePresignedUrlVO;
import com.remotesensing.platform.vo.MinioUploadVO;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MinioServiceImpl implements MinioService {

    private static final DateTimeFormatter YEAR_FORMATTER = DateTimeFormatter.ofPattern("yyyy");
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("MM");
    private static final int DEFAULT_PRESIGNED_EXPIRE_SECONDS = 30 * 60;

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    public MinioServiceImpl(MinioClient minioClient, MinioProperties minioProperties) {
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
    }

    @Override
    public MinioUploadVO uploadGeoTiff(MultipartFile file) {
        validateGeoTiff(file);
        ensureBucketExists();

        String objectKey = buildObjectKey(file.getOriginalFilename());
        String contentType = resolveContentType(file);
        try (InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioProperties.getBucketName())
                    .object(objectKey)
                    .stream(inputStream, file.getSize(), -1)
                    .contentType(contentType)
                    .build());
            return new MinioUploadVO(minioProperties.getBucketName(), objectKey, file.getSize(), contentType);
        } catch (Exception exception) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "上传文件到 MinIO 失败：" + exception.getMessage());
        }
    }

    @Override
    public FilePresignedUrlVO generatePresignedUrl(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "objectKey 不能为空");
        }

        try {
            String url = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(minioProperties.getBucketName())
                    .object(objectKey)
                    .expiry(DEFAULT_PRESIGNED_EXPIRE_SECONDS)
                    .build());
            return new FilePresignedUrlVO(objectKey, url, DEFAULT_PRESIGNED_EXPIRE_SECONDS);
        } catch (Exception exception) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "生成文件预签名 URL 失败：" + exception.getMessage());
        }
    }

    @Override
    public MinioUploadVO uploadLocalFile(Path filePath, String objectKey, String contentType) {
        ensureBucketExists();
        try (InputStream inputStream = Files.newInputStream(filePath)) {
            long fileSize = Files.size(filePath);
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioProperties.getBucketName())
                    .object(objectKey)
                    .stream(inputStream, fileSize, -1)
                    .contentType(contentType)
                    .build());
            return new MinioUploadVO(minioProperties.getBucketName(), objectKey, fileSize, contentType);
        } catch (Exception exception) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "上传本地文件到 MinIO 失败：" + exception.getMessage());
        }
    }

    private void ensureBucketExists() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(minioProperties.getBucketName())
                    .build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder()
                        .bucket(minioProperties.getBucketName())
                        .build());
            }
        } catch (Exception exception) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "检查或创建 MinIO bucket 失败：" + exception.getMessage());
        }
    }

    private void validateGeoTiff(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "上传文件不能为空");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "文件名不能为空");
        }

        String lowerFilename = originalFilename.toLowerCase(Locale.ROOT);
        if (!lowerFilename.endsWith(".tif") && !lowerFilename.endsWith(".tiff")) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "只允许上传 .tif 或 .tiff 格式的 GeoTIFF 文件");
        }
    }

    private String buildObjectKey(String originalFilename) {
        LocalDate now = LocalDate.now();
        String safeFilename = originalFilename
                .replace("\\", "_")
                .replace("/", "_")
                .replaceAll("\\s+", "_");
        return "raw/%s/%s/%s_%s".formatted(
                YEAR_FORMATTER.format(now),
                MONTH_FORMATTER.format(now),
                UUID.randomUUID(),
                safeFilename
        );
    }

    private String resolveContentType(MultipartFile file) {
        String contentType = file.getContentType();
        return contentType == null || contentType.isBlank() ? "image/tiff" : contentType;
    }
}
