import unittest

from fetch_public_sample import calibration_for_cog


class PublicCalibrationTest(unittest.TestCase):
    def test_already_applied_offset_is_not_applied_twice(self):
        asset = {"raster:bands": [{"scale": 0.0001, "offset": -0.1}]}
        self.assertEqual(calibration_for_cog(
            {"properties": {"earthsearch:boa_offset_applied": True}}, asset), (0.0001, 0))
        self.assertEqual(calibration_for_cog(
            {"properties": {"earthsearch:boa_offset_applied": False}}, asset), (0.0001, -0.1))
        with self.assertRaises(ValueError):
            calibration_for_cog({"properties": {}}, asset)


if __name__ == "__main__":
    unittest.main()
