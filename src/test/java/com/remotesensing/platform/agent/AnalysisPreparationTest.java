package com.remotesensing.platform.agent;

import static com.remotesensing.platform.agent.AgentJson.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.remotesensing.platform.entity.RsImage;
import com.remotesensing.platform.mapper.MonitoringRegionMapper;
import com.remotesensing.platform.mapper.RsImageMapper;
import com.remotesensing.platform.vo.MonitoringRegionVO;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnalysisPreparationTest {
    @Test void currentPreparationReturnsOriginalFormDraftWithoutCreatingAnExecution() {
        AgentClient client=mock(AgentClient.class);
        RsImageMapper images=mock(RsImageMapper.class);
        MonitoringRegionMapper regions=mock(MonitoringRegionMapper.class);
        AgentResources resources=new AgentResources(images,regions);
        AnalysisPreparation preparation=new AnalysisPreparation(client,new AgentProperties(),images,regions,resources);
        RsImage image=new RsImage(); image.setId(3L); image.setImageName("历史标准化影像");
        image.setOwnerId("owner"); image.setStatus("READY"); image.setVisibility("PRIVATE");
        image.setMetadataJson("{\"admission\":{\"status\":\"PASSED\",\"validatorVersion\":\"input-v1\",\"manifest\":{}}}");
        MonitoringRegionVO region=new MonitoringRegionVO(); region.setId(5L); region.setName("我的样区"); region.setVersion(2);
        when(client.post(eq("preparation"),any(),eq(Duration.ofSeconds(30)))).thenReturn(
                object().set("intent",object().put("kind","REGION_NDVI").put("regionName","我的样区")));
        when(regions.searchForPreparation("owner","我的样区")).thenReturn(List.of(region));
        when(images.searchForPreparation("owner",null,null,null,5L)).thenReturn(List.of(image));
        when(regions.find(5L,"owner")).thenReturn(region);
        var result=preparation.prepare(object().put("goal","分析我的样区的NDVI"),"owner");
        assertThat(result.path("status").asText()).isEqualTo("READY");
        assertThat(result.path("draft").path("id").asLong()).isEqualTo(3);
        assertThat(result.path("draft").path("regionVersion").asInt()).isEqualTo(2);
        assertThat(result.path("draft").path("coverage").asText()).isEqualTo("UNKNOWN");
        assertThat(result.has("executionId")).isFalse(); assertThat(result.has("taskId")).isFalse();
        verify(client,times(1)).post(eq("preparation"),any(),any());
        when(images.selectAccessibleById(3L,"another")).thenReturn(null);
        assertThatThrownBy(() -> preparation.select(object().put("imageId",3).put("monitoringRegionId",5),"another"))
                .isInstanceOf(AgentFailure.class);
        verify(regions,never()).find(5L,"another");
    }
}
