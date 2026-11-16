package com.remotesensing.platform.service;

import com.remotesensing.platform.common.PageResult;
import com.remotesensing.platform.entity.RsImage;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.RsImageMapper;
import com.remotesensing.platform.vo.FilePresignedUrlVO;
import com.remotesensing.platform.vo.PublicImageVO;
import com.remotesensing.platform.vo.RsImageVO.ThumbnailPreview;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class PublicImageService {
    private static final int PREVIEW_LIMIT_PER_MINUTE = 120;

    private final RsImageMapper imageMapper;
    private final MinioService minioService;
    private final RateLimitService rateLimit;

    public PublicImageService(RsImageMapper imageMapper, MinioService minioService, RateLimitService rateLimit) {
        this.imageMapper = imageMapper;
        this.minioService = minioService;
        this.rateLimit = rateLimit;
    }

    @Transactional(readOnly = true)
    public PageResult<PublicImageVO> page(String keyword, Integer pageNum, Integer pageSize) {
        int page = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, 100);
        String name = StringUtils.hasText(keyword) ? keyword.trim() : null;
        List<PublicImageVO> rows = imageMapper.selectPublicPage(name, (long) (page - 1) * size, size).stream()
                .map(image -> display(image, null, null))
                .toList();
        return new PageResult<>(rows, imageMapper.countPublic(name), page, size);
    }

    @Transactional(readOnly = true)
    public PublicImageVO detail(Long id, String remoteAddress) {
        RsImage image = imageMapper.selectPublicById(id);
        if (image == null) return null;
        boolean previewAvailable = StringUtils.hasText(image.getThumbnailObjectKey());
        ThumbnailPreview preview = previewAvailable ? signPreview(id, remoteAddress) : null;
        return display(image, previewAvailable, preview);
    }

    private ThumbnailPreview signPreview(Long id, String remoteAddress) {
        try {
            rateLimit.check("public-image-preview:ip:" + remoteAddress, PREVIEW_LIMIT_PER_MINUTE, Duration.ofMinutes(1));
            Instant issuedAt = Instant.now();
            FilePresignedUrlVO signed = minioService.generatePublicThumbnailUrl(id);
            return signed == null ? null : new ThumbnailPreview(signed.getUrl(), issuedAt.plusSeconds(signed.getExpireSeconds()));
        } catch (BusinessException unavailable) {
            return null;
        }
    }

    private PublicImageVO display(RsImage image, Boolean previewAvailable, ThumbnailPreview preview) {
        return new PublicImageVO(image.getId(), image.getImageName(), image.getSensorType(), image.getAcquisitionTime(),
                image.getCloudPercent(), image.getStatus(), image.getWidth(), image.getHeight(), image.getResolutionMeter(),
                image.getFootprintWkt(), image.getThumbnailStatus(), previewAvailable, preview);
    }
}
