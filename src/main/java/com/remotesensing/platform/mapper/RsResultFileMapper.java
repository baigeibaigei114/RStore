package com.remotesensing.platform.mapper;

import com.remotesensing.platform.entity.RsResultFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 遥感任务结果文件（rs_result_file）Mapper 接口。
 * 提供结果文件的增删改查以及 GeoServer 发布状态流转操作。
 */
@Mapper
public interface RsResultFileMapper {

    /**
     * 插入一条结果文件记录。
     *
     * @param resultFile 结果文件实体，插入后 MyBatis 回填 id
     * @return 受影响行数
     */
    int insert(RsResultFile resultFile);

    /**
     * 根据主键 ID 查询结果文件。
     *
     * @param id 结果文件主键
     * @return 结果文件实体，不存在返回 null
     */
    RsResultFile selectById(@Param("id") Long id);

    /**
     * 根据任务 ID 查询关联的结果文件。
     *
     * @param taskId 任务主键
     * @return 结果文件实体，不存在返回 null
     */
    RsResultFile selectByTaskId(@Param("taskId") Long taskId);

    /**
     * 将发布失败的结果文件重置为待发布状态，并更新记录版本。
     * 用于手动重试发布场景。
     *
     * @param resultFile 包含新状态和版本信息的结果文件实体
     * @return 受影响行数
     */
    int resetPendingPublish(RsResultFile resultFile);

    /**
     * 将结果文件状态标记为发布中（PUBLISHING）。
     * 通过 WHERE status='PENDING_PUBLISH' 保证并发安全。
     *
     * @param id 结果文件主键
     * @return 受影响行数
     */
    int markPublishing(@Param("id") Long id);

    /**
     * 将结果文件标记为已发布（PUBLISHED），并回填 GeoServer 发布信息。
     *
     * @param id        结果文件主键
     * @param workspace GeoServer 工作空间
     * @param storeName GeoServer 存储名称
     * @param layerName GeoServer 图层名称
     * @param wmsUrl    WMS 服务 URL
     * @param wcsUrl    WCS 服务 URL
     * @return 受影响行数
     */
    int markPublished(@Param("id") Long id,
                      @Param("workspace") String workspace,
                      @Param("storeName") String storeName,
                      @Param("layerName") String layerName,
                      @Param("wmsUrl") String wmsUrl,
                      @Param("wcsUrl") String wcsUrl);

    /**
     * 将结果文件标记为发布失败（FAILED），并记录错误信息。
     *
     * @param id            结果文件主键
     * @param errorMessage  失败错误信息
     * @return 受影响行数
     */
    int markPublishFailed(@Param("id") Long id,
                          @Param("errorMessage") String errorMessage);
}