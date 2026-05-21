package com.remotesensing.platform.common.enums;

/**
 * 影像资产生命周期状态枚举，对应 rs_image.status 字段。
 * <p>
 * 状态流转：UPLOADING -> PARSING -> READY -> PROCESSING -> DELETE_LOCKED/READY -> DELETED
 * 上传中 -> 元数据解析中 -> 就绪 -> 处理中 -> 删除锁定/就绪 -> 已删除
 */
public enum ImageStatus {

    /** 文件正在上传到 MinIO 中。 */
    UPLOADING,

    /** 上传完成，正在通过 Python 脚本解析元数据。 */
    PARSING,

    /** 元数据解析完成，影像可供检索、预览和提交任务。 */
    READY,

    /** 影像上有任务正在运行中，不允许删除。 */
    PROCESSING,

    /** 标记删除，等待物理删除，只读不可写。 */
    DELETE_LOCKED,

    /** 已软删除，在回收站保留一段时间后可物理清除。 */
    DELETED,

    /** 上传或解析过程中发生错误。 */
    FAILED;

    /** 从数据库字符串转换为枚举，忽略大小写。 */
    public static ImageStatus fromDb(String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("影像状态不能为空");
        }
        try {
            return ImageStatus.valueOf(status.trim().toUpperCase());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("非法影像状态：" + status, exception);
        }
    }

    /** 返回数据库持久化时使用的字符串值（枚举名）。 */
    public String dbValue() {
        return name();
    }

    /** 影像处于 READY 状态时允许提交处理任务。 */
    public boolean canSubmitTask() {
        return this == READY;
    }

    /** 影像处于 READY 或 FAILED 状态时允许删除。 */
    public boolean canDelete() {
        return this == READY || this == FAILED;
    }

    /** 判断影像是否已被标记为已删除。 */
    public boolean isDeleted() {
        return this == DELETED;
    }
}
