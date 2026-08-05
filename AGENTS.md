<!--
 - Licensed to the Apache Software Foundation (ASF) under one
 - or more contributor license agreements.  See the NOTICE file
 - distributed with this work for additional information
 - regarding copyright ownership.  The ASF licenses this file
 - to you under the Apache License, Version 2.0 (the
 - "License"); you may not use this file except in compliance
 - with the License.  You may obtain a copy of the License at
 -
 -     http://www.apache.org/licenses/LICENSE-2.0
 -
 - Unless required by applicable law or agreed to in writing, software
 - distributed under the License is distributed on an "AS IS" BASIS,
 - WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 - See the License for the specific language governing permissions and
 - limitations under the License.
-->

# Apache XTable Agent Guide

This file gives repository-specific instructions for agents working in this repo.

## Scope

- Applies to the whole repository unless a deeper `AGENTS.md` is added later.

## Repo Structure

- `xtable-api`: shared public interfaces and SPI contracts.
- `xtable-core`: core conversion logic, sync flow, and common implementation code.
- `xtable-hudi-support`: Hudi-specific support modules, including shaded extensions under `xtable-hudi-support-extensions`.
- `xtable-utilities`: bundled CLI and shaded distribution jar.
- `xtable-aws`: AWS-related shaded support dependencies.
- `xtable-hive-metastore`: Hive Metastore shaded support dependencies.
- `xtable-service`: service-layer code.
- `release/scripts`: release and compliance automation, including shaded license tooling.
- `spec` and `rfc`: design docs and proposal material.
- `website`: project site content.

When changing code, prefer to work in the narrowest module that owns the behavior. If a change crosses module boundaries, verify all affected modules instead of only the top-level caller.

## Common Validation

Use Java 11 for local Maven work.

Common commands:

```bash
./mvnw test
./mvnw verify
./mvnw spotless:check
./mvnw spotless:apply
```

Prefer targeted commands while iterating:

```bash
./mvnw -pl <module[,module...]> test
./mvnw -pl <module[,module...]> verify
./mvnw -pl <module[,module...]> -Dtest=<TestClass> test
```

Test control flags wired in the root `pom.xml`:

- `-DskipTests` skips both unit and integration tests.
- `-DskipUTs` skips surefire unit tests only.
- `-DskipITs` skips failsafe integration tests only.

Validation expectations:

- For a focused code change, run the narrowest module test or verify command that covers the edited behavior.
- For shared build logic, parent POM changes, cross-module APIs, or release tooling, run broader Maven validation before finishing.
- If formatting might be affected, run `./mvnw spotless:check` and use `./mvnw spotless:apply` if needed.

## Dependency Changes

When adding, removing, or upgrading a dependency, always follow this sequence:

1. Update the relevant `pom.xml` dependency declarations.
2. If the module uses `maven-shade-plugin`, regenerate the runtime dependency tree and keep the shade `<artifactSet><includes>` list aligned to runtime dependencies only.
3. Regenerate bundled license metadata for shaded modules.
4. Run the shaded license validator.
5. Run the narrowest Maven verification needed for the changed modules.

Do not stop after updating the Maven dependency declaration alone.

## Shaded Modules

Modules with `maven-shade-plugin` must use explicit `<artifactSet><includes>` entries.

Rules:

- Includes must reflect the current `dependency:tree -Dscope=runtime` output.
- Do not include `provided` or `test` dependencies.
- Do not hand-wave transitive dependencies; if they are shaded, they must be listed explicitly.
- If a dependency upgrade changes the runtime tree, update the include list to match.

Current shaded modules include:

- `xtable-aws`
- `xtable-hive-metastore`
- `xtable-hudi-support/xtable-hudi-support-extensions`
- `xtable-utilities`

`LICENSE` and `NOTICE` are generated, not maintained by hand.

- `maven-remote-resources-plugin` renders each shaded module's bundled `META-INF/LICENSE` from that module's own resolved dependency tree, using the shared template `release/license/bundled/META-INF/LICENSE.vm`.
- `maven-shade-plugin`'s `ApacheNoticeResourceTransformer` assembles `META-INF/NOTICE` by merging the bundled dependencies' own NOTICE files.
- The only hand-edited inputs are `release/license/bundled/supplemental-models.xml`, which names a dependency's license canonically when its POM does not, and the license texts inside `LICENSE.vm`.

A dependency whose declared license is not recognised produces an `UNMAPPED-LICENSE` line and fails the build. Fix it by adding a `<supplement>`, or by adding the license text to `LICENSE.vm` if the license is new here. Only if the artifact genuinely does not say - no `<licenses>` block, no license file in the jar - record it in `release/license/bundled/unresolved-licenses.txt` with what you checked.

Each shaded module declares `<xtable.bundled.artifactIds>` mirroring its shade `<artifactSet><includes>`. The runtime tree is a strict superset of what is bundled, so without that filter the LICENSE would describe components that are not in the jar. The validator fails if the two lists disagree, so update both together.

## Required Commands For Dependency Work

Generate runtime dependency trees for changed shaded modules:

```bash
./mvnw -pl <module[,module...]> -am -DskipTests dependency:tree -Dscope=runtime -DoutputType=text -DoutputFile=target/dependency-tree-runtime.txt
```

Validate the jar license layout, the bundled artifactId lists and license coverage:

