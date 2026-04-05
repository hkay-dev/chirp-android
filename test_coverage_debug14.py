from desloppify.engine.policy.zones import FileZoneMap
import glob
files = glob.glob("**/*.kt", recursive=True)

production_files = []
for f in files:
    if "test" not in f and "androidTest" not in f:
        production_files.append(f)

import desloppify.languages.kotlin.test_coverage as kc

test_file = "app/src/test/java/dev/chirpboard/app/ui/settings/SettingsViewModelTest.kt"
mapped = kc.map_test_to_source(test_file, set(production_files))
print(f"MAPPED FOR {test_file}: {mapped}")

from desloppify.engine.detectors.coverage.mapping import naming_based_mapping
tested = naming_based_mapping({test_file}, set(production_files), "kotlin")
print(f"NAMING_BASED: {tested}")

