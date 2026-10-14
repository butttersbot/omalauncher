#!/usr/bin/env python3
"""Proves oma-runner against fake-agent on this machine's loopback: python3 tools/mac-runner/test-runner.py

The spec's "First proof" list, as far as it goes without the phone and without a signed-in Claude Code.
"""
import json, os, pathlib, signal, socket, subprocess, sys, tempfile, time, urllib.error, urllib.request

HERE = pathlib.Path(__file__).resolve().parent
work = pathlib.Path(tempfile.mkdtemp(prefix='oma-runner-test-')).resolve()
root, outside = work / 'root', work / 'outside'
project = root / 'scratch'
project.mkdir(parents=True)
outside.mkdir()
(project / 'notes.md').write_text('x')
(work / 'token').write_text('k' * 40)
free = socket.socket()
free.bind(('127.0.0.1', 0))
port = free.getsockname()[1]
free.close()
base = 'http://127.0.0.1:%d' % port
environment = {**os.environ, 'OMA_RUNNER_PORT': str(port), 'OMA_RUNNER_TOKEN': str(work / 'token'), 'OMA_RUNNER_STATE': str(work / 'state'),
               'OMA_RUNNER_LOGS': str(work / 'logs'), 'OMA_RUNNER_ROOTS': str(root), 'OMA_RUNNER_AGENT': str(HERE / 'fake-agent'), 'OMA_RUNNER_BIND': '127.0.0.1',
               'OMA_RUNNER_CODEX': str(HERE / 'fake-codex'), 'OMA_RUNNER_CURSOR': str(HERE / 'fake-cursor'), 'CODEX_HOME': str(work / 'codex')}
(work / 'codex').mkdir()
(work / 'codex' / 'config.toml').write_text('[mcp_servers.paper]\nurl = "http://127.0.0.1:1/mcp"\n')
passed = 0


def start():
    process = subprocess.Popen([sys.executable, str(HERE / 'oma-runner'), 'serve'], env=environment, stdout=subprocess.PIPE, text=True)
    process.stdout.readline()
    return process


def call(method, path, body=None, token='k' * 40):
    request = urllib.request.Request(base + path, method=method, data=None if body is None else json.dumps(body).encode(),
                                     headers={'Authorization': 'Bearer ' + token} if token else {})
    try:
        with urllib.request.urlopen(request, timeout=10) as reply:
            return reply.status, json.loads(reply.read())
    except urllib.error.HTTPError as problem:
        return problem.code, json.loads(problem.read())


def stream(thread, after=0, until=('result',), limit=20):
    """Follows the stream from an event id until one of the named events; the events seen."""
    request = urllib.request.Request(base + '/threads/%s/stream?after=%d' % (thread, after), headers={'Authorization': 'Bearer ' + 'k' * 40})
    seen = []
    with urllib.request.urlopen(request, timeout=limit) as reply:
        for line in reply:
            if line.startswith(b'data: '):
                seen.append(json.loads(line[6:]))
                if seen[-1]['type'] in until:
                    return seen
    return seen


def check(what, truth):
    global passed
    if not truth:
        sys.exit('FAILED: ' + what)
    passed += 1
    print('ok  ' + what)


def status_of(thread):
    return call('GET', '/threads/' + thread)[1]['thread']['status']


def settle(thread, status, seconds=5):
    deadline = time.time() + seconds
    while time.time() < deadline and status_of(thread) != status:
        time.sleep(0.05)
    return status_of(thread) == status


def starts():
    return [json.loads(line) for line in (project / 'starts.log').read_text().splitlines()]


