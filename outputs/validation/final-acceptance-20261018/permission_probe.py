"""Fresh/stale-role HTTP checks using disposable users and this run's private database only."""
from pathlib import Path
import os,json,re,subprocess,urllib.request,urllib.error,http.cookiejar,secrets,time
ROOT=Path(__file__).resolve().parent
folders=[p for p in Path('/private/tmp').glob('codeoj-native-stack-*') if p.stat().st_uid==os.getuid() and (p/'local.properties').exists()];assert len(folders)==1
work=folders[0];props=dict(line.split('=',1) for line in (work/'local.properties').read_text().splitlines() if '=' in line)
rt=json.loads(Path('/private/tmp/codeoj-final-acceptance-p3f4rsrl/runtimes/runtimes.json').read_text());mysql=rt['paths']['mysql']+'/bin/mysql';port=re.search(r'127.0.0.1:(\d+)',props['spring.datasource.url']).group(1)
def sql(statement):
 r=subprocess.run([mysql,'--no-defaults','--protocol=TCP','-h127.0.0.1','-P'+port,'-ucodeoj_fixture','--batch','--raw','--skip-column-names','codeoj'],input=statement,env={**os.environ,'MYSQL_PWD':props['spring.datasource.password']},text=True,capture_output=True,timeout=15);assert r.returncode==0;return r.stdout.strip()
processes=subprocess.check_output(['ps','-ax','-o','command='],text=True).splitlines();gateway=next(x for x in processes if str(work/'local.properties') in x and 'codeoj-backend-gateway-0.0.1-SNAPSHOT.jar' in x);gateway_port=re.search(r'--server.port=(\d+)',gateway).group(1);base='http://127.0.0.1:'+gateway_port+'/api'
def call(path,method='GET',body=None,client=None):
 url=path if path.startswith('http') else base+path;headers={'Content-Type':'application/json'} if body is not None else {}
 req=urllib.request.Request(url,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
 try:
  with (client or urllib.request.build_opener()).open(req,timeout=25) as r:status=r.status;raw=r.read()
 except urllib.error.HTTPError as e:status=e.code;raw=e.read()
 try:data=json.loads(raw)
 except ValueError:data={}
 return status,data
password=secrets.token_hex(20);ids={};clients={}
for role in ['user','admin']:
 client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()));account='permission_'+role+'_'+secrets.token_hex(4)
 status,data=call('/user/register','POST',{'userAccount':account,'userPassword':password,'checkPassword':password},client);assert data.get('code')==0;uid=str(int(data['data']));ids[role]=uid
 if role=='admin':sql("UPDATE user SET userRole='admin' WHERE id="+uid)
 status,data=call('/user/login','POST',{'userAccount':account,'userPassword':password},client);assert data.get('code')==0;clients[role]=client
rows=[]
def record(name,passed,status,**facts):rows.append({'case':name,'status':'PASS' if passed else 'FAIL','httpStatus':status,**facts});(ROOT/'permission-results.json').write_text(json.dumps(rows,indent=2)+'\n')
# Own independent private contest; no fixture time editing or registration needed.
now=__import__('datetime').datetime.now(__import__('datetime').timezone.utc)
def at(n):return (now+__import__('datetime').timedelta(seconds=n)).isoformat().replace('+00:00','Z')
draft={'title':'Acceptance permissions','contestType':'PRIVATE','capacity':1,'registrationOpenAt':at(10),'registrationCloseAt':at(50),'startAt':at(55),'endAt':at(90),'problems':[{'problemCode':'A','title':'Private permission sample','content':'Private statement','limits':{'timeLimit':1000,'memoryLimit':262144},'cases':[{'input':'1 2','output':'3'}]}]}
status,data=call('/contests','POST',draft,clients['admin']);assert data.get('code')==0;cid=data['data']['id'];version=data['data']['version']
status,data=call('/contests/'+cid+'/publish','POST',{'expectedVersion':version},clients['admin']);assert data.get('code')==0
status,data=call('/contests/'+cid+'/scoreboard');record('anonymous scoreboard denied',status==401,status)
status,data=call('/contests/'+cid+'/scoreboard',client=clients['user']);record('logged-in observer scoreboard allowed',status==200 and data.get('code')==0,status)
status,data=call('/contests/'+cid,client=clients['user']);record('private statement absent for unregistered user',status==200 and data.get('data',{}).get('problems')==[],status)
status,data=call('/contests/'+cid+'?admin=true',client=clients['user']);record('admin query flag cannot grant role',status==403,status)
status,_=call('/question/inner/get/id?questionId=1');record('gateway internal endpoint refused',status==403,status)
question=props['spring.cloud.discovery.client.simple.instances.codeoj-backend-question-service[0].uri']
status,_=call(question+'/api/question/inner/get/id?questionId=1');record('direct service internal endpoint refused',status==403,status)
end=time.monotonic()+100;submission=''
while time.monotonic()<end:
 submission=sql('SELECT id FROM question_submit ORDER BY id LIMIT 1')
 if submission:break
 time.sleep(.5)
if submission:
 status,data=call('/submissions/'+submission,client=clients['user']);record('another users submission refused',status==403,status)
qid=sql('SELECT id FROM question ORDER BY id LIMIT 1');assert qid
status,data=call('/question/get?id='+qid,client=clients['admin']);record('fixture administrator initially has full question permission',status==200 and data.get('code')==0,status)
sql("UPDATE user SET userRole='user' WHERE id="+ids['admin'])
status,data=call('/contests/'+cid+'?admin=true',client=clients['admin']);record('revoked cached admin denied contest management',status==403,status)
status,data=call('/question/get?id='+qid,client=clients['admin']);private=data.get('data') or {};exposed=[k for k in ['judgeCase','answer'] if private.get(k) is not None]
record('revoked cached admin denied legacy full question',status==403 or data.get('code') in (40101,40300),status,businessCode=data.get('code'),privateFieldNamesExposed=exposed)
title=sql('SELECT title FROM question WHERE id='+qid)
status,data=call('/question/update','POST',{'id':qid,'title':title},clients['admin'])
record('revoked cached admin denied legacy question update',status==403 or data.get('code') in (40101,40300),status,businessCode=data.get('code'),sameValueWriteOnly=True,updateReportedSuccess=data.get('data') is True)
print(json.dumps(rows,indent=2))
