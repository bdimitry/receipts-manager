import unittest

from profiles import DEFAULT_OCR_PROFILE, available_profiles, resolve_profile


class OcrProfilesTests(unittest.TestCase):

    def test_default_profile_is_en_and_available(self):
        self.assertEqual(DEFAULT_OCR_PROFILE, "en")
        self.assertEqual(resolve_profile(None).name, "en")
        self.assertEqual([profile.name for profile in available_profiles()], ["en", "cyrillic", "latin"])

    def test_resolve_profile_rejects_unknown_profile(self):
        with self.assertRaises(ValueError):
            resolve_profile("unknown")


if __name__ == "__main__":
    unittest.main()
