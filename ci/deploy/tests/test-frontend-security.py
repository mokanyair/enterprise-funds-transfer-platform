#!/usr/bin/env python3
"""Offline frontend security regression suite; Python 3.9+, no application builds."""
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import runpy
import shutil
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]
EXTRACT = ROOT / 'lib/extract-frontend.py'
VALIDATE = ROOT / 'lib/validate-artifact.py'
STATE = runpy.run_path(str(ROOT / 'lib/release-state.py'))
COUNT = 0

def check(name, fn):
    global COUNT
    fn()
    COUNT += 1
    print(f'PASS {COUNT}: {name}', flush=True)

def run(args, ok=True, **kwargs):
    result = subprocess.run([str(a) for a in args], capture_output=True, text=True, **kwargs)
    if (result.returncode == 0) != ok:
        raise AssertionError(f'Unexpected exit {result.returncode}: {args}\n{result.stdout}\n{result.stderr}')
    return result.stdout

def reject(fn):
    try:
        fn()
    except (ValueError, OSError, tarfile.TarError):
        return
    raise AssertionError('Unsafe input accepted')

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def archive(path, entries):
    with tarfile.open(path, 'w:gz') as tar:
        for name, data, kind in entries:
            info = tarfile.TarInfo(name)
            info.type = kind
            if kind == tarfile.REGTYPE:
                info.size = len(data)
                tar.addfile(info, io.BytesIO(data))
            else:
                info.linkname = 'index.html'
                tar.addfile(info)

GOOD = [('index.html', b'<html>TEST</html>', tarfile.REGTYPE), ('assets/app.js', b'console.log(1)', tarfile.REGTYPE)]

