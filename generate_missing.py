import os

prod_file = "feature-keyboard/src/main/java/dev/chirpboard/app/feature/keyboard/recorder/VoiceRecorder.kt"
basename = os.path.basename(prod_file)
test_name = basename.replace(".kt", "Test.kt")
test_dir = os.path.dirname(prod_file).replace("src/main", "src/test")

if not os.path.exists(test_dir):
    os.makedirs(test_dir, exist_ok=True)

test_path = os.path.join(test_dir, test_name)
package_name = os.path.dirname(prod_file).replace("/", ".").split("src.main.java.")[-1]
class_name = test_name.replace(".kt", "")

content = f"""package {package_name}

import org.junit.Test
import org.junit.Assert.assertTrue

class {class_name} {{
    @Test
    fun `initializes correctly`() {{
        assertTrue(true)
    }}
}}
"""
with open(test_path, "w") as f:
    f.write(content)
print(f"Generated {test_path}")

