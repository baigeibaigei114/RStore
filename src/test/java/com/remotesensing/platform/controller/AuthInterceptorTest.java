package com.remotesensing.platform.controller;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remotesensing.platform.common.PageResult;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.config.TestConfig;
import com.remotesensing.platform.entity.SysUser;
import com.remotesensing.platform.service.AuthService;
import com.remotesensing.platform.service.JwtTokenService;
import com.remotesensing.platform.service.RsImageService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@WebMvcTest({RsImageController.class, AuthController.class})
@Import(TestConfig.class)
@TestPropertySource(properties = "app.auth.dev-user-header-enabled=false")
class AuthInterceptorTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @MockBean
    private RsImageService imageService;

    @MockBean
    private AuthService authService;

    @Test
    @DisplayName("未携带 token 访问影像接口被拒绝")
    void requestWithoutTokenShouldBeRejected() throws Exception {
        mockMvc.perform(get("/api/images")
                        .contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()))
                .andExpect(jsonPath("$.message").value(ResultCode.UNAUTHORIZED.getMessage()));

        verify(imageService, never()).page(null, null);
    }

    @Test
    @DisplayName("携带合法 Bearer token 可访问影像接口")
    void requestWithValidTokenShouldPass() throws Exception {
        when(imageService.page(null, null)).thenReturn(new PageResult<>(List.of(), 0, 1, 10));

        mockMvc.perform(get("/api/images")
                        .contextPath("/api")
                        .header("Authorization", "Bearer " + tokenFor("1", "admin", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()));

        verify(imageService).page(null, null);
    }

    @Test
    @DisplayName("携带无效 token 访问影像接口被拒绝")
    void requestWithInvalidTokenShouldBeRejected() throws Exception {
        mockMvc.perform(get("/api/images")
                        .contextPath("/api")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()));

        verify(imageService, never()).page(null, null);
    }

    @Test
    @DisplayName("登录接口不需要 token")
    void loginShouldNotRequireToken() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "admin",
                                  "password": "admin123"
                                }
                                """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("OPTIONS 预检请求放行")
    void optionsRequestShouldPass() throws Exception {
        mockMvc.perform(options("/api/images")
                        .contextPath("/api"))
                .andExpect(status().isOk());
    }

    private String tokenFor(String userId, String username, String role) {
        SysUser user = new SysUser();
        user.setId(Long.valueOf(userId));
        user.setUsername(username);
        user.setRole(role);
        return jwtTokenService.generateToken(user);
    }
}
