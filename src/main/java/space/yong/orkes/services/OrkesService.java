package space.yong.orkes.services;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrkesService {
    @GetMapping("/")
    public String index() {
        return "Hello World!";
    }

    @GetMapping("/meh")
    public String meh() {
        return "Meh!";
    }
}
