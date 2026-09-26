package com.octometer.smoke;

import octometer.kit.spring.IngestController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One real request to {@link IngestController} of this app (issue #69,
 * the maintainer's decision 3). This app has no Spring Security starter,
 * so this test needs no CSRF token; the CSRF exemption test runs in
 * `tools/consumer-smoke/spring34-maven` alone.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IngestControllerRequestTest {

    private static final String VALID_BODY = "{\"sessionId\":\"0b0e4e0e-6a55-4c1e-9a53-0c1f6f7a2d11\",\"clicks\":[]}";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aRequestToTheIngestPathGives204() throws Exception {
        mockMvc.perform(post(IngestController.DEFAULT_INGEST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNoContent());
    }
}
