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

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Checks the bundled jar itself rather than the Maven test classpath, which is where every shading
 * regression so far has lived: a ServiceLoader entry naming a class the shade whitelist dropped, a
 * method compiled against one build of a dependency and linked against another, or a filter that
 * silently stopped applying.
 */
class ITBundledJar {
  private static final long MAX_JAR_BYTES = 500L * 1024 * 1024;
  private static final String BASELINE = "bundled-jar-linkage-baseline.txt";

  private static Path jar;
  private static ZipFile zip;
  private static List<String> entries;

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
    zip = new ZipFile(jar.toFile());
    entries = new ArrayList<>();
    for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
      entries.add(e.nextElement().getName());
    }
  }

  @Test
  void sizeStaysWithinBudget() throws IOException {
    long bytes = Files.size(jar);
    assertTrue(
        bytes < MAX_JAR_BYTES,
        "bundled jar is "
            + bytes
            + " bytes; the budget is "
            + MAX_JAR_BYTES
            + ". A dependency bump that pulls a fat artifact back in, such as the AWS SDK bundle"
            + " unfiltered, shows up here first.");
  }

  @Test
  void shadeFiltersApply() {
    // An execution-level <filters> block replaces a plugin-level one instead of merging with
    // it, so a filter that lands in the wrong place passes the build and filters nothing.
    List<String> leaked =
        entries.stream()
            .filter(
                e ->
                    e.matches("META-INF/[^/]+\\.(SF|DSA|RSA)")
                        || e.startsWith("librocksdbjni-linux-ppc64le")
                        || e.startsWith("librocksdbjni-linux-s390x")
                        || e.startsWith("librocksdbjni-linux32"))
            .collect(Collectors.toList());
    assertTrue(leaked.isEmpty(), "shade filter excludes are not applied: " + leaked);
    assertTrue(entries.contains("librocksdbjni-linux64.so"), "x86_64 RocksDB native missing");
  }

  @Test
  void serviceRegistrationsResolve() throws IOException {
    Set<String> classes = classIndex();
    List<String> dangling = new ArrayList<>();
    for (String entry : entries) {
      if (!entry.startsWith("META-INF/services/") || entry.endsWith("/")) {
        continue;
      }
      for (String impl : serviceEntries(entry)) {
        if (!classes.contains(impl.replace('.', '/'))) {
          dangling.add(entry + " -> " + impl);
        }
      }
    }
    assertTrue(
        dangling.isEmpty(), "ServiceLoader registrations name dropped classes:\n" + dangling);

    assertTrue(
        serviceEntries("META-INF/services/org.apache.spark.sql.sources.DataSourceRegister")
            .contains("org.apache.spark.sql.delta.sources.DeltaDataSource"),
        "Delta source registration lost; the ServicesResourceTransformer is not merging");
    assertTrue(
        serviceEntries("META-INF/services/software.amazon.awssdk.http.SdkHttpService")
            .contains("software.amazon.awssdk.http.apache.ApacheSdkHttpService"),
        "AWS SDK has no synchronous HTTP client registered");
    assertTrue(
        serviceEntries("META-INF/services/org.apache.hadoop.fs.FileSystem")
            .contains("com.google.cloud.hadoop.fs.gcs.GoogleHadoopFileSystem"),
        "gs:// FileSystem registration lost");
  }

  /**
   * Resolves every class, field and method reference in the jar against the jar and the JDK, the
   * way the JVM would on first use. A class-level check such as jdeps is not enough: the hadoop-aws
   * break in #897 was a method whose parameter type differed between two builds of the same
   * artifact, which only a member-level resolution sees.
   *
   * <p>A fat jar carries optional references that are dangling by design, so the check is against a
   * baseline. A new dangling reference fails; a baseline entry that resolved again is printed so
   * the baseline can be trimmed.
   */
  @Test
  void noNewDanglingReferences() throws IOException {
    Linkage linkage = new Linkage(zip, entries);
    TreeSet<String> missing = linkage.missingTargets();
    Set<String> baseline = baseline();

    List<String> stale =
        baseline.stream().filter(b -> !missing.contains(b)).collect(Collectors.toList());
    if (!stale.isEmpty()) {
      System.out.println(
          "Baseline entries that resolve again and can be removed from " + BASELINE + ":");
      stale.forEach(s -> System.out.println("  " + s));
    }

    List<String> added =
        missing.stream().filter(m -> !baseline.contains(m)).collect(Collectors.toList());
    assertTrue(
        added.isEmpty(),
        "References that no longer resolve inside the bundled jar (NoClassDefFoundError or"
            + " NoSuchMethodError at runtime). Add to "
            + BASELINE
            + " only if the caller is"
            + " unreachable from RunSync and RunCatalogSync:\n"
            + added.stream().map(a -> "  " + a).collect(Collectors.joining("\n")));
  }

  private static Set<String> classIndex() {
    Set<String> classes = new HashSet<>();
    for (String entry : entries) {
      if (!entry.endsWith(".class")) {
        continue;
      }
      String name = entry;
      if (name.startsWith("META-INF/versions/")) {
        // META-INF/versions/<n>/pkg/Name.class
        name = name.substring(name.indexOf('/', "META-INF/versions/".length()) + 1);
      }
      classes.add(name.substring(0, name.length() - ".class".length()));
    }
    return classes;
  }

  private static List<String> serviceEntries(String entry) throws IOException {
    ZipEntry e = zip.getEntry(entry);
    if (e == null) {
      return Collections.emptyList();
    }
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(zip.getInputStream(e), StandardCharsets.UTF_8))) {
      return reader
          .lines()
          .map(l -> l.indexOf('#') >= 0 ? l.substring(0, l.indexOf('#')) : l)
          .map(String::trim)
          .filter(l -> !l.isEmpty())
          .collect(Collectors.toList());
    }
  }

  private static Set<String> baseline() throws IOException {
    try (InputStream in = ITBundledJar.class.getResourceAsStream("/" + BASELINE)) {
      if (in == null) {
        return Collections.emptySet();
      }
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        return reader
            .lines()
            .map(String::trim)
            .filter(l -> !l.isEmpty() && !l.startsWith("#"))
            .collect(Collectors.toSet());
      }
    }
  }

  /** Constant-pool level linkage resolution over a jar. */
  static final class Linkage {
    private static final ClassLoader JDK = ClassLoader.getSystemClassLoader().getParent();

    private final ZipFile zip;
    private final Map<String, String> classToEntry = new HashMap<>();
    private final Map<String, ClassInfo> parsed = new HashMap<>();
    private final Map<String, Boolean> jdkClasses = new HashMap<>();
    private final List<String> scanOrder = new ArrayList<>();

    Linkage(ZipFile zip, List<String> entries) {
      this.zip = zip;
      for (String entry : entries) {
        if (!entry.endsWith(".class") || entry.endsWith("module-info.class")) {
          continue;
        }
        if (entry.startsWith("META-INF/versions/")) {
          String name = entry.substring(entry.indexOf('/', "META-INF/versions/".length()) + 1);
          classToEntry.putIfAbsent(name.substring(0, name.length() - 6), entry);
          continue;
        }
        classToEntry.put(entry.substring(0, entry.length() - 6), entry);
        scanOrder.add(entry.substring(0, entry.length() - 6));
      }
    }

    TreeSet<String> missingTargets() throws IOException {
      TreeSet<String> missing = new TreeSet<>();
      for (String className : scanOrder) {
        ClassInfo info = parse(className);
        for (String ref : info.classRefs) {
          if (!classExists(ref)) {
            missing.add(ref);
          }
        }
        for (String[] ref : info.memberRefs) {
          String owner = ref[0];
          if (owner.startsWith("[")) {
            continue; // array pseudo-members such as clone()
          }
          if (!classExists(owner)) {
            missing.add(owner);
          } else if (!isJdk(owner) && !hasMember(owner, ref[1] + ":" + ref[2], new HashSet<>())) {
            missing.add(owner + "." + ref[1] + (ref[2].startsWith("(") ? ref[2] : ":" + ref[2]));
          }
        }
        // Every class is scanned once as a referrer; keep only what resolution needs.
        if (!info.referenced) {
          parsed.remove(className);
        }
      }
      return missing;
    }

    private boolean classExists(String name) {
      return classToEntry.containsKey(name) || isJdk(name);
    }

    private boolean isJdk(String name) {
      if (classToEntry.containsKey(name)) {
        return false;
      }
      return jdkClasses.computeIfAbsent(
          name,
          n -> {
            try {
              Class.forName(n.replace('/', '.'), false, JDK);
              return true;
            } catch (ClassNotFoundException | LinkageError e) {
              return false;
            }
          });
    }

    /** True when {@code owner} or a supertype declares the member, or the chain leaves the jar. */
    private boolean hasMember(String owner, String member, Set<String> seen) throws IOException {
      if (!seen.add(owner)) {
        return false;
      }
      if (!classToEntry.containsKey(owner)) {
        // A JDK supertype, checked by reflection. A supertype that is in neither place is
        // reported as a missing class by the class-reference pass.
        return isJdk(owner) && jdkHasMember(jdkClass(owner), member, new HashSet<>());
      }
      ClassInfo info = parse(owner);
      info.referenced = true;
      if (info.members.contains(member)) {
        return true;
      }
      if (info.superName != null && hasMember(info.superName, member, seen)) {
        return true;
      }
      for (String itf : info.interfaces) {
        if (hasMember(itf, member, seen)) {
          return true;
        }
      }
      return false;
    }

    private static Class<?> jdkClass(String name) {
      try {
        return Class.forName(name.replace('/', '.'), false, JDK);
      } catch (ClassNotFoundException | LinkageError e) {
        throw new IllegalStateException(name, e);
      }
    }

    private static boolean jdkHasMember(Class<?> c, String member, Set<Class<?>> seen) {
      if (c == null || !seen.add(c)) {
        return false;
      }
      for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
        if (member.equals(
            m.getName() + ":" + descriptor(m.getParameterTypes(), m.getReturnType()))) {
          return true;
        }
      }
      for (java.lang.reflect.Constructor<?> ctor : c.getDeclaredConstructors()) {
        if (member.equals("<init>:" + descriptor(ctor.getParameterTypes(), void.class))) {
          return true;
        }
      }
      for (java.lang.reflect.Field f : c.getDeclaredFields()) {
        if (member.equals(f.getName() + ":" + descriptor(f.getType()))) {
          return true;
        }
      }
      if (jdkHasMember(c.getSuperclass(), member, seen)) {
        return true;
      }
      for (Class<?> itf : c.getInterfaces()) {
        if (jdkHasMember(itf, member, seen)) {
          return true;
        }
      }
      return false;
    }

    private static String descriptor(Class<?>[] parameters, Class<?> returnType) {
      StringBuilder sb = new StringBuilder("(");
      for (Class<?> p : parameters) {
        sb.append(descriptor(p));
      }
      return sb.append(')').append(descriptor(returnType)).toString();
    }

    private static String descriptor(Class<?> c) {
      if (c.isArray()) {
        return "[" + descriptor(c.getComponentType());
      }
      if (!c.isPrimitive()) {
        return "L" + c.getName().replace('.', '/') + ";";
      }
      switch (c.getName()) {
        case "int":
          return "I";
        case "long":
          return "J";
        case "boolean":
          return "Z";
        case "byte":
          return "B";
        case "char":
          return "C";
        case "short":
          return "S";
        case "float":
          return "F";
        case "double":
          return "D";
        default:
          return "V";
      }
    }

    private ClassInfo parse(String className) throws IOException {
      ClassInfo cached = parsed.get(className);
      if (cached != null) {
        return cached;
      }
      try (DataInputStream in =
          new DataInputStream(zip.getInputStream(zip.getEntry(classToEntry.get(className))))) {
        ClassInfo info = ClassInfo.read(in);
        parsed.put(className, info);
        return info;
      } catch (IOException | RuntimeException e) {
        throw new UncheckedIOException(new IOException("cannot parse " + className, e));
      }
    }
  }

  /** The parts of a class file that linkage needs: supertypes, declared members, references. */
  static final class ClassInfo {
    String superName;
    final List<String> interfaces = new ArrayList<>();
    final Set<String> members = new HashSet<>();
    final Set<String> classRefs = new HashSet<>();
    final List<String[]> memberRefs = new ArrayList<>();
    boolean referenced;

    static ClassInfo read(DataInputStream in) throws IOException {
      if (in.readInt() != 0xCAFEBABE) {
        throw new IOException("not a class file");
      }
      in.readUnsignedShort(); // minor
      in.readUnsignedShort(); // major
      int count = in.readUnsignedShort();
      Object[] pool = new Object[count];
      byte[] tags = new byte[count];
      for (int i = 1; i < count; i++) {
        int tag = in.readUnsignedByte();
        tags[i] = (byte) tag;
        switch (tag) {
          case 1:
            pool[i] = in.readUTF();
            break;
          case 3:
          case 4:
            in.readInt();
            break;
          case 5:
          case 6:
            in.readLong();
            i++;
            break;
          case 7:
          case 8:
          case 16:
          case 19:
          case 20:
            pool[i] = in.readUnsignedShort();
            break;
          case 9:
          case 10:
          case 11:
          case 12:
          case 17:
          case 18:
            pool[i] = new int[] {in.readUnsignedShort(), in.readUnsignedShort()};
            break;
          case 15:
            in.readUnsignedByte();
            in.readUnsignedShort();
            break;
          default:
            throw new IOException("unknown constant pool tag " + tag);
        }
      }
      ClassInfo info = new ClassInfo();
      for (int i = 1; i < count; i++) {
        if (tags[i] == 7) {
          String name = elementType((String) pool[(Integer) pool[i]]);
          if (name != null) {
            info.classRefs.add(name);
          }
        } else if (tags[i] == 9 || tags[i] == 10 || tags[i] == 11) {
          int[] ref = (int[]) pool[i];
          String owner = (String) pool[(Integer) pool[ref[0]]];
          int[] nameAndType = (int[]) pool[ref[1]];
          info.memberRefs.add(
              new String[] {owner, (String) pool[nameAndType[0]], (String) pool[nameAndType[1]]});
        }
      }
      in.readUnsignedShort(); // access flags
      in.readUnsignedShort(); // this class
      int superIndex = in.readUnsignedShort();
      info.superName = superIndex == 0 ? null : (String) pool[(Integer) pool[superIndex]];
      int interfaceCount = in.readUnsignedShort();
      for (int i = 0; i < interfaceCount; i++) {
        info.interfaces.add((String) pool[(Integer) pool[in.readUnsignedShort()]]);
      }
      for (int section = 0; section < 2; section++) { // fields, then methods
        int memberCount = in.readUnsignedShort();
        for (int i = 0; i < memberCount; i++) {
          in.readUnsignedShort(); // access flags
          String name = (String) pool[in.readUnsignedShort()];
          String descriptor = (String) pool[in.readUnsignedShort()];
          info.members.add(name + ":" + descriptor);
          skipAttributes(in);
        }
      }
      return info;
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
      int attributeCount = in.readUnsignedShort();
      for (int i = 0; i < attributeCount; i++) {
        in.readUnsignedShort(); // name
        int length = in.readInt();
        while (length > 0) {
          int skipped = in.skipBytes(length);
          if (skipped <= 0) {
            throw new IOException("truncated attribute");
          }
          length -= skipped;
        }
      }
    }

    /** Strips array dimensions; returns null for primitive arrays. */
    private static String elementType(String name) {
      if (!name.startsWith("[")) {
        return name;
      }
      int i = 0;
      while (name.charAt(i) == '[') {
        i++;
      }
      return name.charAt(i) == 'L' ? name.substring(i + 1, name.length() - 1) : null;
    }
  }
}
