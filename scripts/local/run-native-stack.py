#!/usr/bin/env python3
"""Disposable real MySQL/Redis/RabbitMQ + five real CodeOJ services. No Docker, no mocks."""
import argparse, base64, contextlib, datetime, hashlib, http.cookiejar, json, os, pathlib, secrets
import shutil, signal, socket, subprocess, sys, tempfile, time, urllib.error, urllib.request, zipfile

REPO = pathlib.Path(__file__).resolve().parents[2]

class Stack:
    def __init__(self, runtimes, evidence):
        self.runtime_info=json.loads((pathlib.Path(runtimes)/'runtimes.json').read_text())
        self.rt=self.runtime_info['paths'];self.evidence=pathlib.Path(evidence).resolve();self.evidence.mkdir(parents=True,exist_ok=True)
        self.work=pathlib.Path(tempfile.mkdtemp(prefix='codeoj-native-stack-',dir='/private/tmp'))
        self.procs={};self.logs={};self.launches={};self.secret={k:secrets.token_hex(20) for k in ['root','db','redis','mq','inner','sandbox','cookie','test']}
        names=['mysql','redis','amqp','management','epmd','distribution','user','question','judge','sandbox','gateway','frontend']
        reservations=[];self.ports={}
        for name in names:
            sock=socket.socket();sock.bind(('127.0.0.1',0));self.ports[name]=sock.getsockname()[1];reservations.append(sock)
        for sock in reservations:sock.close()
        self.env=os.environ.copy();self.java=self.env.get('CODEOJ_JAVA8_HOME','/Library/Java/JavaVirtualMachines/amazon-corretto-8.jdk/Contents/Home')
        self.env.update({'JAVA_HOME':self.java,'PATH':self.java+'/bin:'+self.env['PATH']})
        self.events=[];self.state={};self.ok=False
    def event(self, action, **details):
        row={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'action':action,**details};self.events.append(row);print(json.dumps(row),flush=True)
    def spawn(self,name,args,env=None,cwd=None):
        log=(self.work/(name+'.log')).open('ab');self.logs[name]=log
        p=subprocess.Popen([str(v) for v in args],cwd=cwd or self.work,env=env or self.env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
        self.procs[name]=p;self.launches[name]=(args,env,cwd);return p
    def stop(self,name):
        p=self.procs.get(name)
        if p is None:return
        if p.poll() is None:
            os.killpg(p.pid,signal.SIGTERM)
            try:p.wait(timeout=15)
            except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait(timeout=5)
        self.event('PROCESS_STOPPED',service=name)
    def restart(self,name,port=None):
        args,env,cwd=self.launches[name];self.spawn(name,args,env,cwd)
        self.wait(name,lambda:self.socket_ready(port or name),90)
        self.event('PROCESS_RESTARTED',service=name)
    def wait(self,name,check,seconds=90):
        end=time.monotonic()+seconds
        while time.monotonic()<end:
            p=self.procs.get(name)
            if p and p.poll() is not None:raise RuntimeError(name+' exited; see sanitized service log')
            try:
                result=check()
                if result:return result
            except (OSError,ValueError,urllib.error.URLError,subprocess.SubprocessError):pass
            time.sleep(.3)
        raise RuntimeError(name+' readiness deadline exceeded')
    def socket_ready(self,name):
        with socket.create_connection(('127.0.0.1',self.ports[name]),timeout=.5):return True
    def sql(self,sql,root=False):
        env=self.env.copy();env['MYSQL_PWD']=self.secret['root' if root else 'db']
        args=[self.rt['mysql']+'/bin/mysql','--no-defaults','--protocol=TCP','-h127.0.0.1','-P'+str(self.ports['mysql']),'-u'+('root' if root else 'codeoj_fixture'),'--batch','--raw','--skip-column-names','--default-character-set=utf8mb4']
        if not root:args+=['codeoj']
        r=subprocess.run(args,input=sql,text=True,env=env,capture_output=True,timeout=25)
        if r.returncode:raise RuntimeError('MySQL statement failed: '+r.stderr[:2000])
        return r.stdout.strip()
    def http(self,url,method='GET',body=None,opener=None,headers=None):
        headers=dict(headers or {})
        if body is not None:headers['Content-Type']='application/json'
        request=urllib.request.Request(url,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
        try:
            with (opener or urllib.request.build_opener()).open(request,timeout=45) as response:return response.status,json.load(response)
        except urllib.error.HTTPError as error:
            raw=error.read()
            try:data=json.loads(raw)
            except ValueError:data={'text':raw.decode(errors='replace')[:200]}
            return error.code,data
    def api(self,path,method='GET',body=None,actor='admin',key=None,allow_error=False):
        headers={'Idempotency-Key':key} if key else {}
        status,data=self.http('http://127.0.0.1:'+str(self.ports['gateway'])+'/api'+path,method,body,self.clients.get(actor),headers)
        if not allow_error and (status>=400 or data.get('code')!=0):raise RuntimeError('API failed '+path+' '+str(status)+' '+str(data))
        return (status,data) if allow_error else data['data']
    def verify_artifacts(self):
        checks=[]
        for module in ['user-service','question-service','judge-service','gateway','codesandbox']:
            folder=REPO/'codeoj-backend'/('codeoj-backend-'+module)
            with zipfile.ZipFile(folder/'target'/('codeoj-backend-'+module+'-0.0.1-SNAPSHOT.jar')) as jar:
                classes=list((folder/'target/classes').rglob('*.class'))
                for path in classes:
                    archived=jar.read('BOOT-INF/classes/'+str(path.relative_to(folder/'target/classes')))
                    if archived!=path.read_bytes(): raise RuntimeError('STALE_PACKAGED_CLASS: '+module)
                nested=[]
                for dependency in ['common','model','service-client']:
                    name='codeoj-backend-'+dependency+'-0.0.1-SNAPSHOT.jar'
                    member='BOOT-INF/lib/'+name
                    if member not in jar.namelist(): continue
                    current=(REPO/'codeoj-backend'/('codeoj-backend-'+dependency)/'target'/name).read_bytes()
                    if jar.read(member)!=current: raise RuntimeError('STALE_PACKAGED_DEPENDENCY: '+module+'/'+dependency)
                    nested.append(dependency)
                checks.append({'module':module,'classes':len(classes),'matchingDependencies':nested})
        (self.evidence/'artifact-checks.json').write_text(json.dumps(checks,indent=2)+'\n')
        self.event('PACKAGED_ARTIFACTS_MATCH_CURRENT_BUILD')
    def setup_infrastructure(self):
        mysql=pathlib.Path(self.rt['mysql']);data=self.work/'mysql-data';data.mkdir()
        with (self.work/'mysql-init.log').open('w') as log:
            subprocess.run([mysql/'bin/mysqld','--no-defaults','--initialize-insecure','--basedir='+str(mysql),'--datadir='+str(data)],stdout=log,stderr=subprocess.STDOUT,check=True,timeout=90)
        self.spawn('mysql',[mysql/'bin/mysqld','--no-defaults','--basedir='+str(mysql),'--datadir='+str(data),'--port='+str(self.ports['mysql']),'--bind-address=127.0.0.1','--socket='+str(self.work/'mysql.sock'),'--pid-file='+str(self.work/'mysql.pid'),'--mysqlx=0','--default-time-zone=+00:00','--character-set-server=utf8mb4','--collation-server=utf8mb4_unicode_ci','--skip-log-bin'])
        self.wait('mysql',lambda:self.socket_ready('mysql'))
        init=f"ALTER USER 'root'@'localhost' IDENTIFIED BY '{self.secret['root']}'; CREATE USER 'codeoj_fixture'@'127.0.0.1' IDENTIFIED BY '{self.secret['db']}'; GRANT ALL ON codeoj.* TO 'codeoj_fixture'@'127.0.0.1';"
        subprocess.run([mysql/'bin/mysql','--no-defaults','--protocol=SOCKET','--socket='+str(self.work/'mysql.sock'),'-uroot'],input=init,text=True,capture_output=True,check=True,timeout=20)
        self.sql((REPO/'codeoj-backend/mysql-init/create_table.sql').read_text(),True)
        for path in sorted((REPO/'codeoj-backend/mysql-init/migrations').glob('V*.sql')):
            self.sql('USE codeoj;\n'+path.read_text(),True);self.event('MIGRATION_APPLIED',name=path.name)
        redis_data=self.work/'redis-data';redis_data.mkdir()
        self.spawn('redis',[self.rt['redis'],'--bind','127.0.0.1','--port',str(self.ports['redis']),'--requirepass',self.secret['redis'],'--dir',str(redis_data),'--appendonly','yes','--appendfsync','always','--save',''])
        self.wait('redis',lambda:self.socket_ready('redis'))
        erlang=pathlib.Path(self.rt['erlang']);env=self.env.copy()
        env.update({'ERL_ROOTDIR':str(erlang),'DYLD_LIBRARY_PATH':self.rt['openssl'],'ERL_FLAGS':'+S 2:2 +A 4','ERL_EPMD_PORT':str(self.ports['epmd']),'ERL_EPMD_ADDRESS':'127.0.0.1'})
        env['PATH']=str(erlang/'bin')+':'+env['PATH']
        crypto=subprocess.run([erlang/'bin/erl','-noshell','-eval','crypto:strong_rand_bytes(1), halt().'],env=env,capture_output=True,text=True,timeout=20)
        if crypto.returncode:raise RuntimeError('Erlang crypto dependency probe failed: '+crypto.stdout[:1000]+crypto.stderr[:1000])
        self.spawn('epmd',[erlang/'bin/epmd','-port',str(self.ports['epmd'])],env)
        self.wait('epmd',lambda:self.socket_ready('epmd'))
        rabbit=self.work/'rabbit';rabbit.mkdir();(rabbit/'enabled_plugins').write_text('[rabbitmq_management].\n')
        cfg=f'''listeners.tcp.1 = 127.0.0.1:{self.ports['amqp']}
management.tcp.ip = 127.0.0.1
management.tcp.port = {self.ports['management']}
default_user = codeoj_fixture
default_pass = {self.secret['mq']}
loopback_users.codeoj_fixture = true
log.console.level = warning
log.file.level = warning
'''
        (rabbit/'rabbitmq.conf').write_text(cfg);os.chmod(rabbit/'rabbitmq.conf',0o600)
        env.update({'RABBITMQ_HOME':self.rt['rabbitmq'],'RABBITMQ_CONFIG_FILE':str(rabbit/'rabbitmq'),'RABBITMQ_MNESIA_BASE':str(rabbit/'mnesia'),'RABBITMQ_LOG_BASE':str(rabbit/'logs'),'RABBITMQ_ENABLED_PLUGINS_FILE':str(rabbit/'enabled_plugins'),'RABBITMQ_NODENAME':'codeoj_'+self.work.name.replace('-','_')+'@localhost','RABBITMQ_DIST_PORT':str(self.ports['distribution']),'RABBITMQ_ERLANG_COOKIE':self.secret['cookie'],'RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS':'+S 2:2 +A 4 -kernel inet_dist_use_interface {127,0,0,1}'})
        self.rabbit_env=env
        self.spawn('rabbitmq',[self.rt['rabbitmq']+'/sbin/rabbitmq-server'],env)
        self.wait('rabbitmq',lambda:self.socket_ready('management'))
        status,info=self.wait('rabbitmq',lambda:(lambda response:response if response[0]==200 and 'rabbitmq_version' in response[1] else None)(self.mq('/overview')))
        self.event('INFRASTRUCTURE_READY',mysql=self.sql('SELECT VERSION();'),rabbitmq=info['rabbitmq_version'],erlang=info['erlang_version'],redis='6.2.24')
    def mq(self,path,method='GET',body=None):
        auth=base64.b64encode(('codeoj_fixture:'+self.secret['mq']).encode()).decode()
        return self.http('http://127.0.0.1:'+str(self.ports['management'])+'/api'+path,method,body,headers={'Authorization':'Basic '+auth})
    def setup_services(self):
        cfg={
          'server.address':'127.0.0.1','spring.cloud.nacos.discovery.enabled':'false','spring.cloud.nacos.discovery.register-enabled':'false','spring.cloud.nacos.config.enabled':'false',
          'spring.datasource.url':f"jdbc:mysql://127.0.0.1:{self.ports['mysql']}/codeoj?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8",
          'spring.datasource.username':'codeoj_fixture','spring.datasource.password':self.secret['db'],
          'spring.redis.host':'127.0.0.1','spring.redis.port':self.ports['redis'],'spring.redis.password':self.secret['redis'],'spring.redis.database':1,
          'spring.rabbitmq.host':'127.0.0.1','spring.rabbitmq.port':self.ports['amqp'],'spring.rabbitmq.username':'codeoj_fixture','spring.rabbitmq.password':self.secret['mq'],
          'codeoj.inner-auth-secret':self.secret['inner'],'codeoj.reliable-judge.enabled':'true','codeoj.registration.enabled':'true','codeoj.scoreboard.enabled':'true',
          'codesandbox.auth-secret':self.secret['sandbox'],'codesandbox.remote.auth-secret':self.secret['sandbox'],'codesandbox.remote.url':f"http://127.0.0.1:{self.ports['sandbox']}/executeCode",
          'mybatis-plus.configuration.log-impl':'org.apache.ibatis.logging.nologging.NoLoggingImpl','logging.level.root':'WARN','spring.main.banner-mode':'off','knife4j.gateway.discover.enabled':'false',
          'codeoj.cors-allowed-origins':f"http://127.0.0.1:{self.ports['frontend']}",
        }
        for name in ['user','question','judge']:
            cfg['spring.cloud.discovery.client.simple.instances.codeoj-backend-'+name+'-service[0].uri']='http://127.0.0.1:'+str(self.ports[name])
        file=self.work/'local.properties';file.write_text(''.join(str(k)+'='+str(v)+'\n' for k,v in cfg.items()));os.chmod(file,0o600)
        for name in ['sandbox','user','question','judge','gateway']:
            module='codesandbox' if name=='sandbox' else name if name=='gateway' else name+'-service'
            jar=REPO/'codeoj-backend'/('codeoj-backend-'+module)/'target'/('codeoj-backend-'+module+'-0.0.1-SNAPSHOT.jar')
            self.service_args=getattr(self,'service_args',{})
            args=[self.java+'/bin/java','-Xms64m','-Xmx256m','-Duser.home='+str(self.work),'-Dcsp.sentinel.log.dir='+str(self.work/'sentinel-logs'),'-DJM.LOG.PATH='+str(self.work/'nacos-logs'),'-Duser.timezone=UTC','-Dfile.encoding=UTF-8','-jar',jar,'--spring.profiles.active=native-local','--spring.config.additional-location=file:'+str(file),'--server.port='+str(self.ports[name])]
            self.service_args[name]=args;self.spawn(name,args)
            self.wait(name,lambda n=name:self.socket_ready(n),120);self.event('SERVICE_READY',service=name)
    def accounts(self):
        self.clients={}
        for name in ['admin','participant','observer']:
            jar=http.cookiejar.CookieJar();opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar));self.clients[name]=opener
            account='fixture_'+name;body={'userAccount':account,'userPassword':self.secret['test'],'checkPassword':self.secret['test']}
            uid=self.api('/user/register','POST',body,actor=name)
            if name=='admin':self.sql("UPDATE user SET userRole='admin' WHERE id="+str(int(uid)))
            login=self.api('/user/login','POST',{'userAccount':account,'userPassword':self.secret['test']},actor=name)
            assert int(login['id'])==int(uid);self.state[name+'Id']=str(uid)
        self.event('REAL_SESSION_LOGIN_PASSED')
    def exercise(self):
        self.accounts()
        now=datetime.datetime.now(datetime.timezone.utc)
        def at(seconds):return (now+datetime.timedelta(seconds=seconds)).isoformat(timespec='microseconds').replace('+00:00','Z')
        problem={'problemCode':'A','title':'Native integration sum','content':'Read two integers and output their sum.','limits':{'timeLimit':1000,'memoryLimit':262144},'cases':[{'input':'1 2','output':'3'},{'input':'6 7','output':'13'}]}
        draft={'title':'Native local integration','contestType':'PRIVATE','capacity':2,'registrationOpenAt':at(5),'registrationCloseAt':at(30),'startAt':at(35),'endAt':at(210),'problems':[problem]}
        contest=self.api('/contests','POST',draft);cid=contest['id'];self.state['contestId']=cid
        self.api('/contests/'+cid+'/publish','POST',{'expectedVersion':contest['version']})
        detail=lambda:self.api('/contests/'+cid,actor='admin')
        c=detail();self.api('/contests/'+cid+'/registration-admin/prepare','POST',{'expectedVersion':c['version'],'reason':'isolated native fixture cutover'})
        c=detail();self.api('/contests/'+cid+'/registration-admin/reconcile','POST',{'expectedVersion':c['version'],'reason':'verify actual Redis ledger'})
        self.wait('question',lambda:datetime.datetime.now(datetime.timezone.utc)>=now+datetime.timedelta(seconds=5),15)
        registration=self.api('/contests/'+cid+'/registrations','POST',actor='participant',key='native-fixture-registration')
        self.state['registrationRequestId']=registration['requestId']
        self.wait('question',lambda:self.api('/contests/'+cid+'/registrations/me',actor='participant')['status']=='CONFIRMED',20)
        self.event('ASYNC_REGISTRATION_CONFIRMED',requestId=registration['requestId'])
        self.wait('question',lambda:datetime.datetime.now(datetime.timezone.utc)>=now+datetime.timedelta(seconds=35),45)
        c=self.api('/contests/'+cid,actor='participant');snapshot=c['problems'][0]['id'];self.state.update({'snapshotId':snapshot,'publishedVersion':c['publishedVersion']})
        valid='public class Main { public static void main(String[] a) throws Exception { java.util.Scanner s=new java.util.Scanner(System.in); System.out.println(s.nextInt()+s.nextInt()); Thread.sleep(100); }}'
        wrong=valid.replace('s.nextInt()+s.nextInt()','s.nextInt()-s.nextInt()')
        def submit(code,key):return self.api('/contests/'+cid+'/submissions','POST',{'problemSnapshotId':snapshot,'publishedVersion':c['publishedVersion'],'language':'java','code':code},'participant',key)
        for verdict,code in [('AC',valid),('WA',wrong),('CE','public class Main { not java }')]:
            sid=submit(code,'native-fixture-'+verdict.lower());self.state[verdict+'SubmissionId']=sid
            result=self.wait('judge',lambda sid=sid:(lambda r:r if r.get('executionStatus')=='COMPLETED' else None)(self.api('/submissions/'+sid,actor='participant')),50)
            assert result['judgeInfo']['verdict']==verdict,result;self.event('REAL_JUDGE_RESULT',submissionId=sid,verdict=verdict)
            assert submit(code,'native-fixture-'+verdict.lower())==sid
        board=self.wait('question',lambda:(lambda b:b if int(b['projectionPendingCount'])==0 and int(b['entries'][0]['solvedCount'])==1 else None)(self.api('/contests/'+cid+'/scoreboard',actor='observer')),20)
        assert int(board['entries'][0]['rank'])==1
        status,_=self.api('/contests/'+cid+'/scoreboard/cache-clear','POST',{'expectedVersion':board['contestVersion'],'expectedProjectionVersion':board['projectionVersion'],'expectedRevision':board['revision'],'reason':'must be forbidden'},'participant',allow_error=True);assert status==403
        self.event('REAL_SCOREBOARD_AND_ROLE_CHECK_PASSED')
        # Functional outage drills only: one fixture request per fault, no throughput/load claim.
        self.stop('judge')
        queued=submit(valid,'native-fixture-durable-queue');self.state['recoverySubmissionId']=queued
        self.wait('question',lambda:self.sql("SELECT publishState FROM reliable_outbox WHERE eventType='JUDGE_DISPATCH' AND aggregateId="+str(int(queued)))=='CONFIRMED',15)
        self.stop('rabbitmq');self.restart('rabbitmq','management');self.restart('judge')
        self.wait('judge',lambda:self.api('/submissions/'+queued,actor='participant').get('executionStatus')=='COMPLETED',45)
        original=json.loads(self.sql("SELECT payload FROM reliable_outbox WHERE eventType='JUDGE_DISPATCH' AND aggregateId="+str(int(queued))))
        publish={'properties':{'delivery_mode':2,'message_id':original['eventId'],'content_type':'application/json'},'routing_key':'judge','payload':json.dumps(original),'payload_encoding':'string'}
        status,published=self.mq('/exchanges/%2F/codeoj.judge.v1.exchange/publish','POST',publish);assert status==200 and published['routed']
        time.sleep(2)
        assert self.sql('SELECT COUNT(*) FROM judge_attempt WHERE submissionId='+str(int(queued)))=='1'
        self.event('DURABLE_QUEUE_BROKER_RESTART_AND_DUPLICATE_PASSED',submissionId=queued)
        self.stop('sandbox')
        recovery=submit(valid,'native-fixture-sandbox-recovery')
        self.wait('question',lambda:self.api('/submissions/'+recovery,actor='participant').get('executionStatus') in ['RETRY_WAIT','NEEDS_ATTENTION'],15)
        self.restart('sandbox')
        recovered=self.wait('judge',lambda:(lambda r:r if r.get('executionStatus')=='COMPLETED' else None)(self.api('/submissions/'+recovery,actor='participant')),45)
        assert recovered['judgeInfo']['verdict']=='AC'
        self.event('SANDBOX_FAILURE_AND_LEASE_RECOVERY_PASSED',submissionId=recovery)
        self.stop('redis')
        unavailable=self.api('/contests/'+cid+'/registrations/me',actor='participant',allow_error=True)
        assert unavailable[0]==503 and unavailable[1].get('message')=='SESSION_STORE_UNAVAILABLE',unavailable
        self.restart('redis')
        self.wait('question',lambda:(lambda reply:reply[0]==200 and reply[1].get('data',{}).get('status')=='CONFIRMED')(self.api('/contests/'+cid+'/registrations/me',actor='participant',allow_error=True)),60)
        self.event('REDIS_RESTART_SESSION_AND_LEDGER_RECOVERY_PASSED')

        self.state.update({'gateway':'http://127.0.0.1:'+str(self.ports['gateway']),'frontendPort':self.ports['frontend'],'password':self.secret['test'],'accounts':{k:'fixture_'+k for k in self.clients},'endAt':draft['endAt']})
        config=self.work/'browser-fixture.json';config.write_text(json.dumps(self.state));os.chmod(config,0o600)
        browser_env=self.env.copy();browser_env['CODEOJ_FIXTURE_CONFIG_FILE']=str(config)
        browser=self.spawn('browser',['node',str(REPO/'scripts/validation/contest-real-browser.cjs')],browser_env,REPO)
        if browser.wait(timeout=100)!=0: raise RuntimeError('Real browser check failed; see sanitized browser.log')
        self.event('REAL_BROWSER_PASSED')
        self.wait('question',lambda:datetime.datetime.now(datetime.timezone.utc)>=now+datetime.timedelta(seconds=211),220)
        self.wait('question',lambda:detail()['status']=='FINALIZING',10)
        current=detail();self.api('/contests/'+cid+'/registration-admin/reconcile','POST',{'expectedVersion':current['version'],'reason':'finalizing ledger seal'})
        def cmd():
            b=self.api('/contests/'+cid+'/scoreboard');return {'expectedVersion':b['contestVersion'],'expectedProjectionVersion':b['projectionVersion'],'expectedRevision':b['revision'],'reason':'verified real native fact reconciliation'}
        rebuilt=self.api('/contests/'+cid+'/scoreboard/rebuild','POST',cmd());self.api('/contests/'+cid+'/scoreboard/finish','POST',cmd())
        final=self.api('/contests/'+cid+'/scoreboard',actor='observer');assert final['final'] and final['status']=='FINISHED'
        assert submit(valid,'native-fixture-ac')==self.state['ACSubmissionId']
        status,_=self.api('/contests/'+cid+'/submissions','POST',{'problemSnapshotId':snapshot,'publishedVersion':c['publishedVersion'],'language':'java','code':valid},'participant','native-after-end-new-key',allow_error=True)
        assert status==409
        facts={'executions':self.sql("SELECT e.submissionId,e.executionStatus,e.activeAttempt,JSON_UNQUOTE(JSON_EXTRACT(q.judgeInfo,'$.verdict')) FROM submission_execution e JOIN question_submit q ON q.id=e.submissionId ORDER BY e.acceptedAt").splitlines(),
               'outboxes':self.sql('SELECT eventType,publishState,COUNT(*) FROM reliable_outbox GROUP BY eventType,publishState').splitlines(),
               'publishAttempts':self.sql('SELECT confirmState,returned,COUNT(*) FROM publish_attempt GROUP BY confirmState,returned').splitlines(),
               'scoreReceipts':int(self.sql('SELECT COUNT(*) FROM scoreboard_result_receipt')),
               'qualificationCounts':self.sql('SELECT status,COUNT(*) FROM contest_registration GROUP BY status').splitlines(),
               'registrationCapacity':self.sql('SELECT capacity,remainingCapacity,registrationMode,status FROM contest').splitlines(),
               'afterEndOriginalKeyReplay':True,'afterEndNewKeyRejected':True}
        (self.evidence/'database-facts.json').write_text(json.dumps(facts,indent=2)+'\n')
        self.state.pop('password',None);self.state.pop('accounts',None)
        self.evidence.joinpath('final-scoreboard.json').write_text(json.dumps(final,indent=2)+'\n')
        self.event('FINALIZATION_PASSED',projectionVersion=rebuilt['projectionVersion'])
        self.ok=True
    def cleanup(self):
        for name in list(self.procs)[::-1]:
            try:self.stop(name)
            except (OSError,subprocess.SubprocessError):pass
        for log in self.logs.values():log.close()
        for path in self.work.glob('*.log'):
            content=path.read_text(errors='replace')
            for secret in self.secret.values():content=content.replace(secret,'[REDACTED]')
            (self.evidence/path.name).write_text(content)
        summary={'scope':'Real native MySQL/Redis/RabbitMQ, Spring Session, Gateway, Feign, remote native Java sandbox; static discovery, no Nacos; not load/production acceptance','passed':self.ok,'ports':self.ports,'runtimeArchives':self.runtime_info['archives'],'events':self.events,'temporaryDataRemoved':True}
        shutil.rmtree(self.work)
        (self.evidence/'run.json').write_text(json.dumps(summary,indent=2)+'\n')

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--runtimes',required=True);parser.add_argument('--evidence',required=True);args=parser.parse_args()
    def interrupted(signum,frame): raise KeyboardInterrupt('Fixture interrupted; cleaning owned processes')
    signal.signal(signal.SIGTERM,interrupted)
    stack=Stack(args.runtimes,args.evidence)
    try:stack.verify_artifacts();stack.setup_infrastructure();stack.setup_services();stack.exercise()
    finally:stack.cleanup()
if __name__=='__main__':main()
