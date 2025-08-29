package space.yong.orkes.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConfigService {
    @Value("${conductor.server.url}")
    private String conductorServerUrl;

    public record ConfigResponse(String conductorServerUrl) {}

    @GetMapping("config")
    public ConfigResponse config() {
        return new ConfigResponse(conductorServerUrl.replace("/api", ""));
    }
}
