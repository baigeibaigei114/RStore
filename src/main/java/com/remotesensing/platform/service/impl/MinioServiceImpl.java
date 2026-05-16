package com.remotesensing.platform.service.impl;

import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.config.MinioProperties;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.FileObjectMapper;
import com.remotesensing.platform.service.MinioService;
import com.remotesensing.platform.vo.FilePresignedUrlVO;
import com.remotesensing.platform.vo.MinioUploadVO;
import io.minio.BucketExistsArgs;
import io.minio.DownloadObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class MinioServiceImpl implements MinioService {

    private static final DateTimeFormatter YEAR_FORMATTER = DateTimeFormatter.ofPattern("yyyy");
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("MM");
    private static final int DEFAULT_PRESIGNED_EXPIRE_SECONDS = 30 * 60;

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final FileObjectMapper fileObjectMapper;
    private final CurrentUserContext currentUserContext;

    public MinioServiceImpl(MinioClient minioClient,
                            MinioProperties minioProperties,
                            FileObjectMapper fileObjectMapper,
                            CurrentUserContext currentUserContext) {
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
        this.fileObjectMapper = fileObjectMapper;
        this.currentUserContext = currentUserContext;
    }

    @Override
    public MinioUploadVO uploadGeoTiff(Path filePath, String originalFilename, String contentType) {
        validateGeoTiffFile(originalFilename, filePath);
        ensureBucketExists();

        // 原始影像路径按日期分区，便于后续按时间归档和排查对象存储文件。
        String objectKey = buildObjectKey(originalFilename);
        String resolvedContentType = defaultContentType(contentType);
        try (InputStream inputStream = Files.newInputStream(filePath)) {
            long fileSize = Files.size(filePath);
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioProperties.getBucketName())
                    .object(objectKey)
                    .stream(inputStream, fileSize, -1)
                    .contentType(resolvedContentType)
                    .build());
            return new MinioUploadVO(minioProperties.getBucketName(), objectKey, fileSize, resolvedContentType);
        } catch (Exception exception) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "上传文件到 MinIO 失败：" + exception.getMessage());
        }
    }

    @Override
    public FilePresignedUrlVO generatePresignedUrl(String objectKey) {
        validatePresignedObjectKey(objectKey);

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

    @Override
    public void downloadObject(String objectKey, Path targetPath) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "objectKey 不能为空");
        }
        try {
            Files.createDirectories(targetPath.getParent());
            minioClient.downloadObject(DownloadObjectArgs.builder()
                    .bucket(minioProperties.getBucketName())
                    .object(objectKey)
                    .filename(targetPath.toAbsolutePath().toString())
                    .build());
        } catch (Exception exception) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "从 MinIO 下载对象失败：" + exception.getMessage());
        }
    }

    @Override
    public void deleteObject(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }

        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(minioProperties.getBucketName())
                    .object(objectKey)
                    .build());
        } catch (Exception exception) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "删除 MinIO 对象失败：" + exception.getMessage());
        }
    }

    private void ensureBucketExists() {
        try {
            // 本地开发环境常从空 MinIO 启动，上传前自动准备 bucket。
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

    private void validateGeoTiffFile(String originalFilename, Path filePath) {
        if (filePath == null || !Files.isRegularFile(filePath)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "上传文件不能为空");
        }
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "文件名不能为空");
        }
        String lowerFilename = originalFilename.toLowerCase(Locale.ROOT);
        if (!lowerFilename.endsWith(".tif") && !lowerFilename.endsWith(".tiff")) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "只允许上传 .tif 或 .tiff 格式的 GeoTIFF 文件");
        }
    }

    private void validatePresignedObjectKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "objectKey 不能为空");
        }
        // 预签名 URL 会直接暴露私有对象的临时访问能力，先收紧路径形态。
        if (objectKey.contains("..") || objectKey.startsWith("/") || objectKey.contains("\\")) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "objectKey 格式不合法");
        }
        if (!objectKey.startsWith("raw/")
                && !objectKey.startsWith("thumbnail/")
                && !objectKey.startsWith("result/")) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "objectKey 只能访问 raw、thumbnail 或 result 目录");
        }
        // 仅允许访问已经被业务表登记的文件，降低猜测 objectKey 访问私有 bucket 的风险。
        String currentUserId = currentUserContext.getCurrentUserId();
        if (fileObjectMapper.countAccessibleObjectKey(objectKey, currentUserId) <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "objectKey 未在业务表中登记，不能生成访问链接");
        }
    }

    private String buildObjectKey(String originalFilename) {
        LocalDate now = LocalDate.now();
        // 文件名只做路径分隔和空白字符规整，保留原始名称便于人工识别。
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

    private String defaultContentType(String contentType) {
        return contentType == null || contentType.isBlank() ? "image/tiff" : contentType;
    }
}
