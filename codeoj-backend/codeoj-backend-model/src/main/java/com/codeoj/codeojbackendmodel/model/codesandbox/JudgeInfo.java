package com.codeoj.codeojbackendmodel.model.codesandbox;

import com.codeoj.codeojbackendmodel.model.enums.JudgeVerdict;
import lombok.Data;

/**
 * 判题信息
 */
@Data
public class JudgeInfo {

    /**
     * 程序执行信息
     */
    private String message;

    /**
     * Peak memory in KiB; null means unavailable, never zero by default.
     */
    private Long memory;

    /**
     * Maximum per-case wall time in milliseconds, including JVM startup.
     */
    private Long time;

    private JudgeVerdict verdict;

    /** Measurement method; NATIVE_RSS_SAMPLED is approximate and excludes descendants. */
    private String memoryMeasurement;
}
