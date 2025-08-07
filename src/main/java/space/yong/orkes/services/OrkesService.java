package space.yong.orkes.services;

import static com.netflix.conductor.client.http.ConductorClientRequest.Method.GET;
import static com.netflix.conductor.client.http.ConductorClientRequest.Method.POST;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.conductor.client.http.ConductorClientRequest;
import com.netflix.conductor.client.http.ConductorClientResponse;
import com.netflix.conductor.common.metadata.tasks.Task;
import com.netflix.conductor.common.metadata.workflow.StartWorkflowRequest;
import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.common.run.Workflow;
import io.orkes.conductor.client.ApiClient;
import io.orkes.conductor.client.enums.Consistency;
import io.orkes.conductor.client.enums.ReturnStrategy;
import io.orkes.conductor.client.http.OrkesMetadataClient;
import io.orkes.conductor.client.http.OrkesTaskClient;
import io.orkes.conductor.client.http.OrkesWorkflowClient;
import io.orkes.conductor.client.model.SignalResponse;
import io.orkes.conductor.client.model.WorkflowRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Slf4j
@RestController
@RequiredArgsConstructor
public class OrkesService {
    private final ObjectMapper objectMapper;
    private final ApiClient client;
    private final OrkesWorkflowClient workflowClient;
    private final OrkesMetadataClient metadataClient;
    private final OrkesTaskClient taskClient;

    public record HumanTaskUserAssignee(String userType, Object user) {}

    @PostMapping("execute/{workflowName}/{version}")
    public WorkflowRun executeWorkflow(
        Authentication auth,
        @PathVariable String workflowName,
        @PathVariable int version,
        @RequestBody Map<String, Object> input
    ) throws JsonProcessingException, ExecutionException, InterruptedException {
        String inputString = objectMapper.writeValueAsString(input);
        log.info("{} executing versioned {} (v{}): {}", auth.getPrincipal(), workflowName, version, inputString);

        var request = new StartWorkflowRequest();
        request.setName(workflowName);
        request.setVersion(version);
        if (input.containsKey("correlationId")) {
            request.setCorrelationId(input.get("correlationId").toString());
            input.remove("correlationId");
        }
        request.setInput(input);
        return workflowClient.executeWorkflow(request, "", 60).get();
    }

    @PostMapping("execute/{workflowName}")
    public SignalResponse executeSyncWorkflow(
        Authentication auth,
        @PathVariable String workflowName,
        @RequestBody Map<String, Object> input
    ) throws JsonProcessingException, ExecutionException, InterruptedException {
        String inputString = objectMapper.writeValueAsString(input);
        log.info("{} executing {}: {}", auth.getPrincipal(), workflowName, inputString);

        var request = new StartWorkflowRequest();
        request.setName(workflowName);
        if (input.containsKey("correlationId")) {
            request.setCorrelationId(input.get("correlationId").toString());
            input.remove("correlationId");
        }
        request.setInput(input);
        return workflowClient.executeWorkflowWithReturnStrategy(request,null,60, Consistency.SYNCHRONOUS, ReturnStrategy.BLOCKING_TASK_INPUT).get();
    }

    @PostMapping("start/{workflowName}/{version}")
    public String startWorkflow(
        Authentication auth,
        @PathVariable String workflowName,
        @PathVariable int version,
        @RequestBody Map<String, Object> input
    ) throws JsonProcessingException {
        String inputString = objectMapper.writeValueAsString(input);
        log.info("{} starting {} (v{}): {}", auth.getPrincipal(), workflowName, version, inputString);

        StartWorkflowRequest request = new StartWorkflowRequest();
        if (input.containsKey("correlationId")) {
            request.setCorrelationId(input.get("correlationId").toString());
            input.remove("correlationId");
        }
        request.setInput(input);
        request.setName(workflowName);
        request.setVersion(version);

        String executionId = workflowClient.startWorkflow(request);
        log.info("Execution ID: {}", executionId);
        return executionId;
    }

