package com.codeoj.codeojbackendmodel.model.reliability;

import lombok.Data;

@Data
public class ManualRetryRequest {
    private long expectedVersion;
    private String reason;
    private int additionalAttempts;
    private int additionalDispatches;
    private int extendRecoverySeconds;
}
