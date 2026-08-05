#!/bin/bash
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
set -o errexit
set -o nounset
set -o pipefail

# Regenerates the third-party licence inventory and diffs it against the copy
# committed at release/license/third-party-inventory.txt.
#
# The other two license checks answer "is what we ship accounted for". This one
# answers "has anything changed", and it answers it as a diff rather than as a
# verdict. A dependency bump that alters licensing shows up in review as the
# lines it added and removed, which is a thing a human can judge; a boolean gate
# reports only that today differs from yesterday, without saying how.
#
# It also cannot pass without having run. A check that reports findings will
# report none when it examined nothing, which is how the previous tooling stayed
# green while covering nothing at all. A missing or truncated inventory is not a
# quiet pass here - it is a diff, and the diff fails.
#
#   release/scripts/validate_third_party_inventory.sh            # check
#   release/scripts/validate_third_party_inventory.sh --update   # accept changes
#
# Run --update whenever a dependency changes, and read the diff it produces
# before committing it.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${ROOT_DIR}"

BASELINE="release/license/third-party-inventory.txt"
GENERATED="target/license-inventory/third-party-inventory.txt"

UPDATE=0
if [[ "${1:-}" == "--update" ]]; then
  UPDATE=1
elif [[ $# -gt 0 ]]; then
  echo "usage: $(basename "$0") [--update]" >&2
  exit 2
fi

# The generated file carries no license header of its own, and apache-rat-plugin
# does not exclude this path, so the committed copy needs one. Written here
# rather than kept only in the committed file, so that the fresh inventory and
# the baseline are byte-comparable.
header() {
  cat <<'EOF'
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# GENERATED FILE - do not edit by hand.
#
# Every third party dependency in the reactor across compile, provided, runtime
# and test scope, with the license its POM resolves to. Wider than the LICENSE
# inside each bundled jar, which covers only what maven-shade-plugin took.
#
# Regenerate with:
#   release/scripts/validate_third_party_inventory.sh --update
#
# A line reading "(Unknown license)" means that artifact's POM declares no
# license and neither does any POM it inherits from. That is not a licensing
# opinion, it is an absence, and it needs one of: an upgrade to a version that
# declares one, evidence recorded in release/license/bundled/supplemental-models.xml
# if the artifact is bundled, or a note in
# release/license/bundled/unresolved-licenses.txt saying what was checked.
EOF
}

echo "Regenerating the third-party inventory. This resolves the whole reactor and is not quick."
./mvnw -B -q org.codehaus.mojo:license-maven-plugin:aggregate-add-third-party \
  -Dmaven.build.cache.enabled=false \
  -DskipTests

if [[ ! -s "${GENERATED}" ]]; then
  echo "FAIL: ${GENERATED} was not produced, or is empty."
  echo "  Nothing can be compared, so this is a failure rather than a pass."
  exit 1
fi

FRESH="$(mktemp)"
trap 'rm -f "${FRESH}"' EXIT
{ header; cat "${GENERATED}"; } > "${FRESH}"

if [[ ${UPDATE} -eq 1 ]]; then
  mkdir -p "$(dirname "${BASELINE}")"
  if [[ -f "${BASELINE}" ]] && diff -q "${BASELINE}" "${FRESH}" >/dev/null 2>&1; then
    echo "OK   ${BASELINE} is already current."
    exit 0
  fi
  if [[ -f "${BASELINE}" ]]; then
    echo "Updating ${BASELINE}:"
    diff -u "${BASELINE}" "${FRESH}" | tail -n +3 || true
  else
    echo "Creating ${BASELINE}."
  fi
  cp "${FRESH}" "${BASELINE}"
  echo
  echo "Updated. Read the diff above before committing it."
  exit 0
fi

if [[ ! -f "${BASELINE}" ]]; then
  echo "FAIL: ${BASELINE} does not exist."
  echo "  Create it with: $(basename "$0") --update"
  exit 1
fi

if diff -u "${BASELINE}" "${FRESH}" > /tmp/third-party-inventory.diff 2>&1; then
  echo "OK   third-party inventory matches ${BASELINE}"
  # Anchored on the report's own line shape. An unanchored match would also
  # count the explanation of "(Unknown license)" in the header above.
  echo "     $(grep -c '^     (' "${BASELINE}" || true) dependencies, $(grep -c '^     (Unknown license)' "${BASELINE}" || true) with no license declared anywhere in their POM chain."
  exit 0
fi

echo "FAIL: the third-party inventory has changed."
echo
sed -n '3,$p' /tmp/third-party-inventory.diff
echo
echo "A dependency was added, removed, upgraded, or changed the license its POM"
echo "resolves to. Read the diff above. If it is intended, record it with:"
echo "  release/scripts/validate_third_party_inventory.sh --update"
exit 1
