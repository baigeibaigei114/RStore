package com.remotesensing.platform.service.impl;

import com.remotesensing.platform.entity.RsImage;
import com.remotesensing.platform.mapper.RsImageMapper;
import com.remotesensing.platform.service.GeoTiffThumbnailService;
import com.remotesensing.platform.service.MinioService;
import com.remotesensing.platform.service.ThumbnailAsyncService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ThumbnailAsyncServiceImpl implements ThumbnailAsyncService {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailAsyncServiceImpl.class);
    private static final DateTimeFormatter YEAR_FORMATTER = DateTimeFormatter.ofPattern("yyyy");
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("MM");
    private static final String THUMBNAIL_PENDING = "PENDING";
    private static final String THUMBNAIL_RUNNING = "RUNNING";
    private static final String THUMBNAIL_FAILED = "FAILED";
    private static final String THUMBNAIL_SKIPPED = "SKIPPED";

    private final Executor thumbnailTaskExecutor;
    private final RsImageMapper imageMapper;
    private final MinioService minioService;
    private final GeoTiffThumbnailService geoTiffThumbnailService;
    private final TransactionTemplate transactionTemplate;

    public ThumbnailAsyncServiceImpl(@Qualifier("thumbnailTaskExecutor") Executor thumbnailTaskExecutor,
                                     RsImageMapper imageMapper,
                                     MinioService minioService,
                                     GeoTiffThumbnailService geoTiffThumbnailService,
                                     PlatformTransactionManager transactionManager) {
        this.thumbnailTaskExecutor = thumbnailTaskExecutor;
        this.imageMapper = imageMapper;
        this.minioService = minioService;
        this.geoTiffThumbnailService = geoTiffThumbnailService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void generateAsync(Long imageId) {
        try {
            thumbnailTaskExecutor.execute(() -> generateThumbnailSafely(imageId));
        } catch (RejectedExecutionException exception) {
            // 调度失败不代表文件或 Python 处理失败，保持 PENDING 交给定时补偿任务再次投递。
            log.warn("缩略图异步任务提交失败，保持 PENDING 等待补偿重试，imageId={}, reason={}", imageId, exception.getMessage());
        }
    }

    private void generateThumbnailSafely(Long imageId) {
        Path tempDir = null;
        String thumbnailObjectKey = null;
        try {
            RsImage image = imageMapper.selectById(imageId);
            if (image == null) {
                updateThumbnailStatus(imageId, null, THUMBNAIL_SKIPPED, "影像不存在或已删除");
                log.info("影像不存在或已删除，跳过缩略图生成，imageId={}", imageId);
                return;
            }
            if (image.getThumbnailObjectKey() != null && !image.getThumbnailObjectKey().isBlank()) {
                updateThumbnailStatus(imageId, null, "SUCCESS", null);
                log.info("影像缩略图已存在，跳过重复生成，imageId={}, objectKey={}", imageId, image.getThumbnailObjectKey());
                return;
            }
            if (updateThumbnailStatus(imageId, THUMBNAIL_PENDING, THUMBNAIL_RUNNING, null) <= 0) {
                log.info("缩略图任务未抢占到 PENDING 状态，跳过执行，imageId={}", imageId);
                return;
            }

            tempDir = Files.createTempDirectory("rs-thumb-async-");
            Path rawFile = tempDir.resolve("source.tif");
            thumbnailObjectKey = buildThumbnailObjectKey(image);

            // 异步任务不依赖上传请求中的临时文件，独立从 MinIO 拉取原始影像，便于请求线程及时清理。
            minioService.downloadObject(image.getObjectKey(), rawFile);
            geoTiffThumbnailService.generateAndUpload(rawFile, thumbnailObjectKey);

            int updated = updateThumbnailInTransaction(imageId, thumbnailObjectKey);
            if (updated <= 0) {
                deleteObjectQuietly(thumbnailObjectKey);
                log.warn("缩略图生成完成但影像已不可回写，已清理缩略图对象，imageId={}, objectKey={}", imageId, thumbnailObjectKey);
                return;
            }
            log.info("缩略图异步生成完成，imageId={}, objectKey={}", imageId, thumbnailObjectKey);
        } catch (Exception exception) {
            deleteObjectQuietly(thumbnailObjectKey);
            updateThumbnailStatus(imageId, THUMBNAIL_RUNNING, THUMBNAIL_FAILED, truncate(exception.getMessage()));
            log.warn("缩略图异步生成失败，imageId={}, reason={}", imageId, exception.getMessage(), exception);
        } finally {
            deleteDirectoryQuietly(tempDir);
        }
    }

    private int updateThumbnailInTransaction(Long imageId, String thumbnailObjectKey) {
        Integer updated = transactionTemplate.execute(status ->
                imageMapper.updateThumbnailObjectKey(imageId, thumbnailObjectKey));
        return updated == null ? 0 : updated;
    }

    private int updateThumbnailStatus(Long imageId, String fromStatus, String toStatus, String errorMessage) {
        Integer updated = transactionTemplate.execute(status ->
                imageMapper.updateThumbnailStatus(imageId, fromStatus, toStatus, truncate(errorMessage)));
        return updated == null ? 0 : updated;
    }

    private String buildThumbnailObjectKey(RsImage image) {
        OffsetDateTime time = image.getCreatedAt() == null ? OffsetDateTime.now() : image.getCreatedAt();
        return "thumbnail/%s/%s/%s.png".formatted(
                YEAR_FORMATTER.format(time),
                MONTH_FORMATTER.format(time),
                image.getId()
        );
    }

    private void deleteObjectQuietly(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            minioService.deleteObject(objectKey);
        } catch (RuntimeException exception) {
            log.warn("缩略图补偿删除失败，objectKey={}, reason={}", objectKey, exception.getMessage());
        }
    }

    private void deleteDirectoryQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(this::deletePathQuietly);
        } catch (IOException exception) {
            log.warn("缩略图临时目录清理失败，path={}, reason={}", dir, exception.getMessage());
        }
    }

    private void deletePathQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("缩略图临时文件清理失败，path={}, reason={}", path, exception.getMessage());
        }
    }

    private String truncate(String message) {
        if (message == null || message.length() <= 1000) {
            return message;
        }
        return message.substring(0, 1000);
    }
}
