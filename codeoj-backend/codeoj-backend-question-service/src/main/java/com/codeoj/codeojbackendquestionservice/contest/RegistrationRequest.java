package com.codeoj.codeojbackendquestionservice.contest;
import lombok.Data;
import java.time.*;
@Data
public class RegistrationRequest {
    private RegistrationReservation reservation;
    private String status,reclaimState,lastError;
    private long version;
    private int maxPublishAttempts;
    private LocalDateTime nextActionAt,deadline;
    public RegistrationEvent event() {
        RegistrationEvent e=new RegistrationEvent();e.setContestId(reservation.getContestId());e.setRequestId(reservation.getRequestId());
        e.setPublishedVersion(reservation.getPublishedVersion());e.setEpoch(reservation.getEpoch());e.setEventId(reservation.getEventId());e.setPayloadVersion(1);return e;
    }
}
