package com.codeoj.codeojbackendmodel.model.enums;

/** Stable verdict codes, independent of execution state and display text. */
public enum JudgeVerdict {
    AC(JudgeInfoMessageEnum.ACCEPTED),
    WA(JudgeInfoMessageEnum.WRONG_ANSWER),
    CE(JudgeInfoMessageEnum.COMPILE_ERROR),
    RE(JudgeInfoMessageEnum.RUNTIME_ERROR),
    TLE(JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED),
    MLE(JudgeInfoMessageEnum.MEMORY_LIMIT_EXCEEDED),
    OUTPUT_LIMIT(JudgeInfoMessageEnum.OUTPUT_LIMIT_EXCEEDED);

    private final JudgeInfoMessageEnum legacyMessage;

    JudgeVerdict(JudgeInfoMessageEnum legacyMessage) {
        this.legacyMessage = legacyMessage;
    }

    public String getMessage() {
        return legacyMessage.getValue();
    }
}
