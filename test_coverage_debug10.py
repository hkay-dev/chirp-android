from desloppify.engine.policy.zones import FileZoneMap
from desloppify.engine.detectors.test_coverage.detector import detect_test_coverage

# Wait, let me just add SettingsViewModel files directly.
import glob
files = glob.glob("**/*.kt", recursive=True)

class DummyZoneMap:
    def all_files(self): return files
    def include_only(self, files, *zones):
        res = []
        for f in files:
            is_test = "src/test" in f or "src/androidTest" in f
            if "test" in [z.value for z in zones]:
                if is_test: res.append(f)
            elif "production" in [z.value for z in zones]:
                if not is_test: res.append(f)
        return res

entries, _ = detect_test_coverage({}, DummyZoneMap(), "kotlin")
for entry in entries:
    if "SettingsViewModel" in entry["file"]:
        print(entry)
