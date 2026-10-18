package com.codeoj.codeojbackendmodel.model.reliability;

import com.codeoj.codeojbackendmodel.model.codesandbox.JudgeInfo;
import lombok.Data;

@Data
public class ExecutionCommand {
    private Long submissionId;
    private Integer attemptNo;
    private String executionToken;
    private JudgeInfo result;
    private String errorCode;

    public boolean valid() {
        return submissionId != null && submissionId > 0 && attemptNo != null && attemptNo > 0
                && executionToken != null && executionToken.matches("[0-9a-f-]{36}");
    }
}
