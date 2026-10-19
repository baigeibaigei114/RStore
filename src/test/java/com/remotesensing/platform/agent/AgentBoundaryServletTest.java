package com.remotesensing.platform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.config.properties.AuthProperties;
import com.remotesensing.platform.service.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual Servlet routing on loopback; business service/provider/database are never started. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentBoundaryServletTest {
    @TempDir static Path directory;
    Tomcat tomcat;
    AnnotationConfigWebApplicationContext web;
    AgentService service;
    String origin;
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @BeforeAll void start() throws Exception {
        tomcat = new Tomcat(); tomcat.setBaseDir(directory.toString()); tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");
        Context context = tomcat.addContext("/api", directory.toString());
        web = new AnnotationConfigWebApplicationContext(); web.setServletContext(context.getServletContext());
        web.register(Mvc.class); web.refresh(); service = web.getBean(AgentService.class);
        Tomcat.addServlet(context,"dispatcher",new DispatcherServlet(web)).setLoadOnStartup(1);
        context.addServletMappingDecoded("/","dispatcher");
        FilterDef filter = new FilterDef(); filter.setFilterName("agentBoundary");
        filter.setFilter(new AgentBoundary(web.getBean(CurrentUserContext.class))); context.addFilterDef(filter);
        FilterMap mapping = new FilterMap(); mapping.setFilterName("agentBoundary"); mapping.addURLPattern("/*"); context.addFilterMap(mapping);
        tomcat.start(); origin = "http://127.0.0.1:"+tomcat.getConnector().getLocalPort();
    }
    @AfterAll void stop() throws Exception {
        if (tomcat != null) { tomcat.stop(); tomcat.destroy(); }
        if (web != null) web.close();
    }
    @BeforeEach void clearCalls() { clearInvocations(service); }
    JsonNode post(String path, String body) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(origin+path)).timeout(Duration.ofSeconds(5))
                .header("Authorization","Bearer synthetic-jwt").header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return AgentJson.JSON.readTree(response.body());
    }
    @Test void realServletRejectsDuplicateAndOversizedBodiesBeforeServiceOnEveryVariant() throws Exception {
        String id = UUID.randomUUID().toString();
        for (String prefix : new String[]{"/api/agent", "/api/agent;v=1", "/api/%61gent;v=1"}) {
            for (String suffix : new String[]{"/executions", "/answers", "/executions/"+id+"/confirm"}) {
                for (String body : new String[]{"{\"text\":\"synthetic-only\",\"text\":\"again\"}", " ".repeat(20000)+"{}"}) {
                    JsonNode response = post(prefix+suffix,body);
                    assertThat(response.path("code").asInt()).isEqualTo(400);
                    assertThat(response.toString()).doesNotContain("synthetic-only");
                }
            }
        }
        verifyNoInteractions(service);
    }
    @Test void realServletAcceptsValidVariantsWithRequestIdWithoutExecutionThen500() throws Exception {
        String id = UUID.randomUUID().toString();
        for (String prefix : new String[]{"/api/agent", "/api/agent;v=1", "/api/%61gent;v=1"}) {
            for (String suffix : new String[]{"/executions", "/answers", "/executions/"+id+"/confirm"}) {
                JsonNode response = post(prefix+suffix,"{}");
                assertThat(response.path("code").asInt()).isEqualTo(200);
                assertThatCode(() -> UUID.fromString(response.path("data").path("requestId").asText())).doesNotThrowAnyException();
            }
        }
        verify(service,times(3)).create(any()); verify(service,times(3)).answer(any()); verify(service,times(3)).confirm(eq(id),any());
    }
    @Configuration(proxyBeanMethods=false) @EnableWebMvc
    static class Mvc {
        @Bean CurrentUserContext users() {
            JwtTokenService tokens = mock(JwtTokenService.class);
            when(tokens.parseToken("synthetic-jwt")).thenReturn(new JwtTokenService.JwtUser("owner-a","a","USER","jti",Long.MAX_VALUE));
            return new CurrentUserContext(tokens,new AuthProperties(),mock(TokenBlacklistService.class));
        }
        @Bean AgentService service() {
            AgentService service = mock(AgentService.class);
            when(service.create(any())).thenAnswer(i -> AgentJson.object().put("executionId",UUID.randomUUID().toString()));
            when(service.answer(any())).thenAnswer(i -> AgentJson.object().put("summary","安全测试回答"));
            when(service.confirm(anyString(),any())).thenAnswer(i -> AgentJson.object().put("taskId","synthetic-task"));
            return service;
        }
        @Bean AgentController controller(AgentService service, CurrentUserContext users) {
            return new AgentController(service,users,mock(RateLimitService.class),new AgentProperties());
        }
        @Bean AgentAdvice advice() { return new AgentAdvice(); }
    }
}
