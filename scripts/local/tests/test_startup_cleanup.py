"""Orchestration failure contracts only; these mocks are not real startup acceptance."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('startup', Path(__file__).resolve().parents[1] / 'start-stack.py')
startup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(startup)


class CleanupContracts(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def runtime(self):
        result = startup.PrivateIsolation(self.root / 'vm', self.root, lambda *_a, **_k: None)
        result.command = Mock()
        result.attempted = True
        return result

    def test_failed_vm_stop_never_deletes_its_live_disk(self):
        runtime = self.runtime()
        runtime.command.side_effect = [json.dumps({'name': 'codeoj', 'status': 'Running'}),
                                       subprocess.CalledProcessError(1, 'stop')]
        with self.assertRaises(subprocess.CalledProcessError):
            runtime.cleanup()
        self.assertFalse(any('delete' in [str(v) for v in call.args[0]] for call in runtime.command.call_args_list))
        self.assertEqual(runtime.env['LIMA_HOME'], str(runtime.home))

    def test_stopped_vm_is_deleted_and_removal_is_checked(self):
        runtime = self.runtime()
        runtime.command.side_effect = [json.dumps({'name': 'codeoj', 'status': 'Stopped'}), None, '']
        self.assertTrue(runtime.cleanup()['vmDeleted'])
        args = [call.args[0] for call in runtime.command.call_args_list]
        self.assertFalse(any('stop' in a for a in args))
        self.assertTrue(any('delete' in a for a in args))
        self.assertEqual(args[-1][1:], ['list', '--json'])

    def test_remaining_vm_is_a_cleanup_failure(self):
        runtime = self.runtime()
        row = json.dumps({'name': 'codeoj', 'status': 'Stopped'})
        runtime.command.side_effect = [row, None, row]
        with self.assertRaisesRegex(RuntimeError, 'OWNED_VM_CLEANUP_INCOMPLETE'):
            runtime.cleanup()

    def delivery(self):
        result = startup.Delivery.__new__(startup.Delivery)
        result.args = SimpleNamespace(mode='verify')
        result.root = self.root / 'private'
        result.root.mkdir()
        (result.root / 'private-config').write_text('retained control data')
        result.evidence = self.root
        result.app = Mock()
        result.app.evidence = self.root / 'application-evidence'
        result.app.evidence.mkdir()
        result.app.work = result.root / 'application-data'
        result.runtime = Mock()
        result.runtime.cleanup.return_value = {'vmDeleted': True}
        result.passed = True
        result.events = []
        result.event = lambda *_a, **_k: None
        return result

    def test_application_stop_keeps_journal_until_physical_vm_cleanup(self):
        app = startup.Application.__new__(startup.Application)
        app.work = self.root / 'application-data'
        app.work.mkdir()
        journal = app.work / 'execution-journal'
        journal.write_text('active physical identity')
        app.evidence = self.root
        app.procs = {}; app.logs = {}; app.ports = {}; app.secret = {}
        app.events = []; app.env = {}; app.runtime_info = {'archives': {}}; app.ok = True
        app.event = lambda *_a, **_k: None
        app.cleanup()
        self.assertEqual(journal.read_text(), 'active physical identity')
        self.assertFalse(json.loads((self.root / 'run.json').read_text())['temporaryDataRemoved'])

    def test_proxy_credentials_are_removed_from_archived_logs(self):
        path = self.root / 'engine.log'
        path.write_text('http://user:placeholder@proxy.example:3128 https://host.lima.internal:7897')
        startup.sanitize_proxy_logs(self.root)
        self.assertNotIn('user:placeholder', path.read_text())
        self.assertIn('[REDACTED]@proxy.example:3128', path.read_text())
        self.assertIn('https://host.lima.internal:7897', path.read_text())

    def test_failed_vm_cleanup_retains_private_control_directory(self):
        delivery = self.delivery()
        delivery.runtime.cleanup.side_effect = OSError('engine unavailable')
        self.assertFalse(delivery.cleanup())
        self.assertTrue((delivery.root / 'private-config').exists())
        facts = json.loads((self.root / 'delivery.json').read_text())
        self.assertFalse(facts['passed'])
        self.assertFalse(facts['cleanup']['privateRootRemoved'])

    def test_failed_process_cleanup_still_attempts_vm_cleanup_and_retains_data(self):
        delivery = self.delivery()
        delivery.app.cleanup.side_effect = RuntimeError('still alive')
        self.assertFalse(delivery.cleanup())
        delivery.runtime.cleanup.assert_called_once()
        self.assertTrue(delivery.root.exists())

    def test_success_removes_only_owned_private_directory(self):
        delivery = self.delivery()
        unrelated = self.root / 'unrelated'
        unrelated.write_text('keep')
        self.assertTrue(delivery.cleanup())
        self.assertFalse(delivery.root.exists())
        self.assertEqual(unrelated.read_text(), 'keep')
        self.assertTrue(json.loads((self.root / 'delivery.json').read_text())['passed'])


class StartupFailureAndProxyContracts(unittest.TestCase):
    def test_failure_after_ready_is_not_reported_as_passed(self):
        fake = SimpleNamespace(passed=True, event=Mock(), start=Mock(side_effect=RuntimeError('SERVICE_EXITED')),
                               cleanup=Mock(return_value=True))
        with patch.object(startup, 'preflight', return_value='/java8'), patch.object(startup, 'Delivery', return_value=fake), \
             patch.object(startup.sys, 'argv', ['start-stack.py', 'serve']), patch.object(startup.signal, 'signal'):
            self.assertEqual(startup.main(), 1)
        self.assertFalse(fake.passed)
        fake.cleanup.assert_called_once()

    def test_loopback_proxy_is_translated_without_mutating_host_environment(self):
        helper = startup.module('proxy_contract', 'private-isolation.py')
        env = {'HTTP_PROXY': 'http://user:placeholder@127.0.0.1:7897', 'HTTPS_PROXY': 'http://[::1]:7897'}
        result = helper.proxies_for_vm(env)
        self.assertEqual(result['http-proxy'], 'http://user:placeholder@host.lima.internal:7897')
        self.assertEqual(result['https-proxy'], 'http://host.lima.internal:7897')
        self.assertEqual(env['HTTPS_PROXY'], 'http://[::1]:7897')

    def test_remote_proxy_and_no_proxy_rules_are_preserved(self):
        helper = startup.module('proxy_contract', 'private-isolation.py')
        result = helper.proxies_for_vm({'http_proxy': 'http://proxy.example:3128', 'NO_PROXY': '.example.test'})
        self.assertEqual(result['http-proxy'], 'http://proxy.example:3128')
        self.assertIn('.example.test', result['no-proxy'])
        self.assertIn('127.0.0.1', result['no-proxy'])

    def test_non_http_proxy_is_rejected(self):
        helper = startup.module('proxy_contract', 'private-isolation.py')
        with self.assertRaisesRegex(ValueError, 'HTTP_PROXY_URL_REQUIRED'):
            helper.proxies_for_vm({'HTTP_PROXY': 'file:///tmp/proxy'})


class ScoreboardWireContract(unittest.TestCase):
    def test_jackson_long_string_zero_is_ready(self):
        self.assertTrue(startup.scoreboard_ready({'entries': [{'solvedCount': 1}], 'projectionPendingCount': '0'}))

    def test_pending_projection_is_not_ready(self):
        self.assertFalse(startup.scoreboard_ready({'entries': [{'solvedCount': 1}], 'projectionPendingCount': '1'}))

    def test_unknown_counter_does_not_become_zero(self):
        with self.assertRaises(TypeError):
            startup.scoreboard_ready({'entries': [{'solvedCount': 1}], 'projectionPendingCount': None})


if __name__ == '__main__':
    unittest.main()
