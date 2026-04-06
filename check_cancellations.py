import os

def check_file(path):
    with open(path, 'r') as f:
        lines = f.readlines()
    for i, line in enumerate(lines):
        if 'catch (e: Exception)' in line:
            if i + 1 < len(lines) and 'CancellationException' not in lines[i+1]:
                # check if it just throws e
                if 'throw e' not in lines[i+1] and 'throw e' not in line:
                    print(f"{path}:{i+1}")
                    print(f"  {lines[i].strip()}")
                    print(f"  {lines[i+1].strip()}")

for root, dirs, files in os.walk('.'):
    for file in files:
        if file.endswith('.kt'):
            check_file(os.path.join(root, file))
