import base64
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest


@unittest.skipIf(os.name == 'nt', 'Run on Linux')
class MediaRuntimeTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('media_runtime', Path(__file__).resolve().parents[1] / 'scripts/configure-media-runtime.py')
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'shared').mkdir()
        for component in ('backend', 'media'):
            stack = self.root / 'production' / component
            stack.mkdir(parents=True)
            (stack / 'compose.yml').write_text('old compose')
            (self.root / 'source' / component).mkdir(parents=True)
            (self.root / 'source' / component / 'compose.yml').write_text('reviewed compose')
        (self.root / 'production/backend/nginx.conf').write_text('old nginx')
        (self.root / 'source/backend/nginx.conf').write_text('reviewed nginx')
        (self.root / 'production/media/mediamtx.yml').write_text('authMethod: internal')
        self.original = "DB_PASSWORD='existing-db-secret'\nJWT_SECRET='" + base64.b64encode(b'l' * 32).decode() + "'\n"
        (self.root / 'shared/backend.env').write_text(self.original)
        (self.root / 'shared/media.env').write_text('MEDIAMTX_PUBLISH_PASSWORD=publisher-secret\n')
        self.payload = {'backend_private_ip': '10.0.0.217', 'publisher_hash': 'a' * 64, 'monitor_hash': 'b' * 64, 'fingerprint': 'c' * 64}

    def test_retry_preserves_key_existing_secrets_and_original_backup(self):
        self.module.prepare('backend', self.payload, self.root, self.root / 'source')
        first = self.module.read_values(self.root / 'shared/backend.env')
        self.module.prepare('backend', self.payload, self.root, self.root / 'source')
        second = self.module.read_values(self.root / 'shared/backend.env')
        self.assertEqual(first, second)
        self.assertNotEqual(first['MEDIA_TOKEN_SECRET'], first['JWT_SECRET'])
        self.assertEqual(first['DB_PASSWORD'], 'existing-db-secret')
        self.assertEqual((self.root / 'shared/backend.env.before-media-auth').read_text(), self.original)
        self.assertEqual((self.root / 'shared/backend.env').stat().st_mode & 0o777, 0o600)
        self.assertEqual((self.root / 'shared/media-auth-tls/tls.key').stat().st_mode & 0o777, 0o600)

    def test_rejects_public_bind_and_invalid_hash_before_writing(self):
        for overrides in ({'backend_private_ip': '8.8.8.8'}, {'publisher_hash': 'not-a-hash'}):
            with self.assertRaises(ValueError):
                self.module.prepare('backend', dict(self.payload, **overrides), self.root, self.root / 'source')
            self.assertEqual((self.root / 'shared/backend.env').read_text(), self.original)

    def test_media_configuration_is_not_activated_before_backend(self):
        self.module.prepare('media', self.payload, self.root, self.root / 'source')
        self.assertEqual((self.root / 'production/media/mediamtx.yml').read_text(), 'authMethod: internal')
        values = self.module.read_values(self.root / 'shared/media.env')
        self.assertEqual(values['MEDIAMTX_PUBLISH_PASSWORD'], 'publisher-secret')
        self.assertEqual(values['MEDIA_AUTH_URL'], 'https://10.0.0.217:8082/internal/media/auth')
        self.assertEqual(values['MEDIA_AUTH_FINGERPRINT'], 'c' * 64)
