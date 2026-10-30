import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('release', Path(__file__).parents[1] / 'scripts/release.py')
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)

class VersionTests(unittest.TestCase):
    def test_version_code_orders_releases(self):
        self.assertEqual(release.version_code('0.1.0'), 1000)
        self.assertLess(release.version_code('1.2.999'), release.version_code('1.3.0'))
        self.assertLess(release.version_code('1.999.999'), release.version_code('2.0.0'))
    def test_rejects_ambiguous_or_invalid_versions(self):
        for value in ['v0.1.0', '01.2.3', '0.0.0', '1.1000.0', '2148.0.0', '1.2.3;echo x', '../1.2.3', '1.2.3-rc1']:
            with self.subTest(value=value), self.assertRaises(ValueError):
                release.version_code(value)

if __name__ == '__main__':
    unittest.main()
