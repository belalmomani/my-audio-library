"""اختبارات بلا اعتماديات خارجية (unittest): python -m unittest discover -s tests -p 'test_pure.py'"""
import os, sys, tempfile, unittest
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from app.normalize import normalize
from app.pipeline.assign import assign_speakers
from app.jobs import JobStore, build_result


class PureTests(unittest.TestCase):
    def test_normalize(self):
        self.assertEqual(normalize("أحمد"), normalize("أحمد،"))
        self.assertEqual(normalize("أحمد"), normalize("«أحمد!»"))
        self.assertEqual(normalize("أحمد"), normalize("أَحْمَد"))
        self.assertNotEqual(normalize("ذهب"), normalize("يذهب"))
        self.assertNotEqual(normalize("الكتاب"), normalize("كتاب"))

    def test_assign(self):
        words = [{"text": "أ", "start_ms": 0, "end_ms": 500}, {"text": "ب", "start_ms": 1000, "end_ms": 1500},
                 {"text": "ج", "start_ms": 5000, "end_ms": 5400}]
        out = assign_speakers(words, [(0.0, 0.9, "S0"), (0.8, 2.0, "S1"), (1.0, 1.4, "S0")])
        self.assertEqual(out[0]["speaker"], "S0")
        self.assertEqual(out[1]["speaker"], "S1")
        self.assertGreater(out[1]["overlap"], 0.5)
        self.assertIsNone(out[2]["speaker"])

    def test_result(self):
        r = build_result([{"text": "أحمد،", "start_ms": 0, "end_ms": 400}], [(0.0, 1.0, "S0")], {"S0": [0.1]}, None)
        self.assertEqual(r["words"][0]["normalized"], normalize("أحمد"))
        self.assertEqual(r["speakers"][0]["embedding"], [0.1])

    def test_store(self):
        with tempfile.TemporaryDirectory() as d:
            st = JobStore(os.path.join(d, "j.sqlite"))
            jid = st.create("u1", "users/u1/tmp/x", {"a": 1})
            self.assertIsNone(st.get(jid, "u2"))
            st.update(jid, stage="FAILED", error="x")
            self.assertEqual(st.get(jid, "u1")["stage"], "FAILED")


if __name__ == "__main__":
    unittest.main()
