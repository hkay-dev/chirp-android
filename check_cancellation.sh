for file in $(rg -l "catch \(e: Exception\)" -g "*.kt" | xargs grep -L "CancellationException"); do
    if grep -q "suspend fun\|launch\|withContext\|Flow" "$file"; then
        echo "Check file: $file"
    fi
done
