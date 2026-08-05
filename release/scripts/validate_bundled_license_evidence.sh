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

# Byte-oriented, not locale-aware. Some embedded license texts are latin-1 rather
# than UTF-8 - jta-1.1's is - and under a UTF-8 locale tr rejects them outright
# with "Illegal byte sequence", which errexit then turns into a silent failure of
# the whole check. The patterns below are ASCII, so nothing is lost.
export LC_ALL=C

# Checks the license we assert for each bundled dependency against the license
# text inside that dependency's own jar.
#
# A POM is a claim; the license file in the artifact is evidence. Where the two
# disagree, the artifact wins, and the disagreement is worth knowing about: the
# retired tooling shipped junit under CPL 1.0 for years because it trusted a
# stale coordinate list rather than the EPL text in junit's own jar. Running this
# on a dependency bump is what catches the next one.
#
# It found two real errors on its first run. Jersey 2.36 was filed under
# Apache-2.0 because its POM lists Apache-2.0 among several licenses - but those
# trailing entries describe third-party components in the Jersey distribution,
# not alternatives for Jersey itself, whose own META-INF/LICENSE.md is EPL 2.0.
# And javax.activation-api declared "CDDL/GPLv2+CE" while shipping CDDL 1.1.
#
# Reads the generated bundled LICENSE files, so run it after a build:
#   ./mvnw package -DskipTests
#   release/scripts/validate_bundled_license_evidence.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${ROOT_DIR}"

M2="${M2_REPO:-${HOME}/.m2/repository}"

# Distinctive phrasing per license family, matched against the whitespace
# collapsed, lowercased text. A jar's own LICENSE is often an aggregate - the
# artifact's license followed by whatever it shades - so every family that
# matches is collected and the assertion only has to appear somewhere in that
# set.
fingerprint() {
  local text="$1" found=""
  grep -qE 'eclipse public license[ -]*v(ersion)? *2\.0' <<<"${text}" && found="${found}|Eclipse Public License 2.0"
  grep -qE 'eclipse public license[ -]*v(ersion)? *1\.0' <<<"${text}" && found="${found}|Eclipse Public License 1.0"
  grep -qE 'common development and distribution license.{0,20}1\.1' <<<"${text}" && found="${found}|Common Development and Distribution License 1.1"
  grep -qE 'common development and distribution license.{0,20}1\.0' <<<"${text}" && found="${found}|Common Development and Distribution License 1.0"
  grep -qE 'mozilla public license version 1\.1' <<<"${text}" && found="${found}|Mozilla Public License 1.1"
  grep -qE 'gnu general public license' <<<"${text}" && found="${found}|GNU General Public License, version 2, with the Classpath Exception"
  grep -qE 'the apache software license, version 1\.1' <<<"${text}" && found="${found}|Apache Software License 1.1"
  grep -qE 'apache license,? version 2\.0|www\.apache\.org/licenses/license-2\.0' <<<"${text}" && found="${found}|Apache License, Version 2.0"
  # "Neither X nor Y may be used to endorse" - the negation is carried by
  # "Neither/nor", so do not require "not" before "be used".
  grep -qE 'neither the name.{0,160}used to endorse' <<<"${text}" && found="${found}|BSD 3-Clause License"
  grep -qE 'redistribution and use in source and binary forms' <<<"${text}" && found="${found}|BSD 2-Clause License"
  grep -qE 'permission is hereby granted, free of charge' <<<"${text}" && found="${found}|MIT License"
  grep -qE 'permission to use, copy, modify, and distribute this software is freely granted' <<<"${text}" && found="${found}|Javolution License"
  grep -qE 'legion of the bouncy castle' <<<"${text}" && found="${found}|Bouncy Castle License"
  printf '%s' "${found}"
}

