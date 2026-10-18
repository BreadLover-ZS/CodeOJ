package com.codeoj.codeojbackendquestionservice.contest;
import lombok.Data;
@Data
public class ContestSubmission {
    private Long problemSnapshotId;
    private Long publishedVersion;
    private String language;
    private String code;
}
