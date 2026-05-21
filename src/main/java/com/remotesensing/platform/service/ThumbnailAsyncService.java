package com.remotesensing.platform.service;

/**
 * 缩略图异步生成服务接口。
 * <p>
 * 在影像上传事务成功提交后，异步调度缩略图生成任务。
 * 缩略图生成失败不影响原始影像的上传结果，仅影响派生资源。
 */
public interface ThumbnailAsyncService {

    /**
     * 数据库事务提交后异步调度缩略图生成任务。
     * 使用 @TransactionalEventListener(phase = AFTER_COMMIT) 确保在事务提交后才触发，
     * 避免在事务未提交时读取不到影像记录。
     * 生成失败只影响派生资源（缩略图），不影响原始影像上传结果。
     *
     * @param imageId 影像 ID，用于定位已上传的 GeoTIFF 文件并生成对应缩略图
     */
    void generateAsync(Long imageId);
}
