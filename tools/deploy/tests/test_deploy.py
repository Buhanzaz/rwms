"""Offline tests: rendering, secrets, subprocess lifecycle and source contract alignment."""
import base64
import contextlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import configuration as cfg
import deploy


def fixture():
    values = {key: 'safe-generated-test-value-' + key + '-1234567890' for key in cfg.SECRET_KEYS}
    values.update(DOMAIN='wms.example.org', PUBLIC_IPV4='8.8.8.8', ACME_EMAIL='admin@example.org',
                  AUTH_BOOTSTRAP_ADMIN_USERNAME='admin', LLM_BASE_URL='https://api.example.org/v1',
                  LLM_MODEL='test-model', PLANNER_SYNC_ENABLED='true',
                  TASK_BOARD_WORKER_OFFLINE_LEASE_SECRET=base64.b64encode(b'x' * 36).decode())
    return values


class ConfigurationTests(unittest.TestCase):
    def test_domain_normalization_and_rejection(self):
        self.assertEqual(cfg.domain_name(' https://WMS.Example.org/ '), 'wms.example.org')
        self.assertTrue(cfg.domain_name('склад.рф').startswith('xn--'))
        for invalid in ['https://user@domain.test', 'https://wms.test/a', 'http://wms.test',
                        'wms.test:443', '1.2.3.4', '*.example.org', 'a.test;return 200;',
                        'https://wms.test/?x=y', '-bad.test', 'bad..test', 'https://wms.test:444']:
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                cfg.domain_name(invalid)

    def test_env_is_atomic_private_and_never_shell_evaluated(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / 'system.env'
            values = fixture()
            values['LLM_API_KEY'] = '''key-$HOME-$(touch /tmp/not-executed)-"quote"-'hash#'\\tail'''
            cfg.save_env(file, values)
            self.assertEqual(file.stat().st_mode & 0o777, 0o600)
            self.assertEqual(cfg.load_env(file), values)
            file.chmod(0o644)
            with self.assertRaises(ValueError):
                cfg.load_env(file)

    def test_duplicate_and_shell_assignment_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / 'system.env'
            for text in ['DOMAIN="a.test"\nDOMAIN="b.test"\n', 'X=$(uname)\n']:
                cfg.private_write(file, text)
                with self.assertRaises(ValueError):
                    cfg.load_env(file)

    def test_symlink_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'original'
            target.write_text('original')
            link = Path(directory) / 'env'
            link.symlink_to(target)
            with self.assertRaises(ValueError):
                cfg.save_env(link, fixture())
            self.assertEqual(target.read_text(), 'original')

    def test_all_services_generated_and_private(self):
        c = fixture()
        cfg.validate(c)
        core = cfg.core_compose(c, Path('/releases/test'), Path('/etc/test'), 'test')
        for name in cfg.APPS:
            self.assertIn(name, core['services'])
        for service in core['services'].values():
            for port in service.get('ports', []):
                self.assertTrue(port.startswith('127.0.0.1:'))
        self.assertEqual(core['networks']['private']['name'], cfg.NETWORK)
        self.assertNotIn('ports', core['services']['kafka'])
        for name in cfg.JAVA:
            env = core['services'][name]['environment']
            self.assertEqual(env['AUTH_ISSUER'], 'https://wms.example.org/auth')
            self.assertEqual(env['SPRING_PROFILES_ACTIVE'], 'production')
            self.assertNotIn('AUTH_DB_PASSWORD', env if name != 'auth-service' else {})
        gateway = core['services']['api-gateway-service']['environment']
        self.assertEqual(gateway['LOGISTICS_PLANNER_URL'], 'http://logistics-planner:8000')
        self.assertEqual(gateway['RWMS_GATEWAY_APPLINKS_SHA256CERTFINGERPRINTS'], cfg.worker_app_links())
        task = core['services']['task-board-service']['environment']
        self.assertTrue(task['AUTH_TOKEN_URI'].startswith('https://'))
        self.assertTrue(task['WAREHOUSE_SERVICE_INTERNAL_BASE_URL'].startswith('https://'))
        # Firebase is not used: no project, credentials file or mount reaches task-board.
        self.assertEqual(task['TASK_BOARD_FCM_ENABLED'], 'false')
        self.assertFalse({'TASK_BOARD_FCM_PROJECT_ID', 'GOOGLE_APPLICATION_CREDENTIALS'} & set(task))
        self.assertNotIn('volumes', core['services']['task-board-service'])
        self.assertNotIn('firebase', cfg.compose_json(core).lower())

    def test_worker_app_links_use_public_pinned_production_signers(self):
        with tempfile.TemporaryDirectory() as directory:
            policy = Path(directory) / 'release-trust-policy.json'
            base = {'schemaVersion': 1, 'applicationId': 'dev.buhanzaz.rwms.worker',
                    'artifactNamePrefix': 'rwms-worker', 'internalTestSignerSha256Allowlist': ['11' * 32]}
            policy.write_text(json.dumps({**base, 'productionSignerSha256Allowlist': ['0a' * 32, 'f9' * 32]}))
            self.assertEqual(cfg.worker_app_links(policy), ':'.join(['0A'] * 32) + ',' + ':'.join(['F9'] * 32))
            for broken in ({**base, 'productionSignerSha256Allowlist': []},
                           {**base, 'productionSignerSha256Allowlist': ['0A' * 32]},
                           {**base, 'productionSignerSha256Allowlist': ['0a' * 31]},
                           {**base, 'applicationId': 'dev.buhanzaz.rwms.driver', 'productionSignerSha256Allowlist': ['0a' * 32]},
                           {**base, 'schemaVersion': 2, 'productionSignerSha256Allowlist': ['0a' * 32]}):
                with self.subTest(broken=broken):
                    policy.write_text(json.dumps(broken))
                    with self.assertRaises(ValueError):
                        cfg.worker_app_links(policy)
            with self.assertRaises(ValueError):
                cfg.worker_app_links(Path(directory) / 'missing.json')
        # The checked-in policy yields the gateway format and covers the published WorkerApp signer.
        links = cfg.worker_app_links()
        self.assertRegex(links, r'^(?:[0-9A-F]{2}:){31}[0-9A-F]{2}(?:,(?:[0-9A-F]{2}:){31}[0-9A-F]{2})*$')
        release = json.loads((deploy.REPO / 'worker-download-site/release.json').read_text())
        if release['signerCertificateSha256']:
            self.assertIn(':'.join(re.findall('..', release['signerCertificateSha256'])).upper(), links.split(','))

    def test_configure_asks_neither_firebase_nor_worker_certificate(self):
        answers = iter(['wms.example.org', '8.8.8.8', 'admin@example.org', '', '', '', '', ''])
        hidden = iter(['', 'llm-test-key', 'llm-test-key', '', ''])
        prompts = []

        def reply(source):
            return lambda label='': prompts.append(label) or next(source)

        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()) as screen, \
                patch('builtins.input', reply(answers)), patch.object(deploy.getpass, 'getpass', reply(hidden)), \
                patch.object(deploy.sys, 'stdin') as stdin:
            stdin.isatty.return_value = True
            file = Path(directory) / 'system.env'
            deploy.wizard(file)
            values = cfg.load_env(file)
        self.assertIsNone(next(answers, None))
        self.assertIsNone(next(hidden, None))
        dialogue = screen.getvalue() + '\n'.join(prompts)
        for word in ('Firebase', 'FCM', 'SHA-256', 'service-account', 'fingerprint'):
            self.assertNotIn(word, dialogue)
        self.assertEqual(set(values), set(cfg.PUBLIC_KEYS + cfg.SECRET_KEYS))

    def test_auth_username_and_worker_key_obey_production_guards(self):
        c = fixture()
        auth = cfg.service_env('auth-service', c)
        self.assertNotEqual(auth['AUTH_DB_USERNAME'], 'rwms_auth')
        core = cfg.core_compose(c, Path('/release'), Path('/config'), 'test')
        self.assertEqual(auth['AUTH_DB_USERNAME'], core['services']['auth-db']['environment']['POSTGRES_USER'])
        self.assertEqual(len(base64.b64decode(c['TASK_BOARD_WORKER_OFFLINE_LEASE_SECRET'], validate=True)), 36)
        c['TASK_BOARD_WORKER_OFFLINE_LEASE_SECRET'] = 'not_valid_url_safe_' * 3
        with self.assertRaises(ValueError):
            cfg.validate(c)
        c['TASK_BOARD_WORKER_OFFLINE_LEASE_SECRET'] = base64.b64encode(b'x' * 24).decode()
        with self.assertRaises(ValueError):
            cfg.validate(c)

    def test_m2m_secrets_agree_but_are_not_shared_with_unrelated_services(self):
        c = fixture()
        auth = cfg.service_env('auth-service', c)
        for name in ('task-board', 'inventory', 'maintenance', 'logistics'):
            key = name.upper().replace('-', '_') + '_CLIENT_SECRET'
            self.assertEqual(auth[key], cfg.service_env(name + '-service', c)[key])
        planner = cfg.planner_override(c, 'test')['services']['backend']['environment']
        self.assertEqual(auth['LOGISTICS_PLANNER_CLIENT_SECRET'], planner['RWMS_CLIENT_SECRET'])
        self.assertNotIn('LOGISTICS_CLIENT_SECRET', cfg.service_env('assistant-service', c))

    def test_media_and_planner_password_encoding_and_compose_dollars(self):
        c = fixture()
        password = '''a@b:/x?$PASSWORD'"#\\and32morecharacters'''
        c['MEDIA_DB_PASSWORD'] = password
        c['PLANNER_DB_PASSWORD'] = password
        media = cfg.media_env(c)
        self.assertNotIn(password, media['MEDIA_DATABASE_URL'])
        self.assertIn('%40', media['MEDIA_DATABASE_URL'])
        self.assertEqual(media['MEDIA_MINIO_USE_SSL'], 'true')
        self.assertTrue(media['MEDIA_AUTH_JWKS_URL'].startswith('https://'))
        encoded = json.loads(cfg.compose_json({'secret': password}))['secret']
        self.assertEqual(encoded.replace('$$', '$'), password)
        planner = cfg.planner_override(c, 'test')['services']
        self.assertEqual(planner['backend']['environment']['DATABASE_URL'],
                         planner['truck-restrictions-indexer']['environment']['DATABASE_URL'])
        self.assertEqual(planner['frontend']['build']['args']['VITE_APP_BASE_PATH'], '/logistics-panel/')

    def test_nginx_maintenance_keeps_private_discovery_and_blocks_public_writes(self):
        c = fixture()
        text = cfg.nginx_config(c, True, True)
        public, private = text.split('listen ' + cfg.BRIDGE_IP + ':443 ssl;')
        public = public.split('listen ' + c['PUBLIC_IPV4'] + ':443 ssl;')[1]
        self.assertIn('location / { return 503; }', public)
        self.assertNotIn('proxy_pass', public)
        self.assertIn('location /auth/', private)
        self.assertIn('listen ' + cfg.BRIDGE_IP + ':9444 ssl;', text)
        self.assertIn('location = /auth/callback', private)
        self.assertIn('location ^~ /api/internal/ { return 404; }', text)
        self.assertIn('location ^~ /logistics-panel/api/admin/ { return 404; }', text)
        self.assertNotIn('/logistics-simulator/', text)
        self.assertNotIn('location /logistics/', text)

    def test_current_task_board_destination_guard_matches_configuration(self):
        root = deploy.REPO / 'services/task-board-service/src/main'
        source = (root / 'java/dev/buhanzaz/rwms/taskboard/config/TaskBoardProductionSafetyValidator.java').read_text()
        expected = re.findall(r'"(rwms\.[^"]+)"', source.split('List<String> requiredDestinations =')[1].split(');')[0])
        yaml = (root / 'resources/application.yaml').read_text()
        actual = re.findall(r'- (rwms\.[\w.-]+)', yaml.split('      destinations:')[1].split('  task-board:')[0])
        self.assertEqual(expected, actual)
        self.assertIn('rwms.task-board.driver-shift-owner-proof.v1', actual)

    def test_required_env_placeholders_have_effective_values(self):
        # Exact no-default ${KEY} references must be supplied to every Java service.
        c = fixture()
        for name in cfg.JAVA:
            source = (deploy.REPO / 'services' / name / 'src/main/resources/application.yaml').read_text()
            required = set(re.findall(r'\$\{([A-Z][A-Z0-9_]*)\}', source))
            env = cfg.service_env(name, c)
            self.assertEqual(required - set(env), set(), name)

    def test_canonical_topics_cover_media_and_planner(self):
        topics = cfg.kafka_topics(deploy.REPO)
        for topic in ['rwms.media.processing.v1.media-service-processing-v1.dlt',
                      'rwms.task-board.driver-shift-owner-proof.v1', 'rwms.logistics.rental-inquiry.events.v1']:
            self.assertIn(topic, topics)
        self.assertEqual(len(topics), len(set(topics)))
        self.assertEqual(sum(step[2] for step in deploy.STEPS), 100)


