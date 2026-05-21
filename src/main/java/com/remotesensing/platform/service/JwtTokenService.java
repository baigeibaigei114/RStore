package com.remotesensing.platform.service;

import com.remotesensing.platform.entity.SysUser;

/**
 * JWT Token 服务接口。
 * <p>
 * 职责：
 * - 为登录用户生成 JWT Token。
 * - 解析 Token 获取用户身份信息（userId / username / role）。
 */
public interface JwtTokenService {

    /**
     * 为用户生成 JWT Token。
     *
     * @param user 登录用户实体
     * @return JWT 字符串
     */
    String generateToken(SysUser user);

    /**
     * 解析 JWT Token 并返回用户信息。
     *
     * @param token JWT 字符串
     * @return 解析后的用户信息记录
     */
    JwtUser parseToken(String token);

    /** JWT 解析结果：包含用户 ID、用户名和角色。 */
    record JwtUser(String userId, String username, String role) {
    }
}
