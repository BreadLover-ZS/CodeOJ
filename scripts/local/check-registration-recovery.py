#!/usr/bin/env python3
"""Real disposable MySQL/Redis/MQ registration recovery. Fault fixtures are explicit;
no user code execution, existing DB access, global service changes or load claims.
"""
import argparse
import datetime as dt
import hashlib
import importlib.util
import json
from pathlib import Path
import secrets
import signal
import socket
import time
import uuid

spec = importlib.util.spec_from_file_location('registration_native', Path(__file__).with_name('run-native-stack.py'))
native = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)


class RedisError(RuntimeError):
    pass


def read_resp(stream):
    kind = stream.read(1)
    line = stream.readline().rstrip(b'\r\n')
    if kind == b'+': return line.decode()
    if kind == b'-': raise RedisError(line.decode(errors='replace'))
    if kind == b':': return int(line)
    if kind == b'$':
        n = int(line)
        if n < 0: return None
        data = stream.read(n)
        if len(data) != n or stream.read(2) != b'\r\n': raise RedisError('SHORT_RESPONSE')
        return data
    if kind == b'*': return [read_resp(stream) for _ in range(int(line))]
    raise RedisError('INVALID_RESPONSE')


class RecoveryStack(native.Stack):
    def redis(self, *args):
        def send(stream, parts):
            encoded = [p if isinstance(p, bytes) else str(p).encode() for p in parts]
            stream.write(b'*' + str(len(encoded)).encode() + b'\r\n' + b''.join(b'$'+str(len(p)).encode()+b'\r\n'+p+b'\r\n' for p in encoded))
            stream.flush()
            return read_resp(stream)
        with socket.create_connection(('127.0.0.1', self.ports['redis']), timeout=10) as sock:
            with sock.makefile('rwb') as stream:
                assert send(stream, ['AUTH', self.secret['redis']]) == 'OK'
                assert send(stream, ['SELECT', 1]) == 'OK'  # Same private database as the application.
                return send(stream, args)

    def keys(self, contest):
        return ['contest:registration:{' + contest + '}:' + suffix for suffix in ['meta', 'stock', 'users', 'ledger', 'pending']]

    def lua(self, contest, args, marker=None):
        source = (native.REPO / 'codeoj-backend/codeoj-backend-question-service/src/main/resources/lua/contest-registration.lua').read_text()
        if marker:
            assert marker in source
            source = source.replace(marker, marker + "\n error('CONTROLLED_REAL_REDIS_FAULT')")
        raw = self.redis('EVAL', source, 5, *self.keys(contest), *args)
        return json.loads(raw)

    def sql_string(self, value):
        # Values come from this fixture and Lua; use a hex literal, never shell interpolation.
        return "CONVERT(0x" + str(value).encode().hex() + " USING utf8mb4)"

    def view(self, cid):
        return self.api('/contests/' + cid)

    def contest(self, capacity=2):
        now = dt.datetime.now(dt.timezone.utc)
        at = lambda n: (now + dt.timedelta(seconds=n)).isoformat(timespec='microseconds').replace('+00:00', 'Z')
        draft = {'title': 'Registration recovery fixture', 'contestType': 'PRIVATE', 'capacity': capacity,
                 'registrationOpenAt': at(3), 'registrationCloseAt': at(900), 'startAt': at(930), 'endAt': at(1000),
                 'problems': [{'problemCode': 'A', 'title': 'Never executed', 'content': 'Registration-only validation.',
                               'limits': {'timeLimit': 1000, 'memoryLimit': 131072}, 'cases': [{'input': '1', 'output': '1'}]}]}
        if capacity > 10000:
            status, body = self.api('/contests', 'POST', draft, allow_error=True)
            assert status == 400 and body['data'] is None
            return None
        c = self.api('/contests', 'POST', draft)
        cid = c['id']
        self.api('/contests/'+cid+'/publish', 'POST', {'expectedVersion': c['version']})
        self.api('/contests/'+cid+'/registration-admin/prepare', 'POST', {'expectedVersion': self.view(cid)['version'], 'reason': 'Disposable recovery fixture'})
        self.api('/contests/'+cid+'/registration-admin/reconcile', 'POST', {'expectedVersion': self.view(cid)['version'], 'reason': 'Initialize private ledger'})
        self.wait('question', lambda: dt.datetime.now(dt.timezone.utc) >= now + dt.timedelta(seconds=3), 10)
        return cid

    def reserve(self, cid, actor, marker=None, minimum=0):
        version = int(self.sql('SELECT publishedVersion FROM contest WHERE id='+cid))
        epoch = int(self.sql('SELECT registrationEpoch FROM contest WHERE id='+cid))
        request = str(7000000000000000000 + secrets.randbelow(1000000000000))
        event = str(uuid.uuid4())
        key = 'real-recovery-' + actor
        digest = hashlib.sha256(json.dumps({'type': 'REGISTRATION', 'contestId': int(cid), 'publishedVersion': version}, separators=(',', ':')).encode()).hexdigest()
        args = ['reserve', cid, version, epoch, self.state[actor+'Id'], request, event, key, digest,
                int(self.sql('SELECT FLOOR(UNIX_TIMESTAMP(UTC_TIMESTAMP(6))*1000)')), 2000, minimum]
        if marker:
            try: self.lua(cid, args, marker)
            except RedisError as error: assert 'CONTROLLED_REAL_REDIS_FAULT' in str(error)
            else: raise AssertionError('Injected failure not reached')
            return {'requestId': request, 'eventId': event, 'requestKey': key}
        reply = self.lua(cid, args)
        assert reply['code'] == 'OK', reply['code']
        return reply['reservation']

    def row(self, r, state, marker):
        reserved = dt.datetime.fromtimestamp(int(r['reservedAtMicros']) // 1000000, dt.timezone.utc).replace(microsecond=int(r['reservedAtMicros']) % 1000000).strftime('%Y-%m-%d %H:%M:%S.%f')
        values = [r['requestId'], r['contestId'], r['publishedVersion'], r['userId'], r['eventId'], r['requestKey'], r['requestHash'], reserved, state, marker, reserved, r['epoch'], r['sequence']]
        columns = 'id,contestId,publishedVersion,userId,eventId,requestKey,requestHash,reservedAt,status,reclaimState,createdAt,reservationEpoch,reservationSequence'
        return 'INSERT INTO contest_registration_request('+columns+',stateVersion) VALUES('+','.join(self.sql_string(v) for v in values)+',0);'

    def confirmed(self, cid, actor):
        return self.wait('question', lambda: (lambda r: r if r['status'] == 'CONFIRMED' else None)(self.api('/contests/'+cid+'/registrations/me', actor=actor)), 25)

    def exercise(self):
        self.wait('gateway', lambda: self.http('http://127.0.0.1:'+str(self.ports['gateway'])+'/api/contests')[1].get('code') == 0, 45)
        self.accounts()
        assert self.sql("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='codeoj' AND table_name='contest_registration_request' AND index_name='uk_registration_sequence'") == '3'
        self.event('REAL_MYSQL_V005_AND_UNIQUE_INDEX_PASSED')

        # Real APIs, real Outbox relay and real consumer, no manual confirmation.
        cid = self.contest(1)
        accepted = self.api('/contests/'+cid+'/registrations', 'POST', actor='participant', key='real-normal-registration')
        original = self.confirmed(cid, 'participant')
        assert accepted['requestId'] == original['requestId']
        assert self.sql('SELECT reservationSequence FROM contest_registration_request WHERE id='+original['requestId']) == '1'
        self.event('REAL_HTTP_MQ_REGISTRATION_AND_ORDINAL_PASSED')

        # Stop the manager so maintenance cannot erase the intended fault window.
        cid = self.contest(2)
        self.stop('question')
        early = self.reserve(cid, 'participant')
        snapshot = {k: self.redis('DUMP', k) for k in self.keys(cid)}
        b = self.reserve(cid, 'admin')
        # Explicit durable-confirmed fault fixture in real InnoDB, not a claimed consumer result.
        self.sql('START TRANSACTION;'+self.row(b, 'CONFIRMED', 'COMPLETE')+'UPDATE contest SET remainingCapacity=remainingCapacity-1 WHERE id='+cid+';INSERT INTO contest_registration(id,contestId,userId,requestId,confirmedAt,status) VALUES('+','.join([b['requestId'], cid, b['userId'], b['requestId'], 'UTC_TIMESTAMP(6)', "'CONFIRMED'"])+');COMMIT;')
        self.lua(cid, ['finish', b['eventId'], b['requestId'], b['userId'], b['publishedVersion'], b['epoch'], 'CONFIRMED'])
        def restore():
            self.redis('DEL', *self.keys(cid))
            for key, value in snapshot.items():
                if value is not None: self.redis('RESTORE', key, 0, value)
        restore()
        late = self.reserve(cid, 'observer', minimum=0)  # Simulates a stale DB high-water sample.
        assert late['sequence'] == b['sequence']
        try: self.sql(self.row(late, 'PROCESSING', 'RESERVED'))
        except RuntimeError as error: assert '1062' in str(error)
        else: raise AssertionError('Real MySQL ordinal collision was accepted')
        restore()
        self.restart('question')
        self.wait('question', lambda: self.sql('SELECT registrationIssue FROM contest WHERE id='+cid) == 'LEDGER_ROLLBACK_DETECTED', 25)
        status, _ = self.api('/contests/'+cid+'/registrations', 'POST', actor='observer', key='real-rollback-late', allow_error=True)
        assert status in (409, 503)
        assert self.sql('SELECT COUNT(*) FROM contest_registration WHERE contestId='+cid) == '1'
        assert self.sql('SELECT COUNT(*) FROM contest_registration_request WHERE contestId='+cid) == '1'
        assert json.loads(self.redis('HGET', self.keys(cid)[3], early['eventId']))['requestId'] == early['requestId']
        self.event('REAL_OLD_SNAPSHOT_FENCE_AND_MYSQL_1062_PASSED')

        cid = self.contest(1)
        self.stop('question')
        partial = self.reserve(cid, 'participant', '-- reserve.write.stock')
        assert self.redis('HEXISTS', self.keys(cid)[0], 'mutation') == 1
        self.restart('question')
        recovered = self.confirmed(cid, 'participant')
        assert recovered['requestId'] == partial['requestId']
        assert self.redis('HEXISTS', self.keys(cid)[0], 'mutation') == 0
        assert self.redis('GET', self.keys(cid)[1]) == b'0'
        assert self.api('/contests/'+cid+'/registrations', 'POST', actor='participant', key=partial['requestKey'])['requestId'] == partial['requestId']
        self.event('REAL_LUA_PARTIAL_RESERVE_PROCESS_RESTART_AND_MQ_CONFIRM_PASSED')

        cid = self.contest(1)
        self.stop('question')
        r = self.reserve(cid, 'participant')
        self.sql(self.row(r, 'FAILED', 'RELEASE_PENDING'))
        try: self.lua(cid, ['finish', r['eventId'], r['requestId'], r['userId'], r['publishedVersion'], r['epoch'], 'RELEASED'], '-- finish.write.stock')
        except RedisError as error: assert 'CONTROLLED_REAL_REDIS_FAULT' in str(error)
        else: raise AssertionError('Release fault not reached')
        self.restart('question')
        self.wait('question', lambda: self.sql('SELECT reclaimState FROM contest_registration_request WHERE id='+r['requestId']) == 'RELEASED', 25)
        assert self.redis('GET', self.keys(cid)[1]) == b'1'
        self.api('/contests/'+cid+'/registrations', 'POST', actor='observer', key='real-after-recovered-release')
        self.confirmed(cid, 'observer')
        assert self.redis('GET', self.keys(cid)[1]) == b'0'
        self.event('REAL_INTERRUPTED_RELEASE_AND_SINGLE_RESOLD_SLOT_PASSED')

        cid = self.contest(2)
        self.api('/contests/'+cid+'/registrations', 'POST', actor='participant', key='real-legacy-original')
        original = self.confirmed(cid, 'participant')
        self.stop('question')
        entries = self.redis('HGETALL', self.keys(cid)[3])
        for i in range(0, len(entries), 2):
            row = json.loads(entries[i+1]);row.pop('sequence')
            self.redis('HSET', self.keys(cid)[3], entries[i], json.dumps(row, separators=(',', ':')))
        self.redis('HDEL', self.keys(cid)[0], 'format')
        self.sql('UPDATE contest_registration_request SET reservationSequence=NULL WHERE contestId='+cid)
        self.restart('question')
        self.wait('question', lambda: self.sql('SELECT registrationIssue FROM contest WHERE id='+cid) == 'LEDGER_FORMAT_UPGRADE_REQUIRED', 25)
        self.api('/contests/'+cid+'/registration-admin/reconcile', 'POST', {'expectedVersion': self.view(cid)['version'], 'reason': 'Closed-gate real legacy upgrade'})
        assert self.redis('HGET', self.keys(cid)[0], 'format') == b'2'
        assert self.api('/contests/'+cid+'/registrations/me', actor='participant')['requestId'] == original['requestId']
        self.api('/contests/'+cid+'/registrations', 'POST', actor='observer', key='real-after-legacy-upgrade')
        self.confirmed(cid, 'observer')
        self.event('REAL_LEGACY_IDENTITY_UPGRADE_AND_NEW_REGISTRATION_PASSED')

        self.contest(10001)
        self.event('REAL_HTTP_OVERSIZED_CAPACITY_REJECTED')
        self.wait('question', lambda: self.sql("SELECT COUNT(*) FROM contest_registration_request WHERE status='CONFIRMED' AND reclaimState<>'COMPLETE'") == '0', 25)
        self.event('REAL_TERMINAL_MARKERS_SETTLED')
        facts = {'passed': True, 'scope': 'Real private MySQL/Redis/RabbitMQ/Session/Gateway; explicit direct SQL/Redis fault setup for rollback/release; normal and restart-recovered confirmation used actual MQ consumer; no user code execution, Nacos or load test',
                 'mysqlVersion': self.sql('SELECT VERSION()'), 'requestStates': self.sql('SELECT status,reclaimState,COUNT(*) FROM contest_registration_request GROUP BY status,reclaimState').splitlines(),
                 'ordinals': self.sql('SELECT COUNT(*),COUNT(reservationSequence) FROM contest_registration_request').splitlines(),
                 'qualificationCounts': self.sql('SELECT contestId,COUNT(*) FROM contest_registration GROUP BY contestId').splitlines(),
                 'publishAttempts': self.sql("SELECT p.confirmState,COUNT(*) FROM publish_attempt p JOIN reliable_outbox o ON o.eventId=p.eventId WHERE o.eventType='REGISTRATION_CONFIRM' GROUP BY p.confirmState").splitlines(),
                 'events': self.events}
        (self.evidence/'registration-facts.json').write_text(json.dumps(facts, indent=2)+'\n')
        self.ok = True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtimes', required=True)
    parser.add_argument('--evidence', required=True)
    args = parser.parse_args()
    def interrupted(_s, _f): raise KeyboardInterrupt('Cleaning owned registration fixture')
    signal.signal(signal.SIGTERM, interrupted)
    stack = RecoveryStack(args.runtimes, args.evidence)
    try:
        stack.verify_artifacts();stack.setup_infrastructure();stack.setup_services();stack.exercise()
    finally:
        stack.cleanup()
        remaining=[]
        for name, port in stack.ports.items():
            try:
                with socket.create_connection(('127.0.0.1', port), timeout=.2): remaining.append(name)
            except OSError: pass
        (stack.evidence/'cleanup-check.json').write_text(json.dumps({'temporaryWorkRemoved':not stack.work.exists(),'remainingOpenOwnedPorts':remaining},indent=2)+'\n')
        if remaining: raise RuntimeError('OWNED_REGISTRATION_PORTS_REMAIN')


if __name__ == '__main__': main()
