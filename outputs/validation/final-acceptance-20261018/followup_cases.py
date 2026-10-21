"""Additional isolated checks; no business-source modifications."""
from extended_cases import *
class Advanced(Checks):
 def save(self):(ROOT/'followup-results.json').write_text(json.dumps(self.rows,ensure_ascii=False,indent=2)+'\n')
 def jv04_precise(self):
  # The primary SLEEP(5) exceeded the real Feign 5000ms timeout. Start both
  # processes first, hold messages by binding, then inject only a 2s claim gate.
  self.start_judges();self.start_second_judge();self.unbind('judge');sid=self.practice();event=self.dispatch(sid)
  self.trigger('fa_claim_overlap','submission_execution','BEFORE UPDATE','IF NEW.submissionId='+str(int(sid))+" AND NEW.executionStatus='RUNNING' AND OLD.executionStatus='QUEUED' THEN DO SLEEP(2); END IF;")
  self.bind('judge');self.publish('judge',event);self.publish('judge',event)
  slept=self.wait(self.sleeping,10)
  waited=self.wait(lambda:int(self.s.sql('SELECT COUNT(*) FROM performance_schema.data_lock_waits',True))>0,3)
  self.completed(sid);attempts=self.count('SELECT COUNT(*) FROM judge_attempt WHERE submissionId='+str(int(sid)));assert attempts==1
  return {'actualInnoDbLockWaitObserved':bool(waited),'triggerWindowObserved':bool(slept),'judgeProcesses':2,'effectiveAttempts':attempts,'brokerDuplicateDeliveries':2,'claimGateSeconds':2,'actualFeignReadTimeoutMs':5000,'replacesMisconfiguredPrimaryFaultGate':True}
 def jv15(self):
  cid,draft=self.contest('public-snapshot',capacity=1,redis=False,close=8,start=11,end=50,source=self.qid);self.register(cid,'u0')
  frozen=self.detail(cid)['problems'][0];spec=self.s.sql('SELECT judgeSpecHash FROM contest_problem_snapshot WHERE id='+frozen['id'])
  self.s.api('/question/update','POST',{'id':self.qid,'title':'Acceptance edited source','content':'Edited after publication.','judgeCase':[{'input':'1 2','output':'99'}]})
  start=dt.datetime.fromisoformat(draft['startAt'].replace('Z','+00:00'));self.wait(lambda:dt.datetime.now(dt.timezone.utc)>=start,15)
  detail=self.s.api('/contests/'+cid,actor='u0');assert detail['problems'][0]['title']=='Acceptance original sum'
  sid=self.csubmit(cid,frozen['id'],'u0');self.wait(lambda:self.s.sql('SELECT executionStatus FROM submission_execution WHERE submissionId='+str(int(sid)))=='COMPLETED',30)
  result=self.s.api('/submissions/'+str(sid),actor='u0');assert result['judgeInfo']['verdict']=='AC'
  practice=self.practice();self.completed(practice,'WA');assert self.s.sql('SELECT judgeSpecHash FROM submission_execution WHERE submissionId='+str(int(sid)))==spec
  assert self.count('SELECT acceptedNum FROM question WHERE id='+str(int(self.qid)))==0
  return {'publishedSnapshotUnchangedAfterSourceEdit':True,'originalTitleStillVisible':True,'contestVerdict':'AC','newPracticeVerdict':'WA','practiceAcceptedNumNotIncrementedByContest':True}
 def jv11_redis(self):
  cid,_=self.contest('confirm-redis-outage');self.trigger('fa_confirm_redis','contest_registration_request','BEFORE UPDATE','IF NEW.contestId='+cid+" AND NEW.status='CONFIRMED' AND OLD.status='PROCESSING' THEN DO SLEEP(6); END IF;")
  with cf.ThreadPoolExecutor(1) as pool:
   f=pool.submit(self.safe_call,'/contests/'+cid+'/registrations','POST',None,'u0',self.key('confirm-outage'))
   self.wait(self.sleeping,12);self.s.stop('redis');self.wait(lambda:self.count("SELECT COUNT(*) FROM contest_registration_request WHERE contestId="+cid+" AND status='CONFIRMED'")==1,12);response=f.result(timeout=30)
  reclaim=self.s.sql('SELECT reclaimState FROM contest_registration_request WHERE contestId='+cid);assert reclaim!='COMPLETE'
  assert self.count('SELECT COUNT(*) FROM contest_registration WHERE contestId='+cid)==1
  self.drop_triggers();self.s.restart('redis');self.wait(lambda:self.s.sql('SELECT reclaimState FROM contest_registration_request WHERE contestId='+cid)=='COMPLETE',60)
  self.wait(lambda:self.safe_call('/contests/'+cid+'/registrations/me',actor='u0')[0]==200,60)
  assert int(self.detail(cid)['remainingCapacity'])==1
  self.admin(cid,'registration-admin/reconcile')
  # Repeat at the terminal-before-release boundary. The API response may be unknown
  # because session commit also needs Redis; durable DB state is checked independently.
  release,_=self.contest('release-redis-outage');self.unbind('registration');q=self.register(release,'u0');row=self.s.api('/contests/'+release+'/registration-admin')['requests'][0]
  self.trigger('fa_release_redis','contest_registration_request','BEFORE UPDATE','IF NEW.contestId='+release+" AND NEW.status='FAILED' AND OLD.status='PROCESSING' THEN DO SLEEP(6); END IF;")
  with cf.ThreadPoolExecutor(1) as pool:
   f=pool.submit(self.safe_call,'/contests/'+release+'/registration-admin/requests/'+q['requestId']+'/review','POST',{'expectedVersion':row['stateVersion'],'reason':'fixture release during redis outage','decision':'FAIL'})
   self.wait(self.sleeping,12);self.s.stop('redis');self.wait(lambda:self.s.sql('SELECT status FROM contest_registration_request WHERE id='+q['requestId'])=='FAILED',12);release_response=f.result(timeout=30)
  assert self.s.sql('SELECT reclaimState FROM contest_registration_request WHERE id='+q['requestId'])=='RELEASE_PENDING'
  self.drop_triggers();self.s.restart('redis');self.wait(lambda:self.s.sql('SELECT reclaimState FROM contest_registration_request WHERE id='+q['requestId'])=='RELEASED',60)
  self.wait(lambda:self.safe_call('/contests/'+release+'/registrations/me',actor='u0')[0]==200,60)
  event=json.loads(self.s.sql("SELECT payload FROM reliable_outbox WHERE eventType='REGISTRATION_CONFIRM' AND aggregateId="+q['requestId']));self.bind('registration');self.publish('registration',event);time.sleep(1)
  assert self.redis('GET','contest:registration:{'+release+'}:stock')=='2'
  assert self.count('SELECT COUNT(*) FROM contest_registration WHERE contestId='+release)==0
  return {'dbConfirmationCommittedWhileRedisUnavailable':True,'confirmedMarkRecovered':True,'capacityDeductedOnce':True,'dbTerminalCommittedBeforeRedisRelease':True,'releasePendingDurableDuringOutage':True,'releasedExactlyOnceAfterRecovery':True,'unknownApiStatusesRecorded':[response[0],release_response[0]]}
 def jv23(self):
  cid,_=self.contest('cancel-redis-gate');self.unbind('registration');q=self.register(cid,'u0');c=self.detail(cid)
  self.trigger('fa_cancel_redis','contest','BEFORE UPDATE',"IF NEW.id="+cid+" AND NEW.status='CANCELLED' AND OLD.status='PUBLISHED' THEN DO SLEEP(6); END IF;")
  with cf.ThreadPoolExecutor(1) as pool:
   f=pool.submit(self.safe_call,'/contests/'+cid+'/cancel','POST',{'expectedVersion':c['version'],'reason':'fixture committed cancellation gate outage'})
   self.wait(self.sleeping,12);self.s.stop('redis');self.wait(lambda:self.s.sql('SELECT status FROM contest WHERE id='+cid)=='CANCELLED',12);response=f.result(timeout=30)
  event=json.loads(self.s.sql("SELECT payload FROM reliable_outbox WHERE eventType='REGISTRATION_CONFIRM' AND aggregateId="+q['requestId']));self.bind('registration');self.publish('registration',event)
  time.sleep(1);assert self.count('SELECT COUNT(*) FROM contest_registration WHERE contestId='+cid)==0
  self.drop_triggers();self.s.restart('redis');self.wait(lambda:self.safe_call('/contests/'+cid+'/registrations/me',actor='u0')[0]==200,60)
  self.wait(lambda:self.s.sql('SELECT reclaimState FROM contest_registration_request WHERE id='+q['requestId'])=='RELEASED',30)
  status,_=self.safe_call('/contests/'+cid+'/registrations','POST',None,'observer',self.key('cancelled-new'));assert status==409
  assert self.redis('HGET','contest:registration:{'+cid+'}:meta','open')=='0'
  return {'cancellationCommittedWhileRedisDown':True,'lateRealConsumerCannotConfirm':True,'newQualificationCount':0,'redisGateClosedAfterRecovery':True,'reclaimRecovered':True,'newRequestHttp':status,'cancelResponseMayBeUnknown':response[0]}
 def complete_loss(self):
  cid,_=self.contest('full-ledger-loss');self.register(cid,'u0');self.qualified(cid,'u0');prefix='contest:registration:{'+cid+'}:'
  self.redis('DEL',*[prefix+k for k in ['meta','stock','users','ledger','pending']]);c=self.detail(cid)
  status,_=self.safe_call('/contests/'+cid+'/registration-admin/reconcile','POST',{'expectedVersion':c['version'],'reason':'fixture total ledger loss'})
  assert status==503 and self.detail(cid)['registrationReady'] is False
  assert self.redis('EXISTS',prefix+'stock')=='0'
  assert self.count('SELECT COUNT(*) FROM contest_registration WHERE contestId='+cid)==1 and int(self.detail(cid)['remainingCapacity'])==1
  return {'completeLedgerLossDetected':True,'unknownNeverBecomesEmptyStock':True,'stockNotReinitialized':True,'authorityQualificationRetained':True,'reconcileHttp':status}
 def clock_skew(self):
  # MySQL per-connection timestamp control only; host/Redis clocks are never changed.
  probe=float(self.s.sql('SET timestamp=UNIX_TIMESTAMP()+10; SELECT UNIX_TIMESTAMP(UTC_TIMESTAMP(6));'))-time.time()
  assert probe>7,{'fixtureSessionTimestampSupported':False,'offset':round(probe,2)}
  cid,_=self.contest('clock-skew');original=self.s.launches['question'];observed=[]
  try:
   for sign in [10,-10]:
    self.s.stop('question');args=list(original[0])+['--spring.datasource.hikari.connection-init-sql=SET timestamp=UNIX_TIMESTAMP()'+('+' if sign>0 else '')+str(sign)]
    self.s.spawn('question',args,original[1],original[2]);self.s.wait('question',lambda:self.s.socket_ready('question'),90)
    self.wait(lambda:self.s.sql('SELECT registrationIssue FROM contest WHERE id='+cid)=='CLOCK_SKEW',15)
    assert self.s.sql('SELECT registrationReady FROM contest WHERE id='+cid)=='0'
    assert self.redis('GET','contest:registration:{'+cid+'}:stock')=='2';observed.append(sign)
    self.s.stop('question');self.s.spawn('question',*original);self.s.wait('question',lambda:self.s.socket_ready('question'),90);self.wait(lambda:self.safe_call('/contests?admin=true')[0]==200,30)
    self.admin(cid,'registration-admin/reconcile')
  finally:
   self.s.stop('question');self.s.spawn('question',*original);self.s.wait('question',lambda:self.s.socket_ready('question'),90);self.wait(lambda:self.safe_call('/contests?admin=true')[0]==200,30)
  return {'mysqlSessionOnlyClockOffsetsSeconds':observed,'positiveAndNegativeSkewPause':True,'reservationsCreated':0,'stockNotReset':True,'hostClockUnchanged':True}

def run(s):
 c=Advanced(s)
 for name,jv,fn in [('fixtures',[],c.fixtures),('two-consumer-overlap-precise-observer',['JV04'],c.jv04_precise),('mysql-session-clock-skew-both-directions',['JV24'],c.clock_skew),('published-public-snapshot-edit',['JV15'],c.jv15),('redis-failure-after-confirm-and-before-release',['JV11','JV22'],c.jv11_redis),('cancel-committed-redis-gate-unavailable',['JV23'],c.jv23),('complete-redis-ledger-loss',['JV24'],c.complete_loss)]:c.run_case(name,jv,fn)
 s.ok=all(r['status']=='PASS' for r in c.rows)
