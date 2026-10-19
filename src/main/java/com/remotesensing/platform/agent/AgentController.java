package com.remotesensing.platform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.common.*;
import com.remotesensing.platform.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/agent")
public class AgentController {
    private final AgentService service;
    private final CurrentUserContext users;
    private final RateLimitService rate;
    private final AgentProperties limits;
    public AgentController(AgentService service, CurrentUserContext users, RateLimitService rate, AgentProperties limits) {
        this.service = service; this.users = users; this.rate = rate; this.limits = limits;
    }
    private void write() { rate.check("agent:" + users.getAuthenticatedUserId(), limits.getOwnerRateLimit(), Duration.ofSeconds(limits.getOwnerRateSeconds())); }
    private Result<JsonNode> result(HttpServletRequest request, JsonNode data) {
        if (data.isObject()) ((ObjectNode) data).put("requestId", request.getAttribute(AgentBoundary.REQUEST_ID).toString());
        else data = AgentJson.object().put("requestId", request.getAttribute(AgentBoundary.REQUEST_ID).toString()).set("steps", data);
        return Result.success(data);
    }
    @PostMapping("/executions") public Result<JsonNode> create(@RequestBody JsonNode body, HttpServletRequest request) { write(); return result(request, service.create(body)); }
    @GetMapping("/executions") public Result<JsonNode> page(@RequestParam(defaultValue="1") int pageNum, @RequestParam(defaultValue="20") int pageSize, HttpServletRequest request) { return result(request, service.page(pageNum,pageSize)); }
    @GetMapping("/executions/{id}") public Result<JsonNode> get(@PathVariable String id, HttpServletRequest request) { return result(request, service.get(id)); }
    @PostMapping("/executions/{id}/messages") public Result<JsonNode> message(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) { write(); return result(request, service.message(id,body)); }
    @PostMapping("/executions/{id}/confirm") public Result<JsonNode> confirm(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) { write(); return result(request, service.confirm(id,body)); }
    @PostMapping("/executions/{id}/explanation") public Result<JsonNode> explanation(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) { write(); return result(request, service.explanation(id,body)); }
    @PostMapping("/executions/{id}/retry") public Result<JsonNode> retry(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) { write(); return result(request, service.retry(id,body)); }
    @PostMapping("/executions/{id}/cancel") public Result<JsonNode> cancel(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) { write(); return result(request, service.cancel(id,body)); }
    @GetMapping("/executions/{id}/steps") public Result<JsonNode> steps(@PathVariable String id, @RequestParam(defaultValue="0") int afterSequence, HttpServletRequest request) { return result(request, service.steps(id,afterSequence)); }
    @PostMapping("/answers") public Result<JsonNode> answer(@RequestBody JsonNode body, HttpServletRequest request) { write(); return result(request, service.answer(body)); }
}
