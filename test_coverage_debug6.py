import desloppify.languages.kotlin.test_coverage as kc

test_file = "app/src/test/java/dev/chirpboard/app/ui/settings/SettingsViewModelTest.kt"
prod_set = {"app/src/main/java/dev/chirpboard/app/ui/settings/SettingsViewModel.kt"}

mapped = kc.map_test_to_source(test_file, prod_set)
print(f"MAPPED: {mapped}")
