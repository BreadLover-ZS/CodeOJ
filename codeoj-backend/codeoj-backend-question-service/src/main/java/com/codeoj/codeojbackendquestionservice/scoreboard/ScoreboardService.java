package com.codeoj.codeojbackendquestionservice.scoreboard;

import cn.hutool.json.JSONUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.*;

/** Cache only public page entries. DB authority selects every version and all freshness metadata. */
@Service
@ConditionalOnProperty(name="codeoj.scoreboard.enabled",havingValue="true")
public class ScoreboardService {
    private final ScoreboardTransactions transactions;
    @Autowired(required=false) private StringRedisTemplate redis;
    public ScoreboardService(ScoreboardTransactions transactions) { this.transactions=transactions; }
    private String key(Map<String,Object> view) {
        return "contest:scoreboard:{"+view.get("contestId")+"}:"+view.get("projectionVersion")+":"+view.get("revision")+":"+view.get("cacheEpoch")+":"+view.get("page")+":"+view.get("pageSize");
    }
    public Map<String,Object> page(long id,int page,int size,Long version,long actor) {
        Map<String,Object> metadata=transactions.snapshot(id,page,size,version,actor,false);
        if(redis!=null) {
            try {
                String cached=redis.opsForValue().get(key(metadata));
                if(cached!=null) { metadata.put("entries",JSONUtil.parseArray(cached));return metadata; }
            } catch(RuntimeException unavailable) { /* Cache availability never changes scoring authority. */ }
        }
        Map<String,Object> full=transactions.snapshot(id,page,size,version,actor,true);
        if(redis!=null) { try { redis.opsForValue().set(key(full),JSONUtil.toJsonStr(full.get("entries")),Duration.ofSeconds(15)); }catch(RuntimeException unavailable) { /* DB page remains valid. */ } }
        return full;
    }
}
