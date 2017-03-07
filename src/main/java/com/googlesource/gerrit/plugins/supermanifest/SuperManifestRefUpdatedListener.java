// Copyright (C) 2016 The Android Open Source Project
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

import static com.google.gerrit.reviewdb.client.RefNames.REFS_HEADS;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.GerritPersonIdent;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.CanonicalWebUrl;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.io.FilenameUtils;
import org.apache.http.client.utils.URIBuilder;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.gitrepo.ManifestParser;
import org.eclipse.jgit.gitrepo.RepoCommand;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This plugin will listen for changes to XML files in manifest repositories. When it finds such
 * changes, it will trigger an update of the associated superproject.
 */
@Singleton
class SuperManifestRefUpdatedListener implements GitReferenceUpdatedListener, LifecycleListener {
  private static final Logger log = LoggerFactory.getLogger(SuperManifestRefUpdatedListener.class);

  private static final String SECTION_NAME = "superproject";

  private final GitRepositoryManager repoManager;
  private final URI canonicalWebUrl;
  private final PluginConfigFactory cfgFactory;
  private final String pluginName;
  private final AllProjectsName allProjectsName;
  private final ProjectCache projectCache;
  private final PersonIdent serverIdent;

  // Mutable.
  private Set<ConfigEntry> config;

  @Inject
  SuperManifestRefUpdatedListener(
      AllProjectsName allProjectsName,
      @CanonicalWebUrl String canonicalWebUrl,
      @PluginName String pluginName,
      PluginConfigFactory cfgFactory,
      ProjectCache projectCache,
      @GerritPersonIdent PersonIdent serverIdent,
      GitRepositoryManager repoManager) {
    this.pluginName = pluginName;
    this.serverIdent = serverIdent;
    this.allProjectsName = allProjectsName;
    this.repoManager = repoManager;
    try {
      this.canonicalWebUrl = new URI(canonicalWebUrl);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(e);
    }

    this.cfgFactory = cfgFactory;
    this.projectCache = projectCache;
  }

  private static byte[] readBlob(Repository repo, String idStr) throws IOException {
    try (ObjectReader reader = repo.newObjectReader()) {
      ObjectId id = repo.resolve(idStr);
      if (id == null) {
        throw new RevisionSyntaxException(
            String.format("repo %s does not have %s", repo.toString(), idStr), idStr);
      }
      return reader.open(id).getCachedBytes(Integer.MAX_VALUE);
    }
  }

  private static class ConfigEntry {
    Project.NameKey srcRepoKey;
    String srcRef;
    String srcRepoUrl;
    String xmlPath;
    Project.NameKey destRepoKey;
    boolean recordSubmoduleLabels;

    // destBranch can be "*" in which case srcRef is ignored.
    String destBranch;

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
  }

  /*
     [superproject "submodules:refs/heads/nyc"]
        srcRepo = platforms/manifest
        srcRef = refs/heads/nyc
        srcPath = manifest.xml

  */
  private Set<ConfigEntry> parseConfiguration(PluginConfigFactory cfgFactory, String name) {
    Config cfg = null;
    try {
      cfg = cfgFactory.getProjectPluginConfig(allProjectsName, name);
    } catch (NoSuchProjectException e) {
      Preconditions.checkState(false);
    }

    Set<ConfigEntry> newConf = new HashSet<>();
    Set<String> destinations = new HashSet<>();
    Set<String> wildcardDestinations = new HashSet<>();
    Set<String> sources = new HashSet<>();

    for (String sect : cfg.getSections()) {
      if (!sect.equals(SECTION_NAME)) {
        log.warn(name + ".config: ignoring invalid section " + sect);
      }
    }
    for (String subsect : cfg.getSubsections(SECTION_NAME)) {
      try {
        ConfigEntry configEntry = newConfigEntry(cfg, subsect);
        if (destinations.contains(configEntry.srcRepoKey.get())
            || sources.contains(configEntry.destRepoKey.get())) {
          // Don't want cyclic dependencies.
          throw new ConfigInvalidException(
              String.format("repo in entry %s cannot be both source and destination", configEntry));
        }
        if (configEntry.destBranch.equals("*")) {
          if (wildcardDestinations.contains(configEntry.destRepoKey.get())) {
            throw new ConfigInvalidException(
                String.format(
                    "repo %s already has a wildcard destination branch.", configEntry.destRepoKey));
          }
          wildcardDestinations.add(configEntry.destRepoKey.get());
        }


        sources.add(configEntry.srcRepoKey.get());
        destinations.add(configEntry.destRepoKey.get());

        newConf.add(configEntry);

      } catch (ConfigInvalidException e) {
        log.error("ConfigInvalidException: " + e.toString());
      }
    }

    return newConf;
  }

