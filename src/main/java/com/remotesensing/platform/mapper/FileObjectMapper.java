package com.remotesensing.platform.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FileObjectMapper {

    /**
     * 确认 objectKey 来自业务表，避免为私有 bucket 中的任意对象生成访问链接。
     */
    long countAccessibleObjectKey(@Param("objectKey") String objectKey,
                                  @Param("currentUserId") String currentUserId);
}
