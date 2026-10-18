-- All five keys share {contestId}: meta, stock, users, ledger, pending. No TTL or dynamic keys.
redis.replicate_commands()
local op = ARGV[1]
local function result(code, record) return cjson.encode({code=code, reservation=record}) end
local function nowMs()
  local t=redis.call('TIME'); return tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
end
local function intact()
  return redis.call('EXISTS',KEYS[1])==1 and redis.call('EXISTS',KEYS[2])==1
    and redis.call('HLEN',KEYS[4])==tonumber(redis.call('HGET',KEYS[1],'reservedCount'))
    and redis.call('HLEN',KEYS[3])==redis.call('HLEN',KEYS[4])
    and redis.call('ZCARD',KEYS[5])==tonumber(redis.call('HGET',KEYS[1],'pendingCount'))
end
if op=='init' then
  -- version, epoch, original Redis capacity, openUs, closeUs, DB revision, allowCreate
  if redis.call('EXISTS',KEYS[1])==1 then
    if not intact() then return result('LEDGER_INCOMPLETE') end
    for i,field in ipairs({'version','epoch','capacity','openUs','closeUs'}) do
      if redis.call('HGET',KEYS[1],field)~=ARGV[i+1] then return result('INITIALIZATION_CONFLICT') end
    end
    return result('OK') -- Never reset stock on a publish/initialization retry.
  end
  if ARGV[8]~='1' then return result('LEDGER_MISSING') end
  for i=2,5 do if redis.call('EXISTS',KEYS[i])==1 then return result('LEDGER_INCOMPLETE') end end
  redis.call('HSET',KEYS[1],'version',ARGV[2],'epoch',ARGV[3],'capacity',ARGV[4],
    'openUs',ARGV[5],'closeUs',ARGV[6],'revision',ARGV[7],'open','0','reservedCount','0','pendingCount','0')
  redis.call('SET',KEYS[2],ARGV[4]); return result('OK')
end
if not intact() then return result('LEDGER_INCOMPLETE') end
if op=='gate' then
  -- revision, open, DB time, allowed skew, admin clear clock hold
  if tonumber(ARGV[2])<tonumber(redis.call('HGET',KEYS[1],'revision')) then return result('STALE_GATE_REVISION') end
  redis.call('HSET',KEYS[1],'revision',ARGV[2])
  if ARGV[3]=='1' then
    if math.abs(nowMs()-tonumber(ARGV[4]))>tonumber(ARGV[5]) then
      redis.call('HSET',KEYS[1],'open','0','problem','CLOCK_SKEW');return result('CLOCK_SKEW')
    end
    if ARGV[6]=='1' then redis.call('HDEL',KEYS[1],'problem') end
    if redis.call('HEXISTS',KEYS[1],'problem')==1 then return result('CLOCK_SKEW') end
  end
  redis.call('HSET',KEYS[1],'open',ARGV[3]);return result('OK')