  private ConfigEntry newConfigEntry(Config cfg, String name) throws ConfigInvalidException {
    String[] parts = name.split(":");
    if (parts.length != 2) {
      throw new ConfigInvalidException(
          String.format("pluginName '%s' must have form REPO:BRANCH", name));
    }

    String destRepo = FilenameUtils.normalize(parts[0]);
    String destRef = parts[1];

    if (!destRef.startsWith(REFS_HEADS)) {
      throw new ConfigInvalidException(
          String.format("invalid destination '%s'. Must specify refs/heads/", destRef));
    }

    if (destRef.contains("*") && !destRef.equals(REFS_HEADS + "*")) {
      throw new ConfigInvalidException(
          String.format("invalid destination '%s'. Use just '*' for all branches.", destRef));
    }

    ConfigEntry e = new ConfigEntry();
    String srcRepo = cfg.getString(SECTION_NAME, name, "srcRepo");
    if (srcRepo == null) {
      throw new ConfigInvalidException(String.format("entry %s did not specify srcRepo", name));
    }

    // TODO(hanwen): sanity check repo names.
    srcRepo = FilenameUtils.normalize(srcRepo);
    e.srcRepoKey = new Project.NameKey(srcRepo);

    if (destRef.equals(REFS_HEADS + "*")) {
      e.srcRef = "";
    } else {
      if (!Repository.isValidRefName(destRef)) {
        throw new ConfigInvalidException(String.format("destination branch '%s' invalid", destRef));
      }

      e.srcRef = cfg.getString(SECTION_NAME, name, "srcRef");
      if (!Repository.isValidRefName(e.srcRef)) {
        throw new ConfigInvalidException(String.format("source ref '%s' invalid", e.srcRef));
      }

      if (e.srcRef == null) {
        throw new ConfigInvalidException(String.format("entry %s did not specify srcRef", name));
      }
    }

    e.xmlPath = cfg.getString(SECTION_NAME, name, "srcPath");
    if (e.xmlPath == null) {
      throw new ConfigInvalidException(String.format("entry %s did not specify srcPath", name));
    }

    e.destRepoKey = new Project.NameKey(destRepo);

    // The external format is chosen so we can support copying over tags as well.
    e.destBranch = destRef.substring(REFS_HEADS.length());

    e.recordSubmoduleLabels = cfg.getBoolean(SECTION_NAME, name, "recordSubmoduleLabels", false);
    e.srcRepoUrl = e.srcRepoKey.toString();
    return e;
  }

  /**
   * Example: relativeRepoKey("dir//from", "dir/to") => "../to".
   */
  @VisibleForTesting
  static String relativeRepoKey(String from, String to) {
    String[] dst = FilenameUtils.normalize(to).split("/");
    String[] src = FilenameUtils.normalize(from).split("/");

    int i = 0;
    while (i < dst.length && i < src.length && src[i].equals(dst[i])) {
      i++;
    }

    ArrayList<String> result = new ArrayList<>();

    for (int j = i; j < src.length; j++ ) {
      result.add("..");
    }
    for (int j = i; j < dst.length; j++) {
      result.add(dst[j]);
    }

    return Joiner.on("/").join(result);
  }

  private boolean checkRepoExists(Project.NameKey id) {
    return projectCache.get(id) != null;
  }

  @Override
  public void stop() {}

  @Override
  public void start() {
    updateConfiguration();
  }

  /** for debugging. */
  private String configurationToString() {
    StringBuilder b = new StringBuilder();
    b.append("number of configuration entries: " + config.size() + "\n");
    for (ConfigEntry c : config) {
      b.append(c.toString() + "\n");
    }
    return b.toString();
  }

  private void updateConfiguration() {
    Set<ConfigEntry> entries = parseConfiguration(cfgFactory, pluginName);

    Set<ConfigEntry> filtered = new HashSet<>();
    for (ConfigEntry e : entries) {
      if (!checkRepoExists(e.srcRepoKey)) {
        log.error(String.format("source repo '%s' does not exist", e.srcRepoKey));
      } else if (!checkRepoExists(e.destRepoKey)) {
        log.error(String.format("destination repo '%s' does not exist", e.destRepoKey));
      } else {
        filtered.add(e);
      }
    }

    config = filtered;
    log.info("loaded new configuration: " + configurationToString());
  }

  @Override
  public synchronized void onGitReferenceUpdated(Event event) {
    if (event.getProjectName().equals(allProjectsName.get())) {
      if (event.getRefName().equals("refs/meta/config")) {
        updateConfiguration();
      }
      return;
    }

    for (ConfigEntry c : config) {
      if (!c.srcRepoKey.get().equals(event.getProjectName())) {
        continue;
      }

      if (!(c.destBranch.equals("*") || c.srcRef.equals(event.getRefName()))) {
        continue;
      }

      if (c.destBranch.equals("*") && !event.getRefName().startsWith(REFS_HEADS)) {
        continue;
      }

      try {
        update(c, event.getRefName());
      } catch (IOException | GitAPIException e) {
        // We are in an asynchronously called listener, so there is no user action to give
        // feedback to. We log the error, but it would be nice if we could surface these logs
        // somewhere.  Perhaps we could store these as commits in some special branch (but in
        // what repo?).
        log.error(
            String.format("update for %s (ref %s) failed", c.toString(), event.getRefName()), e);
      }
    }
  }

