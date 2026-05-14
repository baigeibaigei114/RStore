package com.remotesensing.platform.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RsTaskClaimVO {

    private Boolean claimed;
    private String action;
    private String taskStatus;
    private String message;
    private String outputObjectKey;
}
