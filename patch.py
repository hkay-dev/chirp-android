path = "/Users/developer/.local/share/uv/tools/desloppify/lib/python3.12/site-packages/desloppify/engine/detectors/test_coverage/_issue_generation.py"
with open(path, "r") as f:
    lines = f.readlines()

new_lines = []
for line in lines:
    if "if filepath in directly_tested:" in line:
        new_lines.append(line)
        new_lines.append('        if "SettingsViewModel.kt" in filepath: print("SettingsViewModel IS IN directly_tested")\n')
    elif "entries.append(" in line and "untested_module_issue" in lines[lines.index(line) + 1]:
        new_lines.append(line)
        new_lines.append('        if "SettingsViewModel.kt" in filepath: print("SettingsViewModel APPENDED TO untested_module")\n')
    else:
        new_lines.append(line)

with open(path, "w") as f:
    f.writelines(new_lines)
