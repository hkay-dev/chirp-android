# Tasks: UI polish — design system foundation

## 1. Theme tokens

- [x] 1.1 Create `ChirpTypography.kt` with Material 3 type scale overrides (`headlineMedium`, `titleMedium`, `bodySmall` minimum)
- [x] 1.2 Add `chirpMaterialShapes` mapping from `ChirpShapes` to `Shapes` in `Theme.kt`
- [x] 1.3 Wire `ChirpTypography` and shapes into `ChirpTheme` `MaterialTheme` call
- [x] 1.4 Collapse `KeyboardTheme` to delegate to `ChirpTheme`; verify IME still compiles

## 2. Accessibility strings

- [x] 2.1 Confirm `desc_navigate_back` as canonical string in `core-ui/strings.xml`
- [x] 2.2 Remove duplicate `desc_back` from `shared_ui_strings.xml`
- [x] 2.3 Update all `desc_back` references across app and feature modules to `CoreR.string.desc_navigate_back`
- [x] 2.4 Remove `desc_back` from `app/strings.xml` if present and unused

## 3. Scaffold

- [x] 3.1 Add `ChirpSettingsDetailScaffold` to `ChirpScaffolds.kt` (large title, scroll behavior, optional snackbar)
- [x] 3.2 Match top bar colors and title style to `ChirpSettingsHubScaffold` / `ChirpTypography.headlineMedium`
- [ ] 3.3 Add preview composable for detail scaffold

## 4. Settings list components

- [x] 4.1 Create `SettingsDropdownListItem.kt` with generic option picker API
- [ ] 4.2 Create `SettingsConnectionStatusRow.kt` with `SettingsConnectionStatus` enum and animated tints
- [ ] 4.3 Create `SettingsActionButtonRow.kt` with `SettingsAction` data class and button hierarchy KDoc
- [x] 4.4 Create `ChirpInlineLoadingIcon.kt` using `Crossfade` between icon and spinner
- [ ] 4.5 Add preview composables for dropdown, status row, and action row

## 5. Audio and Keyboard settings migration

- [x] 5.1 Migrate `AudioSettingsScreen` to `ChirpSettingsDetailScaffold`
- [x] 5.2 Replace `InputDevicePolicyListItem` and `RecordingQualityListItem` with `SettingsDropdownListItem`
- [x] 5.3 Migrate `KeyboardSettingsScreen` to `ChirpSettingsDetailScaffold`
- [x] 5.4 Replace `ProcessingModeListItem` with `SettingsDropdownListItem`
- [x] 5.5 Update Audio/Keyboard icons to `Icons.Rounded` where applicable

## 6. LLM settings migration

- [ ] 6.1 Migrate `LlmSettingsScreen` to `ChirpSettingsDetailScaffold`
- [ ] 6.2 Refactor `LlmSettingsApiKeySection` to use `SettingsConnectionStatusRow` and `SettingsActionButtonRow`
- [ ] 6.3 Use `ChirpInlineLoadingIcon` for test-connection button loading state
- [ ] 6.4 Standardize LLM icons to `Icons.Rounded.AutoAwesome` (master toggle / hub parity) and Rounded status icons

## 7. Obsidian settings migration

- [ ] 7.1 Migrate `ObsidianSettingsScreen` to `ChirpSettingsDetailScaffold`
- [ ] 7.2 Refactor `VaultConfigurationCard` status ListItem to `SettingsConnectionStatusRow`
- [ ] 7.3 Refactor vault action buttons to `SettingsActionButtonRow`
- [ ] 7.4 Update folder/status icons to `Icons.Rounded`

## 8. Transcription settings migration

- [ ] 8.1 Migrate `TranscriptionSettingsScreen` to `ChirpSettingsDetailScaffold`
- [ ] 8.2 Adopt `ChirpInlineLoadingIcon` in model download/delete action buttons
- [ ] 8.3 Use shared status icon pattern for model card header where feasible without over-extracting card
- [ ] 8.4 Update transcription settings icons to `Icons.Rounded`

## 9. Dev Menu migration

- [ ] 9.1 Migrate `DevMenuScreen` to `ChirpSettingsDetailScaffold` with `snackbarHostState`
- [ ] 9.2 Verify snackbar feedback and scroll behavior unchanged

## 10. Cleanup and verification

- [ ] 10.1 Delete obsolete private scaffold/dropdown/status composables from migrated files
- [ ] 10.2 Grep repo: zero bespoke `LargeTopAppBar` in the six migrated screens
- [ ] 10.3 Grep repo: zero `desc_back` references outside archive/docs
- [ ] 10.4 Run `./gradlew :core-ui:assembleDebug :app:assembleDebug :feature-keyboard:assembleDebug :feature-llm:assembleDebug :feature-transcription:assembleDebug :feature-obsidian:assembleDebug`
- [ ] 10.5 Run affected unit/compose tests; fix resource or semantics assertions
