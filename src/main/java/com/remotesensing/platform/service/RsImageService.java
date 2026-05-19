package com.remotesensing.platform.service;

import com.remotesensing.platform.common.PageResult;
import com.remotesensing.platform.dto.RsImageCreateDTO;
import com.remotesensing.platform.dto.RsImageSearchDTO;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import com.remotesensing.platform.vo.FilePresignedUrlVO;
import com.remotesensing.platform.vo.RsImageListVO;
import com.remotesensing.platform.vo.RsImageVO;
import org.springframework.web.multipart.MultipartFile;

public interface RsImageService {

    RsImageVO create(RsImageCreateDTO createDTO);

    /**
     * 上传 GeoTIFF 后完成对象存储和元数据入库，缩略图在事务提交后异步生成。
     */
    RsImageVO upload(MultipartFile file, String name, String sensor, OffsetDateTime captureTime, BigDecimal cloudPercent);

    RsImageVO getById(Long id);

    FilePresignedUrlVO getDownloadUrl(Long id);

    FilePresignedUrlVO getThumbnailUrl(Long id);

    PageResult<RsImageVO> page(Integer pageNum, Integer pageSize);

    PageResult<RsImageListVO> search(RsImageSearchDTO query, Integer pageNum, Integer pageSize);

    PageResult<RsImageListVO> searchByRegion(RsImageSearchDTO query, Integer pageNum, Integer pageSize);

    RsImageVO updateVisibility(Long id, String visibility);

    void deleteById(Long id);
}
