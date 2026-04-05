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

from desloppify.engine.detectors.coverage.mapping import naming_based_mapping
directly_tested = naming_based_mapping(test_files, production_files, "kotlin")
print(f"Directly tested length: {len(directly_tested)}")
print(f"Is SettingsViewModel in directly_tested? {'app/src/main/java/dev/chirpboard/app/ui/settings/SettingsViewModel.kt' in directly_tested}")
