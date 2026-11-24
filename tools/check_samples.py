#!/usr/bin/env python3
import json
import re
import sys
from pathlib import Path

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parent.parent
SCHEMAS = ROOT / "schema"
SAMPLES = ROOT / "samples"
FIXTURES = ROOT / "tools" / "fixtures"

SAFE_PATH = re.compile(r"^(?![A-Za-z]:)(?!/)(?!.*\\)(?!(?:.*/)?\.\.(?:/|$)).+$")


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def schema_problems(schema_name, data):
    validator = Draft202012Validator(load(SCHEMAS / schema_name))
    out = []
    for error in sorted(validator.iter_errors(data), key=lambda e: list(e.absolute_path)):
        where = "/".join(str(p) for p in error.absolute_path) or "(root)"
        out.append(f"{where}: {error.message}")
    return out


def recipe_problems(recipe):
    problems = schema_problems("recipe.schema.json", recipe)
    if problems:
        return problems
    ids = [i["id"] for i in recipe.get("inputs", [])]
    for dup in {i for i in ids if ids.count(i) > 1}:
        problems.append(f"input id '{dup}' is used twice")
    for n, item in enumerate(recipe.get("inputs", []), 1):
        label = f"input {n}"
        if item["type"] == "number":
            try:
                float(item["default"])
            except (TypeError, ValueError):
                problems.append(f"{label}: default must be a number")
        if item["type"] == "choice" and str(item["default"]) not in item.get("options", []):
            problems.append(f"{label}: default must be one of the options")
        if "min" in item and "max" in item and item["min"] > item["max"]:
            problems.append(f"{label}: min is above max")

    def check_ref(label, value):
        if isinstance(value, str) and value.startswith("$") and value[1:] not in ids:
            problems.append(f"{label}: '{value}' is not an input of this recipe")

    for n, step in enumerate(recipe["steps"], 1):
        label = f"step {n}"
        if step["do"] == "addEffect":
            for value in step.get("set", {}).values():
                check_ref(label, value)
        elif step["do"] == "setEffectParam":
            check_ref(label, step["value"])
        else:
            if len(step["at"]) != len(step["values"]):
                problems.append(f"{label}: at and values need the same number of entries")
            for value in step["values"]:
                check_ref(label, value)
    return problems


def effect_problems(folder, key):
    path = folder / f"{key}.json"
    if not path.is_file():
        return [f"effects/{key}.json is missing"]
    try:
        data = load(path)
    except ValueError as e:
        return [f"effects/{key}.json is not valid JSON: {e}"]
    out = []
    for field in ("id", "name", "category", "params", "sksl"):
        if field not in data:
            out.append(f"effects/{key}.json: '{field}' is required")
    if data.get("native"):
        out.append(f"effects/{key}.json: plugin effects cannot use the native flag")
    return out


def manifest_problems(manifest, base):
    problems = schema_problems("manifest.schema.json", manifest)
    if problems:
        return problems
    contributes = manifest["contributes"]
    for rel in contributes.get("recipes", []):
        path = base / rel
        if not path.is_file():
            problems.append(f"{rel} is missing")
            continue
        try:
            found = recipe_problems(load(path))
        except ValueError as e:
            found = [f"not valid JSON: {e}"]
        problems += [f"{rel}: {p}" for p in found]
    keys = contributes.get("effects", [])
    if keys:
        index = base / "effects" / "index.json"
        if not index.is_file():
            problems.append("effects/index.json is missing")
        else:
            listed = load(index)
            for key in keys:
                if key not in listed:
                    problems.append(f"effects/index.json does not list '{key}'")
        for key in keys:
            problems += effect_problems(base / "effects", key)
    return problems


def main():
    failed = False
    samples = sorted(p for p in SAMPLES.glob("*") if p.is_dir()) if SAMPLES.is_dir() else []
    if not samples:
        print("FAIL no samples found")
        failed = True
    for sample in samples:
        manifest_path = sample / "manifest.json"
        if not manifest_path.is_file():
            print(f"FAIL {sample.name}: manifest.json is missing")
            failed = True
            continue
        problems = manifest_problems(load(manifest_path), sample)
        if problems:
            failed = True
            print(f"FAIL {sample.name}")
            for p in problems:
                print(f"  - {p}")
        else:
            print(f"ok   {sample.name}")

    for fixture in sorted(FIXTURES.glob("bad_*.json")):
        data = load(fixture)
        if "steps" in data:
            problems = recipe_problems(data)
        else:
            problems = schema_problems("manifest.schema.json", data)
        if problems:
            print(f"ok   {fixture.name} is invalid, as expected: {problems[0]}")
        else:
            print(f"FAIL {fixture.name} should be invalid but passed")
            failed = True
    if not list(FIXTURES.glob("bad_*.json")):
        print("FAIL no negative fixtures found")
        failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
