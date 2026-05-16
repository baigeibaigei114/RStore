package com.remotesensing.platform.mapper;

import com.remotesensing.platform.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SysUserMapper {

    SysUser selectById(@Param("id") Long id);

    SysUser selectByUsername(@Param("username") String username);
}
