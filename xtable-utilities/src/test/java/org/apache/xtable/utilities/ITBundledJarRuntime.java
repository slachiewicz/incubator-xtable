/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
 
package org.apache.xtable.utilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.apache.hudi.common.model.HoodieTableType;

import org.apache.xtable.GenericTable;
import org.apache.xtable.TestJavaHudiTable;

/**
 * Runs the bundled jar in a separate JVM, the way users run it. The other tests in this module call
 * {@link RunSync#main} on the Maven test classpath, which never sees what shading dropped, merged
 * or relocated.
 */
class ITBundledJarRuntime {
  private static Path jar;

  @BeforeAll
  static void locateJar() throws IOException {
    Path target = Paths.get("target");
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(target, "*-bundled.jar")) {
      List<Path> jars = new ArrayList<>();
      stream.forEach(jars::add);
      assertEquals(
          1, jars.size(), "expected exactly one bundled jar in " + target.toAbsolutePath());
      jar = jars.get(0);
    }
  }

  @Test
  void runSyncFromBundledJar(@TempDir Path tempDir) throws Exception {
    String tableName = "bundled-jar-table";
    try (GenericTable table =
        TestJavaHudiTable.forStandardSchema(
            tableName, tempDir, null, HoodieTableType.COPY_ON_WRITE)) {
      table.insertRows(20);
      File configFile = writeConfigFile(tempDir, table, tableName);

      Path log = tempDir.resolve("run-sync.log");
      int exit = run(log, "-jar", jar.toString(), "--datasetConfig", configFile.getPath());
      assertEquals(0, exit, "java -jar exited with " + exit + "\n" + read(log));

      Path metadata = Paths.get(URI.create(table.getBasePath() + "/metadata"));
      try (Stream<Path> files = Files.list(metadata)) {
        assertTrue(
            files.anyMatch(p -> p.toString().endsWith("metadata.json")),
            "no Iceberg metadata written by the bundled jar\n" + read(log));
      }
    }
  }

  /**
   * The S3A paths that broke in the shaded jar, run against the jar alone. See {@link
   * BundledJarProbe} for what each probe covers.
   */
  @Test
  void s3aConfigurationPathsLink(@TempDir Path tempDir) throws Exception {
    Path log = tempDir.resolve("probe.log");
    int exit =
        run(
            log,
            "-cp",
            Paths.get("target", "test-classes") + File.pathSeparator + jar,
            BundledJarProbe.class.getName());
    String output = read(log);
    assertEquals(0, exit, "probe exited with " + exit + "\n" + output);
    assertTrue(output.contains("PROBE ALL OK"), output);
  }

  private static int run(Path log, String... args) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-Xmx1500m");
    command.addAll(Arrays.asList(args));
    Process process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    if (!process.waitFor(5, TimeUnit.MINUTES)) {
      process.destroyForcibly();
      throw new AssertionError("timed out: " + command + "\n" + read(log));
    }
    return process.exitValue();
  }

  private static String read(Path log) {
    try {
      byte[] bytes = Files.readAllBytes(log);
      int from = Math.max(0, bytes.length - 20_000);
      return new String(bytes, from, bytes.length - from, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return "(no log: " + e + ")";
    }
  }

  private static File writeConfigFile(Path tempDir, GenericTable table, String tableName)
      throws IOException {
    RunSync.DatasetConfig config =
        RunSync.DatasetConfig.builder()
            .sourceFormat("HUDI")
            .targetFormats(Collections.singletonList("ICEBERG"))
            .datasets(
                Collections.singletonList(
                    RunSync.DatasetConfig.Table.builder()
                        .tableBasePath(table.getBasePath())
                        .tableName(tableName)
                        .build()))
            .build();
    File configFile = tempDir.resolve("config.yaml").toFile();
    RunSync.YAML_MAPPER.writeValue(configFile, config);
    return configFile;
  }
}
