package com.remotesensing.platform.service;

import com.remotesensing.platform.common.PageResult;
import com.remotesensing.platform.dto.RsImageCreateDTO;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import com.remotesensing.platform.vo.RsImageVO;
import org.springframework.web.multipart.MultipartFile;

public interface RsImageService {

    RsImageVO create(RsImageCreateDTO createDTO);

    /**
     * 上传 GeoTIFF 后同步完成对象存储、元数据解析、缩略图生成和影像记录创建。
     */
    RsImageVO upload(MultipartFile file, String name, String sensor, OffsetDateTime captureTime, BigDecimal cloudPercent);

    RsImageVO getById(Long id);

    PageResult<RsImageVO> page(Integer pageNum, Integer pageSize);

    void deleteById(Long id);
}
