package com.remotesensing.platform.agent;

import com.remotesensing.platform.common.*;
import com.remotesensing.platform.exception.BusinessException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** JWT before body, bounded/strict JSON, request-local replay; other APIs retain their policy. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AgentBoundary extends OncePerRequestFilter {
    public static final String REQUEST_ID = "agentRequestId";
    private static final PathPattern AGENT_PATH = PathPatternParser.defaultInstance.parse("/agent/**");
    private final CurrentUserContext users;
    public AgentBoundary(CurrentUserContext users) { this.users = users; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        if (request.getMethod().equals("OPTIONS")) return true;
        // Match the same decoded segments/matrix parameters as MVC, without changing its cached path.
        Object previous = request.getAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE);
        try {
            return !AGENT_PATH.matches(ServletRequestPathUtils.parseAndCache(request).pathWithinApplication());
        } finally { request.setAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE, previous); }
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        request.setAttribute(REQUEST_ID, UUID.randomUUID().toString());
        try {
            // Filters execute before DispatcherServlet installs RequestContextHolder.
            var previous = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            try {
                org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
                users.getAuthenticatedUserId();
            } finally { org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(previous); }
            if (!request.getMethod().equals("POST")) { chain.doFilter(request,response); return; }
            byte[] bytes = request.getInputStream().readNBytes(16385);
            if (bytes.length == 0 || bytes.length > 16384) throw AgentJson.input();
            JsonBody.validate(bytes);
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() {
                    ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
                    return new ServletInputStream() {
                        public int read() { return stream.read(); }
                        public boolean isFinished() { return stream.available() == 0; }
                        public boolean isReady() { return true; }
                        public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous body"); }
                    };
                }
                @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8)); }
            }, response);
        } catch (BusinessException error) {
            response.setStatus(error.getCode() == 401 ? 401 : 200); response.setContentType("application/json;charset=UTF-8");
            AgentJson.JSON.writeValue(response.getOutputStream(), Result.fail(error.getCode(), error.getMessage()));
        }
    }
    private static class JsonBody {
        static void validate(byte[] bytes) {
            try { var value = AgentJson.JSON.readTree(bytes); AgentJson.require(value != null && value.isObject()); }
            catch (IOException e) { throw AgentJson.input(); }
        }
    }
}
