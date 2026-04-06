import glob
import os

for filepath in glob.glob("data/src/main/java/dev/chirpboard/app/data/entity/*.kt"):
    with open(filepath, 'r') as f:
        content = f.read()
    
    if '@Keep' not in content:
        # Add import
        lines = content.split('\n')
        for i, line in enumerate(lines):
            if line.startswith('import '):
                lines.insert(i, 'import androidx.annotation.Keep')
                break
        
        # Add @Keep to data class
        for i, line in enumerate(lines):
            if line.startswith('data class '):
                lines.insert(i, '@Keep')
                break
                
        content = '\n'.join(lines)
        with open(filepath, 'w') as f:
            f.write(content)