    @GetMapping("human-tasks")
    public Object listHumanTasks(Authentication auth) {
        log.info("Listing human tasks for {}", auth.getPrincipal());
        var body = Map.of(
            "size", 15,
            "states", List.of("ASSIGNED"),
            "assignees", List.of(
                new HumanTaskUserAssignee("EXTERNAL_USER", auth.getPrincipal()),
                new HumanTaskUserAssignee("EXTERNAL_GROUP", "LABS")
            )
        );
        var request = ConductorClientRequest.builder()
            .method(POST)
            .path("/human/tasks/search")
            .body(body)
            .build();
        ConductorClientResponse<Object> resp = client.execute(request, new TypeReference<>() {});
        return resp.getData();
    }

    @PostMapping("human-tasks/{taskId}")
    public void claimAndCompleteHumanTask(
        Authentication auth,
        @PathVariable String taskId,
        @RequestBody Map<String, Object> input
    ) throws JsonProcessingException {
        String inputString = objectMapper.writeValueAsString(input);
        log.info("Claim and complete human task by {}: {}", auth.getPrincipal(), inputString);

        client.execute(ConductorClientRequest.builder()
            .method(POST)
            .path("/human/tasks/{taskId}/externalUser/{user}")
            .addPathParam("taskId", taskId)
            .addPathParam("user", auth.getPrincipal().toString())
            .build());

        client.execute(ConductorClientRequest.builder()
            .method(POST)
            .path("/human/tasks/{taskId}/update")
            .addPathParam("taskId", taskId)
            .addQueryParam("complete", "true")
            .body(input)
            .build());
    }

    @GetMapping("human-template")
    public Object getTemplate(
        Authentication auth,
        @RequestParam String name
    ) {
        log.info("{} getting template {}", auth.getPrincipal(), name);
        var request = ConductorClientRequest.builder()
            .method(GET)
            .path("/human/template")
            .addQueryParam("name", name)
            .build();
        ConductorClientResponse<List<Object>> resp = client.execute(request, new TypeReference<>() {});
        return resp.getData();
    }

    @GetMapping("search-executions")
    public List<Workflow> searchExecutions(
        Authentication auth,
        @RequestParam String workflowName,
        @RequestParam boolean identityCorrelation
    ) {
        String email = auth.getPrincipal().toString();
        List<String> correlationId = identityCorrelation ? List.of(email) : List.of();
        log.info("Searching executions for {} ({})", workflowName, correlationId);
        var results = workflowClient.getWorkflowsByNamesAndCorrelationIds(correlationId, List.of(workflowName), false, false);
        return results.containsKey(email) ? results.get(email) : List.of();
    }

    @GetMapping("execution/{executionId}")
    public Object getExecution(
        Authentication auth,
        @PathVariable String executionId
    ) {
        log.info("{} getting execution {}", auth.getPrincipal(), executionId);
        ConductorClientRequest request = ConductorClientRequest.builder()
            .method(ConductorClientRequest.Method.GET)
            .path("/workflow/{workflowId}")
            .addPathParam("workflowId", executionId)
            .build();
        return client.execute(request, new TypeReference<>() {}).getData();
    }

    @PostMapping("signal/{workflowId}")
    public SignalResponse signal(
        @PathVariable String workflowId,
        @RequestBody Map<String, Object> input
    ) throws JsonProcessingException {
        String inputString = objectMapper.writeValueAsString(input);
        log.info("Sending signal to {} ({})", workflowId, inputString);
        return taskClient.signal(workflowId, Task.Status.COMPLETED, input);
    }

    @GetMapping("workflow-def/{name}")
    public WorkflowDef getWorkflowDef(
        Authentication auth,
        @PathVariable String name
    ) {
        log.info("{} getting workflow definition {}", auth.getPrincipal(), name);
        return metadataClient.getWorkflowDef(name, null);
    }

    @GetMapping("schema/{name}")
    public Object getSchema(
        Authentication auth,
        @PathVariable String name
    ) {
        log.info("{} getting schema {}", auth.getPrincipal(), name);
        var request = ConductorClientRequest.builder()
            .method(GET)
            .path("/schema/{name}")
            .addPathParam("name", name)
            .build();
        ConductorClientResponse<Object> resp = client.execute(request, new TypeReference<>() {});
        return resp.getData();
    }

    @DeleteMapping("terminate/{workflowId}")
    public void terminateWorkflow(
        Authentication auth,
        @PathVariable String workflowId,
        @RequestParam(required = false) String reason
    ) {
        log.info("{} terminating workflow {}", auth.getPrincipal(), workflowId);
        workflowClient.terminateWorkflow(workflowId,reason);
    }
}
