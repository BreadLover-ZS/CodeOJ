"""Independent fresh fixture to resolve failed assertions and check revoked sessions."""
import pathlib,sys,signal,json,datetime as dt,subprocess,os,traceback
ROOT=pathlib.Path(__file__).resolve().parent;sys.path.insert(0,str(ROOT))
from run_acceptance import Acceptance
from extended_cases import Checks
s=Acceptance(sys.argv[1],ROOT/'native-diagnostics');rows=[]
def stop(signum,frame):raise KeyboardInterrupt('Cleaning owned fixture')
signal.signal(signal.SIGTERM,stop)
def save():(ROOT/'diagnostic-results.json').write_text(json.dumps(rows,indent=2)+'\n')
try:
 s.verify_artifacts();s.setup_infrastructure();s.setup_services();s.accounts();c=Checks(s);c.fixtures()
 # A separate fresh question avoids acceptedNum inherited from preceding cases.
 before=c.count('SELECT acceptedNum FROM question WHERE id='+str(int(c.qid)))
 cid,draft=c.contest('public-snapshot-independent',capacity=1,redis=False,close=8,start=11,end=50,source=c.qid);c.register(cid,'u0')
 frozen=c.detail(cid)['problems'][0];spec=s.sql('SELECT judgeSpecHash FROM contest_problem_snapshot WHERE id='+frozen['id'])
 s.api('/question/update','POST',{'id':c.qid,'title':'Acceptance edited source','content':'Edited after publication.','judgeCase':[{'input':'1 2','output':'99'}]})
 start=dt.datetime.fromisoformat(draft['startAt'].replace('Z','+00:00'));c.wait(lambda:dt.datetime.now(dt.timezone.utc)>=start,15)
 detail=s.api('/contests/'+cid,actor='u0');assert detail['problems'][0]['title']=='Acceptance original sum','snapshot title changed'
 sid=c.csubmit(cid,frozen['id'],'u0');c.wait(lambda:s.sql('SELECT executionStatus FROM submission_execution WHERE submissionId='+str(int(sid)))=='COMPLETED',30)
 result=s.api('/submissions/'+str(sid),actor='u0');assert result['judgeInfo']['verdict']=='AC','snapshot verdict changed'
 after_contest=c.count('SELECT acceptedNum FROM question WHERE id='+str(int(c.qid)))
 practice=c.practice();c.completed(practice,'WA');after_practice=c.count('SELECT acceptedNum FROM question WHERE id='+str(int(c.qid)))
 assert s.sql('SELECT judgeSpecHash FROM submission_execution WHERE submissionId='+str(int(sid)))==spec,'snapshot hash changed'
 assert before==after_contest==after_practice,'contest changed practice acceptedNum'
 rows.append({'case':'published-public-snapshot-edit-independent','jv':['JV15'],'status':'PASS','facts':{'originalTitleRetained':True,'contestVerdict':'AC','newPracticeVerdict':'WA','frozenSpecHashRetained':True,'practiceAcceptedNumBefore':before,'practiceAcceptedNumAfterContest':after_contest,'practiceAcceptedNumAfterWA':after_practice,'correctedOriginalAssumption':'Compare acceptedNum before and after; do not assume zero after previous AC cases.'}});save()
 subprocess.run([sys.executable,str(ROOT/'permission_probe.py')],check=True,env={**os.environ,'PYTHONDONTWRITEBYTECODE':'1'},timeout=160)
 permission=json.loads((ROOT/'permission-results.json').read_text());rows.append({'case':'real-http-permission-probe','status':'PASS' if all(r['status']=='PASS' for r in permission) else 'FAIL','checks':len(permission)});save()
 s.ok=all(r['status']=='PASS' for r in rows)
except Exception as e:
 rows.append({'case':'diagnostic-run-error','status':'FAIL','errorType':type(e).__name__,'error':str(e)[:300],'tracebackLocation':traceback.extract_tb(e.__traceback__)[-1].lineno});save();raise
finally:s.cleanup()
