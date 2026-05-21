package com.remotesensing.platform.service.impl;

import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.config.properties.MinioProperties;
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
import org.springframework.util.StringUtils;

/**
 * MinIO 对象存储服务实现类。
 *
 * <p>职责：封装 MinIO Java SDK 的上传、下载、删除和预签名 URL 生成等操作，
 * 提供 GeoTIFF 文件上传、本地文件上传、对象下载等业务方法。
 *
 * <p>核心设计点：
 * <ul>
 *   <li>上传路径按 {@code raw/yyyy/MM/} 日期分区，便于后续按时间归档和管理；</li>
 *   <li>预签名 URL 生成时进行多层安全检查：路径格式校验、可访问目录白名单、业务登记校验；</li>
 *   <li>支持外网可访问的公网 Endpoint 配置，通过 {@code publicEndpoint} 与内网上传路径分离；</li>
 *   <li>Bucket 自动创建：上传前检查 Bucket 是否存在，不存在则自动创建，降低运维成本。</li>
 * </ul>
 */
@Service
public class MinioServiceImpl implements MinioService {

    /** 日期格式化器（年），用于构建 yyyy 层级目录。 */
    private static final DateTimeFormatter YEAR_FORMATTER = DateTimeFormatter.ofPattern("yyyy");
    /** 日期格式化器（月），用于构建 MM 层级目录。 */
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("MM");
    /** 预签名 URL 默认过期时间：30 分钟。选择 30 分钟平衡安全性与大文件下载的耗时。 */
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

    /**
     * 上传 GeoTIFF 文件到 MinIO。
     *
     * <p>流程：校验文件类型 -> 确保 Bucket 存在 -> 构建对象路径 -> 上传流式文件。
     * 文件路径按 {@code raw/yyyy/MM/UUID_原始文件名} 格式组织，支持按时间范围浏览。
     *
     * @param filePath         本地临时文件路径
     * @param originalFilename 原始文件名（用于校验扩展名和构建安全文件名）
     * @param contentType      媒体类型，为空时默认使用 image/tiff
     * @return 上传结果（Bucket 名称、对象键、文件大小、内容类型）
     * @throws BusinessException 文件类型不合法或上传失败时抛出
     */
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

    /**
     * 生成对象预签名下载 URL。
     *
     * <p>安全校验层层递进：objectKey 非空 -> 路径格式（无 ..、无绝对路径） -> 目录白名单 -> 业务表登记。
     * 预签名 URL 使用独立构建的 Client（可能使用外网 Endpoint），当前只支持 GET 方法。
     *
     * @param objectKey 对象在 MinIO 中的完整路径
     * @return 预签名 URL 信息（对象键、URL、过期秒数）
     * @throws BusinessException 校验不通过或生成失败时抛出
     */
    @Override
    public FilePresignedUrlVO generatePresignedUrl(String objectKey) {
        validatePresignedObjectKey(objectKey);

        try {
            String url = buildPresignedClient().getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(minioProperties.getBucketName())
                    .object(objectKey)
                    .region(minioProperties.getRegion())
                    .expiry(DEFAULT_PRESIGNED_EXPIRE_SECONDS)
                    .build());
            return new FilePresignedUrlVO(objectKey, url, DEFAULT_PRESIGNED_EXPIRE_SECONDS);
        } catch (Exception exception) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "生成文件预签名 URL 失败：" + exception.getMessage());
        }
    }

    /**
     * 上传本地文件到 MinIO（通用方法，不校验文件类型）。
     *
     * @param filePath    本地文件路径
     * @param objectKey   对象存储目标路径
     * @param contentType 媒体类型
     * @return 上传结果
     * @throws BusinessException 上传失败时抛出
     */
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

    /**
     * 从 MinIO 下载对象到本地路径。
     *
     * <p>先创建目标父目录，再执行下载。MinIO SDK 的 downloadObject 要求目标路径的父目录已存在。
     *
     * @param objectKey  对象键
     * @param targetPath 本地目标文件路径
     * @throws BusinessException 下载失败或 objectKey 为空时抛出
     */
    @Override
    public void downloadObject(String objectKey, Path targetPath) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "objectKey 不能为空");
        }
        try {
            // 确保父目录存在，避免 downloadObject 因目录不存在而失败。
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

    /**
     * 删除 MinIO 中的对象。
     *
     * <p>objectKey 为空时直接返回，避免对空键误操作。
     *
     * @param objectKey 要删除的对象键
     * @throws BusinessException 删除失败时抛出
     */
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

    private MinioClient buildPresignedClient() {
        String endpoint = StringUtils.hasText(minioProperties.getPublicEndpoint())
                ? minioProperties.getPublicEndpoint()
                : minioProperties.getEndpoint();
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(minioProperties.getAccessKey(), minioProperties.getSecretKey())
                .region(minioProperties.getRegion())
                .build();
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
