package com.codeoj.codeojbackendmodel.model.codesandbox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteCodeRequest {

    private List<String> inputList;

    private String code;

    private String language;

    /** Per-case wall time including JVM startup; server caps this value. */
    private Long runTimeLimitMs;

    private Long memoryLimitKiB;

    private String executionToken;

    private Long executionDeadlineEpochMs;
}
