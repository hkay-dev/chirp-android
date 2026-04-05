import sys
from desloppify.engine._state.core import EngineState

state = EngineState.load()
zone_map = state.zone_map

from desloppify.engine.detectors.test_coverage.discovery import _discover_scorable_and_tests

production_files, test_files, scorable, potential = _discover_scorable_and_tests(
    graph={},
    zone_map=zone_map,
    lang_name="kotlin",
    extra_test_files=None,
)
print(f"Test files containing SettingsViewModelTest: {[f for f in test_files if 'SettingsViewModelTest' in f]}")
