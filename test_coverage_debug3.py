import sys
from desloppify.engine._state.core import EngineState

state = EngineState.load()
zone_map = state.zone_map

from desloppify.engine.detectors.test_coverage.detector import detect_test_coverage
entries, potential = detect_test_coverage(
    graph={},
    zone_map=zone_map,
    lang_name="kotlin",
    extra_test_files=None,
)
print("Entries count:", len(entries))
for entry in entries:
    if "SettingsViewModel" in entry["file"]:
        print(entry)
