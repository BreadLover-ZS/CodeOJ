package com.codeoj.codeojbackendquestionservice.contest;
import lombok.Data;
@Data
public class RegistrationEvent {
    private Long contestId,requestId,publishedVersion,epoch;
    private String eventId;
    private Integer payloadVersion;
    public boolean valid() {
        return contestId!=null && contestId>0 && requestId!=null && requestId>0 && publishedVersion!=null && publishedVersion>0
                && epoch!=null && epoch>0 && Integer.valueOf(1).equals(payloadVersion) && eventId!=null && eventId.matches("[a-f0-9-]{36}");
    }
}
