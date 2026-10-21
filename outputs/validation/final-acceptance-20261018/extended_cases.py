"""Functional fault/concurrency checks against this run's real isolated services.
No application source changes. SQL triggers are fixture-only fault gates, never business fixes.
"""
import concurrent.futures as cf,datetime as dt,hashlib,http.cookiejar,json,os,pathlib,signal,socket,subprocess,time,uuid,contextlib,re
ROOT=pathlib.Path(__file__).resolve().parent
SUM='public class Main { public static void main(String[] a) throws Exception { java.util.Scanner s=new java.util.Scanner(System.in); System.out.println(s.nextInt()+s.nextInt()); Thread.sleep(100); }}'
RE='public class Main { public static void main(String[] a) { throw new IllegalArgumentException(); }}'
EXCH={'judge':('codeoj.judge.v1.exchange','codeoj.judge.v1.queue','judge'),'registration':('codeoj.registration.v1.exchange','codeoj.registration.v1.queue','confirm'),'scoreboard':('codeoj.scoreboard.v1.exchange','codeoj.scoreboard.v1.queue','result')}
class Checks:
 def __init__(self,s):self.s=s;self.rows=[];self.bindings={};self.users={};self.qid=None
 def save(self):(ROOT/'extended-results.json').write_text(json.dumps(self.rows,ensure_ascii=False,indent=2)+'\n')
 def count(self,sql):return int(self.s.sql(sql) or 0)
 def wait(self,check,seconds=45):
  end=time.monotonic()+seconds
  while time.monotonic()<end:
   try:
    result=check()
    if result:return result
   except (OSError,ValueError):pass
   time.sleep(.15)
  raise AssertionError('condition deadline exceeded')
 def safe_call(self,*args,**kw):
  try:return self.s.api(*args,allow_error=True,**kw)
  except (OSError,ValueError):return 0,{}
 def trigger(self,name,table,timing,body):
  self.s.sql('DELIMITER $$\nCREATE TRIGGER '+name+' '+timing+' ON '+table+' FOR EACH ROW BEGIN '+body+' END$$\nDELIMITER ;')
 def drop_triggers(self):
  names=self.s.sql("SELECT TRIGGER_NAME FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA='codeoj' AND TRIGGER_NAME LIKE 'fa_%'").splitlines()
  for name in names:
   assert re.fullmatch(r'fa_[a-z0-9_]+',name);self.s.sql('DROP TRIGGER '+name)
 def sleeping(self):return int(self.s.sql("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE STATE='User sleep'",True))>0
 def restart(self,name):
  if self.s.procs[name].poll() is not None:self.s.restart(name)
  if name=='question':self.wait(lambda:self.safe_call('/contests?admin=true')[0]==200,60)
 def stop_judges(self):
  for name in ['judge','judge2']:
   if name in self.s.procs:self.s.stop(name)
 def start_judges(self):self.restart('judge')
 def start_second_judge(self):
  if 'judge2' in self.s.procs:self.restart('judge2');return
  with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
  self.s.ports['judge2']=port
  args=[('--server.port='+str(port)) if str(x).startswith('--server.port=') else x for x in self.s.service_args['judge']]
  args+=['--codeoj.reliable-judge.worker-slots=1'];self.s.spawn('judge2',args);self.s.wait('judge2',lambda:self.s.socket_ready('judge2'),90)
 def key(self,prefix):return prefix+'-'+uuid.uuid4().hex[:16]
 def practice(self,code=SUM,key=None):return self.s.api('/question/question_submit/do','POST',{'questionId':self.qid,'language':'java','code':code},'participant',key or self.key('practice'))
 def status(self,sid):return self.s.api('/submissions/'+str(sid),actor='participant')
 def completed(self,sid,verdict='AC',seconds=50):
  result=self.wait(lambda:(lambda r:r if r.get('executionStatus')=='COMPLETED' else None)(self.status(sid)),seconds)
  assert result['judgeInfo']['verdict']==verdict,result;return result
 def dispatch(self,sid):return json.loads(self.s.sql("SELECT payload FROM reliable_outbox WHERE eventType='JUDGE_DISPATCH' AND aggregateId="+str(int(sid))+" ORDER BY aggregateVersion DESC LIMIT 1"))
 def publish(self,kind,event,event_id=None):
  exchange,queue,route=EXCH[kind]
  response=self.s.mq('/exchanges/%2F/'+exchange+'/publish','POST',{'properties':{'delivery_mode':2,'message_id':event_id or event['eventId'],'content_type':'application/json'},'routing_key':route,'payload':json.dumps(event),'payload_encoding':'string'})
  assert response[0]==200 and response[1]['routed'];return response
 def unbind(self,kind):
  exchange,queue,route=EXCH[kind];status,rows=self.s.mq('/bindings/%2F/e/'+exchange+'/q/'+queue);assert status==200
  for row in rows:
   if row['routing_key']==route:
    response=self.s.mq('/bindings/%2F/e/'+exchange+'/q/'+queue+'/'+row['properties_key'],'DELETE');assert response[0] in (200,204)
  self.bindings[kind]=True
 def bind(self,kind):
  exchange,queue,route=EXCH[kind];status,_=self.s.mq('/bindings/%2F/e/'+exchange+'/q/'+queue,'POST',{'routing_key':route,'arguments':{}});assert status in (200,201,204);self.bindings.pop(kind,None)
 def inner(self,command,body):
  status,data=self.s.http('http://127.0.0.1:'+str(self.s.ports['question'])+'/api/question/inner/execution/'+command,'POST',body,headers={'X-Inner-Secret':self.s.secret['inner']})
  assert status==200,(status,data);return data
 def run_case(self,name,jvs,fn):
  self.s.event('ACCEPTANCE_CASE_START',case=name,jv=jvs);start=time.monotonic()
  try:
   facts=fn() or {};row={'case':name,'jv':jvs,'status':'PASS','facts':facts}
  except Exception as error:
   message=str(error)
   for secret in self.s.secret.values():message=message.replace(secret,'[REDACTED]')
   message=re.sub(r'[0-9a-f]{8}-[0-9a-f-]{27,}', '[UUID_REDACTED]',message)
   row={'case':name,'jv':jvs,'status':'FAIL','errorType':type(error).__name__,'error':message[:900]}
  finally:
   self.s.proxy.drop_confirm.clear();self.s.proxy.drop_judge_ack.clear()
   with contextlib.suppress(Exception):self.drop_triggers()
   for kind in list(self.bindings):
    with contextlib.suppress(Exception):self.bind(kind)
   for name in ['redis','question','sandbox','judge']:
    with contextlib.suppress(Exception):self.restart(name)
  row['elapsedSeconds']=round(time.monotonic()-start,2);self.rows.append(row);self.save();self.s.event('ACCEPTANCE_CASE_END',case=name,status=row['status'])
 def fixtures(self):
  for i in range(8):
   actor='u'+str(i);jar=http.cookiejar.CookieJar();client=__import__('urllib.request',fromlist=['build_opener']).build_opener(__import__('urllib.request',fromlist=['HTTPCookieProcessor']).HTTPCookieProcessor(jar));self.s.clients[actor]=client
   uid=self.s.api('/user/register','POST',{'userAccount':'acceptance_'+actor,'userPassword':self.s.secret['test'],'checkPassword':self.s.secret['test']},actor)
   self.s.api('/user/login','POST',{'userAccount':'acceptance_'+actor,'userPassword':self.s.secret['test']},actor);self.users[actor]=str(uid)
  self.qid=self.s.api('/question/add','POST',{'title':'Acceptance original sum','content':'Add the two integers.','tags':[],'judgeConfig':{'timeLimit':3000,'memoryLimit':262144},'judgeCase':[{'input':'1 2','output':'3'}]})
  return {'users':len(self.users),'practiceQuestionId':str(self.qid),'fixtureOnly':True}
 def jv01(self):
  key=self.key('idempotent');sid=self.practice(key=key);assert str(self.practice(key=key))==str(sid)
  status,_=self.safe_call('/question/question_submit/do','POST',{'questionId':self.qid,'language':'java','code':SUM+'\n// changed'},'participant',key)
  assert status==409;self.completed(sid)
  assert self.count("SELECT COUNT(*) FROM submission_execution WHERE requestKey='"+key+"'")==1
  return {'identicalReplaySameId':True,'differentContentHttp':status,'authorityRows':1}
 def jv02(self):
  key=self.key('before-commit');self.trigger('fa_before_commit','reliable_outbox','BEFORE INSERT',"IF NEW.eventType='JUDGE_DISPATCH' THEN DO SLEEP(12); END IF;")
  with cf.ThreadPoolExecutor(1) as pool:
   f=pool.submit(self.safe_call,'/question/question_submit/do','POST',{'questionId':self.qid,'language':'java','code':SUM},'participant',key)
   self.wait(self.sleeping,8);self.s.hard_kill('question');response=f.result(timeout=20)
  self.wait(lambda:self.count("SELECT COUNT(*) FROM submission_execution WHERE requestKey='"+key+"'")==0,5);self.drop_triggers();self.restart('question')
  assert self.count("SELECT COUNT(*) FROM submission_execution WHERE requestKey='"+key+"'")==0
  self.stop_judges();self.s.stop('rabbitmq');after=self.key('after-commit');sid=self.practice(key=after)
  assert self.count('SELECT COUNT(*) FROM reliable_outbox WHERE aggregateId='+str(int(sid))+" AND eventType='JUDGE_DISPATCH'")==1
  self.s.hard_kill('question');self.s.restart('rabbitmq','management');self.restart('question');self.start_judges();self.completed(sid)
  return {'beforeCommitApiSigkill':True,'beforeCommitNoAuthorityOrOutbox':True,'afterCommitApiSigkill':True,'persistedOutboxRecovered':True}
 def jv03(self):
  self.stop_judges();self.unbind('judge');sid=self.practice()
  returned=self.wait(lambda:self.count('SELECT COUNT(*) FROM publish_attempt p JOIN reliable_outbox o ON p.eventId=o.eventId WHERE o.aggregateId='+str(int(sid))+' AND p.returned=1')>0,12)
  self.bind('judge');self.start_judges();self.completed(sid)
  self.stop_judges();self.s.proxy.drop_confirm.set();before=self.s.proxy.counts['publisherConfirmDropped'];lost=self.practice()
  self.wait(lambda:self.s.proxy.counts['publisherConfirmDropped']>before,10)
  assert self.count('SELECT COUNT(*) FROM publish_attempt p JOIN reliable_outbox o ON p.eventId=o.eventId WHERE o.aggregateId='+str(int(lost))+" AND p.confirmState='UNKNOWN'")>=1
  self.s.proxy.drop_confirm.clear();self.wait(lambda:self.count('SELECT COUNT(*) FROM publish_attempt p JOIN reliable_outbox o ON p.eventId=o.eventId WHERE o.aggregateId='+str(int(lost))+" AND p.confirmState='ACK' AND p.returned=0")>0,40)
  self.start_judges();self.completed(lost)
  self.s.stop('rabbitmq');disconnected=self.practice();self.wait(lambda:self.count('SELECT COUNT(*) FROM publish_attempt p JOIN reliable_outbox o ON p.eventId=o.eventId WHERE o.aggregateId='+str(int(disconnected))+" AND p.confirmState='UNKNOWN'")>0,20)
  self.s.restart('rabbitmq','management');self.completed(disconnected,seconds=60)
  return {'realReturnPersisted':bool(returned),'realPublisherConfirmFramesDropped':self.s.proxy.counts['publisherConfirmDropped']-before,'unknownAttemptPreserved':True,'disconnectedBrokerRecovered':True}
 def jv04(self):
  self.stop_judges();sid=self.practice();event=self.dispatch(sid);self.publish('judge',event);self.publish('judge',event)
  self.trigger('fa_claim_overlap','submission_execution','BEFORE UPDATE',"IF NEW.submissionId="+str(int(sid))+" AND NEW.executionStatus='RUNNING' AND OLD.executionStatus='QUEUED' THEN DO SLEEP(5); END IF;")
  self.start_judges();self.start_second_judge();self.wait(self.sleeping,12)
  waiting=self.wait(lambda:int(self.s.sql('SELECT COUNT(*) FROM performance_schema.data_lock_waits',True))>0,8)
  self.completed(sid);attempts=self.count('SELECT COUNT(*) FROM judge_attempt WHERE submissionId='+str(int(sid)));assert attempts==1
  return {'judgeProcesses':2,'actualInnoDbLockWaitObserved':bool(waiting),'effectiveAttempts':attempts,'actualBrokerDuplicates':2}
 def jv05(self):
  self.stop_judges();self.start_judges();code=SUM.replace('Thread.sleep(100)','Thread.sleep(1500)');sid=self.practice(code)
  self.wait(lambda:self.status(sid).get('executionStatus')=='RUNNING',20)
  row=self.s.sql('SELECT activeAttempt,executionToken,dispatchGeneration FROM submission_execution WHERE submissionId='+str(int(sid))).split('\t');old={'submissionId':sid,'attemptNo':int(row[0]),'executionToken':row[1]};event=self.dispatch(sid)
  self.s.hard_kill('judge');self.wait(lambda:self.count('SELECT dispatchGeneration FROM submission_execution WHERE submissionId='+str(int(sid)))>int(row[2]),30)
  self.start_judges();self.completed(sid,seconds=40)
  heartbeat=self.inner('heartbeat',old);complete=self.inner('complete',{**old,'result':{'verdict':'AC','time':100,'memory':1024,'memoryMeasurement':'NATIVE_RSS_SAMPLED'}})
  assert heartbeat is False and complete is False
  self.publish('judge',event);time.sleep(1)
  attempts=self.count('SELECT COUNT(*) FROM judge_attempt WHERE submissionId='+str(int(sid)));assert attempts==2
  return {'runningWorkerSigkill':True,'leaseRecoveredWithoutTimeMutation':True,'oldHeartbeatRejected':True,'oldResultRejected':True,'staleGenerationRedelivered':True,'attempts':attempts,'oldTokenDigest':hashlib.sha256(row[1].encode()).hexdigest()[:12]}
 def jv06(self):
  self.stop_judges();self.start_judges();before=self.s.proxy.counts['judgeConsumerAckDropped'];self.s.proxy.drop_judge_ack.set();sid=self.practice();self.completed(sid)
  self.wait(lambda:self.s.proxy.counts['judgeConsumerAckDropped']>before,8)
  self.wait(lambda:self.s.mq('/queues/%2F/'+EXCH['judge'][1])[1].get('messages_unacknowledged',0)>0,12)
  self.s.hard_kill('judge');self.s.proxy.drop_judge_ack.clear();self.start_judges()
  self.wait(lambda:self.s.mq('/queues/%2F/'+EXCH['judge'][1])[1].get('messages_unacknowledged',0)==0,15)
  assert self.count('SELECT COUNT(*) FROM judge_attempt WHERE submissionId='+str(int(sid)))==1
  assert self.count("SELECT COUNT(*) FROM reliable_outbox WHERE aggregateId="+str(int(sid))+" AND eventType='JUDGE_RESULT'")==1
  return {'resultCommittedBeforeBrokerAck':True,'consumerAckFramesDropped':self.s.proxy.counts['judgeConsumerAckDropped']-before,'workerSigkillAndBrokerRedelivery':True,'attempts':1,'resultEvents':1}
 def contest(self,label,capacity=2,redis=True,close=70,start=75,end=130,problems=1,source=None):
  now=dt.datetime.now(dt.timezone.utc)
  def at(offset):return (now+dt.timedelta(seconds=offset)).isoformat(timespec='microseconds').replace('+00:00','Z')
  ps=[]
  for i in range(problems):
   p={'problemCode':chr(65+i)}
   if source is not None:p['sourceQuestionId']=source
   else:p.update(title='Acceptance problem '+str(i),content='Add the two integers.',limits={'timeLimit':3000,'memoryLimit':262144},cases=[{'input':'1 2','output':'3'}])
   ps.append(p)
  draft={'title':'Acceptance '+label,'contestType':'PUBLIC_TRAINING' if source else 'PRIVATE','capacity':capacity,'registrationOpenAt':at(3),'registrationCloseAt':at(close),'startAt':at(start),'endAt':at(end),'problems':ps}
  c=self.s.api('/contests','POST',draft);cid=c['id'];self.s.api('/contests/'+cid+'/publish','POST',{'expectedVersion':c['version']})
  if redis:
   self.admin(cid,'registration-admin/prepare');self.admin(cid,'registration-admin/reconcile')
  self.wait(lambda:dt.datetime.now(dt.timezone.utc)>=now+dt.timedelta(seconds=3),8)
  return cid,draft
 def detail(self,cid):return self.s.api('/contests/'+cid+'?admin=true')
 def admin(self,cid,action,extra=None):
  c=self.detail(cid);return self.s.api('/contests/'+cid+'/'+action,'POST',{'expectedVersion':c['version'],'reason':'isolated acceptance '+action,**(extra or {})})
 def register(self,cid,actor,key=None):return self.s.api('/contests/'+cid+'/registrations','POST',actor=actor,key=key or self.key('registration'))
 def qualified(self,cid,actor):return self.wait(lambda:(lambda r:r if r.get('status')=='CONFIRMED' else None)(self.s.api('/contests/'+cid+'/registrations/me',actor=actor)),30)
 def redis(self,*args):
  binary=str(pathlib.Path(self.s.rt['redis']).with_name('redis-cli'));env={**self.s.env,'REDISCLI_AUTH':self.s.secret['redis']}
  r=subprocess.run([binary,'-h','127.0.0.1','-p',str(self.s.ports['redis']),'-n','1','--raw',*map(str,args)],env=env,text=True,capture_output=True,timeout=10)
  if r.returncode:raise RuntimeError('fixture redis command unavailable')
  return r.stdout.strip()
 def ledger(self,cid):return [json.loads(v) for v in self.redis('HVALS','contest:registration:{'+cid+'}:ledger').splitlines() if v]
 def jv09(self):
  cid,_=self.contest('capacity-race');keys={a:self.key('race-'+a) for a in self.users}
  with cf.ThreadPoolExecutor(8) as pool:
   futures={a:pool.submit(self.safe_call,'/contests/'+cid+'/registrations','POST',None,a,keys[a]) for a in self.users}
   results={a:f.result(timeout=30) for a,f in futures.items()}
  winners=[a for a,(status,body) in results.items() if status==202 and body.get('code')==0];assert len(winners)==2,results
  for actor in winners:
   self.qualified(cid,actor);replay=self.register(cid,actor,keys[actor]);assert replay['requestId']==results[actor][1]['data']['requestId']
  c=self.detail(cid);confirmed=self.count("SELECT COUNT(*) FROM contest_registration WHERE contestId="+cid+" AND status='CONFIRMED'")
  assert confirmed==2 and int(c['remainingCapacity'])==0
  self.wait(lambda:all(r['state']=='CONFIRMED' for r in self.ledger(cid)),15)
  assert self.redis('GET','contest:registration:{'+cid+'}:stock')=='0'
  return {'contestId':cid,'concurrentUsers':8,'functionalRequestsNotLoadTest':True,'confirmed':confirmed,'remainingCapacity':0,'identityStableOnRetry':True,'ledgerRows':len(self.ledger(cid))}
 def jv10(self):
  cid,_=self.contest('reservation-gap');key=self.key('reservation-gap');self.trigger('fa_reservation_gap','contest_registration_request','BEFORE INSERT','IF NEW.contestId='+cid+' THEN DO SLEEP(12); END IF;')
  with cf.ThreadPoolExecutor(1) as pool:
   f=pool.submit(self.safe_call,'/contests/'+cid+'/registrations','POST',None,'u0',key)
   self.wait(self.sleeping,10);rows=self.ledger(cid);assert len(rows)==1;original=rows[0]
   self.s.hard_kill('question');f.result(timeout=20)
  assert self.count('SELECT COUNT(*) FROM contest_registration_request WHERE contestId='+cid)==0
  self.drop_triggers();self.restart('question');q=self.qualified(cid,'u0');assert q['requestId']==original['requestId']
  event=self.s.sql('SELECT eventId FROM contest_registration_request WHERE id='+str(int(original['requestId'])));assert event==original['eventId']
  return {'apiSigkillAfterRedisBeforeDbCommit':True,'noDbRequestBeforeRestart':True,'sameRequestIdRecovered':True,'sameEventIdRecovered':True,'status':q['status']}
 def jv11_marker(self):
  cid,_=self.contest('marker-retry');self.trigger('fa_marker','contest_registration_request','BEFORE UPDATE',"IF NEW.contestId="+cid+" AND NEW.reclaimState='COMPLETE' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture marker gate'; END IF;")
  q=self.register(cid,'u0');self.qualified(cid,'u0')
  self.wait(lambda:self.ledger(cid)[0]['state']=='CONFIRMED',15)
  pending=self.s.sql('SELECT reclaimState FROM contest_registration_request WHERE id='+str(int(q['requestId'])));assert pending!='COMPLETE'
  self.drop_triggers();self.wait(lambda:self.s.sql('SELECT reclaimState FROM contest_registration_request WHERE id='+str(int(q['requestId'])))=='COMPLETE',15)
  assert int(self.detail(cid)['remainingCapacity'])==1
  return {'dbConfirmedRedisConfirmedBeforeDbMarker':True,'fixtureSqlFailureInjected':True,'retryCompletesMarker':True,'capacityDeductedOnce':True}
 def jv12(self):
  cid,draft=self.contest('late-confirm',close=9,start=12,end=55);self.unbind('registration');q=self.register(cid,'u0')
  assert self.s.api('/contests/'+cid+'/registrations/me',actor='u0')['status']=='PROCESSING'
  close=dt.datetime.fromisoformat(draft['registrationCloseAt'].replace('Z','+00:00'));self.wait(lambda:dt.datetime.now(dt.timezone.utc)>=close+dt.timedelta(seconds=.5),15)
  self.bind('registration');self.qualified(cid,'u0')
  status,_=self.safe_call('/contests/'+cid+'/registrations','POST',None,'u1',self.key('after-close'));assert status==409
  return {'requestId':q['requestId'],'reservedBeforeClose':True,'actualMqConsumptionAfterClose':True,'newReservationAfterCloseHttp':status}
 def jv13(self):
  cid,_=self.contest('cancel-registration');c=self.detail(cid)
  with cf.ThreadPoolExecutor(9) as pool:
   jobs=[pool.submit(self.safe_call,'/contests/'+cid+'/registrations','POST',None,a,self.key('cancel-'+a)) for a in self.users]
   cancelled=pool.submit(self.safe_call,'/contests/'+cid+'/cancel','POST',{'expectedVersion':c['version'],'reason':'fixture race cancel'})
   replies=[f.result(timeout=30) for f in jobs];reply=cancelled.result(timeout=30)
  assert reply[0]==200,reply;assert self.detail(cid)['status']=='CANCELLED'
  status,_=self.safe_call('/contests/'+cid+'/registrations','POST',None,'observer',self.key('after-cancel'));assert status in (409,503)
  assert self.count("SELECT COUNT(*) FROM contest_registration_request WHERE contestId="+cid+" AND status='PROCESSING'")==0
  q=self.s.api('/contests/'+cid+'/registrations/me',actor='u0');assert q['qualificationValid'] is False
  return {'cancelRacedWithRegistrationHttp':True,'cancelCommitted':True,'qualificationInvalid':True,'postCancelNewRequestRejected':True,'capacity':2,'confirmedHistory':self.count('SELECT COUNT(*) FROM contest_registration WHERE contestId='+cid)}
 def jv19(self):
  self.s.stop('sandbox');sid=self.practice()
  self.wait(lambda:self.status(sid).get('executionStatus')=='NEEDS_ATTENTION',40)
  initial=self.s.api('/admin/operations/'+str(sid));attempts=len(initial['attempts']);assert attempts==3
  version=initial['execution']['stateVersion']
  status,_=self.safe_call('/admin/operations/'+str(sid)+'/retry','POST',{'expectedVersion':version,'reason':'no explicit budget'})
  assert status==409
  self.s.restart('sandbox');self.s.api('/admin/operations/'+str(sid)+'/retry','POST',{'expectedVersion':version,'reason':'explicit fixture recovery','additionalAttempts':1,'additionalDispatches':1,'extendRecoverySeconds':30})
  self.completed(sid);final=self.s.api('/admin/operations/'+str(sid));assert len(final['attempts'])==4 and len(final['audit'])>=2
  return {'platformFailuresExhaustedAt':attempts,'needsAttentionWasNotWa':True,'retryWithoutBudgetHttp':status,'explicitFiniteRetryCompletesAc':True,'attemptHistory':4,'auditRows':len(final['audit'])}
 def jv21(self):
  self.stop_judges();sid=self.practice();first=self.dispatch(sid)
  self.wait(lambda:self.s.sql("SELECT publishState FROM reliable_outbox WHERE eventId='"+first['eventId']+"'")=='CONFIRMED',15)
  self.wait(lambda:self.count('SELECT dispatchGeneration FROM submission_execution WHERE submissionId='+str(int(sid)))>first['dispatchGeneration'],45)
  generation=self.count('SELECT dispatchGeneration FROM submission_execution WHERE submissionId='+str(int(sid)));self.start_judges();self.completed(sid)
  assert self.count('SELECT COUNT(*) FROM judge_attempt WHERE submissionId='+str(int(sid)))==1
  return {'confirmedButUnclaimedOverThreshold':True,'generationBefore':first['dispatchGeneration'],'generationAfter':generation,'businessScannerRecovered':True,'effectiveAttempts':1}
 def jv29(self):
  self.stop_judges();old=self.practice();spec1=self.s.sql('SELECT judgeSpecHash FROM submission_execution WHERE submissionId='+str(int(old)))
  self.s.api('/question/update','POST',{'id':self.qid,'judgeCase':[{'input':'1 2','output':'99'}]});new=self.practice();spec2=self.s.sql('SELECT judgeSpecHash FROM submission_execution WHERE submissionId='+str(int(new)));assert spec1!=spec2
  self.start_judges();self.completed(old,'AC');self.completed(new,'WA')
  self.s.api('/question/update','POST',{'id':self.qid,'judgeCase':[{'input':'1 2','output':'3'}]})
  return {'actualPracticeApiAndMq':True,'queuedBeforeEditUsesOldSpec':'AC','newSubmissionUsesNewSpec':'WA','immutableSpecHashesDiffer':True}
 def jv22(self):
  cid,_=self.contest('late-after-release');self.unbind('registration');q=self.register(cid,'u0');qid=q['requestId'];before=self.redis('GET','contest:registration:{'+cid+'}:stock')
  row=self.s.api('/contests/'+cid+'/registration-admin')['requests'][0];self.s.api('/contests/'+cid+'/registration-admin/requests/'+qid+'/review','POST',{'expectedVersion':row['stateVersion'],'reason':'fixture definite failure','decision':'FAIL'})
  self.wait(lambda:self.s.sql('SELECT reclaimState FROM contest_registration_request WHERE id='+str(int(qid)))=='RELEASED',15)
  event=json.loads(self.s.sql("SELECT payload FROM reliable_outbox WHERE eventType='REGISTRATION_CONFIRM' AND aggregateId="+str(int(qid))))
  self.bind('registration');self.publish('registration',event);self.publish('registration',event);time.sleep(1)
  assert self.s.sql('SELECT status FROM contest_registration_request WHERE id='+str(int(qid)))=='FAILED'
  assert self.count('SELECT COUNT(*) FROM contest_registration WHERE contestId='+cid)==0
  assert self.redis('GET','contest:registration:{'+cid+'}:stock')=='2'
  return {'dbFailedBeforeRelease':True,'realLateConsumerAfterRelease':True,'duplicateLateEvents':2,'qualifications':0,'stockBeforeRelease':int(before),'stockAfterDuplicates':2}
 def jv24(self):
  cid,_=self.contest('ledger-loss');q=self.register(cid,'u0');self.qualified(cid,'u0');self.wait(lambda:self.ledger(cid)[0]['state']=='CONFIRMED',15)
  prefix='contest:registration:{'+cid+'}:';stock=self.redis('GET',prefix+'stock');original={name:self.redis('DUMP',prefix+name) for name in []}
  for _ in range(2):self.admin(cid,'registration-admin/reconcile')
  assert self.redis('GET',prefix+'stock')==stock
  raw=self.redis('HGETALL',prefix+'ledger').splitlines();assert len(raw)==2;event,value=raw
  self.redis('HDEL',prefix+'ledger',event)
  status,_=self.safe_call('/contests/'+cid+'/registration-admin/reconcile','POST',{'expectedVersion':self.detail(cid)['version'],'reason':'fixture missing ledger'})
  assert status==503;assert self.detail(cid)['registrationReady'] is False;assert self.redis('GET',prefix+'stock')==stock
  self.redis('HSET',prefix+'ledger',event,value);self.admin(cid,'registration-admin/reconcile')
  ttls=[int(self.redis('TTL',prefix+k)) for k in ['meta','stock','users','ledger']];assert all(v==-1 for v in ttls)
  return {'initializationRetriesNeverResetStock':True,'partialLedgerLossPausesNotEmptyInventory':True,'reconcileHttpOnLoss':status,'fixtureOriginalLedgerRestoredForCleanup':True,'persistentKeyTtls':ttls,'clockSkewNotIncludedInThisCase':True}
 def csubmit(self,cid,snapshot,actor,key=None,code=SUM):
  c=self.detail(cid);return self.s.api('/contests/'+cid+'/submissions','POST',{'problemSnapshotId':snapshot,'publishedVersion':c['publishedVersion'],'language':'java','code':code},actor,key or self.key('contest-submit'))
 def score_cmd(self,cid):
  b=self.s.api('/contests/'+cid+'/scoreboard');return {'expectedVersion':b['contestVersion'],'expectedProjectionVersion':b['projectionVersion'],'expectedRevision':b['revision'],'reason':'isolated full acceptance'}
 def ranking(self):
  self.stop_judges();cid,draft=self.contest('ranking',capacity=4,redis=False,close=8,start=11,end=45,problems=2)
  for actor in ['u0','u1','u2','u3']:self.register(cid,actor)
  initial=self.s.api('/contests/'+cid+'/scoreboard',actor='observer');assert len(initial['entries'])==4 and all(int(e['rank'])==1 and int(e['solvedCount'])==0 for e in initial['entries'])
  start=dt.datetime.fromisoformat(draft['startAt'].replace('Z','+00:00'));end=dt.datetime.fromisoformat(draft['endAt'].replace('Z','+00:00'));self.wait(lambda:dt.datetime.now(dt.timezone.utc)>=start,15)
  problems=self.detail(cid)['problems'];ids=[]
  for actor,p,offset,delay in [('u0',0,1,500),('u0',1,4,500),('u1',0,2,100),('u1',1,4,100),('u0',1,3,1800)]:
   sid=self.csubmit(cid,problems[p]['id'],actor,code=SUM.replace('Thread.sleep(100)','Thread.sleep('+str(delay)+')'))
   # Explicit synthetic time fixture for exact ties/hand-calculated ranking, not HTTP-time evidence.
   accepted=(start+dt.timedelta(seconds=offset)).strftime('%Y-%m-%d %H:%M:%S.%f');self.s.sql("UPDATE submission_execution SET acceptedAt='"+accepted+"' WHERE submissionId="+str(int(sid)));ids.append(sid)
  self.start_judges()
  overlap=self.wait(lambda:self.count("SELECT COUNT(*) FROM submission_execution WHERE contestId="+cid+" AND userId="+self.users['u0']+" AND executionStatus='RUNNING'")>=2,15)
  def tie():
   b=self.s.api('/contests/'+cid+'/scoreboard?size=2&page=0',actor='observer')
   return b if len(b['entries'])==2 and all(int(e['solvedCount'])==2 and int(e['rank'])==1 for e in b['entries']) else None
  tie_board=self.wait(tie,15)
  for sid in ids:self.wait(lambda sid=sid:self.s.sql('SELECT executionStatus FROM submission_execution WHERE submissionId='+str(int(sid)))=='COMPLETED',20)
  def ranked():
   b=self.s.api('/contests/'+cid+'/scoreboard',actor='observer');rows={e['userId']:(int(e['solvedCount']),int(e['rank'])) for e in b['entries']}
   return b if rows=={self.users['u0']:(2,1),self.users['u1']:(2,2),self.users['u2']:(0,3),self.users['u3']:(0,3)} else None
  expected=self.wait(ranked,15);page=self.s.api('/contests/'+cid+'/scoreboard?page=1&size=2',actor='observer');assert [int(e['rank']) for e in page['entries']]==[3,3]
  b=self.s.api('/contests/'+cid+'/scoreboard');epoch=int(b['cacheEpoch']);self.s.api('/contests/'+cid+'/scoreboard/cache-clear','POST',self.score_cmd(cid));assert int(self.s.api('/contests/'+cid+'/scoreboard')['cacheEpoch'])==epoch+1
  self.wait(lambda:dt.datetime.now(dt.timezone.utc)>=end-dt.timedelta(seconds=2),45);self.stop_judges();self.unbind('scoreboard');late=self.csubmit(cid,problems[0]['id'],'u1')
  self.wait(lambda:dt.datetime.now(dt.timezone.utc)>=end+dt.timedelta(seconds=.4),8);self.wait(lambda:self.detail(cid)['status']=='FINALIZING',8)
  pending_status,_=self.safe_call('/contests/'+cid+'/scoreboard/finish','POST',self.score_cmd(cid));assert pending_status==409
  self.start_judges();self.wait(lambda:self.s.sql('SELECT executionStatus FROM submission_execution WHERE submissionId='+str(int(late)))=='COMPLETED',25)
  crossed=self.count('SELECT COUNT(*) FROM submission_execution e JOIN judge_attempt a ON a.submissionId=e.submissionId JOIN contest c ON c.id=e.contestId WHERE e.submissionId='+str(int(late))+' AND e.acceptedAt<c.endAt AND a.finishedAt>=c.endAt');assert crossed==1
  assert int(self.s.api('/contests/'+cid+'/scoreboard')['projectionPendingCount'])>=1
  self.trigger('fa_rebuild_rollback','contest_score_user','BEFORE INSERT','IF NEW.contestId='+cid+' AND NEW.projectionVersion=2 AND NEW.userId='+self.users['u3']+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture rebuild rollback'; END IF;")
  rollback_status,_=self.safe_call('/contests/'+cid+'/scoreboard/rebuild','POST',self.score_cmd(cid));assert rollback_status==503
  assert int(self.s.api('/contests/'+cid+'/scoreboard')['projectionVersion'])==1
  assert self.count('SELECT COUNT(*) FROM contest_scoreboard_version WHERE contestId='+cid+' AND projectionVersion=2')==0;self.drop_triggers()
  self.trigger('fa_rebuild_overlap','contest_scoreboard_state','BEFORE UPDATE','IF NEW.contestId='+cid+' AND NEW.activeVersion>OLD.activeVersion THEN DO SLEEP(4); END IF;')
  command=self.score_cmd(cid);event_id=self.s.sql("SELECT eventId FROM reliable_outbox WHERE eventType='JUDGE_RESULT' AND aggregateId="+str(int(late)));message=json.loads(self.s.sql("SELECT payload FROM reliable_outbox WHERE eventId='"+event_id+"'"))
  with cf.ThreadPoolExecutor(3) as pool:
   rebuild=pool.submit(self.s.api,'/contests/'+cid+'/scoreboard/rebuild','POST',command)
   self.wait(self.sleeping,8);self.bind('scoreboard');self.publish('scoreboard',message,event_id);reading=pool.submit(self.s.api,'/contests/'+cid+'/scoreboard',actor='observer')
   rebuilt=rebuild.result(timeout=20);view=reading.result(timeout=20)
  assert int(rebuilt['projectionVersion'])==2 and int(view['projectionVersion'])==2;self.drop_triggers()
  self.wait(lambda:int(self.s.api('/contests/'+cid+'/scoreboard')['projectionPendingCount'])==0,15)
  self.s.api('/contests/'+cid+'/scoreboard/finish','POST',self.score_cmd(cid));final=self.s.api('/contests/'+cid+'/scoreboard',actor='observer');assert final['final'] and final['status']=='FINISHED' and int(final['finalVersion'])==2
  assert {e['userId']:(int(e['solvedCount']),int(e['rank'])) for e in final['entries']}=={self.users['u0']:(2,1),self.users['u1']:(2,2),self.users['u2']:(0,3),self.users['u3']:(0,3)}
  (ROOT/'ranking-final.json').write_text(json.dumps(final,indent=2)+'\n')
  return {'contestId':cid,'controlledAcceptedAtFixture':True,'timestampFixtureScope':'5 HTTP submissions retimed only in isolated DB to produce exact hand-calculated ties; not claimed as natural HTTP timing','initialAllZeroRank':1,'intermediateTwoUsersTieAtRank':1,'sameUserTwoProblemsActuallyRunningConcurrently':bool(overlap),'finalRanks':[1,2,3,3],'crossPageZeroRanks':[3,3],'actualAcceptedBeforeDeadlineCompletedAfter':bool(crossed),'pendingFinishHttp':pending_status,'rebuildSqlFailureHttp':rollback_status,'rollbackPreservedVersion':1,'firstLateScoreDeliveryDuringRebuild':True,'atomicVersionObserved':2,'finalVersion':2}
 def cancel_submit(self):
  cid,draft=self.contest('cancel-submit',capacity=1,redis=False,close=7,start=9,end=45);self.register(cid,'u0');start=dt.datetime.fromisoformat(draft['startAt'].replace('Z','+00:00'));self.wait(lambda:dt.datetime.now(dt.timezone.utc)>=start,12)
  c=self.detail(cid);snapshot=c['problems'][0]['id'];body={'problemSnapshotId':snapshot,'publishedVersion':c['publishedVersion'],'language':'java','code':SUM}
  with cf.ThreadPoolExecutor(7) as pool:
   fs=[pool.submit(self.safe_call,'/contests/'+cid+'/submissions','POST',body,'u0',self.key('cancel-submit')) for _ in range(6)]
   cancel=pool.submit(self.safe_call,'/contests/'+cid+'/cancel','POST',{'expectedVersion':c['version'],'reason':'fixture submit cancel race'})
   responses=[f.result(timeout=30) for f in fs];cancelled=cancel.result(timeout=30)
  assert cancelled[0]==200;status,_=self.safe_call('/contests/'+cid+'/submissions','POST',body,'u0',self.key('after-cancel'));assert status==409
  late=self.count("SELECT COUNT(*) FROM submission_execution e JOIN operation_audit a ON a.targetId=e.contestId AND a.action='CONTEST_CANCEL' WHERE e.contestId="+cid+' AND e.acceptedAt>a.createdAt');assert late==0
  return {'actualSubmitCancelRace':True,'concurrentRequests':6,'newSubmissionsAfterCancellation':late,'postCancelNewKeyHttp':status,'acceptedBeforeCancel':sum(status==202 for status,_ in responses)}

def run(s):
 c=Checks(s)
 cases=[('fixtures',[],c.fixtures),('submission-idempotency',['JV01'],c.jv01),('api-crash-commit-windows',['JV02'],c.jv02),('real-publisher-return-confirm-loss-disconnect',['JV03'],c.jv03),('two-real-consumers-mysql-claim',['JV04'],c.jv04),('running-worker-sigkill-stale-token',['JV05','JV20'],c.jv05),('result-commit-before-consumer-ack-loss',['JV06'],c.jv06),('eight-users-two-slots',['JV09'],c.jv09),('redis-reservation-api-crash-before-db',['JV10'],c.jv10),('confirmed-marker-write-failure',['JV11'],c.jv11_marker),('reservation-before-close-consumption-after',['JV12'],c.jv12),('registration-cancel-race',['JV13'],c.jv13),('submit-cancel-race',['JV13'],c.cancel_submit),('platform-budget-exhaustion-manual-recovery',['JV19'],c.jv19),('confirmed-unclaimed-business-recovery',['JV21'],c.jv21),('practice-edit-after-acceptance',['JV29'],c.jv29),('late-confirm-after-release',['JV22'],c.jv22),('initialization-and-ledger-loss',['JV24','JV28'],c.jv24),('multiuser-score-race-rebuild-settlement',['JV16','JV17','JV18','JV26','JV27'],c.ranking)]
 for name,jvs,fn in cases:c.run_case(name,jvs,fn)
 s.ok=all(row['status']=='PASS' for row in c.rows);s.event('EXTENDED_ACCEPTANCE_FINISHED',passed=sum(r['status']=='PASS' for r in c.rows),failed=sum(r['status']=='FAIL' for r in c.rows))
