package space.yong.orkes.services;

import static com.netflix.conductor.client.http.ConductorClientRequest.Method.GET;
import static com.netflix.conductor.client.http.ConductorClientRequest.Method.POST;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.conductor.client.http.ConductorClient;
import com.netflix.conductor.client.http.ConductorClientRequest;
import com.netflix.conductor.client.http.ConductorClientResponse;
import com.netflix.conductor.client.http.WorkflowClient;
import com.netflix.conductor.common.metadata.workflow.StartWorkflowRequest;
import com.netflix.conductor.common.run.Workflow;
import com.netflix.conductor.sdk.workflow.executor.WorkflowExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class OrkesService {
    private final ObjectMapper objectMapper;
    private final WorkflowExecutor executor;
    private final ConductorClient client;
    private final WorkflowClient workflowClient;

    public record HumanTaskUserAssignee(String userType, Object user) {}

    @PostMapping("execute/{workflowName}/{version}")
    public Map<String, Object> executeWorkflow(
        Authentication auth,
        @PathVariable String workflowName,
        @PathVariable int version,
        @RequestBody Map<String, Object> input
    ) throws JsonProcessingException {
        String inputString = objectMapper.writeValueAsString(input);
        log.info("{} executing {} (v{}): {}", auth.getPrincipal(), workflowName, version, inputString);
        Map<String, Object> output = executor.executeWorkflow(workflowName, version, input).join().getOutput();
        log.info("Result: {}", objectMapper.writeValueAsString(output));
        return output;
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
        String correlationId = identityCorrelation ? auth.getPrincipal().toString() : "";
        log.info("Searching executions for {} ({})", workflowName, correlationId);
        return workflowClient.getWorkflows(workflowName, correlationId,false, true);
    };

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
    public Object signal(
        @PathVariable String workflowId,
        @RequestBody Map<String, Object> input
    ) throws JsonProcessingException {
        String inputString = objectMapper.writeValueAsString(input);
        log.info("Sending signal to {} ({})", workflowId, inputString);
        ConductorClientResponse<Object> resp =  client.execute(ConductorClientRequest.builder()
            .method(POST)
            .path("/tasks/{workflowId}/COMPLETED/signal/sync")
            .addPathParam("workflowId", workflowId)
            .body(input)
            .build(), new TypeReference<>() {});
        return resp.getData();
    }

    @GetMapping("workflow-def/{name}")
    public Object getWorkflowDef(
        Authentication auth,
        @PathVariable String name
    ) {
        log.info("{} getting workflow definition {}", auth.getPrincipal(), name);

        ConductorClientRequest request = ConductorClientRequest.builder()
            .method(ConductorClientRequest.Method.GET)
            .path("/metadata/workflow/{name}")
            .addPathParam("name", name)
            .build();
        ConductorClientResponse<Map<String, Object>> resp = client.execute(request, new TypeReference<>() {});
        return resp.getData();
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
}
