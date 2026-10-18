package com.codeoj.codeojbackendquestionservice.submission;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class SubmissionConflictException extends RuntimeException {
    public SubmissionConflictException(String code) { super(code); }
}
