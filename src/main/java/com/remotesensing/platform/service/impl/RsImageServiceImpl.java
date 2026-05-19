package com.remotesensing.platform.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.common.PageResult;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.common.enums.ImageStatus;
import com.remotesensing.platform.common.enums.ThumbnailStatus;
import com.remotesensing.platform.common.enums.Visibility;
import com.remotesensing.platform.config.properties.UploadProperties;
import com.remotesensing.platform.dto.RsImageCreateDTO;
import com.remotesensing.platform.dto.RsImageSearchDTO;
import com.remotesensing.platform.entity.RsImage;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.RsImageMapper;
import com.remotesensing.platform.mapper.RsTaskMapper;
import com.remotesensing.platform.service.GeoTiffMetadataService;
import com.remotesensing.platform.service.MinioService;
import com.remotesensing.platform.service.RsImageService;
import com.remotesensing.platform.service.ThumbnailAsyncService;
import com.remotesensing.platform.vo.GeoTiffMetadataVO;
import com.remotesensing.platform.vo.FilePresignedUrlVO;
import com.remotesensing.platform.vo.MinioUploadVO;
import com.remotesensing.platform.vo.RsImageListVO;
import com.remotesensing.platform.vo.RsImageVO;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class RsImageServiceImpl implements RsImageService {

    private static final Logger log = LoggerFactory.getLogger(RsImageServiceImpl.class);
    private static final int DEFAULT_PAGE_NUM = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    private final RsImageMapper imageMapper;
    private final RsTaskMapper taskMapper;
    private final MinioService minioService;
    private final GeoTiffMetadataService geoTiffMetadataService;
    private final ThumbnailAsyncService thumbnailAsyncService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final CurrentUserContext currentUserContext;
    // 大文件上传会占用磁盘 IO、请求线程和 Python 进程，这里先用轻量闸门限制并发。
    private final Semaphore uploadSemaphore;

    public RsImageServiceImpl(RsImageMapper imageMapper,
                              RsTaskMapper taskMapper,
                              MinioService minioService,
                              GeoTiffMetadataService geoTiffMetadataService,
                              ThumbnailAsyncService thumbnailAsyncService,
                              ObjectMapper objectMapper,
                              UploadProperties uploadProperties,
                              PlatformTransactionManager transactionManager,
                              CurrentUserContext currentUserContext) {
        this.imageMapper = imageMapper;
        this.taskMapper = taskMapper;
        this.minioService = minioService;
        this.geoTiffMetadataService = geoTiffMetadataService;
        this.thumbnailAsyncService = thumbnailAsyncService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.currentUserContext = currentUserContext;
        this.uploadSemaphore = new Semaphore(Math.max(1, uploadProperties.getMaxConcurrent()));
    }

    @Override
    @Transactional
    public RsImageVO create(RsImageCreateDTO createDTO) {
        if (imageMapper.countByImageCode(createDTO.getImageCode()) > 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "影像编码已存在");
        }

        RsImage image = toEntity(createDTO);
        image.setOwnerId(currentUserContext.getCurrentUserId());
        image.setVisibility(Visibility.PRIVATE.dbValue());
        try {
            imageMapper.insert(image);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "影像数据不合法，请检查 footprintWkt 或必填字段");
        }
        return getById(image.getId());
    }

    @Override
    public RsImageVO upload(MultipartFile file,
                            String name,
                            String sensor,
                            OffsetDateTime captureTime,
                            BigDecimal cloudPercent) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "影像名称不能为空");
        }
        // 当前没有专门的上传队列，超过并发上限时快速失败，避免请求长时间堆积。
        if (!uploadSemaphore.tryAcquire()) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "当前上传任务较多，请稍后重试");
        }

        LocalGeoTiffFile localFile = null;
        MinioUploadVO uploadVO = null;
        try {
            localFile = saveToLocalTemp(file);
            // 上传主流程只复用这一份临时文件完成解析和原始影像上传，缩略图交给后台任务独立处理。
            GeoTiffMetadataVO metadata = geoTiffMetadataService.parse(localFile.filePath());
            uploadVO = minioService.uploadGeoTiff(localFile.filePath(), localFile.originalFilename(), localFile.contentType());
            RsImage image = buildUploadImage(name, sensor, captureTime, cloudPercent, metadata, uploadVO);
            image.setOwnerId(currentUserContext.getCurrentUserId());
            image.setVisibility(Visibility.PRIVATE.dbValue());
            insertImageInTransaction(image);

            return getById(image.getId());
        } catch (DataIntegrityViolationException exception) {
            // 数据库事务无法回滚 MinIO 对象，失败分支需要主动清理已上传文件。
            deleteObjectQuietly(uploadVO == null ? null : uploadVO.getObjectKey());
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "影像记录保存失败：" + exception.getMostSpecificCause().getMessage());
        } catch (RuntimeException exception) {
            // 非数据库异常同样可能发生在 MinIO 上传之后，统一做对象存储补偿。
            deleteObjectQuietly(uploadVO == null ? null : uploadVO.getObjectKey());
            throw exception;
        } finally {
            deleteLocalFileQuietly(localFile);
            uploadSemaphore.release();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public RsImageVO getById(Long id) {
        return toVO(getAccessibleImage(id));
    }

    @Override
    @Transactional(readOnly = true)
    public FilePresignedUrlVO getDownloadUrl(Long id) {
        RsImage image = getAccessibleImage(id);
        if (isBlank(image.getObjectKey())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "原始影像文件不存在");
        }
        return minioService.generatePresignedUrl(image.getObjectKey());
    }

    @Override
    @Transactional(readOnly = true)
    public FilePresignedUrlVO getThumbnailUrl(Long id) {
        RsImage image = getAccessibleImage(id);
        if (isBlank(image.getThumbnailObjectKey())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "缩略图尚未生成");
        }
        return minioService.generatePresignedUrl(image.getThumbnailObjectKey());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<RsImageVO> page(Integer pageNum, Integer pageSize) {
        int currentPageNum = normalizePageNum(pageNum);
        int currentPageSize = normalizePageSize(pageSize);
        int offset = (currentPageNum - 1) * currentPageSize;

        String currentUserId = currentUserContext.getCurrentUserId();
        List<RsImageVO> records = imageMapper.selectPage(offset, currentPageSize, currentUserId).stream()
                .map(this::toVO)
                .toList();
        long total = imageMapper.count(currentUserId);
        return new PageResult<>(records, total, currentPageNum, currentPageSize);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<RsImageListVO> search(RsImageSearchDTO query, Integer pageNum, Integer pageSize) {
        validateSearchTimeRange(query);
        int currentPageNum = normalizePageNum(pageNum);
        int currentPageSize = normalizePageSize(pageSize);
        int offset = (currentPageNum - 1) * currentPageSize;

        String currentUserId = currentUserContext.getCurrentUserId();
        List<RsImageListVO> records = imageMapper.searchPage(query, offset, currentPageSize, currentUserId).stream()
                .map(this::toListVO)
                .toList();
        long total = imageMapper.countSearch(query, currentUserId);
        return new PageResult<>(records, total, currentPageNum, currentPageSize);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<RsImageListVO> searchByRegion(RsImageSearchDTO query, Integer pageNum, Integer pageSize) {
        if (query.getRegionId() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "regionId 不能为空");
        }
        validateSearchTimeRange(query);
        int currentPageNum = normalizePageNum(pageNum);
        int currentPageSize = normalizePageSize(pageSize);
        int offset = (currentPageNum - 1) * currentPageSize;

        String currentUserId = currentUserContext.getCurrentUserId();
        List<RsImageListVO> records = imageMapper.searchByRegionPage(query, offset, currentPageSize, currentUserId).stream()
                .map(this::toListVO)
                .toList();
        long total = imageMapper.countSearchByRegion(query, currentUserId);
        return new PageResult<>(records, total, currentPageNum, currentPageSize);
    }

    @Override
    @Transactional
    public RsImageVO updateVisibility(Long id, String visibility) {
        Visibility targetVisibility = normalizeVisibility(visibility);
        String currentUserId = currentUserContext.getCurrentUserId();
        int updated = imageMapper.updateVisibility(id, currentUserId, targetVisibility.dbValue());
        if (updated <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "影像不存在或无权修改可见性");
        }
        return getById(id);
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        String currentUserId = currentUserContext.getCurrentUserId();
        RsImage image = imageMapper.selectAccessibleById(id, currentUserId);
        if (image == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "影像记录不存在");
        }
        if (!currentUserId.equals(image.getOwnerId())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "只能删除自己拥有的影像");
        }
        // 活跃处理任务仍依赖原始影像，删除前先用业务状态拦截，而不是依赖数据库外键报错。
        if (taskMapper.countActiveByImageId(id) > 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "影像存在正在执行的处理任务，暂不能删除");
        }

        try {
            // 历史任务需要保留审计链路，这里只隐藏影像资产，不级联清理任务、日志和结果文件。
            int updated = imageMapper.softDeleteIfDeletable(id, currentUserId, currentUserId, "用户删除影像资产");
            if (updated <= 0) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "影像当前状态不允许删除，请确认没有处理任务正在执行");
            }
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "影像删除失败，请检查关联数据");
        }
    }

    private RsImage buildUploadImage(String name,
                                     String sensor,
                                     OffsetDateTime captureTime,
                                     BigDecimal cloudPercent,
                                     GeoTiffMetadataVO metadata,
                                     MinioUploadVO uploadVO) {
        RsImage image = new RsImage();
        image.setImageCode(generateImageCode());
        image.setImageName(name);
        image.setSensorType(sensor);
        image.setAcquisitionTime(captureTime);
        image.setCloudPercent(cloudPercent);
        fillMetadata(image, metadata);
        image.setFileFormat("GeoTIFF");
        image.setFileSize(uploadVO.getFileSize());
        image.setContentType(uploadVO.getContentType());
        image.setMinioBucket(uploadVO.getBucketName());
        image.setObjectKey(uploadVO.getObjectKey());
        image.setStatus(ImageStatus.READY.dbValue());
        image.setThumbnailStatus(ThumbnailStatus.PENDING.dbValue());
        return image;
    }

    private RsImage getAccessibleImage(Long id) {
        RsImage image = imageMapper.selectAccessibleById(id, currentUserContext.getCurrentUserId());
        if (image == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "影像记录不存在");
        }
        return image;
    }

    private void insertImageInTransaction(RsImage image) {
        // 文件处理完成后才开启短事务，只覆盖影像元数据入库这一步。
        transactionTemplate.executeWithoutResult(status -> {
            imageMapper.insert(image);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    thumbnailAsyncService.generateAsync(image.getId());
                }
            });
        });
    }

    private LocalGeoTiffFile saveToLocalTemp(MultipartFile file) {
        validateGeoTiff(file);
        Path tempDir = null;
        Path filePath = null;
        try {
            // 上传链路只落盘一次，后续解析和原始影像上传都复用这份文件。
            tempDir = Files.createTempDirectory("rs-upload-");
            filePath = tempDir.resolve(buildSafeTempFilename(file.getOriginalFilename()));
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
            return new LocalGeoTiffFile(tempDir, filePath, file.getOriginalFilename(), file.getContentType());
        } catch (IOException exception) {
            deletePathQuietly(filePath);
            deleteDirectoryQuietly(tempDir);
            throw new BusinessException(ResultCode.FAIL.getCode(), "保存上传文件到本地临时目录失败：" + exception.getMessage());
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

    private String buildSafeTempFilename(String originalFilename) {
        String safeFilename = originalFilename
                .replace("\\", "_")
                .replace("/", "_")
                .replaceAll("\\s+", "_");
        return UUID.randomUUID() + "_" + safeFilename;
    }

    private RsImage toEntity(RsImageCreateDTO createDTO) {
        RsImage image = new RsImage();
        image.setImageCode(createDTO.getImageCode());
        image.setImageName(createDTO.getImageName());
        image.setSensorType(createDTO.getSensorType());
        image.setSatelliteName(createDTO.getSatelliteName());
        image.setAcquisitionTime(createDTO.getAcquisitionTime());
        image.setCloudPercent(createDTO.getCloudPercent());
        image.setResolutionMeter(createDTO.getResolutionMeter());
        image.setBandCount(createDTO.getBandCount());
        image.setProjection(createDTO.getProjection());
        image.setWidth(createDTO.getWidth());
        image.setHeight(createDTO.getHeight());
        image.setFileFormat(defaultIfBlank(createDTO.getFileFormat(), "GeoTIFF"));
        image.setFileSize(createDTO.getFileSize());
        image.setMinioBucket(createDTO.getMinioBucket());
        image.setObjectKey(createDTO.getObjectKey());
        image.setOverviewObjectKey(createDTO.getOverviewObjectKey());
        image.setFootprintWkt(createDTO.getFootprintWkt());
        image.setCenterLon(createDTO.getCenterLon());
        image.setCenterLat(createDTO.getCenterLat());
        image.setDescription(createDTO.getDescription());
        return image;
    }

    private RsImageVO toVO(RsImage image) {
        RsImageVO vo = new RsImageVO();
        vo.setId(image.getId());
        vo.setOwnerId(image.getOwnerId());
        vo.setVisibility(image.getVisibility());
        vo.setImageCode(image.getImageCode());
        vo.setImageName(image.getImageName());
        vo.setSensorType(image.getSensorType());
        vo.setSatelliteName(image.getSatelliteName());
        vo.setAcquisitionTime(image.getAcquisitionTime());
        vo.setCloudPercent(image.getCloudPercent());
        vo.setResolutionMeter(image.getResolutionMeter());
        vo.setBandCount(image.getBandCount());
        vo.setProjection(image.getProjection());
        vo.setWidth(image.getWidth());
        vo.setHeight(image.getHeight());
        vo.setFileFormat(image.getFileFormat());
        vo.setFileSize(image.getFileSize());
        vo.setContentType(image.getContentType());
        vo.setMetadataJson(image.getMetadataJson());
        vo.setMinioBucket(image.getMinioBucket());
        vo.setObjectKey(image.getObjectKey());
        vo.setThumbnailObjectKey(image.getThumbnailObjectKey());
        vo.setThumbnailStatus(image.getThumbnailStatus());
        vo.setThumbnailErrorMessage(image.getThumbnailErrorMessage());
        vo.setOverviewObjectKey(image.getOverviewObjectKey());
        vo.setFootprintWkt(image.getFootprintWkt());
        vo.setCenterLon(image.getCenterLon());
        vo.setCenterLat(image.getCenterLat());
        vo.setStatus(image.getStatus());
        vo.setDescription(image.getDescription());
        vo.setDeletedAt(image.getDeletedAt());
        vo.setDeletedReason(image.getDeletedReason());
        vo.setCreatedAt(image.getCreatedAt());
        vo.setUpdatedAt(image.getUpdatedAt());
        return vo;
    }

    private RsImageListVO toListVO(RsImage image) {
        RsImageListVO vo = new RsImageListVO();
        vo.setId(image.getId());
        vo.setOwnerId(image.getOwnerId());
        vo.setVisibility(image.getVisibility());
        vo.setImageCode(image.getImageCode());
        vo.setImageName(image.getImageName());
        vo.setSensorType(image.getSensorType());
        vo.setAcquisitionTime(image.getAcquisitionTime());
        vo.setCloudPercent(image.getCloudPercent());
        vo.setResolutionMeter(image.getResolutionMeter());
        vo.setWidth(image.getWidth());
        vo.setHeight(image.getHeight());
        vo.setObjectKey(image.getObjectKey());
        vo.setThumbnailObjectKey(image.getThumbnailObjectKey());
        vo.setThumbnailStatus(image.getThumbnailStatus());
        vo.setStatus(image.getStatus());
        vo.setCreatedAt(image.getCreatedAt());
        return vo;
    }

    private int normalizePageNum(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? DEFAULT_PAGE_NUM : pageNum;
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Visibility normalizeVisibility(String visibility) {
        try {
            return Visibility.fromDb(visibility);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "非法可见性：" + visibility);
        }
    }

    private String generateImageCode() {
        return "IMG_" + UUID.randomUUID().toString().replace("-", "");
    }

    private void validateSearchTimeRange(RsImageSearchDTO query) {
        if (query.getStartTime() != null
                && query.getEndTime() != null
                && query.getStartTime().isAfter(query.getEndTime())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "startTime 不能晚于 endTime");
        }
    }

    private void fillMetadata(RsImage image, GeoTiffMetadataVO metadata) {
        image.setWidth(metadata.getWidth());
        image.setHeight(metadata.getHeight());
        image.setBandCount(metadata.getBandCount());
        image.setProjection(metadata.getCrs());
        image.setResolutionMeter(metadata.getResolutionMeter());
        if (metadata.getBounds() != null) {
            // rasterio 输出矩形 bounds，数据库侧用 Polygon footprint 支持 PostGIS 空间索引。
            image.setFootprintWkt(toPolygonWkt(metadata.getBounds()));
            image.setCenterLon(center(metadata.getBounds().getLeft(), metadata.getBounds().getRight()));
            image.setCenterLat(center(metadata.getBounds().getBottom(), metadata.getBounds().getTop()));
        }
        image.setMetadataJson(toJson(metadata));
    }

    private String toPolygonWkt(GeoTiffMetadataVO.Bounds bounds) {
        BigDecimal left = bounds.getLeft();
        BigDecimal bottom = bounds.getBottom();
        BigDecimal right = bounds.getRight();
        BigDecimal top = bounds.getTop();
        return "POLYGON(("
                + left + " " + bottom + ","
                + right + " " + bottom + ","
                + right + " " + top + ","
                + left + " " + top + ","
                + left + " " + bottom
                + "))";
    }

    private BigDecimal center(BigDecimal min, BigDecimal max) {
        return min.add(max).divide(BigDecimal.valueOf(2));
    }

    private void deleteObjectQuietly(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            minioService.deleteObject(objectKey);
        } catch (RuntimeException exception) {
            log.warn("MinIO 对象删除失败，objectKey={}, reason={}", objectKey, exception.getMessage());
        }
    }

    private void deleteLocalFileQuietly(LocalGeoTiffFile localFile) {
        if (localFile == null) {
            return;
        }
        deletePathQuietly(localFile.filePath());
        deleteDirectoryQuietly(localFile.tempDir());
    }

    private void deletePathQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("本地临时文件清理失败，path={}, reason={}", path, exception.getMessage());
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
            log.warn("本地临时目录清理失败，path={}, reason={}", dir, exception.getMessage());
        }
    }

    private String toJson(GeoTiffMetadataVO metadata) {
        try {
            // 保留完整元数据，避免后续新增字段时立刻改表。
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "GeoTIFF 元数据 JSON 序列化失败");
        }
    }

    /**
     * 保存上传阶段复用的临时文件信息，避免在多个参数间散落传递路径和原始文件名。
     */
    private record LocalGeoTiffFile(Path tempDir, Path filePath, String originalFilename, String contentType) {
    }
}
