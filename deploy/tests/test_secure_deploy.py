import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


@unittest.skipIf(os.name == 'nt', 'Run on Linux')
class SecureDeployTest(unittest.TestCase):
    def setUp(self):
        path = Path(__file__).resolve().parents[1] / 'scripts/secure-deploy.py'
        spec = importlib.util.spec_from_file_location('secure_deploy', path)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.request = {'image': 'ghcr.io/calmkeep22/streaming_be@sha256:' + 'a' * 64,
                        'registry_user': 'calmkeep22', 'registry_token': 'test-token'}
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.stack = self.root / 'production/backend'
        self.stack.mkdir(parents=True)
        (self.root / 'shared').mkdir()
        (self.root / 'shared/backend.env').write_text('DB_PASSWORD=test\n')
        (self.stack / 'compose.yml').write_text('services: {}\n')
        self.old = 'APP_IMAGE=ghcr.io/calmkeep22/streaming_be@sha256:' + 'b' * 64 + '\n'
        (self.stack / 'image.env').write_text(self.old)
        (self.stack / 'last-good-image.env').write_text(self.old)
        self.calls = []

    def run_fake(self, arguments, **kwargs):
        self.calls.append((arguments, kwargs))
        return subprocess.CompletedProcess(arguments, 0)

    def test_rejects_other_repository(self):
        self.request['image'] = 'ghcr.io/attacker/app@sha256:' + 'a' * 64
        with self.assertRaises(ValueError):
            self.module.deploy('backend', self.request, self.root, self.run_fake)
        self.assertFalse(self.calls)

    def test_rejects_mutable_tags(self):
        self.request['image'] = 'ghcr.io/calmkeep22/streaming_be:latest'
        with self.assertRaises(ValueError):
            self.module.validate('backend', self.request)

    def test_rejects_uploaded_shell_or_compose_fields(self):
        self.request['command'] = 'cat /etc/shadow'
        with self.assertRaises(ValueError):
            self.module.validate('backend', self.request)

    def test_registry_token_only_passed_on_stdin(self):
        self.module.deploy('backend', self.request, self.root, self.run_fake, lambda *_: True)
        arguments, kwargs = self.calls[0]
        self.assertNotIn('test-token', arguments)
        self.assertEqual(kwargs['input'], 'test-token')
        self.assertNotIn('--force-recreate', self.calls[-1][0])

    def test_success_updates_last_good_image(self):
        self.module.deploy('backend', self.request, self.root, self.run_fake, lambda *_: True)
        self.assertIn(self.request['image'], (self.stack / 'last-good-image.env').read_text())

    def test_failed_health_restores_last_good(self):
        checks = iter([False, True])
        with self.assertRaises(RuntimeError):
            self.module.deploy('backend', self.request, self.root, self.run_fake, lambda *_: next(checks))
        self.assertEqual((self.stack / 'image.env').read_text(), self.old)
        self.assertEqual((self.stack / 'last-good-image.env').read_text(), self.old)
        self.assertFalse(any('down' in call[0] or '-v' in call[0] for call in self.calls))

    def test_interrupted_candidate_does_not_replace_last_good(self):
        (self.stack / 'image.env').write_text('APP_IMAGE=unfinished\n')
        checks = iter([False, True])
        with self.assertRaises(RuntimeError):
            self.module.deploy('backend', self.request, self.root, self.run_fake, lambda *_: next(checks))
        self.assertEqual((self.stack / 'image.env').read_text(), self.old)

    def test_media_rejects_custom_image(self):
        with self.assertRaises(ValueError):
            self.module.validate('media', {'image': 'attacker/server:1.2.3', 'config': 'api: true'})


if __name__ == '__main__':
    unittest.main()
