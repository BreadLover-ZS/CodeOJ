package com.codeoj.codeojbackendquestionservice.contest;
import lombok.Data;
@Data
public class RegistrationReservation {
    private Long contestId,publishedVersion,epoch,userId,requestId,reservedAtMs,reservedAtMicros,sequence;
    private String eventId,requestKey,requestHash,state;
    public boolean valid() {
        return contestId!=null && contestId>0 && publishedVersion!=null && publishedVersion>0 && epoch!=null && epoch>0
                && userId!=null && userId>0 && requestId!=null && requestId>0 && reservedAtMs!=null && reservedAtMs>0 && reservedAtMs<=9007199254740L
                && reservedAtMicros!=null && reservedAtMicros<=9007199254740991L && reservedAtMicros>=reservedAtMs*1000 && reservedAtMicros-reservedAtMs*1000<1000
                && (sequence==null || (sequence>0 && sequence<=10000))
                && eventId!=null && eventId.matches("[a-f0-9-]{36}") && requestKey!=null && requestKey.matches("[A-Za-z0-9._:-]{8,128}")
                && requestHash!=null && requestHash.matches("[a-f0-9]{64}")
                && ("PROCESSING".equals(state)||"CONFIRMED".equals(state)||"RELEASED".equals(state));
    }
}
