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
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.GerritPersonIdent;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.CanonicalWebUrl;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.permissions.GlobalPermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.project.BranchResource;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.gitrepo.RepoCommand;
import org.eclipse.jgit.gitrepo.RepoCommand.RemoteFile;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * This plugin will listen for changes to XML files in manifest repositories. When it finds such
 * changes, it will trigger an update of the associated superproject.
 */
@Singleton
public class SuperManifestRefUpdatedListener
    implements GitReferenceUpdatedListener,
        LifecycleListener,
        RestModifyView<BranchResource, BranchInput> {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final GitRepositoryManager repoManager;
  private final URI canonicalWebUrl;
  private final PluginConfigFactory cfgFactory;
  private final String pluginName;
  private final AllProjectsName allProjectsName;
  private final ProjectCache projectCache;
  private final Provider<PersonIdent> serverIdent;
  private final Provider<IdentifiedUser> identifiedUser;
  private final PermissionBackend permissionBackend;

  // Mutable.
  private Set<ConfigEntry> config;

  @Inject
  SuperManifestRefUpdatedListener(
      AllProjectsName allProjectsName,
      @CanonicalWebUrl String canonicalWebUrl,
      @PluginName String pluginName,
      PluginConfigFactory cfgFactory,
      ProjectCache projectCache,
      @GerritPersonIdent Provider<PersonIdent> serverIdent,
      GitRepositoryManager repoManager,
      Provider<IdentifiedUser> identifiedUser,
      PermissionBackend permissionBackend) {

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
    this.identifiedUser = identifiedUser;
    this.permissionBackend = permissionBackend;
  }

  private void warn(String formatStr, Object... args) {
    logger.atWarning().log("%s: %s", canonicalWebUrl, String.format(formatStr, args));
  }

  private void error(String formatStr, Object... args) {
    logger.atSevere().log("%s: %s", canonicalWebUrl, String.format(formatStr, args));
  }

  private void info(String formatStr, Object... args) {
    logger.atInfo().log("%s: %s", canonicalWebUrl, String.format(formatStr, args));
  }

  /*
     [superproject "submodules:refs/heads/nyc"]
        srcRepo = platforms/manifest
        srcRef = refs/heads/nyc
        srcPath = manifest.xml
  */
  private Set<ConfigEntry> parseConfiguration(PluginConfigFactory cfgFactory, String name)
      throws NoSuchProjectException {
    Config cfg = cfgFactory.getProjectPluginConfig(allProjectsName, name);

    Set<ConfigEntry> newConf = new HashSet<>();
    Set<String> destinations = new HashSet<>();
    Set<String> wildcardDestinations = new HashSet<>();
    Set<String> sources = new HashSet<>();

    for (String sect : cfg.getSections()) {
      if (!sect.equals(ConfigEntry.SECTION_NAME)) {
        warn("%s.config: ignoring invalid section %s", name, sect);
      }
    }
    for (String subsect : cfg.getSubsections(ConfigEntry.SECTION_NAME)) {
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
    try {
      updateConfiguration();
    } catch (NoSuchProjectException e) {
      warn("can't read configuration: %s", e.getMessage());
    }
  }

  /** for debugging. */
  private String configurationToString() {
    StringBuilder b = new StringBuilder();
    b.append("Supermanifest config (").append(config.size()).append(") {\n");
    for (ConfigEntry c : config) {
      b.append(" ").append(c).append("\n");
    }
    b.append("}\n");
    return b.toString();
  }

  private void updateConfiguration() throws NoSuchProjectException {
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
  }

  @Override
  public synchronized void onGitReferenceUpdated(Event event) {
    if (event.getProjectName().equals(allProjectsName.get())) {
      if (event.getRefName().equals("refs/meta/config")) {
        try {
          updateConfiguration();
        } catch (NoSuchProjectException e) {
          throw new IllegalStateException(e);
        }
      }
      return;
    }
    try {
      update(event.getProjectName(), event.getRefName(), true);
    } catch (Exception e) {
      // no exceptions since we set continueOnError = true.
    }
  }

  @Override
  public Response<?> apply(BranchResource resource, BranchInput input)
      throws IOException, ConfigInvalidException, GitAPIException, AuthException,
          PermissionBackendException {
    permissionBackend.currentUser().check(GlobalPermission.ADMINISTRATE_SERVER);
    info(
        "manual trigger for %s:%s by %d. Config: %s",
        resource.getBranchKey().getParentKey().get(),
        resource.getBranchKey().get(),
        identifiedUser.get().getAccountId().get(),
        configurationToString());

    update(resource.getProjectState().getProject().getName(), resource.getRef(), false);
    return Response.none();
  }

  /**
   * Updates projects in response to update in given project/ref. Only throws exceptions if
   * continueOnError is false.
   */
  private void update(String project, String refName, boolean continueOnError)
      throws IOException, GitAPIException, ConfigInvalidException {
    for (ConfigEntry c : config) {
      if (!c.srcRepoKey.get().equals(project)) {
        continue;
      }

      if (!(c.destBranch.equals("*") || c.srcRef.equals(refName))) {
        continue;
      }

      if (c.destBranch.equals("*") && !refName.startsWith(REFS_HEADS)) {
        continue;
      }

      try {
        updateForConfig(c, refName);
      } catch (ConfigInvalidException | IOException | GitAPIException e) {
        if (!continueOnError) {
          throw e;
        }
        // We only want the trace up to here. We could recurse into the exception, but this at least
        // trims the very common jgit.gitrepo.RepoCommand.RemoteUnavailableException.
        StackTraceElement here = Thread.currentThread().getStackTrace()[1];
        e.setStackTrace(trimStack(e.getStackTrace(), here));

        // We are in an asynchronously called listener, so there is no user action to give
        // feedback to. We log the error, but it would be nice if we could surface these logs
        // somewhere.  Perhaps we could store these as commits in some special branch (but in
        // what repo?).
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        error("update for %s (ref %s) failed: %s", c.toString(), refName, sw);
      }
    }
  }

  private void updateForConfig(ConfigEntry c, String refName)
      throws ConfigInvalidException, IOException, GitAPIException {
    SubModuleUpdater subModuleUpdater;
    switch (c.getToolType()) {
      case Repo:
        subModuleUpdater = new RepoUpdater(serverIdent.get(), canonicalWebUrl);
        break;
      case Jiri:
        subModuleUpdater = new JiriUpdater(serverIdent.get(), canonicalWebUrl);
        break;
      default:
        throw new ConfigInvalidException(
            String.format("invalid toolType: %s", c.getToolType().name()));
    }
    try (GerritRemoteReader reader = new GerritRemoteReader()) {
      subModuleUpdater.update(reader, c, refName);
    }
  }

  /**
   * Remove boring stack frames. This retains the innermost frames up to and including the {@code
   * class#method} passed in {@code ref}.
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

  // GerritRemoteReader is for injecting Gerrit's Git implementation into JGit.
  class GerritRemoteReader implements RepoCommand.RemoteReader, Closeable {
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

      // This is a (mis)feature of JGit, which ignores SHA1s but only if ignoreRemoteFailures
      // is set.
      if (ObjectId.isId(refName)) {
        return ObjectId.fromString(refName);
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
    public RemoteFile readFileWithMode(String repoName, String ref, String path)
        throws GitAPIException, IOException {
      Repository repo = openRepository(repoName);
      Ref r = repo.findRef(ref);
      if (r == null || r.getObjectId() == null) {
        throw new RevisionSyntaxException(
            String.format("repo %s does not have ref %s", repo.toString(), ref), ref);
      }
      RevCommit commit = repo.parseCommit(r.getObjectId());
      TreeWalk tw = TreeWalk.forPath(repo, path, commit.getTree());
      return new RemoteFile(
          tw.getObjectReader().open(tw.getObjectId(0)).getCachedBytes(Integer.MAX_VALUE),
          tw.getFileMode(0));
    }

    public Repository openRepository(String name) throws IOException {
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
