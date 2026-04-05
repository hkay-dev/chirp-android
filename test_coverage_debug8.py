from desloppify.engine.detectors.test_coverage._issue_quality import select_direct_test_quality_issue

issue = select_direct_test_quality_issue(
    prod_file="app/src/main/java/dev/chirpboard/app/ui/settings/SettingsViewModel.kt",
    related_tests=["app/src/test/java/dev/chirpboard/app/ui/settings/SettingsViewModelTest.kt"],
    test_quality={"app/src/test/java/dev/chirpboard/app/ui/settings/SettingsViewModelTest.kt": {"quality": "adequate"}},
    loc_weight=1.0
)
print("ISSUE:", issue)
