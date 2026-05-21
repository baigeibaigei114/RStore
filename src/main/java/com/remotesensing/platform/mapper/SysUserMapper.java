package com.remotesensing.platform.mapper;

import com.remotesensing.platform.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 系统用户（sys_user）Mapper 接口。
 * 提供用户基本信息的查询操作，用于登录认证和用户身份校验。
 */
@Mapper
public interface SysUserMapper {

    /**
     * 根据主键 ID 查询用户信息。
     *
     * @param id 用户主键
     * @return 用户实体，不存在返回 null
     */
    SysUser selectById(@Param("id") Long id);

    /**
     * 根据用户名查询用户信息，用于登录认证。
     *
     * @param username 登录用户名
     * @return 用户实体，不存在返回 null
     */
    SysUser selectByUsername(@Param("username") String username);
}