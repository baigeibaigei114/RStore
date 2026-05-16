package com.remotesensing.platform.mapper;

import com.remotesensing.platform.entity.RsResultFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RsResultFileMapper {

    int insert(RsResultFile resultFile);

    RsResultFile selectById(@Param("id") Long id);

    RsResultFile selectByTaskId(@Param("taskId") Long taskId);

    int resetPendingPublish(RsResultFile resultFile);

    int markPublishing(@Param("id") Long id);

    int markPublished(@Param("id") Long id,
                      @Param("workspace") String workspace,
                      @Param("storeName") String storeName,
                      @Param("layerName") String layerName,
                      @Param("wmsUrl") String wmsUrl,
                      @Param("wcsUrl") String wcsUrl);

    int markPublishFailed(@Param("id") Long id,
                          @Param("errorMessage") String errorMessage);
}
