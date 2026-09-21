"""Generate achievement documentation from the compiled Java catalog."""
import subprocess
from pathlib import Path
root = Path(__file__).resolve().parents[1]
result = subprocess.run(["java", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-cp", str(root / "target/classes"), "dev.moma.sim.AchievementExportMain"], check=True, capture_output=True, encoding="utf-8")
(root / "docs/achievements-draft.md").write_text(result.stdout, encoding="utf-8")