runner = start()
try:
    check('no key: 401', call('GET', '/health', token=None)[0] == 401)
    check('wrong key: 401 and nothing else', call('GET', '/health', token='x' * 40) == (401, {'ok': False, 'error': 'no'}))
    check('/health answers, naming the agents the Mac has', call('GET', '/health') == (200, {'ok': True, 'runner': 'oma-runner', 'threads': 0, 'working': 0, 'waiting': 0, 'kinds': ['claude', 'codex', 'cursor']}))
    check('a project outside the roots is refused', call('POST', '/threads', {'name': 'x', 'project': str(outside)})[0] == 403)
    check('a path that climbs out of a root is refused', call('POST', '/threads', {'name': 'x', 'project': str(project / '..' / '..' / 'outside')})[0] == 403)
    (root / 'link').symlink_to(outside)
    check('a link that leads out of a root is refused', call('POST', '/threads', {'name': 'x', 'project': str(root / 'link')})[0] == 403)
    code, made = call('POST', '/threads', {'name': 'Scratch', 'project': str(project)})
    thread = made['thread']['id']
    check('a thread is made under a root, home mac, in auto mode by default', code == 200 and made['thread']['home'] == 'mac' and made['thread']['status'] == 'idle'
          and made['thread']['mode'] == 'auto')

    check('the owner\'s words are taken at once', call('POST', '/threads/%s/messages' % thread, {'text': 'what files are here'})[1]['taken'] == 'now')
    events = stream(thread)
    check('they reach the agent unchanged', starts()[-1]['prompt'] == 'what files are here' and events[1] == {**events[1], 'type': 'message', 'text': 'what files are here'})
    check('the agent is run in auto mode, asking through the prompt tool', starts()[-1]['mode'] == 'auto' and starts()[-1]['prompt_tool'] == 'stdio')
    check('the thread answers what files are here', any(event['type'] == 'text' and 'notes.md' in event['text'] for event in events))
    check('it goes back to idle', settle(thread, 'idle'))
    listed = call('GET', '/threads')[1]['threads'][0]
    check('/threads gives summary, tail and when it last worked', listed['summary'] == 'what files are here' and 'notes.md' in listed['tail'] and listed['last_worked'])

    call('POST', '/threads/%s/messages' % thread, {'text': 'write the file'})
    events = stream(thread, after=listed['last_event'], until=('approval',))
    card = events[-1]
    check('in auto mode, what the classifier sends on still shows a request naming the tool and the file', card['tool'] == 'Write' and card['input']['file_path'].endswith('written.txt') and card['nonce'])
    check('the thread waits for approval', status_of(thread) == 'waiting')
    time.sleep(1.5)
    check('it is not allowed by waiting', status_of(thread) == 'waiting' and not (project / 'written.txt').exists())
    path = '/threads/%s/approvals/%s' % (thread, card['approval'])
    check('an answer without the nonce is refused', call('POST', path, {'decision': 'allow', 'nonce': 'guess'})[0] == 403 and status_of(thread) == 'waiting')
    check('an answer without the key is refused', call('POST', path, {'decision': 'allow', 'nonce': card['nonce']}, token=None)[0] == 401)
    check('deny is taken', call('POST', path, {'decision': 'deny', 'nonce': card['nonce']})[0] == 200)
    stream(thread, after=card['id'])
    check('deny refuses the write', not (project / 'written.txt').exists())
    check('the same answer cannot be used again', call('POST', path, {'decision': 'allow', 'nonce': card['nonce']})[0] == 404)

    last = call('GET', '/threads/' + thread)[1]['thread']['last_event']
    call('POST', '/threads/%s/messages' % thread, {'text': 'write the file'})
    card = stream(thread, after=last, until=('approval',))[-1]
    call('POST', '/threads/%s/approvals/%s' % (thread, card['approval']), {'decision': 'allow', 'nonce': card['nonce']})
    stream(thread, after=card['id'])
    check('allow does it', (project / 'written.txt').read_text() == 'hi')
    check('the conversation is continued, not begun again', starts()[-1]['resumed'] and not starts()[0]['resumed'])

    settle(thread, 'idle')
    last = call('GET', '/threads/' + thread)[1]['thread']['last_event']
    call('POST', '/threads/%s/messages' % thread, {'text': 'edit the file'})
    events = stream(thread, after=last)
    check('in auto mode a routine edit runs with no card', (project / 'edited.txt').read_text() == 'hi' and not any(event['type'] == 'approval' for event in events))
    (project / 'edited.txt').unlink()
    check('a mode that is not auto or manual is refused', call('POST', '/threads', {'name': 'x', 'project': str(project), 'mode': 'bypass'})[0] == 400)
    code, made = call('POST', '/threads', {'name': 'Careful', 'project': str(project), 'mode': 'manual'})
    careful = made['thread']['id']
    check('manual mode is taken on request and shown on the thread', code == 200 and made['thread']['mode'] == 'manual'
          and call('GET', '/threads/' + careful)[1]['thread']['mode'] == 'manual')
    call('POST', '/threads/%s/messages' % careful, {'text': 'edit the file'})
    card = stream(careful, until=('approval',))[-1]
    check('in manual mode the agent is run in manual mode and the same edit asks first', starts()[-1]['mode'] == 'manual' and card['tool'] == 'Write'
          and status_of(careful) == 'waiting' and not (project / 'edited.txt').exists())
    call('POST', '/threads/%s/approvals/%s' % (careful, card['approval']), {'decision': 'deny', 'nonce': card['nonce']})
    stream(careful, after=card['id'])
    check('denied, the edit is not made', not (project / 'edited.txt').exists() and settle(careful, 'idle'))

    settle(thread, 'idle')
    last = call('GET', '/threads/' + thread)[1]['thread']['last_event']
    first = call('POST', '/threads/%s/messages' % thread, {'text': 'slow one'})[1]
    second = call('POST', '/threads/%s/messages' % thread, {'text': 'two'})[1]
    check('a message sent while a task works waits its turn, and the answer says so', first == {'ok': True, 'taken': 'now'} and second == {'ok': True, 'taken': 'queued', 'position': 1})
    part = stream(thread, after=last, until=('text',))
    count = len(starts())
    # The link drops here (the stream above was closed mid-task). Pick it up from the last event seen.
    events = []
    while [event['type'] for event in events].count('result') < 2:
        events += stream(thread, after=(events or part)[-1]['id'])
    ids = [event['id'] for event in part + events]
    check('a dropped stream is picked up from the last event, nothing twice, nothing missing', ids == list(range(part[0]['id'], ids[-1] + 1)))
    check('the task did not start again', [entry['prompt'] for entry in starts()[count - 1:]] == ['slow one', 'two'])
    check('the waiting message was taken when the turn ended', [event['text'] for event in part + events if event['type'] == 'message'] == ['slow one', 'two'])
    check('the plain events list matches the stream', call('GET', '/threads/%s/events?after=%d' % (thread, part[-1]['id']))[1]['events'][:len(events)] == events)

    settle(thread, 'idle')
    last = call('GET', '/threads/' + thread)[1]['thread']['last_event']
    call('POST', '/threads/%s/messages' % thread, {'text': 'slow again'})
    call('POST', '/threads/%s/messages' % thread, {'text': 'never'})
    stream(thread, after=last, until=('text',))
    check('stop stops the task', call('POST', '/threads/%s/stop' % thread)[1]['stopped'])
    stopped = stream(thread, after=last, until=('stopped',))[-1]
    check('what was waiting its turn is dropped, what was said stays', stopped['dropped'] == 1 and settle(thread, 'idle') and starts()[-1]['prompt'] == 'slow again')

    last = call('GET', '/threads/' + thread)[1]['thread']['last_event']
    (project / 'written.txt').unlink()
    call('POST', '/threads/%s/messages' % thread, {'text': 'write the file'})
    card = stream(thread, after=last, until=('approval',))[-1]
    count = len(starts())
    runner.send_signal(signal.SIGTERM)
    runner.wait()
    # A thread saved before modes existed has none; it comes back in auto mode.
    saved = work / 'state' / 'threads' / thread / 'thread.json'
    saved.write_text(json.dumps({key: value for key, value in json.loads(saved.read_text()).items() if key != 'mode'}))
    runner = start()
    check('a thread saved without a mode is shown in auto mode', call('GET', '/threads/' + thread)[1]['thread']['mode'] == 'auto')
    check('after the runner restarts the thread is interrupted, with its history', status_of(thread) == 'interrupted' and call('GET', '/threads/%s/events' % thread)[1]['events'][card['id'] - 1] == card)
    check('the waiting request is gone and cannot be allowed', call('POST', '/threads/%s/approvals/%s' % (thread, card['approval']), {'decision': 'allow', 'nonce': card['nonce']})[0] == 404)
    time.sleep(1)
    check('the task was not started again and nothing was written', len(starts()) == count and not (project / 'written.txt').exists())
    call('POST', '/threads/%s/messages' % thread, {'text': 'what files are here'})
    check('the interrupted thread takes the owner\'s next words, in auto mode', stream(thread, after=card['id'])[-1]['type'] == 'result' and starts()[-1]['resumed']
          and starts()[-1]['mode'] == 'auto')

    # Claude Code at a limit, and who wrote what.
    settle(thread, 'idle')
    last = call('GET', '/threads/' + thread)[1]['thread']['last_event']
    call('POST', '/threads/%s/messages' % thread, {'text': 'what files are here'})
    events = stream(thread, after=last)
    texts = [event for event in events if event['type'] == 'text']
    check('the first text and the result name the model and the kind', texts[0].get('model') == 'claude-fake' and texts[0].get('kind') == 'claude'
          and events[-1].get('model') == 'claude-fake' and all('model' not in event for event in texts[1:]))
    check('/threads names the model', call('GET', '/threads/' + thread)[1]['thread']['model'] == 'claude-fake')
    settle(thread, 'idle')
    last = call('GET', '/threads/' + thread)[1]['thread']['last_event']
    call('POST', '/threads/%s/messages' % thread, {'text': 'over the limit'})
    events = stream(thread, after=last, until=('result', 'error'))
    check('a Claude usage limit ends the turn with error code "limit", not a result', events[-1]['type'] == 'error' and events[-1]['code'] == 'limit' and 'limit' in events[-1]['text'])
    check('an unknown kind is refused', call('POST', '/threads', {'name': 'x', 'project': str(project), 'kind': 'gemini'})[0] == 400)
    check('a model that is not a name is refused', call('POST', '/threads', {'name': 'x', 'project': str(project), 'model': 'a b; rm'})[0] == 400)
    check('a model that looks like a flag is refused', all(call('POST', '/threads', {'name': 'x', 'project': str(project), 'model': given})[0] == 400 for given in ('-x', '--dangerously-skip-permissions', '../x')))
    code, made = call('POST', '/threads', {'name': 'Opus', 'project': str(project), 'model': 'claude-opus-5-5'})
    call('POST', '/threads/%s/messages' % made['thread']['id'], {'text': 'hello'})
    stream(made['thread']['id'])
    check('a model asked for is passed to Claude Code', code == 200 and 'claude-opus-5-5' in (project / 'models.log').read_text())

    # Thread t91462875, 2026-10-14: a turn left a background command running; the next message's turn ended 2 s
    # after it began on an empty result Claude Code made for that command, and his words got no answer.
    for words in ('background copy', 'background copy, old'):
        code, made = call('POST', '/threads', {'name': 'Copy', 'project': str(project)})
        copy = made['thread']['id']
        call('POST', '/threads/%s/messages' % copy, {'text': words})
        events = stream(copy)
        check(words + ': background work the turn leaves running is told of when the turn ends', settle(copy, 'idle', 10)
              and any(event['type'] == 'note' and 'copy the games' in event['text'] for event in call('GET', '/threads/%s/events' % copy)[1]['events']))
        last = call('GET', '/threads/' + copy)[1]['thread']['last_event']
        asked = 'what files are here' + (', old' if 'old' in words else '')
        call('POST', '/threads/%s/messages' % copy, {'text': asked})
        events = stream(copy, after=last)
        results = [event for event in events if event['type'] == 'result']
        check(words + ': the empty result Claude Code makes for the orphaned command does not end the next turn', len(results) == 1 and results[0]['text'] == 'done'
              and any(event['type'] == 'text' and event['text'] == 'Heard: ' + asked for event in events))
        said = [event for event in events if event['type'] != 'status']
        check(words + ': the stopped command and the turn Claude Code took for it are notes', [event['type'] for event in said[:4]] == ['message', 'note', 'note', 'text']
              and "didn't finish" in said[1]['text'])

    for kind, tool in (('codex', 'Edit'), ('cursor', 'Bash')):
        (project / 'written.txt').unlink(missing_ok=True)
        code, made = call('POST', '/threads', {'name': kind.title(), 'project': str(project), 'kind': kind})
        other = made['thread']['id']
        check(kind + ': a thread of this kind is made', code == 200 and made['thread']['kind'] == kind)
        call('POST', '/threads/%s/messages' % other, {'text': 'what files are here'})
        events = stream(other)
        check(kind + ': it answers what files are here', any(event['type'] == 'text' and 'notes.md' in event['text'] for event in events) and events[-1]['type'] == 'result')
        check(kind + ': the result names its model and kind', events[-1].get('kind') == kind and events[-1].get('model') == {'codex': 'gpt-fake', 'cursor': 'cursor-auto'}[kind])
        check(kind + ': the owner\'s words reach it unchanged', starts()[-1] == {**starts()[-1], 'kind': kind, 'prompt': 'what files are here'})
        settle(other, 'idle')
        last = call('GET', '/threads/' + other)[1]['thread']['last_event']
        call('POST', '/threads/%s/messages' % other, {'text': 'write the file'})
        card = stream(other, after=last, until=('approval',))[-1]
        check(kind + ': a change shows a card and waits', card['tool'] == tool and card['nonce'] and status_of(other) == 'waiting' and not (project / 'written.txt').exists())
        path = '/threads/%s/approvals/%s' % (other, card['approval'])
        check(kind + ': a wrong nonce is refused', call('POST', path, {'decision': 'allow', 'nonce': 'guess'})[0] == 403)
        call('POST', path, {'decision': 'deny', 'nonce': card['nonce']})
        events = stream(other, after=card['id'])
        check(kind + ': deny refuses it', not (project / 'written.txt').exists() and any('Not allowed' in event.get('text', '') for event in events))
        check(kind + ': the conversation is continued', starts()[-1]['resumed'])
        settle(other, 'idle')
        last = call('GET', '/threads/' + other)[1]['thread']['last_event']
        call('POST', '/threads/%s/messages' % other, {'text': 'write the file'})
        card = stream(other, after=last, until=('approval',))[-1]
        call('POST', '/threads/%s/approvals/%s' % (other, card['approval']), {'decision': 'allow', 'nonce': card['nonce']})
        events = stream(other, after=card['id'])
        check(kind + ': allow does it', (project / 'written.txt').read_text() == 'hi')
        check(kind + ': nothing an earlier turn said is shown again', not any('REPLAYED' in event.get('text', '') for event in events))
        settle(other, 'idle')
        last = call('GET', '/threads/' + other)[1]['thread']['last_event']
        call('POST', '/threads/%s/messages' % other, {'text': 'over the limit'})
        events = stream(other, after=last, until=('result', 'error'))
        check(kind + ': a usage limit ends the turn with error code "limit"', events[-1]['type'] == 'error' and events[-1]['code'] == 'limit')
        check(kind + ': it goes back to idle', settle(other, 'idle'))
        last = call('GET', '/threads/' + other)[1]['thread']['last_event']
        call('POST', '/threads/%s/messages' % other, {'text': 'write the file'})
        call('POST', '/threads/%s/messages' % other, {'text': 'never'})
        card = stream(other, after=last, until=('approval',))[-1]
        check(kind + ': stop during a card answers at once', call('POST', '/threads/%s/stop' % other) == (200, {'ok': True, 'stopped': True}))
        events = stream(other, after=card['id'], until=('stopped',))
        check(kind + ': the card is withdrawn, what waited is dropped, and it goes idle', any(event['type'] == 'approval_result' and event['decision'] == 'withdrawn' for event in events)
              and events[-1]['dropped'] == 1 and settle(other, 'idle'))
        check(kind + ': a late allow does nothing', call('POST', '/threads/%s/approvals/%s' % (other, card['approval']), {'decision': 'allow', 'nonce': card['nonce']})[0] == 404)
    codex = [entry for entry in starts() if entry.get('kind') == 'codex'][-1]
    check('codex: manual by default, in the workspace sandbox, and asks us, not its own reviewer', codex['policy'] == 'untrusted'
          and codex['sandbox'] == 'workspace-write' and codex['reviewer'] == 'user'
          and 'approvals_reviewer="user"' in codex['arguments'] and 'notify=[]' in codex['arguments'] and 'mcp_servers.paper.enabled=false' in codex['arguments'])
    code, made = call('POST', '/threads', {'name': 'Codex auto', 'project': str(project), 'kind': 'codex', 'mode': 'auto'})
    call('POST', '/threads/%s/messages' % made['thread']['id'], {'text': 'what files are here'})
    stream(made['thread']['id'])
    check('codex: auto when asked for by name, asking only for more than the sandbox gives', made['thread']['mode'] == 'auto' and starts()[-1]['policy'] == 'on-request'
          and starts()[-1]['sandbox'] == 'workspace-write')
    code, made = call('POST', '/threads', {'name': 'Cursor auto', 'project': str(project), 'kind': 'cursor', 'mode': 'auto'})
    check('cursor: has no auto mode, so its threads are manual even when auto is asked for', code == 200 and made['thread']['mode'] == 'manual')
    cursor = [entry for entry in starts() if entry.get('kind') == 'cursor'][-1]['permissions']
    check('cursor: its own edits are denied, nothing is allowed without asking, the key is out of reach', 'Write(**)' in cursor['deny'] and cursor['allow'] == []
          and any(str(work / 'token') in rule for rule in cursor['deny']))
    log = (work / 'logs' / 'requests.log').read_text()
    check('each request is logged, and the key never is', 'POST /threads/%s/messages 200' % thread in log and 'k' * 40 not in log and ' 401' in log)
    print('\nall %d checks passed' % passed)
finally:
    runner.kill()
