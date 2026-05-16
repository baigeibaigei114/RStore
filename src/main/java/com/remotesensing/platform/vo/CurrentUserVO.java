package com.remotesensing.platform.vo;

import lombok.Data;

@Data
public class CurrentUserVO {

    private String userId;
    private String username;
    private String displayName;
    private String role;
}
