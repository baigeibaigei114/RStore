package com.remotesensing.platform.service;

public interface ThumbnailAsyncService {

    /**
     * 数据库事务提交后再调度缩略图任务，失败只影响派生资源，不影响原始影像上传结果。
     */
    void generateAsync(Long imageId);
}
