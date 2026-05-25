#!/bin/bash

# Fix ProfileEditorScreen.kt
FILE="feature-recording/src/main/java/dev/chirpboard/app/feature/recording/ui/profile/ProfileEditorScreen.kt"
if ! grep -q "import androidx.lifecycle.compose.collectAsStateWithLifecycle" "$FILE"; then
    sed -i '' 's/import androidx.compose.runtime.\*/import androidx.compose.runtime.*\nimport androidx.lifecycle.compose.collectAsStateWithLifecycle/' "$FILE"
fi

# Fix ProfileListScreen.kt
FILE="feature-recording/src/main/java/dev/chirpboard/app/feature/recording/ui/profile/ProfileListScreen.kt"
if ! grep -q "import androidx.lifecycle.compose.collectAsStateWithLifecycle" "$FILE"; then
    sed -i '' 's/import androidx.compose.runtime.\*/import androidx.compose.runtime.*\nimport androidx.lifecycle.compose.collectAsStateWithLifecycle/' "$FILE"
fi

# Fix TagChip.kt
FILE="feature-recording/src/main/java/dev/chirpboard/app/feature/recording/ui/tag/TagChip.kt"
if ! grep -q "import androidx.compose.runtime.remember" "$FILE"; then
    sed -i '' 's/import androidx.compose.runtime.\*/import androidx.compose.runtime.*\nimport androidx.compose.runtime.remember/' "$FILE"
fi

