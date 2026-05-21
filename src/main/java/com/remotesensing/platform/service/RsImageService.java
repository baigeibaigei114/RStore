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

/**
 * 遥感影像服务接口。
 * <p>
 * 提供影像的创建、上传、查询、搜索、可见性修改和删除功能。
 * 上传完成后异步解析元数据并生成缩略图。
 */
public interface RsImageService {

    /**
     * 创建影像记录（不包含文件上传）。
     * 用于已通过其他方式完成文件上传后，仅入库元数据。
     *
     * @param createDTO 影像创建参数
     * @return 影像详情
     */
    RsImageVO create(RsImageCreateDTO createDTO);

    /**
     * 上传 GeoTIFF 后完成对象存储和元数据入库，缩略图在事务提交后异步生成。
     *
     * @param file         上传的 GeoTIFF 文件
     * @param name         影像名称
     * @param sensor       传感器类型
     * @param captureTime  采集时间
     * @param cloudPercent 云量百分比
     * @return 上传完成后的影像详情
     */
    RsImageVO upload(MultipartFile file, String name, String sensor, OffsetDateTime captureTime, BigDecimal cloudPercent);

    /**
     * 根据 ID 获取影像详情。
     *
     * @param id 影像 ID
     * @return 影像详情（含完整元数据）
     */
    RsImageVO getById(Long id);

    /**
     * 获取影像文件的下载预签名 URL。
     *
     * @param id 影像 ID
     * @return 预签名下载 URL
     */
    FilePresignedUrlVO getDownloadUrl(Long id);

    /**
     * 获取影像缩略图的访问预签名 URL。
     *
     * @param id 影像 ID
     * @return 缩略图预签名 URL
     */
    FilePresignedUrlVO getThumbnailUrl(Long id);

    /**
     * 分页查询用户自己的影像列表（不含已删除的）。
     *
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @return 分页结果
     */
    PageResult<RsImageVO> page(Integer pageNum, Integer pageSize);

    /**
     * 按条件搜索影像（关键字、时间范围、传感器、云量、空间范围等）。
     *
     * @param query    搜索条件
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @return 分页搜索结果
     */
    PageResult<RsImageListVO> search(RsImageSearchDTO query, Integer pageNum, Integer pageSize);

    /**
     * 按行政区划边界搜索影像空间范围内的影像。
     *
     * @param query    搜索条件（含 regionId）
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @return 分页搜索结果
     */
    PageResult<RsImageListVO> searchByRegion(RsImageSearchDTO query, Integer pageNum, Integer pageSize);

    /**
     * 更新影像的可见性（PRIVATE / PUBLIC）。
     *
     * @param id         影像 ID
     * @param visibility 目标可见性
     * @return 更新后的影像详情
     */
    RsImageVO updateVisibility(Long id, String visibility);

    /**
     * 软删除影像（标记为 DELETED 状态）。
     * 只有处于 READY 或 FAILED 状态的影像可删除。
     *
     * @param id 影像 ID
     */
    void deleteById(Long id);
}
