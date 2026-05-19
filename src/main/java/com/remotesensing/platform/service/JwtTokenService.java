package com.remotesensing.platform.service;

import com.remotesensing.platform.entity.SysUser;

public interface JwtTokenService {

    String generateToken(SysUser user);

    JwtUser parseToken(String token);

    record JwtUser(String userId, String username, String role) {
    }
}
