package space.yong.orkes.services;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloService {
    @PostMapping("hello")
    public void hello() {}
}
