package com.remotesensing.platform.mapper;

import com.remotesensing.platform.entity.RsTaskLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RsTaskLogMapper {

    int insert(RsTaskLog taskLog);
}
