"""Exact JV15: accept during contest, then edit source before execution."""
import pathlib,sys,signal,json,datetime as dt,traceback
ROOT=pathlib.Path(__file__).resolve().parent;sys.path.insert(0,str(ROOT))
from run_acceptance import Acceptance
from extended_cases import Checks
s=Acceptance(sys.argv[1],ROOT/'native-snapshot');facts={}
def stop(signum,frame):raise KeyboardInterrupt('Cleaning owned fixture')
signal.signal(signal.SIGTERM,stop)
try:
 s.verify_artifacts();s.setup_infrastructure();s.setup_services();s.accounts();c=Checks(s);c.fixtures()
 before=c.count('SELECT acceptedNum FROM question WHERE id='+str(int(c.qid)))
 cid,draft=c.contest('exact-jv15',capacity=1,redis=False,close=8,start=11,end=70,source=c.qid);c.register(cid,'u0');frozen=c.detail(cid)['problems'][0]
 spec=s.sql('SELECT judgeSpecHash FROM contest_problem_snapshot WHERE id='+frozen['id'])
 c.stop_judges();start=dt.datetime.fromisoformat(draft['startAt'].replace('Z','+00:00'));c.wait(lambda:dt.datetime.now(dt.timezone.utc)>=start,15)
 sid=c.csubmit(cid,frozen['id'],'u0');assert s.sql('SELECT executionStatus FROM submission_execution WHERE submissionId='+str(int(sid)))=='QUEUED'
 s.api('/question/update','POST',{'id':c.qid,'title':'Edited during contest','content':'Edited after acceptance.','judgeCase':[{'input':'1 2','output':'99'}]})
 assert s.api('/contests/'+cid,actor='u0')['problems'][0]['title']=='Acceptance original sum'
 newer=c.practice();c.start_judges();c.completed(newer,'WA')
 c.wait(lambda:s.sql('SELECT executionStatus FROM submission_execution WHERE submissionId='+str(int(sid)))=='COMPLETED',30)
 result=s.api('/submissions/'+str(sid),actor='u0');assert result['judgeInfo']['verdict']=='AC'
 assert s.sql('SELECT judgeSpecHash FROM submission_execution WHERE submissionId='+str(int(sid)))==spec
 after=c.count('SELECT acceptedNum FROM question WHERE id='+str(int(c.qid)));assert before==after
 facts={'case':'source-edited-during-contest-after-acceptance','jv':['JV15'],'status':'PASS','facts':{'acceptedBeforeEditWhileContestRunning':True,'judgeStoppedUntilAfterEdit':True,'originalSnapshotTitleAndHashRetained':True,'contestVerdict':'AC','newPracticeVerdict':'WA','practiceAcceptedNumBefore':before,'practiceAcceptedNumAfter':after,'noAcceptedAtFixtureEdits':True}}
 s.ok=True
except Exception as e:
 facts={'case':'source-edited-during-contest-after-acceptance','jv':['JV15'],'status':'FAIL','errorType':type(e).__name__,'error':str(e)[:300],'line':traceback.extract_tb(e.__traceback__)[-1].lineno};raise
finally:
 (ROOT/'snapshot-results.json').write_text(json.dumps(facts,indent=2)+'\n');s.cleanup()
