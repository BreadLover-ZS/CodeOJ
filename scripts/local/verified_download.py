"""Fixed-digest download with explicit transfer completeness and atomic publication."""
import hashlib
import http.client
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


class TransferIncomplete(RuntimeError):
    pass


class IntegrityError(RuntimeError):
    pass


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def fetch_verified(url, target, expected, headers=None, event=None, cancelled=None, attempts=3):
    target = Path(target)
    emit = event or (lambda *_a, **_k: None)
    if target.exists():
        if digest(target) != expected:
            raise IntegrityError('EXISTING_ASSET_DIGEST_MISMATCH:' + target.name)
        return {'sha256': expected, 'bytes': target.stat().st_size, 'reused': True}
    partial = target.with_name(target.name + '.partial')
    for attempt in range(1, attempts + 1):
        received = 0
        status = None
        declared = None
        content_range = None
        owned = False
        if cancelled is not None and cancelled.is_set():
            raise RuntimeError('DOWNLOAD_CANCELLED')
        # On a transport retry, bypass a stale partial CDN/proxy response without
        # changing artifact identity: the pinned digest remains mandatory.
        current = url
        if attempt > 1:
            parsed = urllib.parse.urlsplit(url)
            query = parsed.query + ('&' if parsed.query else '') + 'codeoj_download=' + uuid.uuid4().hex
            current = urllib.parse.urlunsplit(parsed._replace(query=query))
        request = urllib.request.Request(current, headers={**(headers or {}), 'Cache-Control': 'no-cache', 'Accept-Encoding': 'identity'})
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                status = response.status
                raw_length = response.headers.get('Content-Length')
                declared = int(raw_length) if raw_length is not None else None
                content_range = response.headers.get('Content-Range')
                if status != 200 or content_range is not None:
                    raise TransferIncomplete('FULL_HTTP_RESPONSE_REQUIRED')
                hasher = hashlib.sha256()
                report_at = time.monotonic() + 15
                with partial.open('xb') as output:
                    owned = True
                    while block := response.read(1024 * 1024):
                        if cancelled is not None and cancelled.is_set():
                            raise RuntimeError('DOWNLOAD_CANCELLED')
                        output.write(block)
                        hasher.update(block)
                        received += len(block)
                        if time.monotonic() >= report_at:
                            emit('DOWNLOAD_PROGRESS', asset=target.name, receivedMiB=received // (1024 * 1024))
                            report_at = time.monotonic() + 15
                if declared is not None and received != declared:
                    raise TransferIncomplete('HTTP_LENGTH_MISMATCH')
                actual = hasher.hexdigest()
                if actual != expected:
                    emit('DOWNLOAD_INTEGRITY_REJECTED', asset=target.name, attempt=attempt, httpStatus=status,
                         declaredBytes=declared, receivedBytes=received, actualSha256=actual, expectedSha256=expected)
                    # A complete body with wrong content is not a transient retry.
                    raise IntegrityError('DOWNLOAD_DIGEST_MISMATCH:' + target.name)
                partial.replace(target)
                return {'sha256': expected, 'bytes': received, 'attempts': attempt}
        except (TransferIncomplete, urllib.error.URLError, TimeoutError, http.client.IncompleteRead) as error:
            # HTTPError derives from URLError. Authentication, not-found, and other
            # permanent client failures must not be retried or silently redirected.
            if isinstance(error, urllib.error.HTTPError) and error.code not in (408, 429, 500, 502, 503, 504):
                raise
            emit('DOWNLOAD_TRANSFER_REJECTED', asset=target.name, attempt=attempt, httpStatus=status,
                 declaredBytes=declared, receivedBytes=received, contentRange=content_range,
                 errorType=type(error).__name__)
            if attempt == attempts:
                raise
            time.sleep(attempt)
        finally:
            if owned:
                partial.unlink(missing_ok=True)
    raise RuntimeError('DOWNLOAD_ATTEMPTS_EXHAUSTED')
