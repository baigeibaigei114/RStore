package com.remotesensing.platform.service;

import com.remotesensing.platform.vo.AiReportVO;

/** Read access to saved task reports. */
public interface AiReportService {
    AiReportVO getSavedFromTask(Long taskId);
}
