package com.skyfleet.logistics;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/demo")
public class TaskInstanceController {
    private final TaskInstanceService tasks;
    private final DemoSessionService sessions;
    private final VisitorIdentity visitors;
    private final TutorialProgressService tutorials;
    private final AdvancedRoutingProperties advancedRouting;

    public TaskInstanceController(TaskInstanceService tasks, DemoSessionService sessions, VisitorIdentity visitors,
                                  TutorialProgressService tutorials, AdvancedRoutingProperties advancedRouting) {
        this.tasks = tasks; this.sessions = sessions; this.visitors = visitors;
        this.tutorials = tutorials; this.advancedRouting = advancedRouting;
    }

    @GetMapping("/scenario-templates")
    public Map<String, Object> templates() { return Map.of("items", tasks.templateSummaries()); }

    @PostMapping("/task-instances")
    public Map<String, Object> generate(@RequestBody GenerateRequest body, HttpServletRequest request, HttpServletResponse response) {
        VisitorIdentity.Identity visitor = visitors.resolve(request, response);
        String tutorialId = body.tutorialId();
        String planningMode = body.planningMode() == null ? "BASIC" : body.planningMode();
        String seed = body.seed();
        Map<String, Object> parameters = body.parameters() == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(body.parameters());
        if (TutorialProgressService.GROUND_COOP_ID.equals(tutorialId)) {
            if (!tutorials.groundCoopAvailable(visitor.visitorHash()))
                throw new DemoException(org.springframework.http.HttpStatus.FORBIDDEN, "教程 02 尚未解锁");
            planningMode = "ADVANCED";
            seed = "2026091202";
            parameters.put("tutorialBatteryProtected", true);
            parameters.put("airspaceThemeCount", 2);
            parameters.put("trafficSignalsEnabled", false);
        } else if ("ADVANCED".equalsIgnoreCase(planningMode)
                && !advancedRouting.technicalPreviewEnabled && !tutorials.advancedUnlocked(visitor.visitorHash())) {
            throw new DemoException(org.springframework.http.HttpStatus.FORBIDDEN, "请先完成教程 02 解锁进阶规划");
        }
        return tasks.generate(visitor.visitorHash(), body.scenarioTemplateId(), seed, parameters, planningMode, tutorialId);
    }

    @GetMapping("/task-instances")
    public Map<String, Object> history(@RequestParam(required = false) String cursor,
                                       @RequestParam(defaultValue = "20") int limit,
                                       HttpServletRequest request, HttpServletResponse response) {
        VisitorIdentity.Identity visitor = visitors.resolve(request, response);
        return tasks.history(visitor.visitorHash(), cursor, limit);
    }

    @GetMapping("/task-instances/{taskId}")
    public Map<String, Object> task(@PathVariable String taskId, HttpServletRequest request, HttpServletResponse response) {
        VisitorIdentity.Identity visitor = visitors.resolve(request, response);
        return tasks.getOwned(taskId, visitor.visitorHash());
    }

    @PostMapping("/task-instances/{taskId}/runs")
    public Map<String, Object> start(@PathVariable String taskId, @RequestBody(required = false) StartRunRequest body,
                                     HttpServletRequest request, HttpServletResponse response) {
        VisitorIdentity.Identity visitor = visitors.resolve(request, response);
        return sessions.createFromTask(taskId, visitor.visitorHash(), source(request),
                body == null ? null : body.selectedBaselineRouteCandidateId());
    }

    @GetMapping("/runs/{runId}")
    public Map<String, Object> run(@PathVariable String runId, HttpServletRequest request, HttpServletResponse response) {
        VisitorIdentity.Identity visitor = visitors.resolve(request, response);
        try { return sessions.mission(runId, visitor.visitorHash()); }
        catch (DemoException error) { return castSnapshot(tasks.replay(runId, visitor.visitorHash())); }
    }

    @GetMapping("/runs/{runId}/replay")
    public Map<String, Object> replay(@PathVariable String runId, HttpServletRequest request, HttpServletResponse response) {
        VisitorIdentity.Identity visitor = visitors.resolve(request, response);
        return tasks.replay(runId, visitor.visitorHash());
    }

    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String runId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                             HttpServletRequest request, HttpServletResponse response) {
        VisitorIdentity.Identity visitor = visitors.resolve(request, response);
        return sessions.events(runId, visitor.visitorHash(), lastEventId);
    }

    @PostMapping("/runs/{runId}/airspace-conflicts/{volumeId}/actions")
    public Map<String, Object> airspaceAction(@PathVariable String runId, @PathVariable String volumeId,
                                               @RequestBody AirspaceActionRequest body,
                                               HttpServletRequest request, HttpServletResponse response) {
        VisitorIdentity.Identity visitor = visitors.resolve(request, response);
        return sessions.airspaceAction(runId, visitor.visitorHash(), volumeId, body.actionType());
    }

    @PostMapping("/runs/{runId}/ground-routing-commands")
    public ResponseEntity<Map<String, Object>> groundRoutingCommand(@PathVariable String runId,
                                                                    @RequestBody GroundRouteCommandRequest body,
                                                                    HttpServletRequest request, HttpServletResponse response) {
        VisitorIdentity.Identity visitor = visitors.resolve(request, response);
        Map<String, Object> accepted = sessions.groundRouteCommand(runId, visitor.visitorHash(), body.commandId(),
                body.commandSequence(), body.type(), body.sourceType(), body.targetId(), body.position());
        return ResponseEntity.accepted().body(accepted);
    }

    @PostMapping("/runs/{runId}/rewind-checkpoints/{checkpointId}/restore")
    public Map<String, Object> restoreCheckpoint(@PathVariable String runId, @PathVariable String checkpointId,
                                                  @RequestBody RewindRequest body,
                                                  HttpServletRequest request, HttpServletResponse response) {
        VisitorIdentity.Identity visitor = visitors.resolve(request, response);
        return sessions.restoreCheckpoint(runId, visitor.visitorHash(), checkpointId, body.rewindId(), body.expectedRevision());
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> castSnapshot(Map<String, Object> replay) {
        return (Map<String, Object>) replay.get("snapshot");
    }
    private static String source(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null && !forwarded.isBlank() ? forwarded.split(",", 2)[0].trim() : request.getRemoteAddr();
    }
    public record GenerateRequest(String scenarioTemplateId, String seed, Map<String, Object> parameters,
                                  String planningMode, String tutorialId) {}
    public record StartRunRequest(String selectedBaselineRouteCandidateId) {}
    public record GroundRouteCommandRequest(String commandId, long commandSequence, String type,
                                            String sourceType, String targetId, java.util.List<Number> position) {}
    public record AirspaceActionRequest(String actionType) {}
    public record RewindRequest(String rewindId, long expectedRevision) {}
}
