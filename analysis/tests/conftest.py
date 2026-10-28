import sys
from pathlib import Path

# Let tests import the scripts in analysis/ as modules.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
