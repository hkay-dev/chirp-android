import json
with open(".desloppify/state/zones.json") as f:
    zones = json.load(f)

print("SettingsViewModelTest in overrides?", "app/src/test/java/dev/chirpboard/app/ui/settings/SettingsViewModelTest.kt" in zones["overrides"])
print("Is it in auto_classified?", "app/src/test/java/dev/chirpboard/app/ui/settings/SettingsViewModelTest.kt" in zones.get("auto_classified", {}))
