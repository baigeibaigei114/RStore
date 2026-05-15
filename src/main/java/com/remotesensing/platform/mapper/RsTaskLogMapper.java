package com.remotesensing.platform.mapper;

import com.remotesensing.platform.entity.RsTaskLog;
import com.remotesensing.platform.vo.RsTaskLogVO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RsTaskLogMapper {

    int insert(RsTaskLog taskLog);

    List<RsTaskLogVO> selectByTaskIdOrderByCreatedAt(@Param("taskId") Long taskId);
}
