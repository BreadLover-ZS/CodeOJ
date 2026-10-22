/* Local static UI + transparent API proxy. No fabricated users, sessions or responses. */
const fs = require('fs'), path = require('path'), http = require('http');
const [dist, port, target] = process.argv.slice(2);
const root = fs.realpathSync(dist), gateway = new URL(target);
if (gateway.hostname !== '127.0.0.1') throw new Error('LOOPBACK_GATEWAY_REQUIRED');
const types = {'.html':'text/html; charset=utf-8','.js':'application/javascript','.css':'text/css','.json':'application/json','.svg':'image/svg+xml','.png':'image/png','.ttf':'font/ttf','.woff':'font/woff','.woff2':'font/woff2','.ico':'image/x-icon'};
const server = http.createServer((req,res) => {
  if (req.url.startsWith('/api/')) {
    const proxy = http.request({hostname:'127.0.0.1', port:gateway.port, path:req.url, method:req.method,
                               headers:{...req.headers, host:gateway.host}}, upstream => {
      res.writeHead(upstream.statusCode, upstream.headers); upstream.pipe(res);
    });
    proxy.on('error', () => {if (!res.headersSent) res.writeHead(502, {'Content-Type':'application/json'}); res.end('{"message":"LOCAL_GATEWAY_UNAVAILABLE"}');});
    req.on('aborted', () => proxy.destroy()); req.pipe(proxy); return;
  }
  let requested;
  try {requested = path.resolve(root, '.' + decodeURIComponent(new URL(req.url, 'http://localhost').pathname));}
  catch {res.writeHead(400); res.end(); return;}
  if (!requested.startsWith(root + path.sep) && requested !== root) {res.writeHead(403); res.end(); return;}
  let file = requested;
  if (!fs.existsSync(file) || !fs.statSync(file).isFile()) file = path.join(root,'index.html');
  if (!fs.realpathSync(file).startsWith(root + path.sep)) {res.writeHead(403); res.end(); return;}
  res.setHeader('Content-Type',types[path.extname(file)] || 'application/octet-stream');
  res.setHeader('Cache-Control','no-store');
  if (req.method === 'HEAD') {res.end(); return;}
  fs.createReadStream(file).on('error', () => {res.destroy();}).pipe(res);
});
server.listen(Number(port),'127.0.0.1', () => process.stdout.write('LOCAL_FRONTEND_READY\n'));
process.on('SIGTERM', () => server.close(() => process.exit(0)));
process.on('SIGINT', () => server.close(() => process.exit(0)));
