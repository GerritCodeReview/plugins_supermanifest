package com.googlesource.gerrit.plugins.supermanifest;

import static com.google.gerrit.reviewdb.client.RefNames.REFS_HEADS;

import com.google.gerrit.reviewdb.client.Project;
import com.googlesource.gerrit.plugins.supermanifest.repo.RepoUpdater;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;

public class ConfigEntry {
  public static final String SECTION_NAME = "superproject";

  Project.NameKey srcRepoKey;
  String srcRef;
  URI baseUri;
  String toolType;
  String xmlPath;
  Project.NameKey destRepoKey;
  boolean recordSubmoduleLabels;

  // destBranch can be "*" in which case srcRef is ignored.
  String destBranch;

  ConfigEntry(Config cfg, String name) throws ConfigInvalidException {
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
    srcRepoKey = new Project.NameKey(srcRepo);

    toolType = cfg.getString(SECTION_NAME, name, "toolType");
    if (toolType == null || toolType == "") {
      toolType = "repo";
    }

    switch (toolType) {
      case "repo":
        break;
      default:
        throw new ConfigInvalidException(String.format("entry %s has invalid toolType", name));
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

    xmlPath = cfg.getString(SECTION_NAME, name, "srcPath");
    if (xmlPath == null) {
      throw new ConfigInvalidException(String.format("entry %s did not specify srcPath", name));
    }

    destRepoKey = new Project.NameKey(destRepo);

    // The external format is chosen so we can support copying over tags as well.
    destBranch = destRef.substring(REFS_HEADS.length());

    recordSubmoduleLabels = cfg.getBoolean(SECTION_NAME, name, "recordSubmoduleLabels", false);

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
    return String.format("%s => %s", src(), dest());
  }

  public SubModuleUpdater getSubModuleUpdater(PersonIdent serverIdent, URI canonicalWebUrl) throws ConfigInvalidException {
    switch (toolType) {
      case "repo":
        return new RepoUpdater(serverIdent, canonicalWebUrl);
      default:
        throw new ConfigInvalidException(String.format("invalid toolType: %s", toolType));
    }
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
  public String getToolType() {
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

  /** @return the destBranch */
  public String getDestBranch() {
    return destBranch;
  }
}
