# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.
"""Renders config/groups.toml into one Data Prepper pipelines file per Deployment.

    python3 config/render_groups.py ENV.toml OUT_DIR [--groups config/groups.toml]

ENV.toml holds what differs between environments (local compose, EKS staging/production):
OpenSearch, the board_state database, in-container paths, where to find tenant lists, the closure
sweeper, and optional overrides (refresh interval, replicas per Deployment). It may add
environment-only groups ([[extra_group]], same keys as a group, plus per-platform spec/base_url
in [[extra_group.platform]]), such as the local fixture board for the closure test. See
local/live/env.toml.

Writes OUT_DIR/<deployment>.yaml for every Deployment with at least one runnable group. A platform
is runnable when its spec (config/specs/<name>.yaml) and its tenant list exist; others are listed
as "not built yet". A group with no runnable platform is skipped. Fails on a platform assigned to
two groups, an unknown Deployment or a duplicate group id, so a bad groups file never deploys.
Standard library only (Python 3.11+)."""
import argparse, json, pathlib, sys, tomllib

HERE = pathlib.Path(__file__).resolve().parent
UPSERT = (HERE / "upsert.painless").read_text().rstrip("\n").splitlines()


def die(msg):
    sys.exit(f"render_groups: {msg}")


def load(path):
    with open(path, "rb") as f:
        return tomllib.load(f)


def validate(groups_cfg):
    deployments = groups_cfg.get("deployments", {})
    seen_ids, owner, problems = set(), {}, []
    for g in groups_cfg.get("group", []):
        for key in ("id", "name", "deployment", "refresh", "workers", "platforms"):
            if key not in g:
                problems.append(f"group {g.get('id', '?')}: missing '{key}'")
        if g.get("id") in seen_ids:
            problems.append(f"group id {g['id']} used twice")
        seen_ids.add(g.get("id"))
        if g.get("deployment") not in deployments:
            problems.append(f"group {g.get('id')}: unknown deployment '{g.get('deployment')}'")
        for p in g.get("platforms", []):
            if p in owner:
                problems.append(f"platform '{p}' is in groups {owner[p]} and {g.get('id')}")
            owner[p] = g.get("id")
    if problems:
        die("invalid groups file:\n  " + "\n  ".join(problems))


def q(s):
    return json.dumps(s)  # a JSON string is a valid YAML double-quoted scalar


