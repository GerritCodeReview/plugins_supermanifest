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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

  private void warn(String formatStr, Object... args) {
    // This assumes that the URL does not contain the % char.
    log.warn(canonicalWebUrl + " : " + formatStr, args);
  }
  private void error(String formatStr, Object... args) {
    log.error(canonicalWebUrl + " : " + formatStr, args);
  }
  private void info(String formatStr, Object... args) {
    log.info(canonicalWebUrl + " : " + formatStr, args);
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
    URI baseUri;
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

      if (destRef.equals(REFS_HEADS + "*")) {
        srcRef = "";
      } else {
        if (!Repository.isValidRefName(destRef)) {
          throw new ConfigInvalidException(
              String.format("destination branch '%s' invalid", destRef));
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
    Config cfg;
    try {
      cfg = cfgFactory.getProjectPluginConfig(allProjectsName, name);
    } catch (NoSuchProjectException e) {
      throw new IllegalStateException(e);
    }

    Set<ConfigEntry> newConf = new HashSet<>();
    Set<String> destinations = new HashSet<>();
    Set<String> wildcardDestinations = new HashSet<>();
    Set<String> sources = new HashSet<>();

    for (String sect : cfg.getSections()) {
      if (!sect.equals(SECTION_NAME)) {
        warn("%s.config: ignoring invalid section %s", name, sect);
      }
    }
    for (String subsect : cfg.getSubsections(SECTION_NAME)) {
      try {
        ConfigEntry configEntry = new ConfigEntry(cfg, subsect);
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
        error("invalid configuration: %s", e);
      }
    }

    return newConf;
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
        error("source repo '%s' does not exist", e.srcRepoKey);
      } else if (!checkRepoExists(e.destRepoKey)) {
        error("destination repo '%s' does not exist", e.destRepoKey);
      } else {
        filtered.add(e);
      }
    }

    config = filtered;
    info("loaded new configuration: %s", configurationToString());
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
        // We only want the trace up to here. We could recurse into the exception, but this at least
        // trims the very common jgit.gitrepo.RepoCommand.RemoteUnavailableException.
        StackTraceElement here = Thread.currentThread().getStackTrace()[1];
        e.setStackTrace(trimStack(e.getStackTrace(), here));

        // We are in an asynchronously called listener, so there is no user action to give
        // feedback to. We log the error, but it would be nice if we could surface these logs
        // somewhere.  Perhaps we could store these as commits in some special branch (but in
        // what repo?).
        error(
        "update for %s (ref %s) failed: %s", c.toString(), event.getRefName(), e);
      }
    }
  }

  /**
   * Remove boring stack frames. This retains the innermost frames up to and including the
   * {@code class#method} passed in {@code ref}.
   */
  @VisibleForTesting
  static StackTraceElement[] trimStack(StackTraceElement[] trace, StackTraceElement ref) {
    List<StackTraceElement> trimmed = new ArrayList<>();
    for (StackTraceElement e : trace) {
      trimmed.add(e);
      if (e.getClassName().equals(ref.getClassName())
          && e.getMethodName().equals(ref.getMethodName())) {
        break;
      }
    }

    return trimmed.toArray(new StackTraceElement[trimmed.size()]);
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

      cmd.setAuthor(serverIdent)
          .setRecordRemoteBranch(true)
          .setRecordSubmoduleLabels(c.recordSubmoduleLabels)
          .setInputStream(manifestStream)
          .setRecommendShallow(true)
          .setRemoteReader(reader)
          .setTargetURI(c.destRepoKey.toString())
          .setURI(c.baseUri.toString());

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
          warn("in repo %s: cannot resolve ref %s", uriStr, refName);
          return null;
        }

        ref = repo.peel(ref);
        ObjectId id = ref.getPeeledObjectId();
        return id != null ? id : ref.getObjectId();
      } catch (RepositoryNotFoundException e) {
        warn("failed to open repository %s: %s", repoName, e);
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
  static String urlToRepoKey(URI baseUri, String name) {
    if (name.startsWith(baseUri.toString())) {
      // It would be nice to parse the URL and do relativize on the Path, but
      // I am lazy, and nio.Path considers the file system and symlinks.
      name = name.substring(baseUri.toString().length());
      while (name.startsWith("/")) {
        name = name.substring(1);
      }
    }
    return name;
  }
}
