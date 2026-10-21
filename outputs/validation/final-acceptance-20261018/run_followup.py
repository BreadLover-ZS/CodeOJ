import pathlib,sys,signal
ROOT=pathlib.Path(__file__).resolve().parent;sys.path.insert(0,str(ROOT))
from run_acceptance import Acceptance
import followup_cases
s=Acceptance(sys.argv[1],ROOT/'native-followup')
def stop(signum,frame):raise KeyboardInterrupt('Cleaning owned fixture')
signal.signal(signal.SIGTERM,stop)
try:
 s.verify_artifacts();s.setup_infrastructure();s.setup_services();s.accounts();followup_cases.run(s)
finally:s.cleanup()
