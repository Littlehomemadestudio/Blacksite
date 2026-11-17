import os
import sys
import tempfile
import unittest

PYTHON_SOURCE = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "..", "main", "python")
)
sys.path.insert(0, PYTHON_SOURCE)

from oracle_runner import run


class OracleRunnerTest(unittest.TestCase):
    def test_executes_normal_code_and_restores_process_state(self):
        previous_cwd = os.getcwd()
        previous_argv = list(sys.argv)
        with tempfile.TemporaryDirectory() as workspace:
            result = run("print('oracle-ready')", workspace)
        self.assertIn("oracle-ready", result)
        self.assertEqual(previous_cwd, os.getcwd())
        self.assertEqual(previous_argv, sys.argv)

    def test_stops_a_normal_python_infinite_loop_at_the_soft_deadline(self):
        with tempfile.TemporaryDirectory() as workspace:
            result = run("while True:\n    pass", workspace, timeout_seconds=0.05)
        self.assertIn("line-execution limit", result)

    def test_caps_captured_stdout(self):
        with tempfile.TemporaryDirectory() as workspace:
            result = run("print('x' * 5000)", workspace, max_output_chars=128)
        self.assertIn("stdout truncated", result)
        self.assertLessEqual(result.count("x"), 128)

    def test_rejects_oversized_source(self):
        with tempfile.TemporaryDirectory() as workspace:
            result = run(" " * (16 * 1024 + 1), workspace)
        self.assertIn("16 KB execution limit", result)


if __name__ == "__main__":
    unittest.main()
