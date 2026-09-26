package octometer.kit.core.ingest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A test-only {@link IngestRequestProcessor.RequestView}, for a test of
 * {@link IngestRequestProcessorTest} (issue #69). A test builds one
 * instance with the fluent setters, then gives it to
 * {@link IngestRequestProcessor#beforeBody}.
 */
final class FakeRequestView implements IngestRequestProcessor.RequestView {

    private final Map<String, List<String>> headers = new LinkedHashMap<>();
    private Long declaredContentLengthBytes;
    private String remoteAddress = "192.0.2.1";
    private Supplier<String> userIdSupplier = () -> null;

    FakeRequestView() {
        headers.put("Content-Type", List.of("application/json"));
    }

    FakeRequestView contentType(String value) {
        headers.put("Content-Type", value == null ? List.of() : List.of(value));
        return this;
    }

    FakeRequestView header(String name, String... lines) {
        headers.put(name, List.of(lines));
        return this;
    }

    FakeRequestView declaredContentLengthBytes(Long value) {
        this.declaredContentLengthBytes = value;
        return this;
    }

    FakeRequestView remoteAddress(String value) {
        this.remoteAddress = value;
        return this;
    }

    FakeRequestView userId(String value) {
        this.userIdSupplier = () -> value;
        return this;
    }

    FakeRequestView userIdSupplier(Supplier<String> supplier) {
        this.userIdSupplier = supplier;
        return this;
    }

    @Override
    public List<String> headerLines(String headerName) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(headerName)) {
                return entry.getValue();
            }
        }
        return List.of();
    }

    @Override
    public Long declaredContentLengthBytes() {
        return declaredContentLengthBytes;
    }

    @Override
    public String remoteAddress() {
        return remoteAddress;
    }

    @Override
    public String resolveUserId() {
        return userIdSupplier.get();
    }
}
