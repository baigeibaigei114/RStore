package com.remotesensing.platform.mapper;

import com.remotesensing.platform.dto.AiPlanSearchDTO;
import com.remotesensing.platform.entity.AiPlan;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * AI Plan 数据访问接口。
 */
@Mapper
public interface AiPlanMapper {

    int insert(AiPlan plan);

    AiPlan selectByIdForUser(@Param("id") Long id, @Param("userId") String userId);

    List<AiPlan> pageByUser(@Param("userId") String userId,
                            @Param("query") AiPlanSearchDTO query,
                            @Param("limit") int limit,
                            @Param("offset") int offset);

    long countByUser(@Param("userId") String userId, @Param("query") AiPlanSearchDTO query);

    int updateStatusForUser(@Param("id") Long id,
                            @Param("userId") String userId,
                            @Param("status") String status);
}
