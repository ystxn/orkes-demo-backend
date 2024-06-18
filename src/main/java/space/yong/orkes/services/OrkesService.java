package space.yong.orkes.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.conductor.sdk.workflow.executor.WorkflowExecutor;
import io.orkes.conductor.client.ApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.orkes.conductor.client.http.Pair;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class OrkesService {
    private final ObjectMapper objectMapper;
    private final WorkflowExecutor executor;
    private final ApiClient apiClient;

    @PostMapping("execute/{workflowName}/{version}")
    public Map<String, Object> execute(
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

    @GetMapping("human-tasks")
    public Object listHumanTasks(Authentication auth) {
        log.info("Listing human tasks for {}", auth.getPrincipal());
        var call = apiClient.buildCall(
            "/human/tasks/search",
            "POST",
            new ArrayList<>(),
            new ArrayList<>(),
            Map.of(
                "size", 15,
                "states", List.of("ASSIGNED"),
                "assignees", List.of(
                    Map.of(
                        "userType", "CONDUCTOR_USER",
                        "user", auth.getPrincipal()
                    )
                )
            ),
            new HashMap<>(),
            Map.of(),
            new String[] {"api_key"},
            null);
        var response = apiClient.execute(call, Map.class);
        return response.getData();
    }

    @GetMapping("human-template")
    public Object getTemplate(
        Authentication auth,
        @RequestParam String name
    ) {
        log.info("{} getting template {}", auth.getPrincipal(), name);
        List<Pair> queryParams = new ArrayList<>();
        queryParams.add(new Pair("name", name));

        var call = apiClient.buildCall(
            "/human/template",
            "GET",
            queryParams,
            new ArrayList<>(),
            new HashMap<>(),
            new HashMap<>(),
            Map.of(),
            new String[] {"api_key"},
            null);
        var response = apiClient.execute(call, List.class);
        return response.getData();
    }
}
