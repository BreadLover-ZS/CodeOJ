-- Five same-slot keys: meta, stock, users, ledger, pending. No TTL.
-- Script atomicity does not roll back writes on runtime errors. A durable intent
-- precedes compound mutations; repair writes their deterministic target values.
redis.replicate_commands()
local op=ARGV[1]
local LIMIT=10000
local function result(code,record) return cjson.encode({code=code,reservation=record}) end
local function nowMs() local t=redis.call('TIME');return tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000) end
local function integer(v) local n=tonumber(v);return n and n>=0 and n==math.floor(n) end
local function types()
  local expected={'hash','string','hash','hash','zset'}
  for i,t in ipairs(expected) do local actual=redis.call('TYPE',KEYS[i]).ok;if actual~='none' and actual~=t then return false end end
  return true
end
local function intact()
  return types() and redis.call('EXISTS',KEYS[1])==1 and redis.call('EXISTS',KEYS[2])==1
    and not redis.call('HGET',KEYS[1],'mutation')
    and integer(redis.call('GET',KEYS[2]))
    and redis.call('HLEN',KEYS[4])==tonumber(redis.call('HGET',KEYS[1],'reservedCount'))
    and redis.call('HLEN',KEYS[3])==redis.call('HLEN',KEYS[4])
    and redis.call('ZCARD',KEYS[5])==tonumber(redis.call('HGET',KEYS[1],'pendingCount'))
end
local function same(a,b)
  for _,f in ipairs({'contestId','publishedVersion','epoch','userId','requestId','eventId','requestKey','requestHash','reservedAtMicros','sequence'}) do
    if tostring(a[f])~=tostring(b[f]) then return false end
  end
  return true
end
local function apply(m)
  if m.op=='init' then
    redis.call('SET',KEYS[2],m.stock) -- init.write.stock
  elseif m.op=='reserve' then
    local r=m.record
    redis.call('SET',KEYS[2],m.stock-1) -- reserve.write.stock
    redis.call('HSET',KEYS[3],r.userId,r.eventId) -- reserve.write.user
    redis.call('HSET',KEYS[4],r.eventId,cjson.encode(r)) -- reserve.write.ledger
    redis.call('ZADD',KEYS[5],r.reservedAtMs,r.eventId) -- reserve.write.pending
    redis.call('HSET',KEYS[1],'reservedCount',m.count+1,'pendingCount',m.pending+1) -- reserve.write.counters
  elseif m.op=='finish' then
    local r=m.record
    redis.call('SET',KEYS[2],m.stock+(r.state=='RELEASED' and 1 or 0)) -- finish.write.stock
    redis.call('HSET',KEYS[4],r.eventId,cjson.encode(r)) -- finish.write.ledger
    redis.call('ZREM',KEYS[5],r.eventId) -- finish.write.pending
    redis.call('HSET',KEYS[1],'pendingCount',m.pending-(m.wasPending and 1 or 0)) -- finish.write.counters
  end
  redis.call('HDEL',KEYS[1],'mutation') -- mutation.clear
