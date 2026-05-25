for file in data/src/main/java/dev/chirpboard/app/data/entity/*.kt; do
  if ! grep -q "import androidx.annotation.Keep" "$file"; then
    sed -i '' '/import /a\
import androidx.annotation.Keep
' "$file"
  fi
  if ! grep -q "@Keep" "$file"; then
    sed -i '' 's/data class/@Keep\
data class/g' "$file"
  fi
done
