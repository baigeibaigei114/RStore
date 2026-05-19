package com.remotesensing.platform.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.config.properties.AuthProperties;
import com.remotesensing.platform.dto.LoginRequestDTO;
import com.remotesensing.platform.entity.SysUser;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.SysUserMapper;
import com.remotesensing.platform.service.JwtTokenService;
import com.remotesensing.platform.vo.AuthLoginVO;
import com.remotesensing.platform.vo.CurrentUserVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private CurrentUserContext currentUserContext;

    private PasswordEncoder passwordEncoder;
    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        AuthProperties authProperties = new AuthProperties();
        authProperties.setJwtSecret("test-secret");
        JwtTokenService jwtTokenService = new JwtTokenServiceImpl(authProperties, new ObjectMapper());
        authService = new AuthServiceImpl(sysUserMapper, passwordEncoder, jwtTokenService, currentUserContext);
    }

    @Test
    @DisplayName("admin 使用正确密码登录成功")
    void loginShouldReturnTokenForAdmin() {
        SysUser admin = user(1L, "admin", "admin123", "管理员", "ADMIN", "ACTIVE");
        when(sysUserMapper.selectByUsername("admin")).thenReturn(admin);

        AuthLoginVO result = authService.login(loginRequest("admin", "admin123"));

        assertThat(result.getAccessToken()).isNotBlank();
        assertThat(result.getTokenType()).isEqualTo("Bearer");
        assertThat(result.getUserId()).isEqualTo("1");
        assertThat(result.getUsername()).isEqualTo("admin");
        assertThat(result.getRole()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("错误密码登录失败")
    void loginShouldRejectWrongPassword() {
        SysUser admin = user(1L, "admin", "admin123", "管理员", "ADMIN", "ACTIVE");
        when(sysUserMapper.selectByUsername("admin")).thenReturn(admin);

        assertThatThrownBy(() -> authService.login(loginRequest("admin", "wrong")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户名或密码错误");
    }

    @Test
    @DisplayName("禁用用户不能登录")
    void loginShouldRejectDisabledUser() {
        SysUser admin = user(1L, "admin", "admin123", "管理员", "ADMIN", "DISABLED");
        when(sysUserMapper.selectByUsername("admin")).thenReturn(admin);

        assertThatThrownBy(() -> authService.login(loginRequest("admin", "admin123")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户已禁用");
    }

    @Test
    @DisplayName("me 返回当前 token 用户信息")
    void meShouldReturnCurrentUser() {
        SysUser admin = user(1L, "admin", "admin123", "管理员", "ADMIN", "ACTIVE");
        when(currentUserContext.getCurrentUserId()).thenReturn("1");
        when(sysUserMapper.selectById(1L)).thenReturn(admin);

        CurrentUserVO result = authService.me();

        assertThat(result.getUserId()).isEqualTo("1");
        assertThat(result.getUsername()).isEqualTo("admin");
        assertThat(result.getDisplayName()).isEqualTo("管理员");
        assertThat(result.getRole()).isEqualTo("ADMIN");
    }

    private LoginRequestDTO loginRequest(String username, String password) {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }

    private SysUser user(Long id, String username, String rawPassword, String displayName, String role, String status) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setDisplayName(displayName);
        user.setRole(role);
        user.setStatus(status);
        return user;
    }
}
