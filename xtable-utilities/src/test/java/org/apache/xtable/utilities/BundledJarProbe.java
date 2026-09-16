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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.s3a.auth.STSClientFactory;
import org.apache.hadoop.fs.s3a.impl.ConfigureShadedAWSSocketFactory;
import org.apache.hadoop.security.ssl.DelegatingSSLSocketFactory;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.apache.ApacheHttpClient;

/**
 * Exercises the S3A configuration paths that have broken in the bundled jar, from a JVM whose class
 * path is the jar and nothing else. {@link ITBundledJarRuntime} runs it as a subprocess.
 *
 * <p>Nothing here needs network access: the S3A client is built with the bucket probe off and the
 * region fixed, and the assume-role probe points STS at a reserved domain that never resolves, so
 * the request fails in the SDK after the client was built. The failure this looks for is a {@link
 * LinkageError}, which means the jar ships a class compiled against a build of a dependency it does
 * not contain.
 */
public final class BundledJarProbe {
  private static final String PREFIX = "PROBE ";

  private BundledJarProbe() {}

  public static void main(String[] args) {
    List<String> failures = new ArrayList<>();
    probe("shaded socket factory", BundledJarProbe::shadedSocketFactory, false, failures);
    probe("STS client with endpoint", BundledJarProbe::stsClientWithEndpoint, false, failures);
    probe("S3A with static credentials", () -> s3a(base()), false, failures);
    probe(
        "S3A with an SDK credentials provider named in configuration",
        () -> {
          Configuration conf = base();
          conf.set(
              "fs.s3a.aws.credentials.provider",
              "software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider");
          s3a(conf);
        },
        false,
        failures);
    // AssumedRoleCredentialProvider calls STS while it is constructed, so this one ends in an
    // SdkException from the request. That is the pass: the client was built, which is where
    // the relocated URIBuilder is needed.
    probe(
        "S3A assume-role with an STS endpoint",
        () -> {
          Configuration conf = base();
          conf.set(
              "fs.s3a.aws.credentials.provider",
              "org.apache.hadoop.fs.s3a.auth.AssumedRoleCredentialProvider");
          conf.set("fs.s3a.assumed.role.arn", "arn:aws:iam::123456789012:role/probe");
          conf.set(
              "fs.s3a.assumed.role.credentials.provider",
              "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider");
          conf.set("fs.s3a.assumed.role.sts.endpoint", "sts.probe.invalid");
          conf.set("fs.s3a.assumed.role.sts.endpoint.region", "eu-north-1");
          s3a(conf);
        },
        true,
        failures);
    if (!failures.isEmpty()) {
      System.out.println(PREFIX + "FAILED " + failures);
      System.exit(1);
    }
    System.out.println(PREFIX + "ALL OK");
  }

  private static void shadedSocketFactory() throws Exception {
    new ConfigureShadedAWSSocketFactory()
        .configureSocketFactory(
            ApacheHttpClient.builder(), DelegatingSSLSocketFactory.SSLChannelMode.Default_JSSE);
  }

  private static void stsClientWithEndpoint() throws Exception {
    // getSTSEndpoint runs only when an endpoint is set and needs the relocated URIBuilder.
    STSClientFactory.builder(
            base(),
            "probe-bucket",
            StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIAPROBE", "probe")),
            "sts.eu-north-1.amazonaws.com",
            "eu-north-1")
        .build()
        .close();
  }

  private static void s3a(Configuration conf) throws Exception {
    FileSystem.newInstance(URI.create("s3a://probe-bucket/"), conf).close();
  }

  private static Configuration base() {
    Configuration conf = new Configuration(false);
    conf.set("fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem");
    conf.set("fs.s3a.access.key", "AKIAPROBE");
    conf.set("fs.s3a.secret.key", "probe");
    conf.set("fs.s3a.endpoint", "http://127.0.0.1:1");
    conf.set("fs.s3a.endpoint.region", "eu-north-1");
    conf.set("fs.s3a.path.style.access", "true");
    conf.set("fs.s3a.bucket.probe", "0");
    conf.set("fs.s3a.ssl.channel.mode", "Default_JSSE");
    return conf;
  }

  private static void probe(
      String name, Probe body, boolean sdkRequestFailureIsOk, List<String> failures) {
    try {
      body.run();
      System.out.println(PREFIX + name + ": OK");
    } catch (Throwable t) {
      StringBuilder chain = new StringBuilder();
      boolean linkage = false;
      boolean sdkRequest = false;
      for (Throwable c = t; c != null; c = c.getCause()) {
        chain.append(chain.length() == 0 ? "" : " <- ").append(c);
        linkage |= c instanceof LinkageError;
        sdkRequest |= c instanceof SdkException;
      }
      if (sdkRequestFailureIsOk && sdkRequest && !linkage) {
        System.out.println(
            PREFIX + name + ": OK (request failed after the client was built: " + chain + ")");
        return;
      }
      System.out.println(PREFIX + name + ": FAIL " + chain);
      failures.add(name);
    }
  }

  private interface Probe {
    void run() throws Exception;
  }
}
