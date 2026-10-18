"""Read/rejected-write checks against this run's disposable fixture; never print credentials."""
from pathlib import Path
import json,os,http.cookiejar,urllib.request,urllib.error
rows=[]
paths=[p for p in Path('/private/tmp').glob('codeoj-native-stack-*') if p.stat().st_uid==os.getuid() and (p/'browser-fixture.json').exists()]
assert len(paths)==1,'Expected this run\'s single disposable fixture'
c=json.loads((paths[0]/'browser-fixture.json').read_text());base=c['gateway']+'/api';cid=c['contestId']
def call(url,method='GET',body=None,client=None,key=None):
 headers={}
 if key:headers['Idempotency-Key']=key
 if body is not None:headers['Content-Type']='application/json'
 request=urllib.request.Request(url,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
 try:
  with (client or urllib.request.build_opener()).open(request,timeout=20) as r:return r.status,json.load(r)
 except urllib.error.HTTPError as e:
  raw=e.read()
  try:data=json.loads(raw)
  except ValueError:data={}
  return e.code,data
def login(actor):
 client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
 status,data=call(base+'/user/login','POST',{'userAccount':c['accounts'][actor],'userPassword':c['password']},client)
 assert status==200 and data['code']==0
 return client
def record(name,passed,**facts):
 rows.append({'case':name,'passed':passed,**facts})
participant=login('participant');observer=login('observer')
code='public class Main { public static void main(String[] a) throws Exception { java.util.Scanner s=new java.util.Scanner(System.in); System.out.println(s.nextInt()+s.nextInt()); Thread.sleep(100); }}'
body={'problemSnapshotId':c['snapshotId'],'publishedVersion':c['publishedVersion'],'language':'java','code':code}
status,data=call(base+'/contests/'+cid+'/submissions','POST',body,participant,'native-fixture-ac')
record('same key identical content replays original',status==202 and str(data.get('data'))==str(c['ACSubmissionId']),httpStatus=status)
body['code']=code.replace('s.nextInt()+s.nextInt()','s.nextInt()-s.nextInt()')
status,data=call(base+'/contests/'+cid+'/submissions','POST',body,participant,'native-fixture-ac')
record('same key changed code rejected',status==409,httpStatus=status)
status,data=call(base+'/contests/'+cid+'/scoreboard')
record('anonymous scoreboard denied',status==401,httpStatus=status)
status,data=call(base+'/contests/'+cid,client=observer)
record('nonparticipant cannot read private statement',status==200 and data.get('data',{}).get('problems')==[],httpStatus=status)
status,data=call(base+'/submissions/'+str(c['ACSubmissionId']),client=observer)
record('other user cannot read submission',status==403,httpStatus=status)
status,data=call(base+'/contests/'+cid+'?admin=true',client=observer)
record('query flag cannot grant administrator view',status==403,httpStatus=status)
# No internal credential: gateway and service must both refuse the internal path.
status,_=call(base+'/question/inner/get/id?questionId=1')
record('gateway refuses internal endpoint',status==403,httpStatus=status)
properties=dict(line.split('=',1) for line in (paths[0]/'local.properties').read_text().splitlines() if '=' in line)
url=properties['spring.cloud.discovery.client.simple.instances.codeoj-backend-question-service[0].uri']+'/api/question/inner/get/id?questionId=1'
status,_=call(url)
record('direct service refuses internal endpoint without secret',status==403,httpStatus=status)
Path('outputs/validation/acceptance-20261018/live-contract-results.json').write_text(json.dumps(rows,indent=2)+'\n')
print(json.dumps(rows,indent=2));assert all(r['passed'] for r in rows)