def pipeline(group, platforms, env, replicas):
    crawl = env.get("crawl", {})
    paths = env["paths"]
    os_ = env["opensearch"]
    bs = env["board_state"]
    name = f"g{group['id']}-{group['name']}"
    refresh = group["refresh"] if group.get("keep_refresh") else crawl.get("refresh_override", group["refresh"])
    lines = [
        f"# Group {group['id']}: {group['description']}" if group.get("description") else f"# Group {group['id']}",
        f"{name}:",
        f"  workers: {crawl.get('pipeline_workers', 2)}",
        "  delay: 100",
        "  source:",
        "    job_board_crawler:",
        f"      refresh_interval: {q(refresh)}",
        f"      leader_interval: {q(crawl.get('leader_interval', 'PT1M'))}",
        f"      workers: {group['workers']}",
        "      acknowledgments: true",
        "      politeness:",
        f"        replicas: {replicas}                 # pods in this Deployment; each takes 1/{replicas} of a platform's budget",
        f"        request_timeout: {q(crawl.get('request_timeout', 'PT30S'))}",
        "      board_state:",
        f"        url: {q(bs['url'])}",
        f"        username: {q(bs['username'])}",
        f"        password: {q(bs['password'])}",
    ]
    cl = env.get("closure")
    if cl:
        lines += ["      closure:", f"        opensearch_url: {q(cl.get('opensearch_url', os_['hosts'][0]))}",
                  f"        index: {q(cl.get('index', os_.get('index', 'jobs-v1')))}"]
        for k in ("snapshots", "mass_closure_ratio", "mass_closure_min_jobs", "retire_after"):
            if k in cl:
                lines.append(f"        {k}: {cl[k]}")
    lines.append("      platforms:")
    for p in platforms:
        extra = p if isinstance(p, dict) else {}
        name = extra.get("name", p)
        lines += [
            f"        - name: {name}",
            f"          spec: {q(paths['specs'] + '/' + extra.get('spec', name) + '.yaml')}",
            f"          tenants_file: {q(paths['tenants'] + '/' + name + '.csv')}",
        ]
        if extra.get("base_url"):
            lines.append(f"          base_url: {q(extra['base_url'])}")
    lines += [
        "  buffer:",
        "    bounded_blocking:",
        "      buffer_size: 4096",
        "      batch_size: 256",
        "  sink:",
        "    - opensearch:",
        f"        hosts: {json.dumps(os_['hosts'])}",
    ]
    if os_.get("insecure"):
        lines.append("        insecure: true")
    for k, v in os_.get("extra", {}).items():
        lines.append(f"        {k}: {json.dumps(v)}")
    lines += [
        f"        index: {q(os_.get('index', 'jobs-v1'))}",
        "        index_type: custom",
        "        template_type: index-template",
        f"        template_file: {q(paths['template'])}",
        '        document_id: "${/job_key}"',
        '        action: "upsert"',
        "        script:",
        "          source: |",
        *["            " + line for line in UPSERT],
        "        max_retries: 5",
        "        bulk_size: 5",
    ]
    return name, "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("env")
    ap.add_argument("out")
    ap.add_argument("--groups", default=str(HERE / "groups.toml"))
    a = ap.parse_args()
    groups_cfg = load(a.groups)
    validate(groups_cfg)
    env_path = pathlib.Path(a.env).resolve()
    env = load(env_path)
    specs_src = (env_path.parent / env["paths"]["specs_src"]).resolve()
    tenants_src = (env_path.parent / env["paths"]["tenants_src"]).resolve()
    replicas = {d: c.get("replicas", 1) for d, c in groups_cfg["deployments"].items()}
    replicas.update(env.get("replicas", {}))

    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    files, report = {}, []
    extra = env.get("extra_group", [])
    for g in extra:
        g["keep_refresh"] = True
    known = {g["id"] for g in groups_cfg["group"]}
    for g in extra:
        if g["id"] in known or g.get("deployment") not in groups_cfg["deployments"]:
            die(f"extra_group {g.get('id')}: id clashes with groups.toml or unknown deployment")
    for g in groups_cfg["group"] + extra:
        label = f"g{g['id']}-{g['name']}"
        if not g.get("active", True):
            report.append(f"  {label:22} inactive")
            continue
        def built(p):
            name = p["name"] if isinstance(p, dict) else p
            spec = p.get("spec", name) if isinstance(p, dict) else p
            return (specs_src / f"{spec}.yaml").exists() and (tenants_src / f"{name}.csv").exists()
        plats = g.get("platform", []) if "platforms" not in g else g["platforms"]
        runnable = [p for p in plats if built(p)]
        missing = [p if isinstance(p, str) else p["name"] for p in plats if not built(p)]
        if not runnable:
            report.append(f"  {label:22} skipped: no platform built yet")
            continue
        name, text = pipeline(g, runnable, env, replicas[g["deployment"]])
        files.setdefault(g["deployment"], []).append(text)
        note = f"; not built yet: {', '.join(missing)}" if missing else ""
        names = [p["name"] if isinstance(p, dict) else p for p in runnable]
        report.append(f"  {label:22} -> {g['deployment']}: {', '.join(names)}{note}")

    for d in groups_cfg["deployments"]:
        target = out / f"{d}.yaml"
        if d in files:
            header = (f"# Generated by config/render_groups.py from config/groups.toml for Deployment '{d}'.\n"
                      f"# Do not edit; change groups.toml or {env_path.name} and re-render.\n")
            target.write_text(header + "\n".join(files[d]))
        elif target.exists():
            target.unlink()  # a stale file would keep running groups that moved away
    print("groups:")
    print("\n".join(report))
    for d in groups_cfg["deployments"]:
        n = len(files.get(d, []))
        print(f"deployment {d}: {n} pipeline(s), replicas {replicas[d]}" + ("" if n else " (nothing to run; do not deploy)"))


if __name__ == "__main__":
    main()
