import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.rehearse_java_workspace import runtime_environment, workspace


class JavaWorkspaceTests(unittest.TestCase):
    def test_child_environment_excludes_credentials_and_configuration_overrides(self):
        environment = {'PATH': '/runtime/bin', 'JAVA_HOME': '/runtime/java', 'LANG': 'en_US.UTF-8',
                       'POSTGRES_PASSWORD': 'sentinel-admin', 'PUBLISH_PASSWORD': 'sentinel-publisher',
                       'SPRING_APPLICATION_JSON': 'sentinel-override', 'SPRING_CONFIG_LOCATION': '/private/config',
                       'JAVA_TOOL_OPTIONS': '-Dsentinel=true', 'JDK_JAVA_OPTIONS': '-Dsentinel=true',
                       '_JAVA_OPTIONS': '-Dsentinel=true', 'HTTP_PROXY': 'http://example.invalid',
                       'RUNREVIQ_AUTH_CONFIG_PATH': '/private/auth'}
        with patch.dict('os.environ', environment, clear=True):
            self.assertEqual(runtime_environment(), {k: environment[k] for k in ('PATH','JAVA_HOME','LANG')})

    def test_non_disposable_database_rejected_before_credentials_or_process(self):
        with patch('scripts.rehearse_java_workspace.credentials') as credentials:
            with self.assertRaises(ValueError):
                with workspace('opslens', Path('/unused')):
                    self.fail('Must not start')
            credentials.assert_not_called()
