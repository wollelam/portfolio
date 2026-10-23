#!/usr/bin/env python3
"""Bridge a private web request mailbox to an existing guarded quote-only updater.

No broker posting, mail, arbitrary command, or direct portfolio write is implemented here.
Run `poll` on the web host and install the same script for `remote` on the updater host.
"""
import argparse
import datetime as dt
import fcntl
import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import tempfile
import uuid


def now():
    return dt.datetime.now(dt.timezone.utc)


def timestamp(value):
    if value is None:
        return None
    parsed = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
    if parsed.tzinfo is None:
        raise ValueError('Timezone required')
    return parsed.astimezone(dt.timezone.utc).isoformat()


def read(path, default=None):
    if not path.exists():
        return default
    if path.stat().st_size > 65536:
        raise ValueError('Oversized bridge record')
    return json.loads(path.read_text())


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, name = tempfile.mkstemp(prefix='pending-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as output:
            json.dump(data, output)
        os.replace(name, path)
    finally:
        Path(name).unlink(missing_ok=True)


def request_id(value):
    canonical = str(uuid.UUID(value))
    if value != canonical:
        raise ValueError('Canonical request UUID required')
    return canonical


def busy(path):
    try:
        with path.open('r') as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                return False
            except BlockingIOError:
                return True
    except FileNotFoundError:
        return False


def remote_status(finance_state, receipts):
    last = read(finance_state / 'quote-last.json', {})
    status = last.get('status')
    starts = read(receipts / 'last-start.json', {})
    recent = [timestamp(value) for value in (last.get('verified_utc'), starts.get('startedAt')) if value]
    not_before = (dt.datetime.fromisoformat(max(recent)) + dt.timedelta(minutes=15)).isoformat() if recent else None
    return {
        'available': True,
        'sourceBusy': busy(finance_state / 'job.lock'),
        'requiresVerification': (finance_state / 'pending.json').exists(),
        'lastSuccessAt': timestamp(last.get('verified_utc')) if status in ('current', 'updated', 'partial') else None,
        'providerFailures': max(0, int(last.get('provider_failures', 0))),
        'lastResult': status if status in ('current', 'updated', 'partial') else None,
        'nextAllowedAt': not_before,
    }


def execute_guarded(runner, timeout=1500):
    env = os.environ.copy()
    env['PATH'] = str(Path.home() / '.local/bin') + os.pathsep + env.get('PATH', '')
    # Capture privately in memory: runner output can contain private Drive identifiers.
    process = subprocess.Popen(
        ['/usr/bin/python3', str(runner), '--quotes-only', '--apply'],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env,
        start_new_session=True)
    try:
        stdout, _ = process.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.communicate()
        return 'unconfirmed'
    try:
        result = json.loads(stdout)
    except (ValueError, TypeError):
        return 'unconfirmed'
    if result.get('error_type') == 'BlockingIOError':
        return 'busy'
    if process.returncode or result.get('status') == 'failed':
        return 'failed'
    if result.get('applied') is not True or result.get('status') not in ('current', 'updated', 'partial'):
        return 'unconfirmed'
    return 'partial' if result['status'] == 'partial' else 'complete'


def remote(finance_state, receipts, runner, requested=None, inspect=None):
    receipts.mkdir(parents=True, exist_ok=True, mode=0o700)
    if inspect:
        return {**remote_status(finance_state, receipts), 'receipt': read(receipts / (request_id(inspect) + '.json'))}
    if requested is None:
        return remote_status(finance_state, receipts)
    path = receipts / (request_id(requested) + '.json')
    with (receipts / 'worker.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return {**remote_status(finance_state, receipts), 'receipt': read(path, {'id': requested, 'state': 'busy'})}
        previous = read(path)
        if previous is not None:
            # An interrupted receipt is never authorization to rerun an upload.
            return {**remote_status(finance_state, receipts), 'receipt': previous}
        status = remote_status(finance_state, receipts)
        cooldown = status['nextAllowedAt'] and now() < dt.datetime.fromisoformat(status['nextAllowedAt'])
        if status['sourceBusy'] or status['requiresVerification'] or cooldown:
            receipt = {'id': requested, 'state': 'busy', 'finishedAt': now().isoformat()}
        else:
            write(path, {'id': requested, 'state': 'running', 'startedAt': now().isoformat()})
            write(receipts / 'last-start.json', {'startedAt': now().isoformat()})
            state = execute_guarded(runner)
            receipt = {'id': requested, 'state': state, 'finishedAt': now().isoformat()}
        write(path, receipt)
        return {**remote_status(finance_state, receipts), 'receipt': receipt}


def invoke(config, requested=None, inspect=None, heartbeat=None):
    command = ['/usr/bin/python3', config['remote_script'], 'remote',
               '--finance-state', config['finance_state'], '--receipts', config['receipts'],
               '--runner', config['runner']]
    if requested:
        command += ['--request', request_id(requested)]
    if inspect:
        command += ['--inspect-request', request_id(inspect)]
    process = subprocess.Popen(
        ['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', config['host'], shlex.join(command)],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    deadline = now() + dt.timedelta(seconds=1560 if requested else 30)
    while True:
        try:
            stdout, _ = process.communicate(timeout=15)
            if process.returncode:
                raise RuntimeError('Updater connection failed')
            return json.loads(stdout)
        except subprocess.TimeoutExpired:
            if now() >= deadline:
                process.kill()
                process.communicate()
                raise RuntimeError('Updater outcome is unconfirmed')
            if heartbeat:
                heartbeat()


def poll(directory, config_path=None, call=invoke):
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(directory, 0o700)
    with (directory / 'poll.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return
        # Administrative configuration stays outside the web container's writable mailbox.
        config = read(config_path or Path.home() / '.config/portfolio-web/price-worker.json')
        previous = read(directory / 'status.json', {})
        request = read(directory / 'request.json')
        state = {**previous, 'checkedAt': now().isoformat(), 'available': False,
                 'source': config['source'], 'schedule': config.get('schedule', 'Existing updater schedule')}

        def publish():
            state['checkedAt'] = now().isoformat()
            write(directory / 'status.json', state)

        try:
            state.update(call(config))
            if request:
                identifier = request_id(request['id'])
                if request.get('source') != config['source']:
                    state.update(requestId=identifier, state='failed')
                    publish()
                    return
                if identifier == previous.get('requestId'):
                    if previous.get('state') in ('running', 'unconfirmed'):
                        observed = call(config, inspect=identifier)
                        receipt = observed.pop('receipt', None)
                        state.update(observed)
                        state['state'] = (receipt or {}).get('state', 'unconfirmed')
                        if state['state'] == 'running' and not state.get('sourceBusy'):
                            state['state'] = 'unconfirmed'
                else:
                    age = now() - dt.datetime.fromisoformat(timestamp(request['requestedAt']))
                    state.update(requestId=identifier, state='unconfirmed')
                    publish()  # Durable before launch: a crash never causes a blind resubmission.
                    if dt.timedelta(0) <= age <= dt.timedelta(minutes=15):
                        state['state'] = 'running'
                        publish()
                        result = call(config, requested=identifier, heartbeat=publish)
                        receipt = result.pop('receipt', {})
                        state.update(result)
                        state['state'] = receipt.get('state', 'unconfirmed')
                        if state['state'] == 'running':
                            state['state'] = 'unconfirmed'
                    else:
                        state['state'] = 'failed'  # Expired before dispatch; no writer was launched.
            state.setdefault('state', 'idle')
        except (OSError, ValueError, KeyError, RuntimeError):
            state['available'] = False
            if state.get('state') == 'running':
                state['state'] = 'unconfirmed'
        publish()


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='mode', required=True)
    local = sub.add_parser('poll')
    local.add_argument('--directory', type=Path, required=True)
    local.add_argument('--config', type=Path)
    remote_parser = sub.add_parser('remote')
    for field in ('finance-state', 'receipts', 'runner'):
        remote_parser.add_argument('--' + field, type=Path, required=True)
    remote_parser.add_argument('--request')
    remote_parser.add_argument('--inspect-request')
    args = parser.parse_args()
    if args.mode == 'poll':
        poll(args.directory, args.config)
    else:
        print(json.dumps(remote(args.finance_state, args.receipts, args.runner, args.request, args.inspect_request)))


if __name__ == '__main__':
    main()
