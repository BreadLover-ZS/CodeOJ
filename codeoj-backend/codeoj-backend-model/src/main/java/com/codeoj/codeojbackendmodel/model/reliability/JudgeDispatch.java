package com.codeoj.codeojbackendmodel.model.reliability;

import lombok.Data;

/** No code or private test cases in broker messages. */
@Data
public class JudgeDispatch {
    private String eventId;
    private Long submissionId;
    private Integer dispatchGeneration;
    private Integer payloadVersion;
    private String traceId;

    public boolean valid() {
        return eventId != null && eventId.matches("[0-9a-f-]{36}") && submissionId != null && submissionId > 0
                && dispatchGeneration != null && dispatchGeneration > 0 && Integer.valueOf(1).equals(payloadVersion)
                && traceId != null && traceId.matches("[A-Za-z0-9._:-]{1,64}");
    }
}
