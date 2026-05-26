package com.remotesensing.platform.service;

import com.remotesensing.platform.dto.RemoteSensingTaskMessage.TaskType;
import com.remotesensing.platform.entity.RsImage;
import com.remotesensing.platform.vo.GeoTiffMetadataVO;
import com.remotesensing.platform.vo.ImageBandCapabilityVO;
import java.util.Map;

/**
 * 影像波段能力服务。
 *
 * <p>负责从 GeoTIFF 元数据、颜色解释或用户确认配置中解析红光、绿光、蓝光、近红外等波段映射，
 * 并据此判断影像是否具备 NDVI、NDWI 等处理能力。</p>
 */
public interface ImageBandCapabilityService {

    /**
     * 为上传解析得到的 GeoTIFF 元数据补充波段映射和可执行任务类型。
     *
     * @param metadata Python 解析脚本返回的 GeoTIFF 元数据
     * @return 已补充波段能力字段的元数据对象
     */
    GeoTiffMetadataVO enrichMetadata(GeoTiffMetadataVO metadata);

    /**
     * 根据影像记录中的 metadataJson 解析当前影像的波段能力。
     *
     * @param image 影像资产记录
     * @return 波段映射、来源、置信度和可执行任务类型
     */
    ImageBandCapabilityVO resolve(RsImage image);

    /**
     * 校验任务类型是否被当前影像支持，并在参数缺失时填充可信波段编号。
     *
     * @param image 影像资产记录
     * @param taskType 任务类型
     * @param params 任务参数，会在缺少 redBand/greenBand/nirBand 时被补齐
     */
    void validateAndFillTaskParams(RsImage image, TaskType taskType, Map<String, Object> params);
}