end
if op=='repair' then
  -- Expected publication/epoch. Never infer a refund from an exception.
  if not types() then return result('LEDGER_INCOMPLETE') end
  if redis.call('EXISTS',KEYS[1])==0 then return result('ABSENT') end
  local raw=redis.call('HGET',KEYS[1],'mutation');if not raw then return result('OK') end
  if redis.call('HGET',KEYS[1],'version')~=ARGV[2] or redis.call('HGET',KEYS[1],'epoch')~=ARGV[3] then return result('VERSION_CONFLICT') end
  local m=cjson.decode(raw)
  if not integer(m.stock) or not integer(m.count) or not integer(m.pending) then return result('MUTATION_EVIDENCE_INVALID') end
  if m.op=='init' then
    if m.count~=0 or m.pending~=0 or redis.call('HLEN',KEYS[3])~=0 or redis.call('HLEN',KEYS[4])~=0 or redis.call('ZCARD',KEYS[5])~=0 then return result('MUTATION_EVIDENCE_INVALID') end
    local stock=redis.call('GET',KEYS[2]);if stock and tonumber(stock)~=m.stock then return result('MUTATION_EVIDENCE_INVALID') end
  elseif m.op=='reserve' or m.op=='finish' then
    local r=m.record
    if not r or r.publishedVersion~=ARGV[2] or r.epoch~=ARGV[3] or not integer(r.sequence) or tonumber(r.sequence)<1 or tonumber(r.sequence)>LIMIT then return result('MUTATION_EVIDENCE_INVALID') end
    local stock=tonumber(redis.call('GET',KEYS[2]));local target=m.stock+(m.op=='reserve' and -1 or (r.state=='RELEASED' and 1 or 0))
    if stock~=m.stock and stock~=target then return result('MUTATION_EVIDENCE_INVALID') end
    local mapped=redis.call('HGET',KEYS[3],r.userId)
    if (mapped and mapped~=r.eventId) or (m.op=='finish' and not mapped) then return result('MUTATION_EVIDENCE_INVALID') end
    local existing=redis.call('HGET',KEYS[4],r.eventId)
    if existing then local old=cjson.decode(existing);if not same(old,r) or (old.state~='PROCESSING' and old.state~=r.state) then return result('MUTATION_EVIDENCE_INVALID') end
    elseif m.op=='finish' then return result('MUTATION_EVIDENCE_INVALID') end
    local afterCount=m.count+(m.op=='reserve' and 1 or 0)
    local afterPending=m.pending+(m.op=='reserve' and 1 or -(m.wasPending and 1 or 0))
    for _,i in ipairs({3,4}) do local n=redis.call('HLEN',KEYS[i]);if n~=m.count and n~=afterCount then return result('MUTATION_EVIDENCE_INVALID') end end
    local n=redis.call('ZCARD',KEYS[5]);if n~=m.pending and n~=afterPending then return result('MUTATION_EVIDENCE_INVALID') end
    local count=tonumber(redis.call('HGET',KEYS[1],'reservedCount'));local pending=tonumber(redis.call('HGET',KEYS[1],'pendingCount'))
    if (count~=m.count and count~=afterCount) or (pending~=m.pending and pending~=afterPending) then return result('MUTATION_EVIDENCE_INVALID') end
  else return result('MUTATION_EVIDENCE_INVALID') end
  apply(m);return result('OK',m.record)
end
if op=='init' then
  if not types() then return result('LEDGER_INCOMPLETE') end
  if redis.call('EXISTS',KEYS[1])==1 then
    if not intact() then return result('LEDGER_INCOMPLETE') end
    for i,field in ipairs({'version','epoch','capacity','openUs','closeUs'}) do if redis.call('HGET',KEYS[1],field)~=ARGV[i+1] then return result('INITIALIZATION_CONFLICT') end end
    return result('OK')
  end
  if ARGV[8]~='1' then return result('LEDGER_MISSING') end
  for i=2,5 do if redis.call('EXISTS',KEYS[i])==1 then return result('LEDGER_INCOMPLETE') end end
  local m={op='init',stock=tonumber(ARGV[4]),count=0,pending=0}
  local intent=cjson.encode(m)
  redis.call('HSET',KEYS[1],'version',ARGV[2],'epoch',ARGV[3],'capacity',ARGV[4],'openUs',ARGV[5],'closeUs',ARGV[6],
    'revision',ARGV[7],'format','2','open','0','reservedCount','0','pendingCount','0','mutation',intent) -- init.write.intent
  apply(m);return result('OK')
end
if not intact() then return result('LEDGER_INCOMPLETE') end
local function anchored(minimum)
  if redis.call('HGET',KEYS[1],'format')~='2' then redis.call('HSET',KEYS[1],'open','0');return 'LEDGER_FORMAT_UPGRADE_REQUIRED' end
  if tonumber(redis.call('HGET',KEYS[1],'reservedCount'))<tonumber(minimum) then
    redis.call('HSET',KEYS[1],'open','0');return 'LEDGER_ROLLBACK_DETECTED'
  end
  return nil
