package com.octometer.smoke;

import octometer.kit.spring.IngestController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The CSRF exemption test of issue #69 (the maintainer's decision 3).
 * {@code @AutoConfigureMockMvc} applies the real
 * {@code SecurityConfiguration} filter chain of this app to {@link
 * #mockMvc}, with no {@code addFilters=false}. Neither test sends
 * {@code X-XSRF-TOKEN}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IngestControllerCsrfTest {

    private static final String VALID_BODY = "{\"sessionId\":\"0b0e4e0e-6a55-4c1e-9a53-0c1f6f7a2d11\",\"clicks\":[]}";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theIngestPathIsExemptFromCsrf() throws Exception {
        mockMvc.perform(post(IngestController.DEFAULT_INGEST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNoContent());
    }

    @Test
    void anotherPathIsNotExemptFromCsrf() throws Exception {
        mockMvc.perform(post("/api/smoke/other"))
                .andExpect(status().isForbidden());
    }
}