# Is the license we assert consistent with what the artifact's text shows?
claim_is_supported() {
  local claimed="$1" detected="$2"

  case "${detected}" in
    *"|${claimed}"*) return 0 ;;
  esac

  # An Apache-licensed artifact may carry either the 1.1 or the 2.0 text; both
  # are "Apache", and which one is settled by the supplemental model.
  case "${claimed}" in
    Apache*) case "${detected}" in *"|Apache"*) return 0 ;; esac ;;
  esac

  # This is one document that opens with the CDDL and continues into the GPL.
  case "${claimed}" in
    "CDDL + GPLv2 with classpath exception")
      case "${detected}" in *"|Common Development"*) return 0 ;; esac ;;
  esac

  # MIT-0 is the MIT text minus the attribution requirement, so it matches the
  # MIT fingerprint. Nothing else does, so this cannot mask a real mismatch.
  case "${claimed}" in
    "MIT-0") case "${detected}" in *"|MIT License"*) return 0 ;; esac ;;
  esac

  return 1
}

# coordinate<TAB>license section, from each module's generated bundled LICENSE.
collect_claims() {
  local f
  while IFS= read -r f; do
    # In the generated file a license section is a line fenced above and below by
    # a rule, followed by the "  - coordinate" lines it covers.
    awk '
      { line[NR] = $0 }
      END {
        for (i = 2; i < NR; i++)
          if (line[i-1] ~ /^-{60,}$/ && line[i+1] ~ /^-{60,}$/ && line[i] != "")
            heading[i] = line[i]
        for (i = 1; i <= NR; i++) {
          if (i in heading) section = heading[i]
          if (line[i] ~ /^  - / && section != "") {
            coord = line[i]; sub(/^  - /, "", coord)
            print coord "\t" section
          }
        }
      }
    ' "${f}"
  done < <(find . -path '*/target/bundled-archive-resources/META-INF/LICENSE' -type f | sort)
}

claims="$(collect_claims | sort -u)"

if [[ -z "${claims}" ]]; then
  echo "FAIL: no generated bundled LICENSE files found under */target/bundled-archive-resources."
  echo "  Build first: ./mvnw package -DskipTests"
  exit 1
fi

total=0
mismatches=0

# Three different things used to share one counter, and only one of them says
# anything about the dependency:
#
#   absent      - the artifact is not in the local repository, so this script
#                 could not look. A gap in the audit, not a fact about the
#                 dependency, and the reason it is reported separately: with a
#                 sparse local repository every coordinate lands here and the
#                 run still ends "0 disagreed with what we assert", which reads
#                 as a clean audit and is not one.
#   silent      - the artifact is present and ships no license text of its own,
#                 so its POM really is the only evidence there is.
#   unreadable  - it ships a license text that matches no fingerprint. Worth
#                 knowing: it is usually a license family this script cannot
#                 recognise yet.
own=0
absent=0
silent=0
unreadable=0
corroborated=0

