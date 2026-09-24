import gzip
import json
from pathlib import Path
import tempfile
import unittest
from summarize_progression import analyze, wilson, write_csv


class ReportTest(unittest.TestCase):
    def make_run(self, path):
        (path/'rules.txt').write_text('runs=2\nseed=100\nroundCap=10000\nbatchTicks=500\nscenarios=none\n')
        rows=[dict(seed=100+i,round=30,completedRounds=29,ticks=18000,outcome='ENEMY_LIMIT',
                   summons=8,sales=0,maxGrade='RARE',checkpoints=[]) for i in range(2)]
        with gzip.open(path/'none.jsonl.gz','wt') as f:
            f.write(''.join(json.dumps(r)+'\n' for r in rows))
        write_csv(path/'summary.csv',[dict(scenario='none',runs=2,mean_round=30,simulated_seconds=1800,reach30=2)])

    def test_complete_cohort_and_missing_seed(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);self.make_run(p)
            result=analyze(p,2,p/'analysis')
            self.assertEqual(2,result['validated_games'])
            self.assertEqual(2,result['scenarios']['none']['reached'][30])
            with gzip.open(p/'none.jsonl.gz','rt') as f:
                lines=f.readlines()
            with gzip.open(p/'none.jsonl.gz','wt') as f:
                f.write(lines[0]+lines[0])
            with self.assertRaisesRegex(ValueError,'seed'):
                analyze(p,2,p/'bad')

    def test_wrong_summary_rejected_and_zero_not_impossible(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);self.make_run(p)
            content=(p/'summary.csv').read_text().replace('1800,2','1800,1')
            (p/'summary.csv').write_text(content)
            with self.assertRaisesRegex(ValueError,'Summary count'):
                analyze(p,2,p/'bad')
        self.assertGreater(wilson(0,100000)[1],0)
        self.assertLess(wilson(100000,100000)[0],100)


if __name__=='__main__':
    unittest.main()
