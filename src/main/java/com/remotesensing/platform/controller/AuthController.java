package com.remotesensing.platform.controller;

import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.dto.LoginRequestDTO;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.service.AuthService;
import com.remotesensing.platform.vo.AuthLoginVO;
import com.remotesensing.platform.vo.CurrentUserVO;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证与授权控制器。
 * <p>负责用户登录认证和当前登录态查询。所有接口均以 /auth 开头，
 * 登录成功后颁发 Token，后续请求通过 Token 鉴权。</p>
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 用户登录。
     * <p>接收用户名密码，校验通过后返回包含 Token 的登录结果。
     * 参数通过 {@link Valid} 校验，防止空值或格式异常请求到达业务层。</p>
     *
     * @param requestDTO 登录请求参数（用户名 + 密码），已启用 Bean Validation
     * @return 包含 Token、用户信息的登录结果 VO
     */
    @PostMapping("/login")
    public Result<AuthLoginVO> login(@Valid @RequestBody LoginRequestDTO requestDTO) {
        return Result.success(authService.login(requestDTO));
    }

    /**
     * 获取当前登录用户信息。
     * <p>从安全上下文中解析当前请求携带的 Token，返回用户基本信息。
     * 无参数，完全依赖请求头中的认证凭证。</p>
     *
     * @return 当前登录用户的基本信息 VO
     */
    @GetMapping("/me")
    public Result<CurrentUserVO> me() {
        return Result.success(authService.me());
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        authService.logout(extractBearerToken(request));
        return Result.success();
    }

    private String extractBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMessage());
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (token.isBlank()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMessage());
        }
        return token;
    }
}
