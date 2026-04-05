from desloppify.engine.detectors.coverage.mapping_analysis import analyze_test_quality
from desloppify.engine.detectors.test_coverage.heuristics import _load_lang_test_coverage_module
from desloppify.engine.detectors.test_coverage.io import read_coverage_file

test_files = {"app/src/test/java/dev/chirpboard/app/ui/settings/SettingsViewModelTest.kt"}
quality = analyze_test_quality(
    test_files,
    "kotlin",
    load_lang_module=_load_lang_test_coverage_module,
    read_coverage_file_fn=read_coverage_file,
    logger=None,
)
print("QUALITY:", quality)
