import sys
path = "/Users/developer/.local/share/uv/tools/desloppify/lib/python3.12/site-packages/desloppify/engine/detectors/test_coverage/_issue_generation.py"
with open(path, "r") as f:
    lines = f.readlines()

new_lines = []
for line in lines:
    if "if \"SettingsViewModel.kt\" in filepath: print(\"SettingsViewModel" in line:
        pass
    else:
        new_lines.append(line)

with open(path, "w") as f:
    f.writelines(new_lines)
