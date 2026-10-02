package com.remotesensing.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.dto.MonitoringRegionDTO;
import com.remotesensing.platform.mapper.MonitoringRegionMapper;
import com.remotesensing.platform.vo.MonitoringRegionVO;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MonitoringRegionServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final MonitoringRegionMapper mapper = mock(MonitoringRegionMapper.class);
    private final CurrentUserContext users = mock(CurrentUserContext.class);
    private final MonitoringRegionService service = new MonitoringRegionService(mapper, users, json);
    private static final String GEOMETRY = """
        {"type":"Polygon","coordinates":[[[116,28],[117,28],[117,29],[116,28]]]}
        """;

    @Test
    void validatesStructureBeforeDatabase() throws Exception {
        MonitoringRegionService.validateGeometry(json.readTree(GEOMETRY));
        for (String invalid : new String[]{
                "{}", GEOMETRY.replace("Polygon", "MultiPolygon"),
                GEOMETRY.replace("[117,29]", "[117,90]"),
                GEOMETRY.replace("[116,28]]]","[116,29]]]"),
                GEOMETRY.replace("[117,29]", "[117]")}) {
            assertThatThrownBy(() -> MonitoringRegionService.validateGeometry(json.readTree(invalid)))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void unauthorizedReadAndUpdateCannotReachWrite() throws Exception {
        when(users.getCurrentUserId()).thenReturn("other-user");
        assertThatThrownBy(() -> service.snapshot(3L)).hasMessageContaining("无权访问");
        MonitoringRegionDTO dto = new MonitoringRegionDTO();
        dto.setName("new"); dto.setGeometry(json.readTree(GEOMETRY));
        assertThatThrownBy(() -> service.save(3L, dto)).hasMessageContaining("无权访问");
        verify(mapper, never()).update(any(), any());
        verify(mapper, never()).valid(any());
    }

    @Test
    void historicalSnapshotDoesNotFollowEdits() {
        when(users.getCurrentUserId()).thenReturn("owner");
        MonitoringRegionVO region = new MonitoringRegionVO();
        region.setId(3L); region.setName("original"); region.setVersion(1); region.setGeometryJson(GEOMETRY);
        when(mapper.find(3L, "owner")).thenReturn(region);
        var snapshot = service.snapshot(3L);
        region.setName("edited"); region.setVersion(2);
        assertThat(snapshot.path("name").asText()).isEqualTo("original");
        assertThat(snapshot.path("version").asInt()).isEqualTo(1);
        assertThat(service.snapshot(3L).path("version").asInt()).isEqualTo(2);
    }

    @Test
    void rejectsInvalidTopologyBeforeInsert() throws Exception {
        MonitoringRegionDTO dto = new MonitoringRegionDTO();
        dto.setName("region"); dto.setGeometry(json.readTree(GEOMETRY));
        when(mapper.valid(any())).thenReturn(false);
        assertThatThrownBy(() -> service.save(null, dto)).hasMessageContaining("自交");
        verify(mapper, never()).insert(any(), any());
    }
}
