package com.remotesensing.platform.controller;

import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.dto.LoginRequestDTO;
import com.remotesensing.platform.service.AuthService;
import com.remotesensing.platform.vo.AuthLoginVO;
import com.remotesensing.platform.vo.CurrentUserVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Result<AuthLoginVO> login(@Valid @RequestBody LoginRequestDTO requestDTO) {
        return Result.success(authService.login(requestDTO));
    }

    @GetMapping("/me")
    public Result<CurrentUserVO> me() {
        return Result.success(authService.me());
    }
}
