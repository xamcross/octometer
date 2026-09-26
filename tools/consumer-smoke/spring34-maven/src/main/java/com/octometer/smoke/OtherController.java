package com.octometer.smoke;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A second POST route of this app, with no CSRF exemption (issue #69,
 * the maintainer's decision 3). {@code SecurityConfiguration} exempts
 * the ingest path of {@code IngestController} from CSRF; this route
 * proves the contrast: a POST here without {@code X-XSRF-TOKEN} gives
 * 403, so the exemption of the ingest path is a real exemption, and not
 * a security chain that lets every path through.
 */
@RestController
public class OtherController {

    @PostMapping("/api/smoke/other")
    public ResponseEntity<Void> other() {
        return ResponseEntity.ok().build();
    }
}
