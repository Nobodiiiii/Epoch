"""Isolated bootstrap ownership tests; never inspect or launch installed games."""
from contextlib import contextmanager
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
import launch_epoch as api


class BootstrapTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='epoch-bootstrap-tests-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        root_patch = patch.object(api, 'ROOT', self.root)
        root_patch.start()
        self.addCleanup(root_patch.stop)
        self.epoch = self.root / '.minecraft' / 'epoch'
        self.epoch.mkdir(parents=True)
        self.seed_path = self.epoch / 'control' / 'source' / 'seed-test.json'
        self.seed_path.parent.mkdir(parents=True)
        self.seed = dict(sessionId='new-session', gameDir=str(self.root / 'source'))
        self.write(self.seed_path, self.seed)

    @staticmethod
    def write(path, value):
        path.write_text(json.dumps(value), encoding='utf8')

    def streaming_source(self):
        game = Path(self.seed['gameDir'])
        binding = game / 'saves' / 'world' / 'epoch' / 'authority.json'
        binding.parent.mkdir(parents=True)
        self.write(binding, dict(schema=3, worldId='world-a'))
        self.seed['controlDir'] = str(self.seed_path.parent)
        diagnostics = dict(streamingProtocol=3, streamingWorldId='world-a', streamingBinding=str(binding))
        self.write(self.seed_path.parent / 'status.json', dict(diagnostics=diagnostics))
        return binding, diagnostics

    def test_streaming_binding_follows_running_world(self):
        binding, _ = self.streaming_source()
        self.assertEqual(api.streaming_binding(self.seed), binding.resolve())

    def test_streaming_binding_rejects_different_world(self):
        binding, _ = self.streaming_source()
        self.write(binding, dict(schema=3, worldId='world-b'))
        with self.assertRaisesRegex(ValueError, 'running world'):
            api.streaming_binding(self.seed)

    def test_streaming_binding_rejects_external_file(self):
        _, diagnostics = self.streaming_source()
        external = self.root / 'unrelated.json'
        self.write(external, dict(schema=3, worldId='world-a'))
        diagnostics['streamingBinding'] = str(external)
        self.write(self.seed_path.parent / 'status.json', dict(diagnostics=diagnostics))
        with self.assertRaisesRegex(ValueError, 'outside'):
            api.streaming_binding(self.seed)

    def test_legacy_seed_has_no_streaming_binding(self):
        self.assertIsNone(api.streaming_binding(self.seed))

    @contextmanager
    def held_process(self, command):
        process = subprocess.Popen(command, stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   text=True)
        try:
            self.assertEqual('LOCKED', process.stdout.readline().strip())
            yield process
        finally:
            if process.poll() is None:
                try:
                    process.stdin.write('\n')
                    process.stdin.flush()
                    process.wait(timeout=10)
                except (OSError, subprocess.TimeoutExpired):
                    process.kill()
                    process.wait(timeout=10)
            process.stdin.close()
            process.stdout.close()
            process.stderr.close()

    def held_host_lock(self):
        script = (
            'import msvcrt,sys;'
            'f=open(sys.argv[1],"a+b");f.seek(0);'
            'msvcrt.locking(f.fileno(),msvcrt.LK_NBLCK,1);'
            'print("LOCKED",flush=True);sys.stdin.readline();'
            'f.seek(0);msvcrt.locking(f.fileno(),msvcrt.LK_UNLCK,1);f.close()'
        )
        return self.held_process([sys.executable, '-c', script, str(self.epoch / 'host.lock')])

    def test_success_cleanup_removes_only_matching_session_error(self):
        error_path = self.seed_path.parent / 'bootstrap-error.json'
        self.write(error_path, dict(sessionId='other-session', error='preserve'))
        before = error_path.read_bytes()
        api.clear_bootstrap_error(self.seed_path, 'new-session')
        self.assertEqual(before, error_path.read_bytes())
        self.write(error_path, dict(sessionId='new-session', error='old failure'))
        api.clear_bootstrap_error(self.seed_path, 'new-session')
        self.assertFalse(error_path.exists())
        api.clear_bootstrap_error(self.seed_path, 'new-session')

    @unittest.skipUnless(os.name == 'nt', 'Windows byte-range locking contract')
    def test_other_session_with_live_lock_cannot_replace_catalog_even_error(self):
        catalog = self.epoch / 'catalog.json'
        original = b'{"sessionId":"owner","sentinel":"untouched"}'
        catalog.write_bytes(original)
        seed_before = self.seed_path.read_bytes()
        with self.held_host_lock():
            for phase in ('ERROR', 'STOPPED', 'READY'):
                with self.subTest(phase=phase):
                    self.write(self.epoch / 'host-status.json',
                               dict(sessionId='owner', phase=phase, heartbeat=0))
                    with patch.object(api, 'discover') as discover:
                        with self.assertRaises(RuntimeError):
                            api.session_catalog(self.seed_path)
                        discover.assert_not_called()
                    self.assertEqual(original, catalog.read_bytes())
                    self.assertEqual(seed_before, self.seed_path.read_bytes())

    @unittest.skipUnless(os.name == 'nt', 'Windows byte-range locking contract')
    def test_host_lock_probe_reports_release(self):
        self.assertFalse(api.host_lock_busy())
        with self.held_host_lock():
            self.assertTrue(api.host_lock_busy())
        self.assertFalse(api.host_lock_busy())

    @unittest.skipUnless(os.name == 'nt', 'Windows byte-range locking contract')
    def test_bootstrap_serializes_helpers_and_releases_after_exception(self):
        script = '''import sys
from pathlib import Path
sys.path.insert(0, sys.argv[1])
import launch_epoch as api
api.ROOT = Path(sys.argv[2])
try:
    with api.bootstrap_lock(timeout=.15):
        print("ACQUIRED")
except TimeoutError:
    print("BUSY")
'''
        def probe():
            return subprocess.run([sys.executable, '-c', script, str(SCRIPTS), str(self.root)],
                                  capture_output=True, text=True, check=True, timeout=10).stdout.strip()
        with self.assertRaisesRegex(RuntimeError, 'intentional'):
            with api.bootstrap_lock():
                self.assertEqual('BUSY', probe())
                raise RuntimeError('intentional')
        self.assertEqual('ACQUIRED', probe())

    @unittest.skipUnless(os.name == 'nt', 'Windows Java/Python locking contract')
    def test_host_probe_observes_java_filechannel_lock(self):
        java = api.JAVA17 / 'java.exe'
        if not java.is_file():
            self.skipTest('Configured Java 17 runtime unavailable')
        source = self.root / 'LockProbe.java'
        source.write_text('''import java.nio.file.*;
import java.nio.channels.*;
public class LockProbe {
    public static void main(String[] args) throws Exception {
        try (FileChannel channel = FileChannel.open(Path.of(args[0]),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            System.out.println("LOCKED");
            System.out.flush();
            System.in.read();
        }
    }
}
''', encoding='utf8')
        with self.held_process([str(java), str(source), str(self.epoch / 'host.lock')]):
            self.assertTrue(api.host_lock_busy())
        self.assertFalse(api.host_lock_busy())


if __name__ == '__main__':
    unittest.main(verbosity=2)