while IFS=$'\t' read -r coord claimed; do
  [[ -z "${coord}" ]] && continue

  # This project's own modules. They are Apache-2.0 by construction and are not
  # third party components, so there is no external claim here to corroborate.
  # They also never reach the local repository: CI builds with `package`, not
  # `install`, so a reactor module exists only under its own target directory.
  # Counting them as unreachable would fail the coverage gate below on every
  # run, for the one set of artifacts whose licensing is not in question.
  if [[ "${coord}" == org.apache.xtable:* ]]; then
    own=$((own + 1))
    continue
  fi

  total=$((total + 1))

  group="${coord%%:*}"
  rest="${coord#*:}"
  artifact="${rest%%:*}"
  version="${rest#*:}"
  jar="${M2}/${group//.//}/${artifact}/${version}/${artifact}-${version}.jar"

  # A coordinate carries no classifier, but the artifact resolved for it may
  # have one - netty-transport-native-epoll is published only as
  # linux-x86_64/aarch_64 and has no plain jar. Without this those artifacts
  # look permanently absent, which under the coverage floor below would fail
  # the build for a reason that has nothing to do with licensing.
  if [[ ! -f "${jar}" ]]; then
    for classified in "${M2}/${group//.//}/${artifact}/${version}/${artifact}-${version}"-*.jar; do
      case "${classified}" in
        *-sources.jar|*-javadoc.jar|*-tests.jar) continue ;;
      esac
      [[ -f "${classified}" ]] && { jar="${classified}"; break; }
    done
  fi

  if [[ ! -f "${jar}" ]]; then
    absent=$((absent + 1))
    continue
  fi

  # Only the artifact's OWN license file. Third-party texts it merely embeds -
  # META-INF/ASM_LICENSE.txt inside datanucleus, META-INF/licenses-binary/ inside
  # hadoop-client-runtime - say nothing about the license of the artifact
  # carrying them, and treating them as evidence produces false findings.
  text=""
  for candidate in META-INF/LICENSE META-INF/LICENSE.txt META-INF/LICENSE.md \
                   LICENSE LICENSE.txt LICENSE.md META-INF/COPYING COPYING; do
    if raw="$(unzip -p "${jar}" "${candidate}" 2>/dev/null)" && [[ -n "${raw}" ]]; then
      text="$(printf '%s' "${raw}" | tr '\n' ' ' | tr -s '[:space:]' ' ' | tr '[:upper:]' '[:lower:]')"
      found_in="${candidate}"
      break
    fi
  done

  if [[ -z "${text}" ]]; then
    silent=$((silent + 1))
    continue
  fi

  detected="$(fingerprint "${text}")"
  if [[ -z "${detected}" ]]; then
    unreadable=$((unreadable + 1))
    continue
  fi

  corroborated=$((corroborated + 1))

  if ! claim_is_supported "${claimed}" "${detected}"; then
    echo "FAIL ${coord}"
    echo "    we assert : ${claimed}"
    echo "    ${found_in} shows : ${detected#|}"
    mismatches=$((mismatches + 1))
  fi
done <<<"${claims}"

echo
echo "Audited ${total} third-party bundled coordinates against ${M2}."
echo "  ${own} of this project's own modules skipped: Apache-2.0 by construction, and not third party."
echo "  ${corroborated} corroborated against the license file in their own jar."
echo "  ${silent} ship no license text of their own, so their POM is the only evidence."
echo "  ${unreadable} ship a license text this script cannot fingerprint."
echo "  ${absent} could not be examined: no jar for them in the local repository."

if [[ ${mismatches} -gt 0 ]]; then
  echo
  echo "${mismatches} artifact(s) ship a license that is not the one we assert."
  echo "Fix by adding a <supplement> to release/license/bundled/supplemental-models.xml"
  echo "naming the license the artifact itself carries. The artifact wins, not the POM."
  exit 1
fi

echo "  0 of the ${corroborated} examined disagreed with what we assert."

# Coverage floor. Everything above this line reports; this is the only part that
# gates, and it gates on whether the audit ran at all rather than on what it
# found. The build that produces the LICENSE files this script reads resolves
# every bundled dependency, so after it every coordinate must have a jar in the
# local repository. When they do not, the run above examined a fraction of the
# bundle and still ended in "0 disagreed", which is the shape of a passing check
# and carries none of its meaning.
if [[ ${absent} -gt 0 ]]; then
  echo
  if [[ "${XTABLE_LICENSE_EVIDENCE_ALLOW_ABSENT:-0}" == "1" ]]; then
    echo "WARN: ${absent} of ${total} coordinates were not examined at all."
    echo "  XTABLE_LICENSE_EVIDENCE_ALLOW_ABSENT=1 is set, so this is not being treated as a failure."
    echo "  The result covers ${corroborated} artifacts, not ${total}. Do not read it as a clean audit."
  else
    echo "FAIL: ${absent} of ${total} coordinates have no jar in ${M2}, so they were never checked."
    echo "  Resolve them first, from the repository root:"
    echo "    ./mvnw package -DskipTests -Dmaven.build.cache.enabled=false"
    echo "  Point the script at another repository with M2_REPO=/path/to/repository, or set"
    echo "  XTABLE_LICENSE_EVIDENCE_ALLOW_ABSENT=1 to accept partial coverage knowingly."
    exit 1
  fi
fi
