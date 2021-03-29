// Copyright (C) 2020 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.googlesource.gerrit.plugins.supermanifest;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.entities.Project;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.junit.Test;

public class ConfigEntryTest {

  @Test
  public void singleBranch_ok() throws ConfigInvalidException {
    String conf =
        String.format(
            "[superproject \"%s:%s\"]\n"
                + "  srcRepo = %s\n"
                + "  srcRef = %s\n"
                + "  srcPath = %s\n",
            "superproject", "refs/heads/nyc", "manifest", "refs/heads/nyc-src", "default.xml");

    Config cfg = new Config();
    cfg.fromText(conf);

    ConfigEntry entry = new ConfigEntry(cfg, "superproject:refs/heads/nyc");
    assertThat(entry.getDestRepoKey()).isEqualTo(Project.nameKey("superproject"));
    assertThat(entry.getDestBranch()).isEqualTo("nyc"); // DestBranch removes the refs/heads prefix
    assertThat(entry.getSrcRepoKey()).isEqualTo(Project.nameKey("manifest"));
    assertThat(entry.getSrcRef()).isEqualTo("refs/heads/nyc-src");
    assertThat(entry.getXmlPath()).isEqualTo("default.xml");
  }

  @Test
  public void allBranches_ok() throws ConfigInvalidException {
    String conf =
        getBasicConf(
            "superproject", "refs/heads/*", "manifest", "refs/heads/nyc-src", "default.xml");

    Config cfg = new Config();
    cfg.fromText(conf);

    ConfigEntry entry = new ConfigEntry(cfg, "superproject:refs/heads/*");
    assertThat(entry.getDestRepoKey()).isEqualTo(Project.nameKey("superproject"));
    assertThat(entry.getDestBranch()).isEqualTo("*");
    assertThat(entry.getSrcRepoKey()).isEqualTo(Project.nameKey("manifest"));
    assertThat(entry.getSrcRef()).isEqualTo(""); // Ignored
    assertThat(entry.getXmlPath()).isEqualTo("default.xml");
  }

  @Test
  public void repoGroups() throws ConfigInvalidException {
    StringBuilder builder =
        new StringBuilder(
                getBasicConf(
                    "superproject",
                    "refs/heads/nyc",
                    "manifest",
                    "refs/heads/nyc-src",
                    "default.xml"))
            .append("  groups=a,b,c\n");
    Config cfg = new Config();
    cfg.fromText(builder.toString());

    ConfigEntry entry = new ConfigEntry(cfg, "superproject:refs/heads/nyc");

    assertThat(entry.repoGroups).isEqualTo("a,b,c");
  }

  @Test
  public void recordSubmoduleLabels() throws ConfigInvalidException {
    StringBuilder builder =
        new StringBuilder(
                getBasicConf(
                    "superproject",
                    "refs/heads/nyc",
                    "manifest",
                    "refs/heads/nyc-src",
                    "default.xml"))
            .append("  recordSubmoduleLabels = true\n");
    Config cfg = new Config();
    cfg.fromText(builder.toString());

    ConfigEntry entry = new ConfigEntry(cfg, "superproject:refs/heads/nyc");

    assertThat(entry.recordSubmoduleLabels).isTrue();
  }

  @Test
  public void ignoreRemoteFailures() throws ConfigInvalidException {
    StringBuilder builder =
        new StringBuilder(
                getBasicConf(
                    "superproject",
                    "refs/heads/nyc",
                    "manifest",
                    "refs/heads/nyc-src",
                    "default.xml"))
            .append("  ignoreRemoteFailures = true\n");
    Config cfg = new Config();
    cfg.fromText(builder.toString());

    ConfigEntry entry = new ConfigEntry(cfg, "superproject:refs/heads/nyc");

    assertThat(entry.ignoreRemoteFailures).isTrue();
  }

  @Test
  public void excluded() throws ConfigInvalidException {
    StringBuilder builder =
        new StringBuilder(
                getBasicConf(
                    "superproject",
                    "refs/heads/*",
                    "manifest",
                    "refs/heads/nyc-src",
                    "default.xml"))
            .append("  exclude = refs/heads/a,refs/heads/b,refs/heads/c\n");
    Config cfg = new Config();
    cfg.fromText(builder.toString());

    ConfigEntry entry = new ConfigEntry(cfg, "superproject:refs/heads/*");

    assertThat(entry.srcRefsExcluded)
        .containsExactly("refs/heads/a", "refs/heads/b", "refs/heads/c");
  }

  private String getBasicConf(
      String destRepoKey, String destBranch, String srcRepoKey, String srcRef, String xmlPath) {
    return String.format(
        "[superproject \"%s:%s\"]\n" + "  srcRepo = %s\n" + "  srcRef = %s\n" + "  srcPath = %s\n",
        destRepoKey, destBranch, srcRepoKey, srcRef, xmlPath);
  }
}
