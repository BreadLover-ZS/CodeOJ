"""Read-only business-source acceptance; all fault controls affect only owned fixtures."""
import sys,pathlib,importlib.util,json,os,time,signal,urllib.request,urllib.error,base64
ROOT=pathlib.Path(__file__).resolve().parent;REPO=ROOT.parents[2]
sys.path.insert(0,str(ROOT));from fault_proxy import FaultProxy
spec=importlib.util.spec_from_file_location('native',REPO/'scripts/local/run-native-stack.py');native=importlib.util.module_from_spec(spec);spec.loader.exec_module(native)
class Acceptance(native.Stack):
 def setup_services(self):
  self.proxy=FaultProxy(self.ports['amqp']);real=self.ports['amqp'];self.ports['amqp']=self.proxy.port
  try:super().setup_services()
  finally:self.ports['amqp']=real
  self.ports['amqpProxy']=self.proxy.port;self.event('REAL_AMQP_FAULT_PROXY_READY')
 def mq(self,path,method='GET',body=None):
  auth=base64.b64encode(('codeoj_fixture:'+self.secret['mq']).encode()).decode();headers={'Authorization':'Basic '+auth}
  data=None
  if body is not None:data=json.dumps(body).encode();headers['Content-Type']='application/json'
  req=urllib.request.Request('http://127.0.0.1:'+str(self.ports['management'])+'/api'+path,data=data,headers=headers,method=method)
  try:
   with urllib.request.urlopen(req,timeout=15) as r:
    raw=r.read();return r.status,json.loads(raw) if raw else {}
  except urllib.error.HTTPError as e:
   raw=e.read();return e.code,json.loads(raw) if raw else {}
 def hard_kill(self,name):
  p=self.procs[name];os.killpg(p.pid,signal.SIGKILL);p.wait(timeout=10);self.event('PROCESS_SIGKILL',service=name)
 def exercise(self):
  super().exercise();self.event('BASELINE_REAL_CHAIN_PASSED');self.ok=False
  path=ROOT/'extended_cases.py';deadline=time.monotonic()+180
  while not path.exists() and time.monotonic()<deadline:time.sleep(.5)
  spec=importlib.util.spec_from_file_location('extended',path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.run(self)
 def cleanup(self):
  try:super().cleanup()
  finally:
   if hasattr(self,'proxy'):
    (self.evidence/'amqp-fault-counts.json').write_text(json.dumps(self.proxy.counts,indent=2)+'\n');self.proxy.close()
if __name__=='__main__':
 s=Acceptance(sys.argv[1],ROOT/'native')
 def stop(signum,frame):raise KeyboardInterrupt('Cleaning owned acceptance processes')
 signal.signal(signal.SIGTERM,stop)
 try:s.verify_artifacts();s.setup_infrastructure();s.setup_services();s.exercise()
 finally:s.cleanup()
