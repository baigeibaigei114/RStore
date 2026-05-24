package com.remotesensing.platform.service;

import com.remotesensing.platform.dto.LoginRequestDTO;
import com.remotesensing.platform.vo.AuthLoginVO;
import com.remotesensing.platform.vo.CurrentUserVO;

/**
 * 认证服务接口。
 * <p>
 * 提供用户登录（用户名+密码校验，返回 JWT Token）和获取当前登录用户信息功能。
 */
public interface AuthService {

    /**
     * 用户登录。
     * 校验用户名和密码，验证通过后生成并返回 JWT Token。
     *
     * @param requestDTO 登录请求（用户名 + 密码）
     * @return 登录成功后的 Token 和用户信息
     */
    AuthLoginVO login(LoginRequestDTO requestDTO);

    /**
     * 获取当前登录用户信息。
     * 根据请求上下文中的 JWT Token 解析出用户信息。
     *
     * @return 当前用户信息
     */
    CurrentUserVO me();

    void logout(String token);
}
