package com.codeoj.codeojbackendquestionservice.scoreboard;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScoreboardCacheTest {
    Map<String,Object> view(long revision,boolean rows) {
        Map<String,Object> m=new LinkedHashMap<>();m.put("contestId","1");m.put("projectionVersion",2L);m.put("revision",revision);m.put("cacheEpoch",0L);m.put("page",0);m.put("pageSize",20);m.put("pendingCount",3L);
        if(rows) { m.put("entries",Collections.singletonList(Collections.singletonMap("userId","9007199254741999"))); }return m;
    }
    @Test void cacheHitStillReadsFreshAuthorityAndOnlyCachesEntries() {
        ScoreboardTransactions t=mock(ScoreboardTransactions.class);StringRedisTemplate redis=mock(StringRedisTemplate.class);ValueOperations<String,String> values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);when(values.get(anyString())).thenReturn("[{\"userId\":\"9007199254741999\"}]");when(t.snapshot(1,0,20,null,10,false)).thenReturn(view(1,false));
        ScoreboardService service=new ScoreboardService(t);ReflectionTestUtils.setField(service,"redis",redis);Map<String,Object> result=service.page(1,0,20,null,10);
        assertEquals(3L,result.get("pendingCount"));assertTrue(result.get("entries").toString().contains("9007199254741999"));verify(t,never()).snapshot(1,0,20,null,10,true);verify(values).get("contest:scoreboard:{1}:2:1:0:0:20");
    }
    @Test void redisFailureDoesNotRollbackOrHideDatabasePage() {
        ScoreboardTransactions t=mock(ScoreboardTransactions.class);StringRedisTemplate redis=mock(StringRedisTemplate.class);when(redis.opsForValue()).thenThrow(new IllegalStateException("CACHE_UNAVAILABLE"));
        when(t.snapshot(1,0,20,null,10,false)).thenReturn(view(1,false));when(t.snapshot(1,0,20,null,10,true)).thenReturn(view(1,true));
        ScoreboardService service=new ScoreboardService(t);ReflectionTestUtils.setField(service,"redis",redis);assertEquals(view(1,true),service.page(1,0,20,null,10));
    }
    @Test void revisionChangedDuringMissPublishesOnlyUnderNewRevision() {
        ScoreboardTransactions t=mock(ScoreboardTransactions.class);StringRedisTemplate redis=mock(StringRedisTemplate.class);ValueOperations<String,String> values=mock(ValueOperations.class);when(redis.opsForValue()).thenReturn(values);
        when(t.snapshot(1,0,20,null,10,false)).thenReturn(view(1,false));when(t.snapshot(1,0,20,null,10,true)).thenReturn(view(2,true));
        ScoreboardService service=new ScoreboardService(t);ReflectionTestUtils.setField(service,"redis",redis);assertEquals(2L,service.page(1,0,20,null,10).get("revision"));
        verify(values).set(eq("contest:scoreboard:{1}:2:2:0:0:20"),anyString(),eq(Duration.ofSeconds(15)));
    }
}
