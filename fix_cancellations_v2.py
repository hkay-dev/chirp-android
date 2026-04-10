import os

for root, _, filenames in os.walk('.'):
    if '.git' in root or 'build' in root:
        continue
    for f in filenames:
        if f.endswith('.kt'):
            filepath = os.path.join(root, f)
            with open(filepath, 'r') as file:
                lines = file.readlines()
            
            changed = False
            for i, line in enumerate(lines):
                if 'catch (e: Exception)' in line or 'catch (e: Throwable)' in line or 'catch (_: Exception)' in line:
                    next_line = lines[i+1] if i+1 < len(lines) else ""
                    if 'CancellationException' not in next_line:
                        var_name = 'e'
                        if 'catch (_: Exception)' in line:
                            # If it's `_`, it can't be referenced. We have to change `_` to `e` first.
                            # But let's just ignore `catch (_: Exception)` for now or replace it.
                            continue
                        
                        indent = line[:len(line) - len(line.lstrip())]
                        lines.insert(i+1, indent + '    if (e is kotlinx.coroutines.CancellationException) throw e\n')
                        changed = True
            
            if changed:
                with open(filepath, 'w') as file:
                    file.writelines(lines)
                print(f"Fixed {filepath}")
