while read -r id; do
    desloppify plan unskip "$id" --force
done < wontfix_ids.txt
