package space.yong.orkes.services;

import static com.netflix.conductor.client.http.ConductorClientRequest.Method.GET;
import static com.netflix.conductor.client.http.ConductorClientRequest.Method.POST;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.conductor.client.http.ConductorClient;
import com.netflix.conductor.client.http.ConductorClientRequest;
import com.netflix.conductor.client.http.ConductorClientResponse;
import com.netflix.conductor.sdk.workflow.executor.WorkflowExecutor;
import io.orkes.conductor.client.ApiClient;
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
    private final ApiClient apiClient;

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
        String output = executor.startWorkflow(workflowName, version, input);
        log.info("Execution ID: {}", output);
        return output;
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
}
