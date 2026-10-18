package com.codeoj.codeojbackendquestionservice.contest;
import lombok.Data;
@Data
public class RegistrationReview {
    private Long expectedVersion;
    private String reason,decision;
    private int additionalPublishAttempts,extendRecoverySeconds;
}
