package com.remotesensing.platform.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 文件对象安全校验 Mapper 接口。
 * 用于确认 objectKey 来自合法的业务表记录，避免为私有 bucket 中的任意对象生成访问链接。
 */
@Mapper
public interface FileObjectMapper {

    /**
     * 统计当前用户可访问的指定 objectKey 记录数。
     * 在生成预签名 URL 前调用，确认 objectKey 属于当前用户或公开资源。
     *
     * @param objectKey      待校验的对象键
     * @param currentUserId  当前用户 ID
     * @return 可访问的记录数（大于 0 表示有权访问）
     */
    long countAccessibleObjectKey(@Param("objectKey") String objectKey,
                                  @Param("currentUserId") String currentUserId);
}