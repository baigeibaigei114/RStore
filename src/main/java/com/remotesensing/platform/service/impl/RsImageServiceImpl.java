package com.remotesensing.platform.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.PageResult;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.dto.RsImageCreateDTO;
import com.remotesensing.platform.dto.RsImageSearchDTO;
import com.remotesensing.platform.entity.RsImage;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.RsImageMapper;
import com.remotesensing.platform.service.GeoTiffMetadataService;
import com.remotesensing.platform.service.GeoTiffThumbnailService;
import com.remotesensing.platform.service.RsImageService;
import com.remotesensing.platform.service.MinioService;
import com.remotesensing.platform.vo.GeoTiffMetadataVO;
import com.remotesensing.platform.vo.MinioUploadVO;
import com.remotesensing.platform.vo.RsImageListVO;
import com.remotesensing.platform.vo.RsImageVO;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class RsImageServiceImpl implements RsImageService {

    private static final int DEFAULT_PAGE_NUM = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    private final RsImageMapper imageMapper;
    private final MinioService minioService;
    private final GeoTiffMetadataService geoTiffMetadataService;
    private final GeoTiffThumbnailService geoTiffThumbnailService;
    private final ObjectMapper objectMapper;

    public RsImageServiceImpl(RsImageMapper imageMapper,
                              MinioService minioService,
                              GeoTiffMetadataService geoTiffMetadataService,
                              GeoTiffThumbnailService geoTiffThumbnailService,
                              ObjectMapper objectMapper) {
        this.imageMapper = imageMapper;
        this.minioService = minioService;
        this.geoTiffMetadataService = geoTiffMetadataService;
        this.geoTiffThumbnailService = geoTiffThumbnailService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public RsImageVO create(RsImageCreateDTO createDTO) {
        if (imageMapper.countByImageCode(createDTO.getImageCode()) > 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "影像编码已存在");
        }

        RsImage image = toEntity(createDTO);
        try {
            imageMapper.insert(image);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "影像数据不合法，请检查 footprintWkt 或必填字段");
        }
        return getById(image.getId());
    }

    @Override
    @Transactional
    public RsImageVO upload(MultipartFile file,
                            String name,
                            String sensor,
                            OffsetDateTime captureTime,
                            BigDecimal cloudPercent) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "影像名称不能为空");
        }

        MinioUploadVO uploadVO = minioService.uploadGeoTiff(file);
        GeoTiffMetadataVO metadata = geoTiffMetadataService.parse(file);
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

        try {
            imageMapper.insert(image);
            // 缩略图路径包含 imageId，所以先插入影像记录，再生成并回写缩略图对象路径。
            String thumbnailObjectKey = geoTiffThumbnailService.generateAndUpload(file, image.getId());
            imageMapper.updateThumbnailObjectKey(image.getId(), thumbnailObjectKey);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "影像记录保存失败：" + exception.getMostSpecificCause().getMessage());
        }
        return getById(image.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public RsImageVO getById(Long id) {
        RsImage image = imageMapper.selectById(id);
        if (image == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "影像记录不存在");
        }
        return toVO(image);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<RsImageVO> page(Integer pageNum, Integer pageSize) {
        int currentPageNum = normalizePageNum(pageNum);
        int currentPageSize = normalizePageSize(pageSize);
        int offset = (currentPageNum - 1) * currentPageSize;

        List<RsImageVO> records = imageMapper.selectPage(offset, currentPageSize).stream()
                .map(this::toVO)
                .toList();
        long total = imageMapper.count();
        return new PageResult<>(records, total, currentPageNum, currentPageSize);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<RsImageListVO> search(RsImageSearchDTO query, Integer pageNum, Integer pageSize) {
        validateSearchTimeRange(query);
        int currentPageNum = normalizePageNum(pageNum);
        int currentPageSize = normalizePageSize(pageSize);
        int offset = (currentPageNum - 1) * currentPageSize;

        List<RsImageListVO> records = imageMapper.searchPage(query, offset, currentPageSize).stream()
                .map(this::toListVO)
                .toList();
        long total = imageMapper.countSearch(query);
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

        List<RsImageListVO> records = imageMapper.searchByRegionPage(query, offset, currentPageSize).stream()
                .map(this::toListVO)
                .toList();
        long total = imageMapper.countSearchByRegion(query);
        return new PageResult<>(records, total, currentPageNum, currentPageSize);
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        if (imageMapper.selectById(id) == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "影像记录不存在");
        }

        try {
            imageMapper.deleteById(id);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "影像已关联处理任务，暂不能删除");
        }
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
        vo.setOverviewObjectKey(image.getOverviewObjectKey());
        vo.setFootprintWkt(image.getFootprintWkt());
        vo.setCenterLon(image.getCenterLon());
        vo.setCenterLat(image.getCenterLat());
        vo.setDescription(image.getDescription());
        vo.setCreatedAt(image.getCreatedAt());
        vo.setUpdatedAt(image.getUpdatedAt());
        return vo;
    }

    private RsImageListVO toListVO(RsImage image) {
        RsImageListVO vo = new RsImageListVO();
        vo.setId(image.getId());
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
        if (metadata.getResolution() != null) {
            image.setResolutionMeter(metadata.getResolution().getX());
        }
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

    private String toJson(GeoTiffMetadataVO metadata) {
        try {
            // 保留完整元数据，避免后续新增字段时需要立即改表。
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "GeoTIFF 元数据 JSON 序列化失败");
        }
    }
}