elseif op=='reserve' then
  -- contest, version, epoch, user, proposed request, proposed event, key, hash, DB time, skew
  if redis.call('HGET',KEYS[1],'version')~=ARGV[3] or redis.call('HGET',KEYS[1],'epoch')~=ARGV[4] then return result('VERSION_CONFLICT') end
  local mapped=redis.call('HGET',KEYS[3],ARGV[5])
  if mapped then
    local raw=redis.call('HGET',KEYS[4],mapped);if not raw then return result('LEDGER_INCOMPLETE') end
    local r=cjson.decode(raw)
    if r.userId~=ARGV[5] or r.eventId~=mapped then return result('LEDGER_IDENTITY_INVALID') end
    if r.requestKey~=ARGV[8] or r.requestHash~=ARGV[9] then return result('IDEMPOTENCY_CONFLICT') end
    return result('OK',r)
  end
  if redis.call('HGET',KEYS[1],'open')~='1' then return result('REGISTRATION_PAUSED') end
  local stamp=redis.call('TIME');local micros=tonumber(stamp[1])*1000000+tonumber(stamp[2]);local now=math.floor(micros/1000)
  if math.abs(now-tonumber(ARGV[10]))>tonumber(ARGV[11]) then
    redis.call('HSET',KEYS[1],'open','0','problem','CLOCK_SKEW');return result('CLOCK_SKEW')
  end
  if micros<tonumber(redis.call('HGET',KEYS[1],'openUs')) or micros>=tonumber(redis.call('HGET',KEYS[1],'closeUs')) then return result('REGISTRATION_WINDOW_CLOSED') end
  if tonumber(redis.call('GET',KEYS[2]))<=0 then return result('CONTEST_FULL') end
  if redis.call('HEXISTS',KEYS[4],ARGV[7])==1 then return result('EVENT_ID_CONFLICT') end
  -- Epoch microseconds use a decimal string: cjson's numeric precision must not round away identity/time bits.
  local r={contestId=ARGV[2],publishedVersion=ARGV[3],epoch=ARGV[4],userId=ARGV[5],requestId=ARGV[6],eventId=ARGV[7],requestKey=ARGV[8],requestHash=ARGV[9],reservedAtMs=now,reservedAtMicros=string.format('%.0f',micros),state='PROCESSING'}
  redis.call('DECR',KEYS[2]);redis.call('HSET',KEYS[3],ARGV[5],ARGV[7]);redis.call('HSET',KEYS[4],ARGV[7],cjson.encode(r));redis.call('ZADD',KEYS[5],now,ARGV[7])
  redis.call('HINCRBY',KEYS[1],'reservedCount',1);redis.call('HINCRBY',KEYS[1],'pendingCount',1)
  return result('OK',r)
elseif op=='user' or op=='event' then
  local event=ARGV[2];if op=='user' then event=redis.call('HGET',KEYS[3],ARGV[2]) end
  if not event then return result('ABSENT') end
  local raw=redis.call('HGET',KEYS[4],event);if not raw then return result('LEDGER_INCOMPLETE') end
  local r=cjson.decode(raw)
  if r.eventId~=event or (op=='user' and r.userId~=ARGV[2]) then return result('LEDGER_IDENTITY_INVALID') end
  return result('OK',r)
elseif op=='finish' then
  -- event, request, user, version, epoch, CONFIRMED/RELEASED
  local raw=redis.call('HGET',KEYS[4],ARGV[2]);if not raw then return result('LEDGER_INCOMPLETE') end
  local r=cjson.decode(raw)
  if r.requestId~=ARGV[3] or r.userId~=ARGV[4] or r.publishedVersion~=ARGV[5] or r.epoch~=ARGV[6]
    or redis.call('HGET',KEYS[3],ARGV[4])~=ARGV[2] then return result('RESERVATION_IDENTITY_CONFLICT') end
  if r.state==ARGV[7] then return result('OK',r) end
  if r.state~='PROCESSING' or (ARGV[7]~='CONFIRMED' and ARGV[7]~='RELEASED') then return result('RESERVATION_TERMINAL_CONFLICT') end
  if ARGV[7]=='RELEASED' then redis.call('INCR',KEYS[2]) end
  r.state=ARGV[7];redis.call('HSET',KEYS[4],ARGV[2],cjson.encode(r))
  if redis.call('ZREM',KEYS[5],ARGV[2])==1 then redis.call('HINCRBY',KEYS[1],'pendingCount',-1) end
  return result('OK',r) -- Keep identity/tombstone; FAILED users cannot buy another slot by changing key.
elseif op=='due' then
  local events=redis.call('ZRANGEBYSCORE',KEYS[5],'-inf',nowMs(),'LIMIT',0,20);local records={}
  for _,event in ipairs(events) do
    local raw=redis.call('HGET',KEYS[4],event);if not raw then return result('LEDGER_INCOMPLETE') end
    table.insert(records,cjson.decode(raw));redis.call('ZADD',KEYS[5],nowMs()+5000,event)
  end
  return cjson.encode({code='OK',records=records})
elseif op=='stats' then
  return cjson.encode({code='OK',stock=tonumber(redis.call('GET',KEYS[2])),count=redis.call('HLEN',KEYS[4]),open=redis.call('HGET',KEYS[1],'open'),epoch=redis.call('HGET',KEYS[1],'epoch'),version=redis.call('HGET',KEYS[1],'version')})
end
return result('INVALID_OPERATION')
