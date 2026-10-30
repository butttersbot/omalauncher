#!/usr/bin/env python3
"""Linked questions by meaning, with the REAL model (Boss, 2026-10-29: tonight's two cards were the same question; it must
resolve by itself). Not part of the regular suites: it calls Claude, so run it in his session (its sign-in):
  python3 tools/mac-runner/test-linked-meaning.py
Tonight's pair must be offered for delivery in the MacBook card's own label; a different decision must not be."""
import importlib.util, json, os, pathlib, stat, sys, tempfile, unittest

HERE = pathlib.Path(__file__).resolve().parent
# The runner's model call, with real Claude in place of the stand-in.
real = pathlib.Path(tempfile.mkdtemp(prefix='real-matcher-')).resolve() / 'matcher'
real.write_text('#!/bin/sh\nexec claude -p --no-session-persistence --model "${OMA_RUNNER_MATCH_MODEL:-sonnet}" --output-format json --tools "" '
                '--strict-mcp-config --setting-sources project\n')
real.chmod(real.stat().st_mode | stat.S_IEXEC)
os.environ['OMA_RUNNER_MATCHER_REAL'] = str(real)
spec = importlib.util.spec_from_file_location('linked', HERE / 'test-linked.py')
linked = importlib.util.module_from_spec(spec)
spec.loader.exec_module(linked)
linked.environment['OMA_RUNNER_MATCHER'] = str(real)
linked.environment['PATH'] = os.pathsep.join([str(pathlib.Path.home() / '.local/bin'), '/opt/homebrew/bin', os.environ.get('PATH', '')])
linked.environment['HOME'] = str(pathlib.Path.home())  # Claude's own sign-in


class RealMeaning(linked.ByMeaning):
    def test_1_tonightsPairWordedDifferentlyIsAnsweredInTheOthersOwnTerms(self):
        for chose, expect in (('Restore', 'Bring them back'), ('Keep deleted', 'Leave them deleted')):
            found = self.match(linked.TONIGHT_A, {'Were those 3 skills removed on purpose?': chose})
            m = found[self.macbook]
            print('  %s -> %s' % (chose, m))
            self.assertTrue(m['deliver'], m)
            self.assertEqual({linked.TONIGHT_MACBOOK['questions'][0]['question']: expect}, m['answers'])
            self.assertFalse(found.get(self.other, {}).get('deliver'), found.get(self.other))

    test_2_aMappingOntoALabelTheCardDoesntHaveIsNeverOffered = None
    test_3_aSharedLinkOrTheSameCardNeedsNoModel = None


if __name__ == '__main__':
    unittest.main(argv=[sys.argv[0], 'RealMeaning', '-v'])
