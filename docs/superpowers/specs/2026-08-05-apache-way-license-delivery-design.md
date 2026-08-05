<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->

# Delivering LICENSE and NOTICE the Apache way

Design for apache/incubator-xtable issue [#701](https://github.com/apache/incubator-xtable/issues/701).

Date: 2026-08-05

**Base:** PR [#881](https://github.com/apache/incubator-xtable/pull/881), branch
`bump-asf-parent-39` — ASF parent 33 to 39 plus the delombok fix. This work stacks on top of
it, so the PR for #701 depends on #881 merging first.

## Problem

Published jars carry their licensing information twice, and the shaded jars additionally
carry each dependency's own license file wherever that dependency happened to put it.
From #701, for `xtable-aws-0.3.0-incubating.jar`:

```
META-INF/LICENSE
META-INF/LICENSE-bundled          <- describes dependencies that are not in this jar
META-INF/licenses/                <- likewise
```

Both binding voters raised this in the 0.4.0-incubating RC1 vote thread. Stamatis cited
#701 in his `-1`; JB noted that META-INF carries both `LICENSE` and `LICENSE-bundled` and
"seems to be redundant".

The root cause is that `LICENSE-bundled`, `NOTICE-bundled` and `META-INF/licenses/*` live
under `src/main/resources/META-INF/`. They are *shade inputs* — read back via
`IncludeResourceTransformer` when building the `-bundled` artifact — but because they sit in
a resource directory they are also packaged verbatim into the plain jar.

A second problem sits behind it: those files are produced by
`release/scripts/generate_shaded_license_metadata.py`, roughly 800 lines of bespoke Python
with a curated `license_overrides/` tree, gated by `validate_shaded_license_coverage.sh`.
PR #857 documents that the gate has never actually run in CI — the script calls `rg`, which
`ubuntu-latest` does not ship, inside a process substitution where `errexit` cannot see the
failure, so it reports success while validating nothing.

## Goal

Generate `LICENSE` and `NOTICE` automatically, using the ASF's own build tooling —
`maven-remote-resources-plugin` plus the Apache resource transformers that ship with
`maven-shade-plugin` — and delete the bespoke generator.

## Decisions

| Decision | Choice |
|---|---|
| Mechanism | `maven-remote-resources-plugin` for generation, Apache shade transformers for merging. No bespoke generator. |
| Per-dependency license texts | Inlined into the single generated `META-INF/LICENSE`. No `META-INF/licenses/` directory. |
| NOTICE for bundled jars | `ApacheNoticeResourceTransformer`, merged from the bundled dependencies. No maintained NOTICE file. |
| Delivery | One PR against `main`, superseding #743, #857, #866 and #876. |
| Module layout | Unchanged. No separate `-bundle` modules. |

The last two rows of the table reflect a mid-brainstorm correction: an earlier pass had
settled on hand-maintained texts under `META-INF/licenses/` reached by one
`IncludeResourceTransformer` per file. The instruction to "avoid hand maintain at all and do
it automatically with m-remote-resources-p" supersedes that.

## Verified mechanics

Everything below was read from source or from the published artifacts, not from
documentation, because the documentation is incomplete on two of the three points.

### maven-remote-resources-plugin 3.3.0

From `AbstractProcessRemoteResourcesMojo`:

- **A plain file at `${appendedResourcesDirectory}/META-INF/LICENSE` is appended** to the
  bundle's generated output — `new FileOutputStream(outputFile, true)`.
- **A `META-INF/LICENSE.vm` there replaces it**, evaluated by `Velocity.evaluate` against the
  *same context object* the bundle templates get: `$projects`,
  `$projectsSortedByOrganization`, `$projectTimespan`, `$projectName`, `$project`.
- **The Velocity engine is initialised with a `ClasspathResourceLoader` only**
  (`velocity.setProperty("resource.loaders", "classpath")`). The appended-resource path uses
  the *global* `Velocity` singleton, whose default file loader is rooted at `user.dir`.
  `#include` and `#parse` of repository-relative files are therefore not dependable and must
  not be used. License texts have to live inside the template.
- Relevant parameters and defaults: `appendedResourcesDirectory`
  (`${basedir}/src/main/appended-resources`), `outputDirectory`
  (`${project.build.directory}/maven-shared-archive-resources`), `includeScope` (`runtime`),
  `supplementalModels`, `attachToMain` (`true`), `attachToTest` (`true`).

### apache-jar-resource-bundle

1.7 (currently used) and 1.8 (current release) are **byte-identical** for all three
templates.

- `LICENSE.vm` — static Apache-2.0 text. No dependency iteration.
- `NOTICE.vm` — static: project name, `Copyright ${projectTimespan}`, "developed at The
  Apache Software Foundation".
- `DEPENDENCIES.vm` — the only template that iterates. Walks
  `$projectsSortedByOrganization`, emitting per dependency its name, url, artifact
  coordinate and `License: $license.name ($license.url)`.

So the automatic third-party listing must come from our own `LICENSE.vm` override. The
license metadata it consumes comes from dependency POMs, with `supplemental-models.xml`
filling gaps.

### maven-shade-plugin 3.6.2

Both transformers are byte-identical between 3.6.0 and 3.6.2 apart from added `@Override`
annotations, so the reading below holds across the parent upgrade.

- `ApacheLicenseResourceTransformer` discards **every** `META-INF/LICENSE`,
  `META-INF/LICENSE.txt*` and `META-INF/LICENSE.md*` (case-insensitive), *including the
  project's own*, and has no configuration parameters. It leaves `META-INF/licenses/**`
  untouched.
- `ApacheNoticeResourceTransformer` merges `META-INF/NOTICE`, `NOTICE.txt`, `NOTICE.md` from
  every shaded input, grouping by organisation and de-duplicating. Its configurable fields
  are `projectName`, `addHeader`, `preamble1`, `preamble2`, `preamble3`, `organizationName`,
  `organizationURL`, `inceptionYear`, `copyright` and `encoding` — the plugin's
  resource-transformers page documents only `addHeader`. `inceptionYear` defaults to `2006`,
  which would render `Copyright 2006-2026`, so it must be set.
- `IncludeResourceTransformer`'s `<file>` is a `java.io.File`, resolved by Maven's
  configurator relative to the module basedir. It never claims a resource
  (`canTransformResource` returns `false`), so it composes with the other two without
  ordering concerns.

### Current state

On top of #881 the root pom inherits `org.apache:apache:39`. Effective versions:
`maven-remote-resources-plugin` 3.3.0, `maven-shade-plugin` 3.6.2, `maven-jar-plugin` 3.5.0,
`apache-rat-plugin` 0.16.1, `apache-jar-resource-bundle` 1.7. `<inceptionYear>` is 2024.

The three mechanisms this design depends on — the classpath-only Velocity loader, the
append-on-plain-file path and the replace-on-`.vm` path — are all present in
`maven-remote-resources-plugin` 3.3.0, verified against that release's tag rather than
`master`.

Four modules run shade: `xtable-aws`, `xtable-hive-metastore`,
`xtable-hudi-support/xtable-hudi-support-extensions`, `xtable-utilities`. All four use the
same transformer stack — `ApacheLicenseResourceTransformer`, then
`DontIncludeResourceTransformer` for `LICENSE`/`NOTICE`/`NOTICE.txt`, then two
`IncludeResourceTransformer`s reading `target/classes/META-INF/{LICENSE,NOTICE}-bundled`.
`ApacheNoticeResourceTransformer` is used nowhere.

## Design

### Plain jars

Delete `LICENSE-bundled`, `NOTICE-bundled` and the `META-INF/licenses/` directory from
`src/main/resources/META-INF/` in `xtable-aws`, `xtable-hive-metastore` and
`xtable-hudi-support-extensions`. Nothing replaces them.

`xtable-utilities` has no `src/main/resources/META-INF/` directory at all, yet its pom points
an `IncludeResourceTransformer` at `target/classes/META-INF/LICENSE-bundled`. That file never
exists, `hasTransformedResource()` returns `false`, and the transformer silently contributes
nothing — which is why `xtable-utilities-bundled` ships with **no `META-INF/LICENSE`
whatsoever** today, the gap #876 records as its third known issue. This design fixes it as a
side effect: the module gets a generated LICENSE like every other.

The inherited `process-resource-bundles` execution already produces
`META-INF/{LICENSE,NOTICE,DEPENDENCIES,DISCLAIMER}` into
`target/maven-shared-archive-resources`, which is a resource root. Removing the checked-in
files leaves exactly one of each. That closes the non-bundled half of #701 with deletions
only.

`${basedir}/src/main/appended-resources` stays absent in every module, so this execution
keeps emitting the stock Apache-2.0 text.

### Bundled jars

A second `process` execution, declared once in root `pluginManagement` and activated in the
four shaded modules:

```xml
<execution>
  <id>process-bundled-resources</id>
  <goals><goal>process</goal></goals>
  <configuration>
    <outputDirectory>${project.build.directory}/bundled-archive-resources</outputDirectory>
    <appendedResourcesDirectory>${xtable.license.templates}</appendedResourcesDirectory>
    <supplementalModels>
      <supplementalModel>${xtable.license.templates}/supplemental-models.xml</supplementalModel>
    </supplementalModels>
    <properties>
      <projectName>Apache XTable (incubating)</projectName>
    </properties>
    <includeScope>runtime</includeScope>
    <attachToMain>false</attachToMain>
    <attachToTest>false</attachToTest>
  </configuration>
</execution>
```

`attachToMain=false` is what keeps this output out of the plain jar; it writes to a
directory that is never a resource root.

One shared template tree lives at `release/license/bundled/`, addressed by a root-pom
property:

```
release/license/bundled/
  META-INF/LICENSE.vm         <- Apache-2.0 text, then the generated third-party section,
                                 then the full text of each non-ALv2 license in use
  supplemental-models.xml     <- license/organization metadata for thin dependency POMs
```

The template is shared but the output is not: `process` runs per module against that
module's own resolved dependency set, so each bundled jar gets a listing describing itself.

`LICENSE.vm` structure:

1. Verbatim Apache-2.0 text.
2. `#foreach` over `$projectsSortedByOrganization`, emitting for each dependency its
   coordinate, name, url and declared license — the same data `DEPENDENCIES.vm` uses.
   Dependencies whose license resolves to Apache-2.0 need no further treatment.
3. For each distinct non-Apache-2.0 license family present in that set, the full license
   text, guarded by `#if`. Roughly eight families are expected (MIT, BSD 2-Clause, BSD
   3-Clause, EPL 1.0, CDDL 1.0, GPL2+CPE, Public Domain, Bouncy Castle).

Step 3 is the one place static text is maintained, and those texts are immutable boilerplate.
Adding a dependency under an already-covered license requires no edit at all.

### Shade configuration

Replacing the current stack in each of the four modules:

```xml
<transformers>
  <transformer implementation="org.apache.maven.plugins.shade.resource.ApacheLicenseResourceTransformer"/>
  <transformer implementation="org.apache.maven.plugins.shade.resource.ApacheNoticeResourceTransformer">
    <projectName>Apache XTable (incubating)</projectName>
    <inceptionYear>2024</inceptionYear>
  </transformer>
  <transformer implementation="org.apache.maven.plugins.shade.resource.IncludeResourceTransformer">
    <resource>META-INF/LICENSE</resource>
    <file>${project.build.directory}/bundled-archive-resources/META-INF/LICENSE</file>
  </transformer>
</transformers>
```

`DontIncludeResourceTransformer` and both hand-fed `IncludeResourceTransformer`s are removed.
`ApacheLicenseResourceTransformer` drops every incoming `META-INF/LICENSE` including the
project's own; the `IncludeResourceTransformer` then writes the generated one.
`ApacheNoticeResourceTransformer` owns NOTICE outright — no NOTICE file is maintained
anywhere.

`xtable-utilities` keeps its `ManifestResourceTransformer`.

### Resulting jar layout

```
xtable-aws-<v>.jar                  xtable-aws-<v>-bundled.jar
  META-INF/LICENSE                    META-INF/LICENSE     (ALv2 + third-party + texts)
  META-INF/NOTICE                     META-INF/NOTICE      (merged from bundled deps)
  META-INF/DEPENDENCIES               META-INF/DISCLAIMER
  META-INF/DISCLAIMER
```

Exactly one `LICENSE` and one `NOTICE` per jar, which is what #701 asks for.

This differs from the "expected structure" sketched in #701 in one respect: that comment
keeps a `META-INF/licenses/` directory in the bundled jar. Inlining the texts into a single
`LICENSE` is equally compliant — it is what Apache Hadoop and Apache Maven itself do — and it
removes the directory that #701 calls "very hard to review". This should be stated
explicitly on the issue rather than left for a reviewer to notice.

### CI check

`validate_shaded_license_coverage.sh` is replaced by a smaller
`release/scripts/validate_jar_license_layout.sh` asserting, over the built jars:

1. Exactly one `META-INF/LICENSE` and one `META-INF/NOTICE` per jar.
2. No `LICENSE-bundled`, `NOTICE-bundled`, or `META-INF/licenses/` entry in any jar.
3. Every non-Apache-2.0 license family appearing in a bundled jar's generated `LICENSE`
   listing also has its full text present in that same file.

Check 3 is what makes the design safe to leave unattended: a new dependency under an
uncovered license fails the build instead of shipping an incomplete LICENSE.

The script must not use `rg` — `ubuntu-latest` does not ship it, which is exactly how the
current gate came to be a no-op. An empty module list is a failure, not a pass. Both of
those are lessons from #857 and carry over.

PR #876 already contributes a `validate_jar_license_layout.sh` covering checks 1 and 2
against real RC1 artifacts. Reuse it rather than writing a third script.

### Deletions

- `release/scripts/generate_shaded_license_metadata.py`
- `release/scripts/license_overrides/` (as introduced by #857)
- `release/scripts/validate_shaded_license_coverage.sh`
- `*/src/main/resources/META-INF/{LICENSE,NOTICE}-bundled` — 6 files across 3 modules
  (`xtable-utilities` never had them)
- `*/src/main/resources/META-INF/licenses/LICENSE-*` — 22 files: `xtable-aws` 6,
  `xtable-hive-metastore` 11, `xtable-hudi-support-extensions` 5
- The generator sections of `AGENTS.md` (lines ~105, 118, 124, 150, 169, 198-199)

## Open item: shade includes versus runtime scope

`maven-remote-resources-plugin` derives its dependency set from `includeScope=runtime`.
Shade bundles whatever `<artifactSet><includes>` names. The generated LICENSE is only
truthful if those two sets agree.

They do not agree today. [#880](https://github.com/apache/incubator-xtable/issues/880)
records that `xtable-hive-metastore`'s include list names 25 dependencies that are no longer
resolved (`ant:ant`, `asm:*`, `javax.mail:mail`, `oro:oro`, `tomcat:jasper-*`) and omits 97
runtime dependencies that are (all of jetty 9.x, jersey 2.x, hk2, calcite, the `hbase-*`
server modules). The list reads as written for a Hive 2.x dependency set and never updated
for 3.1.3.

Two candidate resolutions:

- **Drop the explicit `<includes>` lists** and let shade bundle the runtime scope. The two
  sets then agree by construction and cannot drift again. This changes what the published
  artifacts contain.
- **Correct the include lists** against the current tree and add a CI check that they match.
  Preserves current artifact contents at the cost of a check that has to keep passing.

The first is better engineering; the second is more conservative for a release branch. This
is a release decision for the PPMC, not a design decision, and it is tracked separately in
#880. Until it is settled, the generated bundled LICENSE for `xtable-hive-metastore` will
describe the runtime tree rather than the include list — which is arguably more accurate
than today's metadata, since today's coordinate list was frozen before the drift.

**This design does not depend on which resolution is chosen.** It should not block on #880.

## Risks and fallbacks

| Risk | Fallback |
|---|---|
| `${maven.multiModuleProjectDirectory}` is set by the Maven launcher and is not dependable in every invocation | Define `xtable.license.templates` in the root pom and override it per module with an explicit relative path (`${project.basedir}/../release/license/bundled`, two levels for `xtable-hudi-support-extensions`) |
| `Velocity.evaluate` on the appended `.vm` may not expose every variable the bundle templates get | Verified to share the same context object; if a variable is missing, fall back to `$projects` which is populated unconditionally |
| Merged NOTICE from `ApacheNoticeResourceTransformer` may read worse than the curated one | Compare output against the current `NOTICE-bundled` before opening the PR; the transformer's `preamble1/2/3` fields allow tuning without abandoning it |
| Reviewers may want `META-INF/licenses/` retained | Raise the inlining decision on #701 *before* the PR, not in review |

## Verification plan

The full reactor builds locally under Temurin 11.0.32, at
`/Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home`, which is the JDK CI uses.
Every step below runs with `JAVA_HOME` set to it, so local results match CI exactly.

#881 makes JDK 21, 25 and 26 work too, by overriding `lombok-maven-plugin`'s embedded lombok.
That is useful but not used here: matching CI removes a variable from an argument about what
ships in a release artifact.

1. `./mvnw apache-rat:check -B`
2. Build each shaded module, then for every produced jar: `jar tf` filtered on
   `LICENSE|NOTICE|licenses/`, asserting the layout above.
3. Diff each generated bundled `LICENSE` against the outgoing `LICENSE-bundled` and account
   for every coordinate that appears or disappears.
4. Diff each merged `NOTICE` against the outgoing `NOTICE-bundled`.
5. Run `validate_jar_license_layout.sh` against the real 0.4.0-incubating-rc1 artifacts and
   confirm it still reports the 21 known offending entries.
6. Confirm the new script runs to completion with `rg` absent from `PATH`.

## Relationship to open PRs

| PR | Disposition |
|---|---|
| #743 — moves `-bundled` files to `src/license/` | Superseded. This deletes them instead. |
| #857 — extends the Python generator | Superseded. The generator is deleted. Its findings on `rg` fail-open and on the junit EPL/CPL correction carry over. |
| #866 — removes orphaned license texts | Superseded. All `META-INF/licenses/` files are deleted. |
| #876 / #875 — `maven-jar-plugin` excludes plus layout validator | Partly superseded. The excludes are unnecessary once the files are deleted; `validate_jar_license_layout.sh` is reused. |

Comment on each with the rationale and let the authors close their own PRs.

## Out of scope

- The `HudiInstantUtils.java` copied-code question (LEGAL-684), tracked on #701's comments.
- `DISCLAIMER-WIP` versus `DISCLAIMER` wording.
- Source-release LICENSE/NOTICE, which are separate top-level files and already correct.
- The ASF parent 33 to 39 upgrade and the delombok fix. Those are #881, which this builds on
  rather than duplicates. If #881 stalls, this work has to be rebased onto `main` and the
  effective plugin versions re-derived — they differ (parent 33 gives
  `maven-remote-resources-plugin` 3.2.0, `maven-shade-plugin` 3.6.0, `maven-jar-plugin`
  3.4.2), though all three mechanisms this design uses are present in both sets.
