"""soma-theme: the look comes from the herd's Fold report first; CSS resolves palette names and alphas."""
import importlib.machinery, importlib.util, json, os, pathlib, sys, tempfile, unittest
from unittest import mock

HERE = pathlib.Path(__file__).resolve().parent
loader = importlib.machinery.SourceFileLoader('soma_theme', str(HERE / 'soma-theme'))
st = importlib.util.module_from_spec(importlib.util.spec_from_loader('soma_theme', loader)); loader.exec_module(st)


class Run:
    def __init__(self, out): self.stdout = out


class SomaTheme(unittest.TestCase):
    def test_look_from_the_herd_report(self):
        herd = {'machines': [{'name': 'jacks-mac-pro', 'report': {'machine': {}}},
                             {'name': 'galaxy-z-fold8', 'report': {'machine': {'soma': {'look': 'manga', 'build': 176}}}}]}
        with mock.patch('subprocess.run', return_value=Run(json.dumps(herd))), mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop('SOMA_LOOK', None)
            look, why = st.active([])
        self.assertEqual(look, 'manga')
        self.assertIn('galaxy-z-fold8', why)
        self.assertIn('176', why)

    def test_flag_and_env_come_first(self):
        self.assertEqual(st.active(['--look', 'riso'])[0], 'riso')
        with mock.patch.dict(os.environ, {'SOMA_LOOK': 'bujo'}):
            self.assertEqual(st.active([])[0], 'bujo')

    def test_colours(self):
        pal = {'ink': '#111113', 'shade': 'ink@0.5'}
        self.assertEqual(st.css_colour(st.colour('ink', pal)), '#111113')
        self.assertEqual(st.css_colour(st.colour('shade', pal)), 'rgb(17 17 19 / 0.5)')
        self.assertIsNone(st.colour('nope', pal))


if __name__ == '__main__':
    unittest.main()
