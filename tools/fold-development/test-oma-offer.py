#!/usr/bin/env python3
"""oma-offer's checks: walks must PASS, drift is never offered, the design line keeps UNAPPROVED/UNCHECKED visible."""
import importlib.machinery, importlib.util, json, pathlib, tempfile, unittest

HERE = pathlib.Path(__file__).resolve().parent
loader = importlib.machinery.SourceFileLoader('oma_offer', str(HERE / 'oma-offer'))
spec = importlib.util.spec_from_loader('oma_offer', loader)
offer = importlib.util.module_from_spec(spec)
loader.exec_module(offer)


def walk(root, name, results):
    d = pathlib.Path(root) / name
    d.mkdir(parents=True)
    (d / 'results.json').write_text(json.dumps({'script': name + '.walk', 'steps': [{'text': t, 'result': r} for t, r in results]}))


class OfferTest(unittest.TestCase):
    def test_walks_and_phone_steps_come_from_results(self):
        with tempfile.TemporaryDirectory() as t:
            walk(t, 'update-pill', [('fold folded', 'PASS'), ('shot x', 'LOOK'), ('[phone] Play Protect prompt?', 'after promotion')])
            walk(t, 'connections', [('tap', 'PASS'), ('expect y', 'FAIL')])
            v = offer.walk_verdicts(t)
            self.assertEqual({'update-pill': (1, 0), 'connections': (1, 1)}, v)
            self.assertEqual(['Play Protect prompt?'], offer.phone_steps(t))

    def test_refusals(self):
        ok = {'w': (3, 0)}
        self.assertIsNone(offer.refuse_reason('dev.omalauncher.hunch', 'abc', 'abc', ok, 0))
        self.assertIn('not Soma', offer.refuse_reason('com.example', 'abc', 'abc', ok, 0))
        self.assertIn('working key', offer.refuse_reason('dev.omalauncher.boss', 'zzz', 'abc', ok, 0))
        self.assertIn('no walk results', offer.refuse_reason('dev.omalauncher.boss', 'abc', 'abc', {}, 0))
        self.assertIn('did not all PASS: w', offer.refuse_reason('dev.omalauncher.boss', 'abc', 'abc', {'w': (3, 1)}, 0))
        self.assertIn('DESIGN FAIL', offer.refuse_reason('dev.omalauncher.boss', 'abc', 'abc', ok, 3))

    def test_design_line_counts(self):
        self.assertEqual({'checked': False, 'match': 0, 'unapproved': 0, 'unchecked': 0}, offer.design_line(None))
        report = {'cards': [{'result': 'match'}, {'result': 'no-approved', 'waived': False}, {'result': 'error'},
                            {'result': 'no-approved', 'waived': True}]}
        self.assertEqual({'checked': True, 'match': 2, 'unapproved': 1, 'unchecked': 1}, offer.design_line(report))


if __name__ == '__main__':
    unittest.main()
