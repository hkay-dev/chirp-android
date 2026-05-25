#!/bin/bash

# Fix SettingsViewModel.kt
FILE="app/src/main/java/dev/chirpboard/app/ui/settings/SettingsViewModel.kt"
sed -i '' 's/^    }//' "$FILE"

# Fix KeyboardSettingsViewModel.kt
FILE="app/src/main/java/dev/chirpboard/app/ui/settings/KeyboardSettingsViewModel.kt"
sed -i '' 's/^    }//' "$FILE"

# Fix DevMenuViewModel.kt
FILE="app/src/main/java/dev/chirpboard/app/debug/DevMenuViewModel.kt"
sed -i '' 's/^    }//' "$FILE"
