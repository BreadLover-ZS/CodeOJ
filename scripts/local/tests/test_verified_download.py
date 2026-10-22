"""Download boundary contracts; synthetic HTTP responses, not external-network acceptance."""
import hashlib
import io
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch
import urllib.error

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verified_download as download


class Response(io.BytesIO):
    def __init__(self, body, declared=None, status=200, content_range=None):
        super().__init__(body)
        self.status = status
        self.headers = {'Content-Length': str(len(body) if declared is None else declared)}
        if content_range:
            self.headers['Content-Range'] = content_range


class DownloadBoundaryContracts(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.target = Path(self.temp.name) / 'asset.tgz'
        self.expected = hashlib.sha256(b'validated artifact').hexdigest()
        self.url = 'https://example.test/immutable-asset.tgz'

    def tearDown(self):
        self.temp.cleanup()

    def test_silent_short_read_is_rejected_and_full_digest_is_still_required(self):
        events = Mock()
        with patch.object(download.urllib.request, 'urlopen', side_effect=[Response(b'va', declared=18), Response(b'validated artifact')]) as fetch, patch.object(download.time, 'sleep'):
            result = download.fetch_verified(self.url, self.target, self.expected, event=events)
        self.assertEqual(result['attempts'], 2)
        self.assertEqual(self.target.read_bytes(), b'validated artifact')
        self.assertIn('codeoj_download=', fetch.call_args_list[1].args[0].full_url)
        self.assertTrue(any(call.args[0] == 'DOWNLOAD_TRANSFER_REJECTED' for call in events.call_args_list))
        self.assertFalse(self.target.with_name('asset.tgz.partial').exists())

    def test_unrequested_partial_response_is_not_published(self):
        with patch.object(download.urllib.request, 'urlopen', side_effect=[Response(b'va', status=206, content_range='bytes 0-1/18'), Response(b'validated artifact')]), patch.object(download.time, 'sleep'):
            download.fetch_verified(self.url, self.target, self.expected)
        self.assertEqual(self.target.read_bytes(), b'validated artifact')

    def test_complete_wrong_digest_fails_without_retry_or_completed_file(self):
        with patch.object(download.urllib.request, 'urlopen', return_value=Response(b'wrong artifact')) as fetch:
            with self.assertRaises(download.IntegrityError):
                download.fetch_verified(self.url, self.target, self.expected)
        self.assertEqual(fetch.call_count, 1)
        self.assertFalse(self.target.exists())
        self.assertFalse(self.target.with_name('asset.tgz.partial').exists())

    def test_timeout_can_retry_same_pinned_asset(self):
        with patch.object(download.urllib.request, 'urlopen', side_effect=[TimeoutError(), Response(b'validated artifact')]), patch.object(download.time, 'sleep'):
            self.assertEqual(download.fetch_verified(self.url, self.target, self.expected)['attempts'], 2)

    def test_transport_retry_budget_is_finite(self):
        with patch.object(download.urllib.request, 'urlopen', side_effect=TimeoutError()) as fetch, patch.object(download.time, 'sleep'):
            with self.assertRaises(TimeoutError):
                download.fetch_verified(self.url, self.target, self.expected)
        self.assertEqual(fetch.call_count, 3)
        self.assertFalse(self.target.exists())

    def test_authentication_error_is_not_retried(self):
        with patch.object(download.urllib.request, 'urlopen', side_effect=urllib.error.HTTPError(self.url, 403, 'Forbidden', {}, None)) as fetch:
            with self.assertRaises(urllib.error.HTTPError):
                download.fetch_verified(self.url, self.target, self.expected)
        self.assertEqual(fetch.call_count, 1)

    def test_existing_corrupt_asset_cannot_be_reused(self):
        self.target.write_bytes(b'wrong artifact')
        with patch.object(download.urllib.request, 'urlopen') as fetch:
            with self.assertRaises(download.IntegrityError):
                download.fetch_verified(self.url, self.target, self.expected)
        fetch.assert_not_called()

    def test_cancelled_peer_download_does_not_start_new_request(self):
        cancelled = threading.Event(); cancelled.set()
        with patch.object(download.urllib.request, 'urlopen') as fetch:
            with self.assertRaisesRegex(RuntimeError, 'DOWNLOAD_CANCELLED'):
                download.fetch_verified(self.url, self.target, self.expected, cancelled=cancelled)
        fetch.assert_not_called()


if __name__ == '__main__':
    unittest.main()
