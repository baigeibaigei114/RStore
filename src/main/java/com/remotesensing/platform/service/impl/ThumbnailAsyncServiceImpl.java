package com.remotesensing.platform.service.impl;

import com.remotesensing.platform.common.enums.ThumbnailStatus;
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
            // 队列满属于调度失败，保持 PENDING 等待定时补偿重新提交。
            log.warn("缩略图任务被线程池拒绝，保持 PENDING 等待重试，imageId={}, reason={}", imageId, exception.getMessage());
        }
    }

    private void generateThumbnailSafely(Long imageId) {
        Path tempDir = null;
        String thumbnailObjectKey = null;
        try {
            RsImage image = imageMapper.selectById(imageId);
            if (image == null) {
                updateThumbnailStatus(imageId, null, ThumbnailStatus.SKIPPED, "影像不存在或已删除");
                log.info("影像不存在或已删除，跳过缩略图生成，imageId={}", imageId);
                return;
            }
            if (image.getThumbnailObjectKey() != null && !image.getThumbnailObjectKey().isBlank()) {
                updateThumbnailStatus(imageId, null, ThumbnailStatus.SUCCESS, null);
                log.info("缩略图已存在，跳过重复生成，imageId={}, objectKey={}", imageId, image.getThumbnailObjectKey());
                return;
            }
            if (updateThumbnailStatus(imageId, ThumbnailStatus.PENDING, ThumbnailStatus.RUNNING, null) <= 0) {
                log.info("缩略图任务未从 PENDING 抢占成功，imageId={}", imageId);
                return;
            }

            tempDir = Files.createTempDirectory("rs-thumb-async-");
            Path rawFile = tempDir.resolve("source.tif");
            thumbnailObjectKey = buildThumbnailObjectKey(image);

            // 异步生成使用独立临时文件，上传请求可以及时清理自己的临时目录。
            minioService.downloadObject(image.getObjectKey(), rawFile);
            geoTiffThumbnailService.generateAndUpload(rawFile, thumbnailObjectKey);

            int updated = updateThumbnailInTransaction(imageId, thumbnailObjectKey);
            if (updated <= 0) {
                deleteObjectQuietly(thumbnailObjectKey);
                log.warn("缩略图已生成但影像记录回写失败，已补偿删除对象，imageId={}, objectKey={}", imageId, thumbnailObjectKey);
                return;
            }
            log.info("缩略图生成成功，imageId={}, objectKey={}", imageId, thumbnailObjectKey);
        } catch (Exception exception) {
            deleteObjectQuietly(thumbnailObjectKey);
            updateThumbnailStatus(imageId, ThumbnailStatus.RUNNING, ThumbnailStatus.FAILED, truncate(exception.getMessage()));
            log.warn("缩略图生成失败，imageId={}, reason={}", imageId, exception.getMessage(), exception);
        } finally {
            deleteDirectoryQuietly(tempDir);
        }
    }

    private int updateThumbnailInTransaction(Long imageId, String thumbnailObjectKey) {
        Integer updated = transactionTemplate.execute(status ->
                imageMapper.updateThumbnailObjectKey(imageId, thumbnailObjectKey));
        return updated == null ? 0 : updated;
    }

    private int updateThumbnailStatus(Long imageId, ThumbnailStatus fromStatus, ThumbnailStatus toStatus, String errorMessage) {
        Integer updated = transactionTemplate.execute(status ->
                imageMapper.updateThumbnailStatus(
                        imageId,
                        fromStatus == null ? null : fromStatus.dbValue(),
                        toStatus.dbValue(),
                        truncate(errorMessage)
                ));
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
