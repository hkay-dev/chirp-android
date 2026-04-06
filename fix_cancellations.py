import os
import glob

files = []
for root, _, filenames in os.walk('.'):
    for f in filenames:
        if f.endswith('.kt'):
            files.append(os.path.join(root, f))

for filepath in files:
    with open(filepath, 'r') as f:
        content = f.read()

    if 'catch (e: Exception)' in content and 'CancellationException' not in content:
        lines = content.split('\n')
        modified = False
        
        # Check if we need to add import
        has_import = any('import kotlinx.coroutines.CancellationException' in line for line in lines)
        has_coroutines_usage = any('import kotlinx.coroutines' in line for line in lines)
        
        # We only add CancellationException import if we are adding the throw
        new_lines = []
        for line in lines:
            new_lines.append(line)
            if 'catch (e: Exception)' in line:
                indent = line[:len(line) - len(line.lstrip())]
                new_lines.append(indent + '    if (e is kotlinx.coroutines.CancellationException) throw e')
                modified = True
        
        if modified:
            with open(filepath, 'w') as f:
                f.write('\n'.join(new_lines))
