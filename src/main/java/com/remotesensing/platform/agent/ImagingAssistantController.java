package com.remotesensing.platform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.service.RateLimitService;
import java.time.Duration;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/agent/imaging-assistant")
public class ImagingAssistantController {
    private final AnalysisPreparation preparation;
    private final AgentService knowledge;
    private final CurrentUserContext users;
    private final RateLimitService rate;
    private final AgentProperties limits;

    public ImagingAssistantController(AnalysisPreparation preparation, AgentService knowledge,
                                      CurrentUserContext users, RateLimitService rate, AgentProperties limits) {
        this.preparation = preparation;
        this.knowledge = knowledge;
        this.users = users;
        this.rate = rate;
        this.limits = limits;
    }

    @PostMapping("/preparation")
    public Result<JsonNode> prepare(@RequestBody JsonNode body) {
        String owner = users.getAuthenticatedUserId();
        rateLimit(owner);
        return Result.success(preparation.prepare(body, owner));
    }

    @PostMapping("/draft")
    public Result<JsonNode> draft(@RequestBody JsonNode body) {
        String owner = users.getAuthenticatedUserId();
        rateLimit(owner);
        return Result.success(preparation.select(body, owner));
    }

    @PostMapping("/knowledge")
    public Result<JsonNode> answer(@RequestBody JsonNode body) {
        rateLimit(users.getAuthenticatedUserId());
        return Result.success(knowledge.answer(body));
    }

    private void rateLimit(String owner) {
        rate.check("agent:" + owner, limits.getOwnerRateLimit(), Duration.ofSeconds(limits.getOwnerRateSeconds()));
    }
}
