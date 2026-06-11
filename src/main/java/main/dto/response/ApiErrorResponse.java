package main.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/*
    Uniform error envelope for every 4xx/5xx response.
    Avoids leaking stack traces or Spring's default whitelabel error page to clients.
 */
@Data
@Builder
public class ApiErrorResponse {
    private int status;
    private String error;
    private String message;
    private String path;
    private Instant timestamp;
    private List<String> details;
}