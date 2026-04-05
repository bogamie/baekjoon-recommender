package com.baekjoonrec.common;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VersionController {

    @GetMapping("/api/version")
    public Map<String, String> getVersion() {
        String version = getClass().getPackage().getImplementationVersion();
        return Map.of("version", version != null ? version : "dev");
    }
}
