"""Transparent local AMQP frame fault injector. No payload/authentication logging.
Frame/Basic methods: https://github.com/rabbitmq/amqp-0.9.1-spec
Only selected ACK method frames are dropped; all other bytes go to the real broker.
"""
import socket,socketserver,struct,threading,contextlib
class FaultProxy:
 def __init__(self,backend):
  self.backend=backend;self.drop_confirm=threading.Event();self.drop_judge_ack=threading.Event();self.lock=threading.Lock();self.sockets=set();self.counts={'publisherConfirmDropped':0,'judgeConsumerAckDropped':0}
  owner=self
  class Handler(socketserver.BaseRequestHandler):
   def handle(self):owner.handle(self.request)
  class Server(socketserver.ThreadingTCPServer):allow_reuse_address=True;daemon_threads=True
  self.server=Server(('127.0.0.1',0),Handler);self.port=self.server.server_address[1]
  self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
 def read(self,s,n):
  parts=[]
  while n:
   b=s.recv(n)
   if not b:raise EOFError()
   parts.append(b);n-=len(b)
  return b''.join(parts)
 def handle(self,client):
  backend=socket.create_connection(('127.0.0.1',self.backend),timeout=10);backend.settimeout(None);client.settimeout(None)
  with self.lock:self.sockets.update([client,backend])
  channels={}
  def transfer(src,dst,to_broker):
   try:
    if to_broker:dst.sendall(self.read(src,8))
    while True:
     header=self.read(src,7);kind,channel,size=struct.unpack('!BHI',header)
     if size>16*1024*1024:raise ValueError('frame budget exceeded')
     body=self.read(src,size+1)
     if body[-1]!=206:raise ValueError('invalid frame end')
     if kind==1 and size>=4:
      cls,method=struct.unpack('!HH',body[:4])
      if to_broker and cls==60 and method==20 and size>=7:
       length=body[6];channels[channel]=body[7:7+length].decode('ascii')
      drop=None
      if not to_broker and cls==60 and method in (80,120) and self.drop_confirm.is_set():drop='publisherConfirmDropped'
      if to_broker and cls==60 and method==80 and channels.get(channel)=='codeoj.judge.v1.queue' and self.drop_judge_ack.is_set():drop='judgeConsumerAckDropped'
      if drop:
       with self.lock:self.counts[drop]+=1
       continue
     dst.sendall(header+body)
   except (OSError,EOFError,ValueError):pass
   finally:
    for s in (src,dst):
     with contextlib.suppress(OSError):s.shutdown(socket.SHUT_RDWR)
     s.close()
    with self.lock:self.sockets.difference_update([src,dst])
  t=threading.Thread(target=transfer,args=(backend,client,False),daemon=True);t.start();transfer(client,backend,True);t.join(timeout=2)
 def close(self):
  self.server.shutdown();self.server.server_close()
  with self.lock:sockets=list(self.sockets)
  for s in sockets:
   with contextlib.suppress(OSError):s.shutdown(socket.SHUT_RDWR)
   s.close()
