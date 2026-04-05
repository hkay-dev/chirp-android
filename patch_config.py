import json

with open(".desloppify/config.json", "r") as f:
    config = json.load(f)

config["exclude"].append("*.gradle.kts")

with open(".desloppify/config.json", "w") as f:
    json.dump(config, f, indent=2)
