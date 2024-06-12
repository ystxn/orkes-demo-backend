package space.yong.orkes.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.conductor.sdk.workflow.executor.WorkflowExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class OrkesService {
    private final WorkflowExecutor executor;
    private final ObjectMapper objectMapper;

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
}
