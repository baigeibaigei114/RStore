package com.remotesensing.platform.service;

/**
 * Token 黑名单服务。
 */
public interface TokenBlacklistService {

    void blacklist(String token);

    boolean isBlacklisted(String token);

    boolean isBlacklistedByJti(String jti);
}