end
if op=='check' then
  if redis.call('HGET',KEYS[1],'version')~=ARGV[3] or redis.call('HGET',KEYS[1],'epoch')~=ARGV[4] then return result('VERSION_CONFLICT') end
  local error=anchored(ARGV[2]);return result(error or 'OK')
elseif op=='upgrade' then
  -- Closed-gate, DB-verified legacy identities -> stable DB-assigned ordinals.
  if redis.call('HGET',KEYS[1],'open')~='0' or redis.call('HGET',KEYS[1],'revision')~=ARGV[2] then return result('STALE_GATE_REVISION') end
  local assignments=cjson.decode(ARGV[3]);local prepared={};local used={};local n=0
  for event,sequence in pairs(assignments) do
    local seq=tonumber(sequence);if not integer(seq) or seq<1 or seq>LIMIT or used[seq] then return result('LEDGER_IDENTITY_INVALID') end
    used[seq]=true;n=n+1
    local raw=redis.call('HGET',KEYS[4],event);if not raw then return result('LEDGER_INCOMPLETE') end
    local r=cjson.decode(raw);if r.eventId~=event or (r.sequence and tostring(r.sequence)~=tostring(sequence)) then return result('LEDGER_IDENTITY_INVALID') end
    r.sequence=tostring(sequence);prepared[event]=cjson.encode(r)
  end
  if n~=redis.call('HLEN',KEYS[4]) or n>LIMIT then return result('LEDGER_IDENTITY_INVALID') end
  for i=1,n do if not used[i] then return result('LEDGER_IDENTITY_INVALID') end end
  -- Legacy format remains closed/non-admitting until every record is upgraded.
  for event,raw in pairs(prepared) do
    redis.call('HSET',KEYS[4],event,raw) -- upgrade.write.record
  end
  redis.call('HSET',KEYS[1],'format','2');return result('OK')
elseif op=='gate' then
  -- revision, open, DB time, skew, admin clear clock hold, durable high-water
  if tonumber(ARGV[2])<tonumber(redis.call('HGET',KEYS[1],'revision')) then return result('STALE_GATE_REVISION') end
  redis.call('HSET',KEYS[1],'revision',ARGV[2])
  if ARGV[3]=='1' then
    local error=anchored(ARGV[7]);if error then return result(error) end
    if math.abs(nowMs()-tonumber(ARGV[4]))>tonumber(ARGV[5]) then redis.call('HSET',KEYS[1],'open','0','problem','CLOCK_SKEW');return result('CLOCK_SKEW') end
    if ARGV[6]=='1' then redis.call('HDEL',KEYS[1],'problem') end
    if redis.call('HEXISTS',KEYS[1],'problem')==1 then return result('CLOCK_SKEW') end
  end
  redis.call('HSET',KEYS[1],'open',ARGV[3]);return result('OK')
