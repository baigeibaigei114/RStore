package com.remotesensing.platform.service;

import com.remotesensing.platform.entity.SysUser;

/**
 * JWT Token 服务接口。
 */
public interface JwtTokenService {

    String generateToken(SysUser user);

    JwtUser parseToken(String token);

    JwtUser parseTokenIgnoringExpiration(String token);

    /** JWT 解析结果：包含用户身份、令牌唯一 ID 和过期时间。 */
    record JwtUser(String userId, String username, String role, String jti, long expiresAtEpochSecond) {
    }
}
