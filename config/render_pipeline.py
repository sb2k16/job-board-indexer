# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.
"""Inlines config/upsert.painless into a pipeline template (placeholder __UPSERT_SCRIPT__, at any indent).
Usage: python3 render_pipeline.py TEMPLATE OUT"""
import pathlib, re, sys
here = pathlib.Path(__file__).parent
script = (here / "upsert.painless").read_text().rstrip("\n").splitlines()
src = pathlib.Path(sys.argv[1]).read_text()
def repl(m):
    indent = m.group(1)
    return indent + "source: |\n" + "\n".join(indent + "  " + line for line in script)
out = re.sub(r"^( *)source: __UPSERT_SCRIPT__$", repl, src, flags=re.M)
assert "__UPSERT_SCRIPT__" not in out
pathlib.Path(sys.argv[2]).write_text(out)