with tempfile.TemporaryDirectory(prefix='frontend-security-') as temp:
    base = Path(temp)
    pkg = base / 'frontend.tar.gz'
    dest = base / 'release'
    archive(pkg, GOOD)
    def valid_extract():
        run(['python3', EXTRACT, '--archive', pkg, '--destination', dest])
        inventory = json.loads((dest / '.release-inventory.json').read_text())
        assert inventory == {name: sha(dest / name) for name, _, _ in GOOD}
        assert (dest / 'index.html').stat().st_mode & 0o777 == 0o644
        assert dest.stat().st_mode & 0o777 == 0o755
    check('valid archive and inventory', valid_extract)
    def existing():
        run(['python3', EXTRACT, '--archive', pkg, '--destination', dest], ok=False)
        assert (dest / 'index.html').read_bytes() == GOOD[0][1]
    check('existing release cannot be overwritten', existing)
    def bad_archive(entries):
        archive(base / 'bad.tar.gz', entries)
        target = base / 'rejected'
        run(['python3', EXTRACT, '--archive', base / 'bad.tar.gz', '--destination', target], ok=False)
        assert not target.exists() and not list(base.glob('.frontend-staging-*'))
        assert not (base / 'escape').exists()
    for name, entry in [('traversal', ('../escape', b'x', tarfile.REGTYPE)), ('absolute path', (str(base / 'escape'), b'x', tarfile.REGTYPE)), ('symlink', ('link', b'', tarfile.SYMTYPE)), ('hardlink', ('link', b'', tarfile.LNKTYPE)), ('FIFO', ('pipe', b'', tarfile.FIFOTYPE)), ('duplicate path', GOOD[0]), ('hidden file', ('.secret', b'x', tarfile.REGTYPE)), ('mock worker', ('mockServiceWorker.js', b'x', tarfile.REGTYPE)), ('source map', ('assets/app.js.map', b'x', tarfile.REGTYPE))]:
        check(name, lambda e=entry: bad_archive(GOOD + [e]))
    check('missing index', lambda: bad_archive(GOOD[1:]))
    check('missing JavaScript', lambda: bad_archive(GOOD[:1]))
    check('empty index', lambda: bad_archive([('index.html', b'', tarfile.REGTYPE), GOOD[1]]))
    def corrupt():
        bad = base / 'corrupt.tar.gz'; bad.write_bytes(b'not gzip')
        run(['python3', EXTRACT, '--archive', bad, '--destination', base / 'rejected'], ok=False)
        assert not (base / 'rejected').exists() and not list(base.glob('.frontend-staging-*'))
    check('corrupt archive', corrupt)
    # Exercise actual limit checks with small thresholds, avoiding large files.
    for limit, value in [('MAX_FILES', 1), ('MAX_FILE_SIZE', 1), ('MAX_TOTAL_SIZE', 20)]:
        def limited(key=limit, n=value):
            extract = runpy.run_path(str(EXTRACT))['extract']
            extract.__globals__[key] = n
            reject(lambda: extract(pkg, base / 'rejected'))
            assert not (base / 'rejected').exists() and not list(base.glob('.frontend-staging-*'))
        check(limit, limited)
    metadata = {'directory': str(dest), 'inventory_sha256': sha(dest / '.release-inventory.json'), 'sha256': sha(pkg)}
    check('valid extracted release', lambda: STATE['verify_frontend'](metadata))
    def tamper(name, change):
        path = dest / name; original = path.read_bytes() if path.exists() else None
        try:
            change(path)
            reject(lambda: STATE['verify_frontend'](metadata))
        finally:
            if path.is_symlink() or path.exists(): path.unlink()
            if original is not None: path.write_bytes(original)
    check('modified JS', lambda: tamper('assets/app.js', lambda p: p.write_bytes(b'bad')))
    check('missing JS', lambda: tamper('assets/app.js', lambda p: p.unlink()))
    check('unexpected file', lambda: tamper('extra.js', lambda p: p.write_bytes(b'x')))
    check('inventory tampering', lambda: tamper('.release-inventory.json', lambda p: p.write_bytes(b'{}')))
    def symlink(p):
        p.unlink(); p.symlink_to(dest / 'index.html')
    check('extracted symlink', lambda: tamper('assets/app.js', symlink))
    active = base / 'current'; active.symlink_to(dest)
    check('correct active frontend', lambda: STATE['verify_active_frontend']({'frontend': metadata}, active))
    active.unlink(); active.symlink_to(base)
    check('wrong active frontend', lambda: reject(lambda: STATE['verify_active_frontend']({'frontend': metadata}, active)))
    # Run the real publisher in a disposable Git repository with a fail-closed AWS mock.
    repo = base / 'repo'; repo.mkdir()
    run(['git', 'init', '-q'], cwd=repo)
    run(['git', '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '--allow-empty', '-qm', 'fixture'], cwd=repo)
    commit = run(['git', 'rev-parse', 'HEAD'], cwd=repo).strip()
    jar = repo / 'application/funds-transfer-service/target/funds-transfer-service-1.0.2-SNAPSHOT.jar'; jar.parent.mkdir(parents=True); jar.write_bytes(b'fixture jar')
    dist = repo / 'application/banking-ui/dist'; (dist / 'assets').mkdir(parents=True)
    for name, data, _ in GOOD: (dist / name).write_bytes(data)
    (dist / 'mockServiceWorker.js').write_bytes(b'mock'); (dist / 'assets/app.js.map').write_bytes(b'map')
    bin_dir = base / 'bin'; bin_dir.mkdir(); mock = bin_dir / 'aws'
    mock.write_text('''#!/usr/bin/env python3
import os, sys, pathlib, shutil
a=sys.argv[1:]
assert a[:2]==['s3api','put-object']
opts=dict(zip(a[2::2],a[3::2]))
assert opts['--if-none-match']=='*' and opts['--server-side-encryption']=='AES256'
assert opts['--bucket']=='eftabankingnvddatavdc'
key=opts['--key']; target=pathlib.Path(os.environ['MOCK_STORE']) / key
if target.exists() or target.name==os.environ.get('MOCK_FAIL'): sys.exit(1)
target.parent.mkdir(parents=True,exist_ok=True)
shutil.copyfile(opts['--body'],target)
with open(os.environ['MOCK_LOG'],'a') as f: f.write(key+'\\n')
'''); mock.chmod(0o755)
    env = dict(os.environ, PATH=str(bin_dir)+os.pathsep+os.environ['PATH'], GIT_COMMIT=commit, BUILD_NUMBER='1', ARTIFACT_BUCKET='eftabankingnvddatavdc', AWS_REGION='us-east-1', MOCK_STORE=str(base/'s3'), MOCK_LOG=str(base/'uploads'), MOCK_FAIL='')
    publisher = ROOT / 'publish-test-artifact.sh'
    prefix = f'jenkins-artifacts/banking-app/test/releases/{commit}/build-1'
    release = repo / '.ci-release'
    def publish():
        run(['bash', publisher], cwd=repo, env=env)
        assert (release/'release-prefix.txt').read_text().strip()==prefix
        assert [Path(x).name for x in (base/'uploads').read_text().splitlines()] == ['app.jar','app.jar.sha256','frontend.tar.gz','frontend.tar.gz.sha256','manifest.json']
        with tarfile.open(release/'frontend.tar.gz') as tar:
            assert not any(n.endswith('.map') or n.endswith('mockServiceWorker.js') for n in tar.getnames())
    check('immutable publication, manifest last, development files excluded', publish)
    def validate(ok=True):
        return run(['python3', VALIDATE, release, prefix, '--require-fullstack'], ok=ok)
    check('published full-stack artifact validates', lambda: validate())
    def manifest_change(fn):
        p=release/'manifest.json'; old=p.read_bytes(); data=json.loads(old)
        try:
            fn(data); p.write_text(json.dumps(data)); validate(False)
        finally: p.write_bytes(old)
    check('wrong frontend size', lambda: manifest_change(lambda m: m['artifacts']['frontend'].__setitem__('size_bytes', 1)))
    check('missing frontend metadata', lambda: manifest_change(lambda m: m['artifacts'].pop('frontend')))
    check('wrong frontend SHA', lambda: manifest_change(lambda m: m['artifacts']['frontend'].__setitem__('sha256', '0'*64)))
    check('legacy manifest rejected for new deployment', lambda: manifest_change(lambda m: m.__setitem__('schema_version', 1)))
    def corrupt_published(name, missing=False):
        p=release/name; old=p.read_bytes()
        try:
            if missing: p.unlink()
            else: p.write_bytes(bytes([old[0]^1])+old[1:])
            validate(False)
        finally: p.write_bytes(old)
    check('corrupt frontend archive checksum', lambda: corrupt_published('frontend.tar.gz'))
    check('missing frontend archive', lambda: corrupt_published('frontend.tar.gz', True))
    check('corrupt checksum file', lambda: corrupt_published('frontend.tar.gz.sha256'))
    def overwrite():
        stored=base/'s3'/prefix; before={p.name:sha(p) for p in stored.iterdir()}
        run(['bash',publisher],cwd=repo,env=env,ok=False)
        assert before=={p.name:sha(p) for p in stored.iterdir()}
        assert not (release/'release-prefix.txt').exists()
    check('duplicate publication rejected', overwrite)
    def partial():
        run(['bash',publisher],cwd=repo,env=dict(env,BUILD_NUMBER='2',MOCK_FAIL='frontend.tar.gz'),ok=False)
        assert not (base/'s3'/prefix.replace('build-1','build-2')/'manifest.json').exists()
        assert not (release/'release-prefix.txt').exists()
    check('partial publication has no completion marker', partial)
    def unsafe_publish():
        (dist/'unsafe').symlink_to(jar)
        run(['bash',publisher],cwd=repo,env=dict(env,BUILD_NUMBER='3'),ok=False)
        assert not (base/'s3'/prefix.replace('build-1','build-3')).exists()
    check('publisher rejects symlink', unsafe_publish)
print(f'ALL {COUNT} FRONTEND SECURITY AND INTEGRITY TESTS PASSED')
