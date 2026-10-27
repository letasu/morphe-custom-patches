import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("metadata", ROOT / "scripts/letasu/metadata.py")
metadata = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metadata)


class MetadataTest(unittest.TestCase):
    def setUp(self):
        self.data = json.loads((ROOT / "patches-bundle.json").read_text(encoding="utf-8"))

    def test_current_manifest(self):
        metadata.validate(self.data)

    def test_timezone_offsets_rejected(self):
        for suffix in ("+09:00", "Z", "-01:00"):
            with self.subTest(suffix=suffix), self.assertRaises(ValueError):
                metadata.validate({**self.data, "created_at": "2026-10-27T12:05:39" + suffix})

    def test_version_url_mismatch_rejected(self):
        with self.assertRaises(ValueError):
            metadata.validate({**self.data, "version": "0.0.0"})

    def test_empty_signature_rejected(self):
        with self.assertRaises(ValueError):
            metadata.validate({**self.data, "signature_download_url": ""})


if __name__ == "__main__":
    unittest.main()
