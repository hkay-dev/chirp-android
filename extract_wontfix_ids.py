import json

with open("wontfix_remaining.json") as f:
    data = json.load(f)

ids = []
for file_issues in data["by_file"].values():
    for issue in file_issues:
        ids.append(issue["id"])

with open("wontfix_ids.txt", "w") as f:
    f.write("\n".join(ids) + "\n")