class RuntimeSafetyTests(unittest.TestCase):
    def test_subprocess_failure_redaction_and_heartbeat(self):
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()) as screen:
            c = fixture()
            runner = deploy.Runner(Path(directory), c, heartbeat=0.02)
            with self.assertRaises(deploy.DeployError):
                runner.run('test', [sys.executable, '-c', 'import sys,time; print(sys.argv[1],flush=True); time.sleep(.1); sys.exit(9)',
                                    c['LLM_API_KEY']], timeout=5)
            log = (Path(directory) / '001.log').read_text()
            self.assertNotIn(c['LLM_API_KEY'], log)
            self.assertIn('[REDACTED]', log)
            self.assertIn('heartbeat', screen.getvalue())
            self.assertNotIn(c['LLM_API_KEY'], screen.getvalue())
            self.assertNotIn('100%', screen.getvalue())

    def test_subprocess_timeout_is_bounded(self):
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()):
            runner = deploy.Runner(Path(directory), fixture(), heartbeat=0.1)
            started = time.monotonic()
            with self.assertRaises(deploy.DeployError):
                runner.run('timeout', [sys.executable, '-c', 'import time; time.sleep(60)'], timeout=1)
            self.assertLess(time.monotonic() - started, 5)

    def test_binary_dump_separate_from_logs(self):
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()):
            runner = deploy.Runner(Path(directory), fixture())
            dump = Path(directory) / 'db.dump'
            runner.run('dump', [sys.executable, '-c', 'import sys; sys.stdout.buffer.write(b"PGDMP\\x00\\xff"); print("diagnostic",file=sys.stderr)'], binary_output=dump)
            self.assertEqual(dump.read_bytes(), b'PGDMP\x00\xff')
            self.assertNotIn('PGDMP', (Path(directory) / '001.log').read_text())
            self.assertEqual(dump.stat().st_mode & 0o777, 0o600)

    def test_no_mutation_for_plan(self):
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()) as screen:
            file = Path(directory) / 'system.env'
            cfg.save_env(file, fixture())
            with patch.object(sys, 'argv', ['deploy.py', 'plan', '--env-file', str(file)]), \
                 patch.object(deploy.subprocess, 'Popen', side_effect=AssertionError('No processes allowed')):
                self.assertEqual(deploy.main(), 0)
            self.assertNotIn(fixture()['LLM_API_KEY'], screen.getvalue())
            self.assertIn(cfg.worker_app_links(), screen.getvalue())

    def test_update_rejects_foreign_and_changed_state(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / 'state.json'
            with patch.object(deploy, 'STATE', state), patch.object(deploy, 'capture', return_value='abcd'):
                with self.assertRaises(deploy.DeployError):
                    deploy.Deployment('update', fixture(), '')
                cfg.private_write(state, json.dumps({'schema': 1, 'repo': str(deploy.REPO), 'config_hash': 'other'}))
                with self.assertRaisesRegex(deploy.DeployError, '.env изменен'):
                    deploy.Deployment('update', fixture(), '')

    def test_stop_on_failed_stage_no_later_stage(self):
        dummy = object.__new__(deploy.Deployment)
        dummy.state = {}
        calls = []
        for name in ('preflight', 'tools', 'network', 'render', 'build', 'quiesce', 'infra', 'routing', 'migrations', 'services', 'planner_start', 'verify'):
            setattr(dummy, name, lambda n=name: calls.append(n))
        def fail():
            raise deploy.DeployError('test failure')
        dummy.tools = fail
        with patch.object(deploy, 'store_state'), contextlib.redirect_stdout(io.StringIO()) as screen:
            with self.assertRaises(deploy.DeployError):
                dummy.run()
        self.assertEqual(calls, ['preflight'])
        self.assertNotIn('100%', screen.getvalue())


if __name__ == '__main__':
    unittest.main()
