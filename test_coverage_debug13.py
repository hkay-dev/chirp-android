
import json
from desloppify.engine.detectors.coverage.mapping import naming_based_mapping
import desloppify.languages.kotlin.test_coverage as kc

test_files = ["app/src/test/java/dev/chirpboard/app/ui/settings/SettingsViewModelTest.kt"]
prod_files = ["app/src/main/java/dev/chirpboard/app/ui/settings/SettingsViewModel.kt"]

mapped = naming_based_mapping(set(test_files), set(prod_files), "kotlin")
print("mapped:", mapped)
