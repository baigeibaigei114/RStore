package com.remotesensing.platform.config;

import com.remotesensing.platform.config.properties.AuthProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 认证配置类，统一管理认证相关的 Spring Bean。
 * <p>
 * 职责：
 * - 启用 AuthProperties 配置绑定（prefix = "app.auth"）。
 * - 提供 BCryptPasswordEncoder 作为全局密码加密器。
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfig {

    /**
     * 密码编码器 Bean，使用 BCrypt 散列算法。
     * 用于用户注册密码加密和登录密码校验。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
