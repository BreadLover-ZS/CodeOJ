package com.codeoj.codeojbackendquestionservice.contest;
import lombok.Data;
@Data
public class ContestCommand {
    private Long expectedVersion;
    private String reason;
    private Boolean registrationReady;
}
