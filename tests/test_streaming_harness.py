"""Command correlation checks without launching a Minecraft client."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from test_streaming_handoff import Client


class StreamingHarnessTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.client = Client.__new__(Client)
        self.client.control = Path(self.temp.name)
        self.client.process = Mock()
        self.client.process.poll.return_value = None

    def status(self, identity, phase):
        (self.client.control / 'status.json').write_text(json.dumps(
            dict(commandId=identity, phase=phase, detail='ownership changed')), encoding='utf-8')

    def test_new_command_ignores_previous_error(self):
        self.status('old', 'ERROR')
        with patch('test_streaming_handoff.time.sleep', side_effect=lambda _: self.status('new', 'IMPORTED')):
            result = self.client.wait(lambda state: state['phase'] == 'IMPORTED', command_id='new')
        self.assertEqual(result['commandId'], 'new')

    def test_matching_error_still_fails(self):
        self.status('new', 'ERROR')
        with self.assertRaisesRegex(RuntimeError, 'ownership changed'):
            self.client.wait(lambda state: state['phase'] == 'IMPORTED', command_id='new')

    def test_expected_rejection_is_returned(self):
        self.status('new', 'ERROR')
        result = self.client.wait(lambda state: state['phase'] == 'ERROR', command_id='new')
        self.assertEqual(result['phase'], 'ERROR')

    def test_transient_status_replace_is_retried(self):
        with patch.object(Path, 'read_text', side_effect=[PermissionError('sharing violation'),
                json.dumps(dict(commandId='new', phase='IMPORTED'))]), \
                patch('test_streaming_handoff.time.sleep'):
            result = self.client.wait(lambda state: state['phase'] == 'IMPORTED', command_id='new')
        self.assertEqual(result['commandId'], 'new')


if __name__ == '__main__':
    unittest.main()
