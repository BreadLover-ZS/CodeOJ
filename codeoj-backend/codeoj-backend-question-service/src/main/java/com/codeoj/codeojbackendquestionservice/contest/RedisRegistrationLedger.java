package com.codeoj.codeojbackendquestionservice.contest;

import cn.hutool.json.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public class RedisRegistrationLedger {
    public static final int MAX_LEDGER_ENTRIES=10000;
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<String> script=new DefaultRedisScript<>();
    @Value("${codeoj.registration.max-clock-skew-ms:2000}") private long skew=2000;
    public RedisRegistrationLedger(StringRedisTemplate redis) {
        this.redis=redis;script.setLocation(new ClassPathResource("lua/contest-registration.lua"));script.setResultType(String.class);
    }
    static List<String> keys(long contest) {
        String p="contest:registration:{"+contest+"}:";return Arrays.asList(p+"meta",p+"stock",p+"users",p+"ledger",p+"pending");
    }
    public static long epoch(LocalDateTime time) { return time.toInstant(ZoneOffset.UTC).toEpochMilli(); }
    public static long micros(LocalDateTime time) { return Math.addExact(Math.multiplyExact(time.toEpochSecond(ZoneOffset.UTC),1000000),time.getNano()/1000); }
    JSONObject call(long contest,String... args) {
        String result=redis.execute(script,keys(contest),(Object[])args);
        if(result==null) { throw new IllegalStateException("REDIS_RESULT_UNKNOWN"); }
        JSONObject parsed=JSONUtil.parseObj(result);String code=parsed.getStr("code");
        if(!"OK".equals(code) && !"ABSENT".equals(code)) { throw new LedgerException(code); }return parsed;
    }
    public static class LedgerException extends RuntimeException {
        private final String code;public LedgerException(String code) { super(code);this.code=code; }public String getCode() { return code; }
    }
    public void repair(RegistrationSettings s) { call(s.getContestId(),"repair",Long.toString(s.getPublishedVersion()),Long.toString(s.getEpoch())); }
    public void check(RegistrationSettings s) { call(s.getContestId(),"check",Long.toString(s.getDurableSequence()),Long.toString(s.getPublishedVersion()),Long.toString(s.getEpoch())); }
    public void upgrade(RegistrationSettings s,List<RegistrationReservation> rows) {
        Map<String,String> assignments=new LinkedHashMap<>();for(RegistrationReservation r:rows) { assignments.put(r.getEventId(),Long.toString(r.getSequence())); }
        call(s.getContestId(),"upgrade",Long.toString(s.getRevision()),JSONUtil.toJsonStr(assignments));
    }
    public void initialize(RegistrationSettings s) {
        call(s.getContestId(),"init",Long.toString(s.getPublishedVersion()),Long.toString(s.getEpoch()),Integer.toString(s.getInitialCapacity()),
                Long.toString(micros(s.getOpen())),Long.toString(micros(s.getClose())),Long.toString(s.getRevision()),s.isInitialized()?"0":"1");
    }
    public void gate(RegistrationSettings s,boolean open,boolean clearClockHold) {
        call(s.getContestId(),"gate",Long.toString(s.getRevision()),open?"1":"0",Long.toString(epoch(s.getNow())),Long.toString(skew),clearClockHold?"1":"0",Long.toString(s.getDurableSequence()));
    }
    public RegistrationReservation reserve(RegistrationSettings s,long user,long request,String event,String key,String hash) {
        return call(s.getContestId(),"reserve",Long.toString(s.getContestId()),Long.toString(s.getPublishedVersion()),Long.toString(s.getEpoch()),
                Long.toString(user),Long.toString(request),event,key,hash,Long.toString(epoch(s.getNow())),Long.toString(skew),Long.toString(s.getDurableSequence())).getBean("reservation",RegistrationReservation.class);
    }
    public RegistrationReservation user(long contest,long user) { return call(contest,"user",Long.toString(user)).getBean("reservation",RegistrationReservation.class); }
    public RegistrationReservation event(long contest,String event) { return call(contest,"event",event).getBean("reservation",RegistrationReservation.class); }
    public void finish(RegistrationReservation r,String state) {
        call(r.getContestId(),"finish",r.getEventId(),Long.toString(r.getRequestId()),Long.toString(r.getUserId()),Long.toString(r.getPublishedVersion()),Long.toString(r.getEpoch()),state);
    }
    public List<RegistrationReservation> due(long contest) {
        JSONObject result=call(contest,"due");Object records=result.get("records");
        return records instanceof JSONArray?((JSONArray)records).toList(RegistrationReservation.class):Collections.emptyList();
    }
    public JSONObject stats(long contest) { return call(contest,"stats"); }
    /** Gate must already be closed. Bounded HSCAN; never overwrite stock with a DB counter. */
    public List<RegistrationReservation> snapshot(long contest) {
        JSONObject before=stats(contest);if(!"0".equals(before.getStr("open")) || before.getInt("count")>MAX_LEDGER_ENTRIES) { throw new LedgerException("RECONCILIATION_BOUND_EXCEEDED_OR_OPEN"); }
        Map<String,RegistrationReservation> rows=new LinkedHashMap<>();long deadline=System.nanoTime()+5000000000L;
        try(Cursor<Map.Entry<Object,Object>> cursor=redis.opsForHash().scan(keys(contest).get(3),ScanOptions.scanOptions().count(200).build())) {
            while(cursor.hasNext()) {
                if(System.nanoTime()>deadline || rows.size()>MAX_LEDGER_ENTRIES) { throw new LedgerException("RECONCILIATION_BOUND_EXCEEDED_OR_OPEN"); }
                Map.Entry<Object,Object> entry=cursor.next();RegistrationReservation r=JSONUtil.toBean(String.valueOf(entry.getValue()),RegistrationReservation.class);
                if(!r.valid() || !String.valueOf(entry.getKey()).equals(r.getEventId())) { throw new LedgerException("LEDGER_IDENTITY_INVALID"); }rows.put(r.getEventId(),r);
            }
        }
        JSONObject after=stats(contest);if(!"0".equals(after.getStr("open")) || rows.size()!=after.getInt("count")) { throw new LedgerException("LEDGER_CHANGED_DURING_RECONCILIATION"); }
        List<Object> users=new ArrayList<>();for(RegistrationReservation r:rows.values()) { users.add(Long.toString(r.getUserId())); }
        if(!users.isEmpty()) {
            List<Object> mapped=redis.opsForHash().multiGet(keys(contest).get(2),users);int index=0;
            for(RegistrationReservation r:rows.values()) { if(!r.getEventId().equals(mapped.get(index++))) { throw new LedgerException("LEDGER_IDENTITY_INVALID"); } }
        }
        return new ArrayList<>(rows.values());
    }
}
