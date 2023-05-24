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

import static com.google.gerrit.server.update.context.RefUpdateContext.RefUpdateType.PLUGIN;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.google.gerrit.extensions.config.DownloadScheme;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.PreconditionFailedException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.metrics.Counter1;
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.Field;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.metrics.Timer1;
import com.google.gerrit.server.GerritPersonIdent;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.CanonicalWebUrl;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.logging.PluginMetadata;
import com.google.gerrit.server.permissions.GlobalPermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.plugincontext.PluginMapContext;
import com.google.gerrit.server.project.BranchResource;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.update.context.RefUpdateContext;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.assistedinject.Assisted;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.eclipse.jgit.api.errors.ConcurrentRefUpdateException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.gitrepo.RepoCommand;
import org.eclipse.jgit.gitrepo.RepoCommand.RemoteFile;
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

  private final SuperManifestRepoManager.Factory repoManagerFactory;
  private final URI canonicalWebUrl;
  private final AllProjectsName allProjectsName;
  private final ProjectCache projectCache;
  private final ConfigParser configParser;
  private final Provider<PersonIdent> serverIdent;
  private final Provider<IdentifiedUser> identifiedUser;
  private final PermissionBackend permissionBackend;
  private final PluginMapContext<DownloadScheme> downloadScheme;
  private final Counter1<String> manifestUpdateResultCounter;
  private final Timer1<ConfigEntry.ToolType> superprojectCommitTimer;

  @Inject
  SuperManifestRefUpdatedListener(
      AllProjectsName allProjectsName,
      @CanonicalWebUrl String canonicalWebUrl,
      PluginMapContext<DownloadScheme> downloadScheme,
      ConfigParser configParser,
      ProjectCache projectCache,
      @GerritPersonIdent Provider<PersonIdent> serverIdent,
      SuperManifestRepoManager.Factory repoManagerFactory,
      Provider<IdentifiedUser> identifiedUser,
      PermissionBackend permissionBackend,
      MetricMaker metrics) {

    this.configParser = configParser;
    this.serverIdent = serverIdent;
    this.allProjectsName = allProjectsName;
    this.repoManagerFactory = repoManagerFactory;
    try {
      this.canonicalWebUrl = new URI(canonicalWebUrl);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(e);
    }

    this.downloadScheme = downloadScheme;
    this.projectCache = projectCache;
    this.identifiedUser = identifiedUser;
    this.permissionBackend = permissionBackend;
    this.manifestUpdateResultCounter =
        metrics.newCounter(
            "supermanifest/update_result",
            new Description(
                "Result of a manifest update for a specific conf (all conf parsed fine)"),
            Field.ofString(
                    "result",
                    (metadataBuilder, fieldValue) ->
                        metadataBuilder
                            .pluginName("supermanifest")
                            .addPluginMetadata(PluginMetadata.create("update_result", fieldValue)))
                .description("result of a manifest update")
                .build());
    this.superprojectCommitTimer =
        metrics.newTimer(
            "supermanifest/superproject_commit_latency",
            new Description("Time taken to parse the manifest and update the superproject"),
            Field.ofEnum(
                    ConfigEntry.ToolType.class,
                    "tool",
                    (metadataBuilder, fieldValue) ->
                        metadataBuilder
                            .pluginName("supermanifest")
                            .addPluginMetadata(PluginMetadata.create("tool", fieldValue)))
                .description("Tool handling the manifest (repo or jiri)")
                .build());
  }

  @FormatMethod
  private void warn(@FormatString String formatStr, Object... args) {
    logger.atWarning().log("%s: %s", canonicalWebUrl, String.format(formatStr, args));
  }

  @FormatMethod
  private void error(@FormatString String formatStr, Object... args) {
    logger.atSevere().log("%s: %s", canonicalWebUrl, String.format(formatStr, args));
  }

  @FormatMethod
  private void errorAtMostOncePerDay(@FormatString String formatStr, Object... args) {
    logger.atSevere().atMostEvery(1, TimeUnit.DAYS).log(
        "%s: %s", canonicalWebUrl, String.format(formatStr, args));
  }

  @FormatMethod
  private void errorWithCause(Exception e, @FormatString String formatStr, Object... args) {
    logger.atSevere().withCause(e).log("%s: %s", canonicalWebUrl, String.format(formatStr, args));
  }

  @FormatMethod
  private void info(@FormatString String formatStr, Object... args) {
    logger.atInfo().log("%s: %s", canonicalWebUrl, String.format(formatStr, args));
  }

  private boolean checkRepoExists(Project.NameKey id) {
    return projectCache.get(id) != null;
  }

  @Override
  public void stop() {}

  @Override
  public void start() {
    try {
      getConfiguration();
    } catch (NoSuchProjectException e) {
      warn("can't read configuration: %s", e.getMessage());
    }
  }

  /** for debugging. */
  private String configurationToString() {
    try {
      Set<ConfigEntry> cfg = getConfiguration();
      StringBuilder b = new StringBuilder();
      b.append("Supermanifest config (").append(cfg.size()).append(") {\n");
      for (ConfigEntry c : cfg) {
        b.append(" ").append(c).append("\n");
      }
      b.append("}\n");
      return b.toString();
    } catch (NoSuchProjectException e) {
      return String.format(
          "The supermanifest configuration could not be loaded from the %s project",
          allProjectsName);
    }
  }

  private ImmutableSet<ConfigEntry> getConfiguration() throws NoSuchProjectException {
    Set<ConfigEntry> entries = configParser.parseConfiguration();
    Set<ConfigEntry> filtered = new HashSet<>();
    for (ConfigEntry e : entries) {
      if (!checkRepoExists(e.srcRepoKey)) {
        errorAtMostOncePerDay(
            "the supermanifest configuration in the %s project contains source repo '%s' that does"
                + " not exist",
            allProjectsName, e.srcRepoKey);
      } else if (!checkRepoExists(e.destRepoKey)) {
        errorAtMostOncePerDay(
            "the supermanifest configuration in the %s project contains destination repo '%s' that"
                + " does not exist",
            allProjectsName, e.destRepoKey);
      } else {
        filtered.add(e);
      }
    }

    return ImmutableSet.copyOf(filtered);
  }

  @Override
  public void onGitReferenceUpdated(Event event) {
    if (event.getProjectName().equals(allProjectsName.get())) {
      // supermanifest updates on All-Projects not supported
      return;
    }
    if (RefNames.isNoteDbMetaRef(event.getRefName())) {
      // NoteDb meta ref updates never cause supermanifest updates.
      return;
    }

    try {
      List<ConfigEntry> relevantConfigEntries =
          findRelevantConfigs(getConfiguration(), event.getProjectName(), event.getRefName());
      for (ConfigEntry relevantConfig : relevantConfigEntries) {
        try {
          updateForConfig(relevantConfig, event.getRefName());
        } catch (ConfigInvalidException | IOException | GitAPIException e) {
          // We only want the trace up to here. We could recurse into the exception, but this at
          // least
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
          error(
              "update for %s (ref %s) failed: %s",
              relevantConfig.toString(), event.getRefName(), sw);
        }
      }
    } catch (ConfigInvalidException e) {
      error(
          "Failed parsing supermanifest for project %s and ref %s: %s",
          event.getProjectName(), event.getRefName(), e.getMessage());
    } catch (NoSuchProjectException e) {
      error(
          "Failed to do supermanifest updates for project %s and ref %s because the plugin could"
              + " not read the supermanifest configuration from the %s project",
          event.getProjectName(), event.getRefName(), allProjectsName);
    }
  }

  @Override
  public Response<?> apply(BranchResource resource, BranchInput input)
      throws AuthException, PermissionBackendException, PreconditionFailedException {
    permissionBackend.currentUser().check(GlobalPermission.ADMINISTRATE_SERVER);
    String manifestProject = resource.getBranchKey().project().get();
    String manifestBranch = resource.getBranchKey().branch();
    info(
        "manual trigger for %s:%s by %d. Config: %s",
        manifestProject,
        manifestBranch,
        identifiedUser.get().getAccountId().get(),
        configurationToString());

    ImmutableSet<ConfigEntry> config;
    try {
      config = getConfiguration();
    } catch (NoSuchProjectException e) {
      error(
          "Plugin could not read the supermanifest configuration from the %s project"
              + " (processing %s:%s)",
          allProjectsName, manifestProject, manifestBranch);
      throw new PreconditionFailedException(
          String.format(
              "Plugin could not read the supermanifest configuration from the %s project",
              allProjectsName));
    }

    List<ConfigEntry> relevantConfigs;
    try {
      relevantConfigs =
          findRelevantConfigs(
              config, resource.getProjectState().getProject().getName(), resource.getRef());
    } catch (ConfigInvalidException e) {
      error("manual trigger for %s:%s: %s", manifestProject, manifestBranch, e.getMessage());
      throw new PreconditionFailedException("Invalid configuration");
    }

    if (relevantConfigs.isEmpty()) {
      info(
          "manual trigger for %s:%s: no configs found, nothing to do.",
          manifestProject, manifestBranch);
      return Response.none();
    }
    for (ConfigEntry configEntry : relevantConfigs) {
      try {
        updateForConfig(configEntry, resource.getRef());
      } catch (ConfigInvalidException e) {
        errorWithCause(e, "Invalid conf processing %s:%s", manifestProject, manifestBranch);
        throw new PreconditionFailedException(e.getMessage());
      } catch (GitAPIException | IOException e) {
        errorWithCause(e, "Internal error processing %s:%s", manifestProject, manifestBranch);
        return Response.withStatusCode(500, "Internal error: " + e.getMessage());
      }
    }
    return Response.ok();
  }

  private List<ConfigEntry> findRelevantConfigs(
      ImmutableSet<ConfigEntry> config, String project, String refName)
      throws ConfigInvalidException {
    List<ConfigEntry> relevantConfigs =
        config.stream().filter(c -> c.matchesSource(project, refName)).collect(Collectors.toList());

    // Don't write twice to same destination (no overlaps)
    Map<String, ConfigEntry> destinations = new HashMap<>();
    for (ConfigEntry configEntry : relevantConfigs) {
      String key = configEntry.getDestRepoKey() + ":" + configEntry.getActualDestBranch(refName);
      if (destinations.containsKey(key)) {
        throw new ConfigInvalidException(
            String.format(
                "Configuration overlap %s:%s writes to %s twice (confs %s and %s)",
                project, refName, key, configEntry, destinations.get(key)));
      }
      destinations.put(key, configEntry);
    }
    return relevantConfigs;
  }

  private void updateForConfig(ConfigEntry configEntry, String refName)
      throws ConfigInvalidException, IOException, GitAPIException {
    SubModuleUpdater subModuleUpdater;
    switch (configEntry.getToolType()) {
      case Repo:
        subModuleUpdater = new RepoUpdater(serverIdent.get());
        break;
      case Jiri:
        subModuleUpdater = new JiriUpdater(serverIdent.get(), canonicalWebUrl, downloadScheme);
        break;
      default:
        throw new ConfigInvalidException(
            String.format("invalid toolType: %s", configEntry.getToolType().name()));
    }

    String status = "NOT_ATTEMPTED";
    try (RefUpdateContext ctx = RefUpdateContext.open(PLUGIN);
        GerritRemoteReader reader =
            new GerritRemoteReader(
                repoManagerFactory.create(configEntry), canonicalWebUrl.toString());
        Timer1.Context<ConfigEntry.ToolType> ignored =
            superprojectCommitTimer.start(configEntry.toolType)) {
      subModuleUpdater.update(reader, configEntry, refName);
      status = "OK";
    } catch (ConcurrentRefUpdateException e) {
      status = "LOCK_FAILURE";
      throw e;
    } catch (ConfigInvalidException e) {
      status = "INVALID_SUBMODULE_CONFIGURATION";
      throw e;
    } catch (GitAPIException e) {
      status = "INTERNAL";
      throw e;
    } catch (IOException e) {
      status = "IO_ERROR";
      throw e;
    } finally {
      manifestUpdateResultCounter.increment(status);
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
  static class GerritRemoteReader implements RepoCommand.RemoteReader, AutoCloseable {
    private static final FluentLogger logger = FluentLogger.forEnclosingClass();
    private final String canonicalWebUrl;
    private final SuperManifestRepoManager repoManager;

    GerritRemoteReader(
        SuperManifestRepoManager repoManager, @CanonicalWebUrl String canonicalWebUrl) {
      this.repoManager = repoManager;
      this.canonicalWebUrl = canonicalWebUrl;
    }

    @Override
    public ObjectId sha1(String uriStr, String refName) throws GitAPIException {
      // This is a (mis)feature of JGit, which ignores SHA1s but only if ignoreRemoteFailures
      // is set.
      if (ObjectId.isId(refName)) {
        return ObjectId.fromString(refName);
      }

      try {
        // When the remote is fetch="<relative path>" the manifest parser uses a repoName as URI.
        // Do a poor man's guessing if we have a repoName or URI
        Repository repo =
            uriStr.contains("://")
                ? repoManager.openByUri(uriStr)
                : repoManager.openByName(Project.nameKey(uriStr));
        Ref ref = repo.findRef(refName);
        if (ref == null || ref.getObjectId() == null) {
          logger.atWarning().log(
              "%s: in repo %s: cannot resolve ref %s", canonicalWebUrl, uriStr, refName);
          return null;
        }

        ref = repo.getRefDatabase().peel(ref);
        ObjectId id = ref.getPeeledObjectId();
        return id != null ? id : ref.getObjectId();
      } catch (RepositoryNotFoundException e) {
        logger.atWarning().withCause(e).log(
            "%s: failed to open repository %s", canonicalWebUrl, uriStr);
        return null;
      } catch (IOException io) {
        RefNotFoundException e =
            new RefNotFoundException(String.format("cannot open %s to read %s", uriStr, refName));
        e.initCause(io);
        throw e;
      }
    }

    @Override
    public RemoteFile readFileWithMode(String uriStr, String ref, String path)
        throws GitAPIException, IOException {
      // When the remote is fetch="<relative path>" the manifest parser uses a repoName as URI.
      // Do a poor man's guessing if we have a repoName or URI
      Repository repo =
          uriStr.contains("://")
              ? repoManager.openByUri(uriStr)
              : repoManager.openByName(Project.nameKey(uriStr));
      Ref r = repo.findRef(ref);
      ObjectId objectId = r == null ? repo.resolve(ref) : r.getObjectId();
      if (objectId == null) {
        throw new RevisionSyntaxException(
            String.format("repo %s does not have ref %s", repo.toString(), ref), ref);
      }
      RevCommit commit = repo.parseCommit(objectId);
      TreeWalk tw = TreeWalk.forPath(repo, path, commit.getTree());
      return new RemoteFile(
          tw.getObjectReader().open(tw.getObjectId(0)).getCachedBytes(Integer.MAX_VALUE),
          tw.getFileMode(0));
    }

    public Repository openRepository(String name) throws IOException {
      return repoManager.openByName(Project.nameKey(name));
    }

    @Override
    public void close() {
      try {
        repoManager.close();
      } catch (Exception e) {
        logger.atWarning().withCause(e).log("Error closing the repoManager");
      }
    }
  }

  // AutoCloseable so implementations can keep a cache
  public interface SuperManifestRepoManager extends AutoCloseable {
    Repository openByUri(String uriStr) throws IOException;

    Repository openByName(Project.NameKey repoName) throws IOException;

    interface Factory {
      SuperManifestRepoManager create(ConfigEntry c);
    }
  }

  static class GerritSuperManifestRepoManager implements SuperManifestRepoManager {
    private final HashMap<Project.NameKey, Repository> repos;
    private final GitRepositoryManager repoManager;
    private final String canonicalWebUrl;

    @Inject
    GerritSuperManifestRepoManager(
        GitRepositoryManager repoManager,
        @CanonicalWebUrl String canonicalWebUrl,
        @Assisted ConfigEntry e) {
      // Add ConfigEntry (even when this implementation doesn't need it) so
      // injection can bind the factory automatically
      this.repos = new HashMap<>();
      this.repoManager = repoManager;
      this.canonicalWebUrl = canonicalWebUrl;
    }

    @Override
    public Repository openByName(Project.NameKey name) throws IOException {
      if (repos.containsKey(name)) {
        return repos.get(name);
      }

      Repository repo = repoManager.openRepository(name);
      repos.put(name, repo);
      return repo;
    }

    @Override
    public Repository openByUri(String uriStr) throws IOException {
      // A URL in this host is <canonicalWebUrl>/<repoName>.
      //
      // In googlesource the canonicalWebUrl is xxxx-review.googlesource.com and
      // the repos are xxx.googlesource.com. Keep taking the path for backwards compatibility and
      // clean it up when googlesource does the right thing.
      String repoName;
      if (uriStr.startsWith(canonicalWebUrl)) {
        repoName = uriStr.substring(canonicalWebUrl.length());
      } else {
        logger.atWarning().log(
            "%s: taking path from %s that looks from another host", canonicalWebUrl, uriStr);
        URI uri;
        try {
          uri = new URI(uriStr);
        } catch (URISyntaxException e) {
          throw new RepositoryNotFoundException("Cannot parse uri: " + uriStr);
        }

        repoName = uri.getPath();
      }

      while (repoName.startsWith("/")) {
        repoName = repoName.substring(1);
      }

      return openByName(Project.nameKey(repoName));
    }

    @Override
    public void close() {
      for (Repository repo : repos.values()) {
        repo.close();
      }
      repos.clear();
    }
  }
}
