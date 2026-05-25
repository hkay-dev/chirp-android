for file in $(rg -l "catch\s*\(\s*\w+\s*:\s*Exception\s*\)" -g "*.kt"); do
  # Check if the file has CancellationException handling
  if ! grep -q "CancellationException" "$file"; then
    echo "Missing CancellationException handling in: $file"
  fi
done
