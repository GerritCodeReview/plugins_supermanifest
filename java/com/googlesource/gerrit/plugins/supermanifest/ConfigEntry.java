// Copyright (C) 2017 The Android Open Source Project
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

import static com.google.common.base.Strings.nullToEmpty;
import static com.google.gerrit.entities.RefNames.REFS_HEADS;

import com.google.common.collect.ImmutableSet;
import com.google.gerrit.entities.Project;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;

public class ConfigEntry {
  public static final String SECTION_NAME = "superproject";

  final Project.NameKey srcRepoKey;
  final String srcRef;
  final URI baseUri;
  final ToolType toolType;
  final String xmlPath;
  final Project.NameKey destRepoKey;
  final String repoGroups;
  final ImmutableSet<String> srcRefsExcluded;
  final boolean recordSubmoduleLabels;
  final boolean ignoreRemoteFailures;

  // destBranch can be "*" in which case srcRef is ignored.
  final String destBranch;

  public ConfigEntry(Config cfg, String name) throws ConfigInvalidException {
    String[] parts = name.split(":");
    if (parts.length != 2) {
      throw new ConfigInvalidException(
          String.format("pluginName '%s' must have form REPO:BRANCH", name));
    }

    String destRepo = parts[0];
    String destRef = parts[1];

    if (!destRef.startsWith(REFS_HEADS)) {
      throw new ConfigInvalidException(
          String.format("invalid destination '%s'. Must specify refs/heads/", destRef));
    }

    if (destRef.contains("*") && !destRef.equals(REFS_HEADS + "*")) {
      throw new ConfigInvalidException(
          String.format("invalid destination '%s'. Use just '*' for all branches.", destRef));
    }

    String srcRepo = cfg.getString(SECTION_NAME, name, "srcRepo");
    if (srcRepo == null) {
      throw new ConfigInvalidException(String.format("entry %s did not specify srcRepo", name));
    }

    // TODO(hanwen): sanity check repo names.
    srcRepoKey = Project.nameKey(srcRepo);

    String toolType = nullToEmpty(cfg.getString(SECTION_NAME, name, "toolType"));

    switch (toolType) {
      case "":
      case "repo":
        this.toolType = ToolType.Repo;
        break;
      case "jiri":
        this.toolType = ToolType.Jiri;
        break;
      default:
        throw new ConfigInvalidException(
            String.format("entry %s has invalid toolType: %s", name, toolType));
    }

    if (destRef.equals(REFS_HEADS + "*")) {
      srcRef = "";
    } else {
      if (!Repository.isValidRefName(destRef)) {
        throw new ConfigInvalidException(String.format("destination branch '%s' invalid", destRef));
      }

      srcRef = cfg.getString(SECTION_NAME, name, "srcRef");
      if (!Repository.isValidRefName(srcRef)) {
        throw new ConfigInvalidException(String.format("source ref '%s' invalid", srcRef));
      }

      if (srcRef == null) {
        throw new ConfigInvalidException(String.format("entry %s did not specify srcRef", name));
      }
    }

    srcRefsExcluded =
        ImmutableSet.copyOf(
            Arrays.asList(nullToEmpty(cfg.getString(SECTION_NAME, name, "exclude")).split(",")));

    xmlPath = cfg.getString(SECTION_NAME, name, "srcPath");
    if (xmlPath == null) {
      throw new ConfigInvalidException(String.format("entry %s did not specify srcPath", name));
    }

    destRepoKey = Project.nameKey(destRepo);

    // The external format is chosen so we can support copying over tags as well.
    destBranch = destRef.substring(REFS_HEADS.length());

    repoGroups = nullToEmpty(cfg.getString(SECTION_NAME, name, "groups"));
    recordSubmoduleLabels = cfg.getBoolean(SECTION_NAME, name, "recordSubmoduleLabels", false);
    ignoreRemoteFailures = cfg.getBoolean(SECTION_NAME, name, "ignoreRemoteFailures", false);

    try {
      // http://foo/platform/manifest => http://foo/platform/
      baseUri = new URI(srcRepoKey.toString()).resolve("");
    } catch (URISyntaxException exception) {
      throw new ConfigInvalidException("could not build src URL", exception);
    }
  }

  public String src() {
    String src = srcRef;
    if (destBranch.equals("*")) {
      src = "*";
    }
    return srcRepoKey + ":" + src + ":" + xmlPath;
  }

  public String dest() {
    return destRepoKey + ":" + destBranch;
  }

  @Override
  public String toString() {
    return String.format("%s (%s) => %s", src(), toolType, dest());
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    ConfigEntry that = (ConfigEntry) o;
    if (!destRepoKey.equals(that.destRepoKey)) return false;
    return destBranch.equals(that.destBranch);
  }

  @Override
  public int hashCode() {
    return Objects.hash(destRepoKey, destBranch);
  }

  /** @return the srcRepoKey */
  public Project.NameKey getSrcRepoKey() {
    return srcRepoKey;
  }

  /** @return the srcRef */
  public String getSrcRef() {
    return srcRef;
  }

  /** @return the baseUri */
  public URI getBaseUri() {
    return baseUri;
  }

  /** @return the toolType */
  public ToolType getToolType() {
    return toolType;
  }

  /** @return the xmlPath */
  public String getXmlPath() {
    return xmlPath;
  }

  /** @return the destRepoKey */
  public Project.NameKey getDestRepoKey() {
    return destRepoKey;
  }

  /** @return the recordSubmoduleLabels */
  public boolean isRecordSubmoduleLabels() {
    return recordSubmoduleLabels;
  }

  /** @return group restriction suitable for passing to {@code repo init -g} */
  public String getGroupsParameter() {
    return repoGroups;
  }

  /** @return the destBranch */
  public String getDestBranch() {
    return destBranch;
  }

  /**
   * Refs that should not be copied
   *
   * @return the refs listed in the "exclude" option
   */
  public Set<String> getSrcRefsExcluded() {
    return srcRefsExcluded;
  }

  enum ToolType {
    Repo,
    Jiri
  }
}