  private static class GerritIncludeReader implements ManifestParser.IncludedFileReader {
    private final Repository repo;
    private final String ref;

    GerritIncludeReader(Repository repo, String ref) {
      this.repo = repo;
      this.ref = ref;
    }

    @Override
    public InputStream readIncludeFile(String path) throws IOException {
      String blobRef = ref + ":" + path;
      return new ByteArrayInputStream(readBlob(repo, blobRef));
    }
  }

  private void update(ConfigEntry c, String srcRef) throws IOException, GitAPIException {
    try (GerritRemoteReader reader = new GerritRemoteReader()) {
      Repository destRepo = reader.openRepository(c.destRepoKey.toString());
      Repository srcRepo = reader.openRepository(c.srcRepoKey.toString());

      RepoCommand cmd = new RepoCommand(destRepo);

      if (c.destBranch.equals("*")) {
        cmd.setTargetBranch(srcRef.substring(REFS_HEADS.length()));
      } else {
        cmd.setTargetBranch(c.destBranch);
      }

      InputStream manifestStream =
          new ByteArrayInputStream(readBlob(srcRepo, srcRef + ":" + c.xmlPath));

      cmd.setAuthor(serverIdent);
      cmd.setRecordRemoteBranch(true);
      cmd.setRecordSubmoduleLabels(c.recordSubmoduleLabels);
      cmd.setInputStream(manifestStream);
      cmd.setRecommendShallow(true);
      cmd.setRemoteReader(reader);
      cmd.setURI(c.srcRepoUrl);

      // Must setup a included file reader; the default is to read the file from the filesystem
      // otherwise, which would leak data from the serving machine.
      cmd.setIncludedFileReader(new GerritIncludeReader(srcRepo, srcRef));

      RevCommit commit = cmd.call();
    }
  }

  // GerritRemoteReader is for injecting Gerrit's Git implementation into JGit.
  private class GerritRemoteReader implements RepoCommand.RemoteReader, Closeable {
    private final Map<String, Repository> repos;

    GerritRemoteReader() {
      this.repos = new HashMap<>();
    }

    @Override
    public ObjectId sha1(String uriStr, String refName) throws GitAPIException {
      URI url;
      try {
        url = new URI(uriStr);
      } catch (URISyntaxException e) {
        // TODO(hanwen): is there a better exception for this?
        throw new InvalidRemoteException(e.getMessage());
      }

      String repoName = url.getPath();
      while (repoName.startsWith("/")) {
        repoName = repoName.substring(1);
      }

      try {
        Repository repo = openRepository(repoName);
        Ref ref = repo.findRef(refName);
        if (ref == null || ref.getObjectId() == null) {
          log.warn(String.format("in repo %s: cannot resolve ref %s", uriStr, refName));
          return null;
        }

        ref = repo.peel(ref);
        ObjectId id = ref.getPeeledObjectId();
        return id != null ? id : ref.getObjectId();
      } catch (RepositoryNotFoundException e) {
        log.warn("failed to open repository: " + repoName, e);
        return null;
      } catch (IOException io) {
        RefNotFoundException e =
            new RefNotFoundException(String.format("cannot open %s to read %s", repoName, refName));
        e.initCause(io);
        throw e;
      }
    }

    @Override
    public byte[] readFile(String repoName, String ref, String path)
        throws GitAPIException, IOException {
      Repository repo;
      repo = openRepository(repoName);
      return readBlob(repo, ref + ":" + path);
    }

    private Repository openRepository(String name) throws IOException {
      name = urlToRepoKey(canonicalWebUrl, name);
      if (repos.containsKey(name)) {
        return repos.get(name);
      }

      Repository repo = repoManager.openRepository(new Project.NameKey(name));
      repos.put(name, repo);
      return repo;
    }

    @Override
    public void close() {
      for (Repository repo : repos.values()) {
        repo.close();
      }
      repos.clear();
    }
  }

  @VisibleForTesting
  static String urlToRepoKey(URI baseUrl, String name) {
    if (name.startsWith(baseUrl.toString())) {
      // It would be nice to parse the URL and do relativize on the Path, but
      // I am lazy, and nio.Path considers the file system and symlinks.
      name = name.substring(baseUrl.toString().length());
      while (name.startsWith("/")) {
        name = name.substring(1);
      }
    }
    return name;
  }

}
