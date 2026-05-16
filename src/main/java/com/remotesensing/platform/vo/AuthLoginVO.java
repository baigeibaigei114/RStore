package com.remotesensing.platform.vo;

import lombok.Data;

@Data
public class AuthLoginVO {

    private String accessToken;
    private String tokenType;
    private String userId;
    private String username;
    private String displayName;
    private String role;
}