```bash
release/scripts/validate_jar_license_layout.sh
```

It builds the shaded modules itself if their jars are not present.

Check the license we assert for each bundled dependency against the license text inside that dependency's own jar:

```bash
release/scripts/validate_bundled_license_evidence.sh
```

Diff the whole reactor's third-party licence inventory against the committed copy:

```bash
release/scripts/validate_third_party_inventory.sh            # check
release/scripts/validate_third_party_inventory.sh --update   # accept the change
```

The two checks above ask whether what ships is accounted for. This one asks what changed, and answers as a diff, so a dependency bump arrives in review as the lines it altered rather than as a pass or a fail. It covers compile, provided, runtime and test scope, well outside any bundle, so a Category X dependency is visible long before it could reach one. Run `--update` when a dependency changes, and read the diff before committing it.

The inventory comes from `license-maven-plugin`, which resolves licenses through Maven's own model builder. That is the point of using it: `bcprov-jdk18on` publishes a POM with no `xmlns`, and `hadoop-project-3.1.0.pom` contains `<Xlint:-unchecked/>`, an undeclared namespace prefix. A hand-written POM reader silently reports the first as undeclared and loses the license every `hadoop-yarn` artifact inherits from the second. Do not reach for the plugin's `failOnMissing` or `failOnBlacklist`: both were tried against a tree with five `(Unknown license)` artifacts in the same run's report, and neither failed the build. The diff is the gate.

Run this on every dependency bump. A POM is a claim; the license file in the artifact is evidence, and where they disagree the artifact wins. This is the check that catches a junit-style error - the retired tooling shipped junit under CPL 1.0 while junit's own jar carries the EPL text. On its first run it found Jersey 2.36 filed under Apache-2.0 (its POM lists Apache-2.0 among several, but those trailing entries describe third-party components in the Jersey distribution, not alternatives for Jersey, whose own `META-INF/LICENSE.md` is EPL 2.0) and `javax.activation-api` declaring `CDDL/GPLv2+CE` while shipping CDDL 1.1.

If only one or two modules changed, prefer targeted Maven verification:

```bash
./mvnw -pl <module[,module...]> -am -DskipTests dependency:tree -Dscope=runtime -DoutputType=text -DoutputFile=target/dependency-tree-runtime.txt
```

If broader confidence is needed, run:

```bash
./mvnw clean install -ntp -B
```

## Bundled License Metadata

Shaded modules must keep these files current:

- `src/main/resources/META-INF/LICENSE-bundled`
- `src/main/resources/META-INF/NOTICE-bundled`

Do not hand-edit a generated `LICENSE` or `NOTICE`: both are build output. Change the inputs instead.

Notes:

- A POM listing several `<license>` entries means the component may be used under any one of them, so a single Apache-2.0 option settles it. The template relies on this; do not "fix" it by recording every entry.
- Category B licenses can be present in convenience binaries. They appear as their own section in the generated LICENSE, so they stay visible.
- A bundled dependency whose license cannot be accounted for fails the build.

## GitHub Actions

Pull requests are expected to pass:

- `.github/workflows/mvn-ci-build.yml`
- `.github/workflows/mvn-license-check.yml`

The license workflow runs:

- `./mvnw apache-rat:check -B`
- `release/scripts/validate_jar_license_layout.sh`
- `release/scripts/validate_bundled_license_evidence.sh`
- `release/scripts/validate_third_party_inventory.sh`

If you change dependency behavior, assume this workflow must still pass.

For non-dependency changes, expect the main Maven CI workflow to be the baseline bar. If you touch release scripts or contributor automation, also sanity-check the affected scripts locally when practical.

## Editing Guidance

- Keep generated dependency/license sections deterministic and sorted where the repo already expects that.
- Prefer targeted edits and targeted Maven runs before broader builds.
- Do not remove bundled metadata files from shaded modules.
- If you add a new shaded module, update this guide and ensure the generator/validator cover it.
- Preserve ASF license headers in new source files and scripts when they are required by surrounding repo conventions.
- Avoid unrelated formatting churn in large generated or metadata-heavy files.
- Check for module-local resources under `src/main/resources` and `src/test/resources` when behavior depends on bundled configs or sample files.

## Non-Dependency Change Checklist

- Identify the owning module and keep the change as narrow as practical.
- Run targeted tests or `verify` for the affected module set.
- Run `spotless:check` if Java formatting may have changed.
- If you changed release tooling or CI-facing scripts, run those scripts directly when possible.
- If you changed shaded modules incidentally, make sure bundled license metadata still matches the current shaded dependencies.

## Final Checklist For Dependency PRs

- Dependency declarations updated.
- Runtime tree regenerated for changed shaded modules.
- Shade include lists match runtime dependencies only.
- `<xtable.bundled.artifactIds>` updated alongside any change to a shade `<artifactSet><includes>`.
- `release/scripts/validate_jar_license_layout.sh` run successfully.
- `release/scripts/validate_bundled_license_evidence.sh` run successfully.
- `release/license/third-party-inventory.txt` regenerated with `validate_third_party_inventory.sh --update`, and its diff read rather than just committed.
- Before declaring a license unresolvable, the dependency's `-sources.jar` checked: `javolution` and `jsp-api` both state theirs in source headers while their POM and binary jar say nothing.
- Any expected Category B warnings reviewed.
- Relevant Maven verification command run successfully.
