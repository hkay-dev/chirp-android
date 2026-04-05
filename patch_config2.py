import json

with open(".desloppify/config.json", "r") as f:
    config = json.load(f)

config["ignore"].extend([
    "app/build.gradle.kts",
    "core/build.gradle.kts",
    "data/build.gradle.kts",
    "feature-keyboard/build.gradle.kts",
    "feature-llm/build.gradle.kts",
    "feature-obsidian/build.gradle.kts",
    "feature-recording/build.gradle.kts",
    "feature-transcription/build.gradle.kts",
    "feature-widget/build.gradle.kts",
    "settings.gradle.kts"
])

with open(".desloppify/config.json", "w") as f:
    json.dump(config, f, indent=2)
