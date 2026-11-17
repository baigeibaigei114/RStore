package com.remotesensing.platform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.common.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/agent")
public class AgentController {
    private final AgentService service;
    public AgentController(AgentService service) { this.service = service; }
    private Result<JsonNode> result(HttpServletRequest request, JsonNode data) {
        if (data.isObject()) ((ObjectNode) data).put("requestId", request.getAttribute(AgentBoundary.REQUEST_ID).toString());
        else data = AgentJson.object().put("requestId", request.getAttribute(AgentBoundary.REQUEST_ID).toString()).set("steps", data);
        return Result.success(data);
    }
    @PostMapping({"/executions", "/answers", "/executions/{id}/messages", "/executions/{id}/confirm",
            "/executions/{id}/explanation", "/executions/{id}/retry", "/executions/{id}/cancel"})
    public Result<JsonNode> retiredWrite() {
        throw new AgentFailure("LEGACY_AGENT_READ_ONLY", "旧助手已改为历史记录，请使用影像助手准备分析或查询资料", 403, false);
    }
    @GetMapping("/executions") public Result<JsonNode> page(@RequestParam(defaultValue="1") int pageNum, @RequestParam(defaultValue="20") int pageSize, HttpServletRequest request) { return result(request, service.page(pageNum,pageSize)); }
    @GetMapping("/executions/{id}") public Result<JsonNode> get(@PathVariable String id, HttpServletRequest request) { return result(request, service.get(id)); }
    @GetMapping("/executions/{id}/steps") public Result<JsonNode> steps(@PathVariable String id, @RequestParam(defaultValue="0") int afterSequence, HttpServletRequest request) { return result(request, service.steps(id,afterSequence)); }
}
