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

/**
 * 异步缩略图生成服务实现类。
 *
 * <p>职责：从 MinIO 下载源 GeoTIFF 文件，在异步线程中生成缩略图，上传回 MinIO，
 * 并更新数据库中的缩略图状态及对象键。</p>
 *
 * <p>核心设计点：</p>
 * <ul>
 *   <li>使用独立线程池 {@code thumbnailTaskExecutor} 执行生成任务，不阻塞请求线程；</li>
 *   <li>线程池满时通过 {@link java.util.concurrent.RejectedExecutionException} 优雅降级，保持 PENDING 状态等待定时补偿；</li>
 *   <li>状态机流转：PENDING -> RUNNING -> SUCCESS / FAILED / SKIPPED，其中 PENDING -> RUNNING 使用乐观 CAS 语义（通过数据库更新行数判断）；</li>
 *   <li>缩略图已存在时跳过生成（幂等设计）；</li>
 *   <li>数据库回写失败时主动删除已上传的 MinIO 对象，避免产生孤儿对象；</li>
 *   <li>临时目录在 finally 中递归删除，防止临时文件残留。</li>
 * </ul>
 */
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

    /**
     * 异步生成指定影像记录的缩略图。
     *
     * <p>将任务提交到 {@code thumbnailTaskExecutor} 线程池后立即返回。如果线程池队列已满，
     * 抛出 {@link RejectedExecutionException}，此时影像的缩略图状态保持 PENDING，
     * 等待 {@link ThumbnailRetryScheduler} 定时补偿重试。</p>
     *
     * <p>注意：该方法不校验 imageId 的有效性——真正的校验推迟到异步线程内部，
     * 因为此时数据库行可能尚未提交（在事务中调用本方法时）。</p>
     *
     * @param imageId 影像记录主键 ID
     */
    @Override
    public void generateAsync(Long imageId) {
        try {
            thumbnailTaskExecutor.execute(() -> generateThumbnailSafely(imageId));
        } catch (RejectedExecutionException exception) {
            // 队列满属于调度失败，保持 PENDING 等待定时补偿重新提交。
            log.warn("缩略图任务被线程池拒绝，保持 PENDING 等待重试，imageId={}, reason={}", imageId, exception.getMessage());
        }
    }

    /**
     * 在异步线程中安全地执行缩略图生成全流程。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>查询影像记录，为空则标记 SKIPPED；</li>
     *   <li>缩略图已存在则标记 SUCCESS（幂等跳过）；</li>
     *   <li>CAS 更新状态从 PENDING -> RUNNING，失败则放弃（说明被其他线程抢占）；</li>
     *   <li>从 MinIO 下载源文件、生成缩略图、上传回 MinIO、回写数据库；</li>
     *   <li>任何异常将状态置为 FAILED 并记录错误消息。</li>
     * </ol>
     *
     * <p>错误处理策略：</p>
     * <ul>
     *   <li>数据库回写失败时补偿删除已上传的 MinIO 对象，防止孤儿对象；</li>
     *   <li>临时目录在 finally 中递归清理，不干扰异常传递。</li>
     * </ul>
     */
    private void generateThumbnailSafely(Long imageId) {
        Path tempDir = null;
        String thumbnailObjectKey = null;
        try {
            // 步骤1: 查询影像记录，确认其存在性和当前状态。
            RsImage image = imageMapper.selectById(imageId);
            if (image == null) {
                // 影像已删除，标记 SKIPPED 并记录日志，避免无效重试。
                updateThumbnailStatus(imageId, null, ThumbnailStatus.SKIPPED, "影像不存在或已删除");
                log.info("影像不存在或已删除，跳过缩略图生成，imageId={}", imageId);
                return;
            }
            // 步骤2: 缩略图已存在则跳过（幂等性保证），适用于重复提交的场景。
            if (image.getThumbnailObjectKey() != null && !image.getThumbnailObjectKey().isBlank()) {
                updateThumbnailStatus(imageId, null, ThumbnailStatus.SUCCESS, null);
                log.info("缩略图已存在，跳过重复生成，imageId={}, objectKey={}", imageId, image.getThumbnailObjectKey());
                return;
            }

            // 步骤3: CAS 方式将状态从 PENDING 抢占为 RUNNING。
            // 只有更新行数 > 0 才表示抢占成功，防止多线程/多实例并发重复生成。
            if (updateThumbnailStatus(imageId, ThumbnailStatus.PENDING, ThumbnailStatus.RUNNING, null) <= 0) {
                log.info("缩略图任务未从 PENDING 抢占成功，imageId={}", imageId);
                return;
            }

            // 步骤4: 执行缩略图生成核心逻辑。
            tempDir = Files.createTempDirectory("rs-thumb-async-");
            Path rawFile = tempDir.resolve("source.tif");
            thumbnailObjectKey = buildThumbnailObjectKey(image);

            // 异步生成使用独立临时文件，上传请求可以及时清理自己的临时目录。
            minioService.downloadObject(image.getObjectKey(), rawFile);
            geoTiffThumbnailService.generateAndUpload(rawFile, thumbnailObjectKey);

            // 步骤5: 在事务中回写缩略图对象键到数据库。
            int updated = updateThumbnailInTransaction(imageId, thumbnailObjectKey);
            if (updated <= 0) {
                // 回写失败说明记录可能已被删除或状态被其他流程变更，补偿删除已上传的孤儿对象。
                deleteObjectQuietly(thumbnailObjectKey);
                log.warn("缩略图已生成但影像记录回写失败，已补偿删除对象，imageId={}, objectKey={}", imageId, thumbnailObjectKey);
                return;
            }
            log.info("缩略图生成成功，imageId={}, objectKey={}", imageId, thumbnailObjectKey);
        } catch (Exception exception) {
            // 异常处理：删除可能已上传的孤儿对象，并将状态置为 FAILED。
            deleteObjectQuietly(thumbnailObjectKey);
            updateThumbnailStatus(imageId, ThumbnailStatus.RUNNING, ThumbnailStatus.FAILED, truncate(exception.getMessage()));
            log.warn("缩略图生成失败，imageId={}, reason={}", imageId, exception.getMessage(), exception);
        } finally {
            // 无论成功还是失败，保证临时目录被递归删除。
            deleteDirectoryQuietly(tempDir);
        }
    }

    /**
     * 在独立事务中更新影像记录的缩略图对象键。
     * <p>使用 {@link TransactionTemplate} 确保缩略图键的更新在一个独立事务中执行，
     * 避免与外部事务混合导致回滚范围过大。</p>
     *
     * @param imageId            影像记录 ID
     * @param thumbnailObjectKey 缩略图 MinIO 对象键
     * @return 数据库更新行数，0 表示未命中（记录可能已被删除）
     */
    private int updateThumbnailInTransaction(Long imageId, String thumbnailObjectKey) {
        Integer updated = transactionTemplate.execute(status ->
                imageMapper.updateThumbnailObjectKey(imageId, thumbnailObjectKey));
        return updated == null ? 0 : updated;
    }

    /**
     * 在独立事务中更新缩略图状态。
     *
     * <p>当 {@code fromStatus} 不为 null 时，执行乐观锁语义的状态转换（如 PENDING -> RUNNING），
     * 仅当当前状态匹配时才更新。当 {@code fromStatus} 为 null 时，直接无条件更新目标状态。</p>
     *
     * @param imageId      影像记录 ID
     * @param fromStatus   当前预期状态，null 表示不校验旧状态
     * @param toStatus     目标状态
     * @param errorMessage 错误消息（仅当 toStatus 为 FAILED 时有效）
     * @return 数据库更新行数
     */
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

    /**
     * 构建缩略图在 MinIO 中的对象键。
     *
     * <p>路径格式：{@code thumbnail/{yyyy}/{MM}/{imageId}.png}，按创建时间分目录存储，
     * 避免单个前缀下对象过多导致 MinIO 列出性能下降。</p>
     *
     * @param image 影像记录
     * @return 缩略图对象键，例如 {@code thumbnail/2025/03/42.png}
     */
    private String buildThumbnailObjectKey(RsImage image) {
        OffsetDateTime time = image.getCreatedAt() == null ? OffsetDateTime.now() : image.getCreatedAt();
        return "thumbnail/%s/%s/%s.png".formatted(
                YEAR_FORMATTER.format(time),
                MONTH_FORMATTER.format(time),
                image.getId()
        );
    }

    /**
     * 安静删除 MinIO 对象，忽略异常。
     *
     * <p>用于补偿删除已上传但回写数据库失败的孤儿缩略图对象。
     * 删除失败仅记录警告日志，避免因对象存储短暂不可用导致主流程异常。</p>
     *
     * @param objectKey MinIO 对象键
     */
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

    /**
     * 安静递归删除目录及其所有子文件和子目录，忽略 I/O 异常。
     *
     * <p>先遍历所有文件按逆序删除（先删文件，再删空目录），避免因空目录检测失败导致整棵树删不干净。</p>
     *
     * @param dir 待删除的目录路径
     */
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

    /**
     * 安静删除单个文件或空目录。
     *
     * <p>捕获并记录 {@link IOException} 但不重新抛出，确保批量清理过程中单个文件的删除失败
     * 不会阻止其他文件的清理。</p>
     */
    private void deletePathQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("缩略图临时文件清理失败，path={}, reason={}", path, exception.getMessage());
        }
    }

    /**
     * 将异常消息截断到 1000 字符以内。
     *
     * <p>数据库中的错误消息字段通常有长度限制（如 VARCHAR(1024)），
     * 截断可避免因消息过长导致 SQL 异常。</p>
     *
     * @param message 原始异常消息
     * @return 截断后的消息，null 或长度不足 1000 时原样返回
     */
    private String truncate(String message) {
        if (message == null || message.length() <= 1000) {
            return message;
        }
        return message.substring(0, 1000);
    }
}