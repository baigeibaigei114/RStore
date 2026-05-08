package com.remotesensing.platform.common;

import java.io.Serializable;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一分页返回结构，pageNum 从 1 开始，便于前端直接使用。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> implements Serializable {

    private List<T> records;
    private long total;
    private int pageNum;
    private int pageSize;
}
