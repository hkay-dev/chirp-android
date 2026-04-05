from desloppify.engine.detectors.coverage.mapping_analysis import get_test_files_for_prod
from desloppify.engine.detectors.coverage.mapping_imports import _parse_test_imports
from desloppify.engine.detectors.coverage.mapping import _map_test_to_source

test_files = {"app/src/test/java/dev/chirpboard/app/ui/settings/SettingsViewModelTest.kt"}
prod_file = "app/src/main/java/dev/chirpboard/app/ui/settings/SettingsViewModel.kt"

tests = get_test_files_for_prod(
    prod_file,
    test_files,
    graph={},
    lang_name="kotlin",
    parsed_imports_by_test={},
    parse_test_imports_fn=_parse_test_imports,
    map_test_to_source_fn=_map_test_to_source,
    project_root="/test"
)
print("TESTS:", tests)