elseif op=='reserve' then
  if redis.call('HGET',KEYS[1],'version')~=ARGV[3] or redis.call('HGET',KEYS[1],'epoch')~=ARGV[4] then return result('VERSION_CONFLICT') end
  local error=anchored(ARGV[12]);if error then return result(error) end
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
  if math.abs(now-tonumber(ARGV[10]))>tonumber(ARGV[11]) then redis.call('HSET',KEYS[1],'open','0','problem','CLOCK_SKEW');return result('CLOCK_SKEW') end
  if micros<tonumber(redis.call('HGET',KEYS[1],'openUs')) or micros>=tonumber(redis.call('HGET',KEYS[1],'closeUs')) then return result('REGISTRATION_WINDOW_CLOSED') end
  if tonumber(redis.call('GET',KEYS[2]))<=0 then return result('CONTEST_FULL') end
  local count=tonumber(redis.call('HGET',KEYS[1],'reservedCount'))
  if count>=LIMIT then return result('REGISTRATION_LEDGER_LIMIT') end
  if redis.call('HEXISTS',KEYS[4],ARGV[7])==1 then return result('EVENT_ID_CONFLICT') end
  local r={contestId=ARGV[2],publishedVersion=ARGV[3],epoch=ARGV[4],userId=ARGV[5],requestId=ARGV[6],eventId=ARGV[7],requestKey=ARGV[8],requestHash=ARGV[9],reservedAtMs=now,reservedAtMicros=string.format('%.0f',micros),sequence=tostring(count+1),state='PROCESSING'}
  local m={op='reserve',record=r,stock=tonumber(redis.call('GET',KEYS[2])),count=count,pending=tonumber(redis.call('HGET',KEYS[1],'pendingCount'))}
  redis.call('HSET',KEYS[1],'mutation',cjson.encode(m)) -- reserve.write.intent
  apply(m);return result('OK',r)
elseif op=='user' or op=='event' then
  local event=ARGV[2];if op=='user' then event=redis.call('HGET',KEYS[3],ARGV[2]) end
  if not event then return result('ABSENT') end
  local raw=redis.call('HGET',KEYS[4],event);if not raw then return result('LEDGER_INCOMPLETE') end
  local r=cjson.decode(raw)
  if r.eventId~=event or (op=='user' and r.userId~=ARGV[2]) then return result('LEDGER_IDENTITY_INVALID') end
  return result('OK',r)
elseif op=='finish' then
  local raw=redis.call('HGET',KEYS[4],ARGV[2]);if not raw then return result('LEDGER_INCOMPLETE') end
  local r=cjson.decode(raw)
  if r.eventId~=ARGV[2] or r.requestId~=ARGV[3] or r.userId~=ARGV[4] or r.publishedVersion~=ARGV[5] or r.epoch~=ARGV[6]
    or redis.call('HGET',KEYS[3],ARGV[4])~=ARGV[2] then return result('RESERVATION_IDENTITY_CONFLICT') end
  if r.state==ARGV[7] then return result('OK',r) end
  if r.state~='PROCESSING' or (ARGV[7]~='CONFIRMED' and ARGV[7]~='RELEASED') then return result('RESERVATION_TERMINAL_CONFLICT') end
  -- Old ledgers are upgraded under reconciliation before further mutations.
  if not r.sequence then return result('LEDGER_FORMAT_UPGRADE_REQUIRED') end
  local m={op='finish',record=r,stock=tonumber(redis.call('GET',KEYS[2])),count=redis.call('HLEN',KEYS[4]),pending=redis.call('ZCARD',KEYS[5]),wasPending=redis.call('ZSCORE',KEYS[5],ARGV[2])~=false}
  r.state=ARGV[7]
  redis.call('HSET',KEYS[1],'mutation',cjson.encode(m)) -- finish.write.intent
  apply(m);return result('OK',r)
elseif op=='due' then
  local events=redis.call('ZRANGEBYSCORE',KEYS[5],'-inf',nowMs(),'LIMIT',0,20);local records={}
  for _,event in ipairs(events) do
    local raw=redis.call('HGET',KEYS[4],event);if not raw then return result('LEDGER_INCOMPLETE') end
    table.insert(records,cjson.decode(raw));redis.call('ZADD',KEYS[5],nowMs()+5000,event)
  end
  return cjson.encode({code='OK',records=records})
elseif op=='stats' then
  return cjson.encode({code='OK',stock=tonumber(redis.call('GET',KEYS[2])),count=redis.call('HLEN',KEYS[4]),open=redis.call('HGET',KEYS[1],'open'),epoch=redis.call('HGET',KEYS[1],'epoch'),version=redis.call('HGET',KEYS[1],'version'),format=redis.call('HGET',KEYS[1],'format')})
end
return result('INVALID_OPERATION')
