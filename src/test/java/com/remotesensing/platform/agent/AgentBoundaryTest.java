package com.remotesensing.platform.agent;

import com.remotesensing.platform.common.*;
import com.remotesensing.platform.config.properties.AuthProperties;
import com.remotesensing.platform.service.*;
import java.util.UUID;
import java.net.URI;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentBoundaryTest {
    MockMvc mvc;
    AgentService service;
    JwtTokenService tokens;
    TokenBlacklistService blacklist;
    @BeforeEach void setup() {
        tokens = mock(JwtTokenService.class); blacklist = mock(TokenBlacklistService.class);
        AuthProperties auth = new AuthProperties(); auth.setDevUserHeaderEnabled(true);
        CurrentUserContext users = new CurrentUserContext(tokens,auth,blacklist);
        when(tokens.parseToken("synthetic-jwt")).thenReturn(new JwtTokenService.JwtUser("owner-a","a","USER","jti",Long.MAX_VALUE));
        service = mock(AgentService.class);
        when(service.create(any())).thenReturn(AgentJson.object().put("executionId", UUID.randomUUID().toString()));
        when(service.answer(any())).thenAnswer(i -> AgentJson.object().put("summary", "安全测试回答"));
        when(service.confirm(anyString(),any())).thenAnswer(i -> AgentJson.object().put("taskId", "synthetic-task"));
        mvc = MockMvcBuilders.standaloneSetup(new AgentController(service,users,mock(RateLimitService.class),new AgentProperties()))
                .setControllerAdvice(new AgentAdvice()).addFilters(new AgentBoundary(users)).build();
    }
    @Test void devHeaderAndWorkerHeaderCannotAuthenticateAgent() throws Exception {
        for (String header : new String[]{"X-User-Id","X-Worker-Token"})
            mvc.perform(post("/agent/executions").header(header,"owner-a").contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void validJwtAndBlacklistedJwtHaveDistinctBoundaries() throws Exception {
        mvc.perform(post("/agent/executions").header("Authorization","Bearer synthetic-jwt")
                .contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.requestId").exists());
        when(blacklist.isBlacklistedByJti("jti")).thenReturn(true);
        mvc.perform(get("/agent/executions").header("Authorization","Bearer synthetic-jwt")).andExpect(status().isUnauthorized());
    }
    @Test void duplicateNonfiniteTrailingAndOversizedBodiesFailBeforeService() throws Exception {
        for (String body : new String[]{"{\"text\":\"password=synthetic-only\",\"text\":\"other\"}", "{\"x\":NaN}","{} {}", "x".repeat(16385)})
            mvc.perform(post("/agent/executions").header("Authorization","Bearer synthetic-jwt")
                    .contentType("application/json").content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(400)).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("synthetic-only"))));
        verifyNoInteractions(service);
    }
    @Test void contextAndMatrixPathsRejectInvalidBodyBeforeAnyBusinessCall() throws Exception {
        String id = UUID.randomUUID().toString();
        for (String prefix : new String[]{"/api/agent", "/api/agent;v=1", "/api/%61gent;v=1"}) {
            for (String suffix : new String[]{"/executions", "/answers", "/executions/"+id+"/confirm"}) {
                for (String body : new String[]{"{\"text\":\"synthetic-only\",\"text\":\"again\"}", " ".repeat(20000)+"{}"})
                    mvc.perform(post(URI.create(prefix+suffix)).contextPath("/api").header("Authorization","Bearer synthetic-jwt")
                            .contentType("application/json").content(body))
                            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(400))
                            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("synthetic-only"))));
            }
        }
        verifyNoInteractions(service);
    }
    @Test void contextAndMatrixPathsAlwaysInitializeRequestIdBeforeBusinessCall() throws Exception {
        String id = UUID.randomUUID().toString();
        for (String prefix : new String[]{"/api/agent", "/api/agent;v=1", "/api/%61gent;v=1"}) {
            for (String suffix : new String[]{"/executions", "/answers", "/executions/"+id+"/confirm"})
                mvc.perform(post(URI.create(prefix+suffix)).contextPath("/api").header("Authorization","Bearer synthetic-jwt")
                        .contentType("application/json").content("{}"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                        .andExpect(jsonPath("$.data.requestId").isNotEmpty());
        }
        verify(service,times(3)).create(any()); verify(service,times(3)).answer(any()); verify(service,times(3)).confirm(eq(id),any());
    }
    @Test void matrixPathsStillRequireJwtBeforeReadingBody() throws Exception {
        mvc.perform(post("/api/agent;v=1/answers").contextPath("/api").header("X-User-Id","owner-a")
                .contentType("application/json").content(" ".repeat(20000)+"{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
