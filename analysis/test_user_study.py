import unittest

try:
    import user_study
except ModuleNotFoundError as error:
    if error.name != "user_study":
        raise
    user_study = None


class UserStudyTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(user_study, "The user-study analysis entry point is missing")

    def test_holm_keeps_pair_order_and_is_monotonic_in_rank(self):
        actual = user_study.holm([0.04, 0.01, 0.03, 0.8])
        for value, expected in zip(actual, [0.09, 0.04, 0.09, 0.8], strict=True):
            self.assertAlmostEqual(value, expected)

    def test_friedman_and_pairwise_results_use_complete_participant_blocks(self):
        blocks = [
            {"participant_id": participant, "condition": condition, "value": index + offset}
            for offset, participant in enumerate(("P01", "P02", "P03", "P04"))
            for index, condition in enumerate(("touch", "threshold", "dtw", "camera"))
        ]
        blocks.append({"participant_id": "P05", "condition": "touch", "value": 1000})
        result = user_study.compare_repeated(blocks, "condition", "value", ("touch", "threshold", "dtw", "camera"))
        self.assertEqual(4, result["n"])
        self.assertAlmostEqual(12.0, result["friedman"]["statistic"])
        self.assertAlmostEqual(1.0, result["friedman"]["kendall_w"])
        self.assertEqual(6, len(result["pairs"]))
        self.assertAlmostEqual(-1.0, result["pairs"][0]["rank_biserial"])
        self.assertAlmostEqual(0.125, result["pairs"][0]["p"])
        self.assertAlmostEqual(0.75, result["pairs"][0]["p_holm"])

    def test_learning_pairs_touch_advantage_in_the_first_and_second_halves(self):
        blocks = [
            {
                "participant_id": "P01",
                "condition": condition,
                "trials": [{"success_ms": time} for time in times],
            }
            for condition, times in (
                ("touch", [100, 100, 100, 100]),
                ("camera", [300, None, 150, 150]),
            )
        ]
        result = user_study.learning_summary(blocks)
        gap = next(row for row in result["h5"] if row["condition"] == "camera")
        self.assertEqual(1, gap["time_n"])
        self.assertEqual(150.0, gap["median_time_gap_shrink_ms"])
        self.assertEqual(0.5, gap["median_success_gap_shrink"])
        self.assertEqual(0.0, result["curves"]["camera"][1]["success_rate"])
        self.assertIsNone(result["curves"]["camera"][1]["median_time_ms"])


    def test_learning_tests_paired_gap_reduction_across_participants(self):
        blocks = [
            {
                "participant_id": participant,
                "condition": condition,
                "trials": [{"success_ms": time} for time in times],
            }
            for participant in ("P01", "P02", "P03", "P04")
            for condition, times in (
                ("touch", [100, 100, 100, 100]), ("camera", [300, None, 150, 150])
            )
        ]
        result = user_study.learning_summary(blocks)
        gap = next(row for row in result["h5"] if row["condition"] == "camera")
        self.assertEqual(4, gap["time_n"])
        self.assertEqual(4, gap["success_n"])
        self.assertAlmostEqual(0.125, gap["time_test"]["p"])
        self.assertAlmostEqual(0.25, gap["time_test"]["p_holm"])
        self.assertAlmostEqual(1.0, gap["success_test"]["rank_biserial"])

    def test_identical_conditions_have_no_effect(self):
        blocks = [
            {"participant_id": "P01", "condition": condition, "value": 0}
            for condition in ("touch", "threshold", "dtw", "camera")
        ] + [
            {"participant_id": "P02", "condition": condition, "value": 7}
            for condition in ("touch", "threshold", "dtw", "camera")
        ]
        result = user_study.compare_repeated(blocks, "condition", "value", ("touch", "threshold", "dtw", "camera"))
        self.assertEqual({"statistic": 0.0, "p": 1.0, "kendall_w": 0.0}, result["friedman"])
        self.assertEqual(0.0, result["pairs"][0]["rank_biserial"])


if __name__ == "__main__":
    unittest.main()
