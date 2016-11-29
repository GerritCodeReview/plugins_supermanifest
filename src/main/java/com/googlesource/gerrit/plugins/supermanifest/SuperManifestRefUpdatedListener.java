package com.googlesource.gerrit.plugins.supermanifest;

import com.google.common.base.Preconditions;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.CanonicalWebUrl;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.gitrepo.RepoCommand;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * This plugin will listen for changes to XML files in manifest repositories. When it finds such changes,
 * it will trigger an update of the associated superproject.
 */
@Singleton
class SuperManifestRefUpdatedListener implements GitReferenceUpdatedListener,
    LifecycleListener {
  private final GitRepositoryManager repoManager;
  private final String canonicalWebUrl;
  private Set<ConfigEntry> config;
  private PluginConfigFactory cfgFactory;
  private String name;
  AllProjectsName allProjectsName;

  static final Logger log =
      LoggerFactory.getLogger(SuperManifestRefUpdatedListener.class);

  private static String SECTION_NAME = "superproject";

  public static byte[] readBlob(Repository repo, String idStr) throws IOException {
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
    String xmlPath;
    Project.NameKey destRepoKey;
    String destBranch;

    // TODO - add groups?

    public String src() {
      return srcRepoKey + ":" + srcRef + ":" + xmlPath;
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
      int result = destRepoKey.hashCode();
      result = 31 * result + destBranch.hashCode();
      return result;
    }
  }

  private static boolean hasDiff(GitRepositoryManager repoManager,
                                 String repoName, String oldId, String newId, String path) throws IOException {
    if (oldId.equals(ObjectId.toString(null))) {
      return true;
    }

    Project.NameKey projectName = new Project.NameKey(repoName);

    try (Repository repo =
             repoManager.openRepository(projectName);
         RevWalk rw = new RevWalk(repo)) {

      RevCommit c1 = rw.parseCommit(ObjectId.fromString(oldId));
      if (c1 == null) {
        return true;
      }
      RevCommit c2 = rw.parseCommit(ObjectId.fromString(newId));

      try (TreeWalk tw = TreeWalk.forPath(
          repo, path, c1.getTree().getId(),
          c2.getTree().getId())) {

        return !tw.getObjectId(0).equals(tw.getObjectId(1));
      }
    }
  }

  /*
      [superproject "submodules:nyc"]
         srcRepo = platforms/manifest
         srcRef = refs/heads/nyc
         srcPath = manifest.xml

   */
  private Set<ConfigEntry> parseConfiguration(PluginConfigFactory cfgFactory, String name) {

    // TODO(hanwen): cfgFactory has a cache which isn't currently cleared on updates to the
    // configuration; how to do that?

    Config cfg = null;
    try {
      cfg = cfgFactory.getProjectPluginConfig(allProjectsName, name);
    } catch (NoSuchProjectException e) {
      Preconditions.checkState(false);
    }

    Set<ConfigEntry> newConf = new HashSet<>();
    for (String subsect : cfg.getSubsections(SECTION_NAME)) {
      try {
        ConfigEntry e = newConfigEntry(cfg, subsect);
        newConf.add(e);
      } catch (ConfigException e) {
        log.error("ConfigException: " + e.msg);
      }
    }

    return newConf;
  }

  public static class ConfigException extends Exception {
    String msg;

    ConfigException(String m) {
      msg = m;
    }
  }

  private static ConfigEntry newConfigEntry(Config cfg, String name) throws ConfigException {
    String[] parts = name.split(":");
    if (parts.length != 2) {
      throw new ConfigException(String.format("name '%s' must have form REPO:BRANCH", name));
    }

    String destRepo = parts[0];
    String destBranch = parts[1];

    ConfigEntry e = new ConfigEntry();
    String srcRepo = cfg.getString(SECTION_NAME, name, "srcRepo");
    if (srcRepo == null) {
      throw new ConfigException(String.format("entry %s did not specify srcRepo", name));
    }
    e.srcRepoKey = new Project.NameKey(srcRepo);

    e.srcRef = cfg.getString(SECTION_NAME, name, "srcRef");
    if (e.srcRef == null) {
      throw new ConfigException(String.format("entry %s did not specify srcRef", name));
    }

    e.xmlPath = cfg.getString(SECTION_NAME, name, "srcPath");
    if (e.xmlPath == null) {
      throw new ConfigException(String.format("entry %s did not specify srcPath", name));
    }

    e.destRepoKey = new Project.NameKey(destRepo);
    e.destBranch = destBranch;

    return e;
  }

  @Inject
  SuperManifestRefUpdatedListener(
      AllProjectsName allProjectsName,
      @CanonicalWebUrl
          String canonicalWebUrl,
      @PluginName String name,
      PluginConfigFactory cfgFactory,
      GitRepositoryManager repoManager) {
    this.name = name;
    this.allProjectsName = allProjectsName;
    this.repoManager = repoManager;
    this.canonicalWebUrl = canonicalWebUrl;
    this.cfgFactory = cfgFactory;
  }

  private boolean checkRepoExists(Project.NameKey id) {
    try (Repository repo = repoManager.openRepository(id)) {
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  @Override
  public void stop() {
  }

  @Override
  public void start() {
    updateConfiguration();
  }

  /**
   * for debugging.
   */
  void printConfiguration() {
    System.err.println("# configuration entries: " + config.size());
    for (ConfigEntry c : config) {
      System.err.println(c.toString());
    }
  }

  public void updateConfiguration() {
    Set<ConfigEntry> entries = parseConfiguration(cfgFactory, name);

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
    printConfiguration();
  }

  @Override
  public void onGitReferenceUpdated(Event event) {
    System.err.println("onGitRU: " + event);

    // TODO - should catch pending changes, parse new config, and post errors as comments?

    if (event.getProjectName().equals(allProjectsName.get())) {
      if (event.getRefName().equals("refs/meta/config")) {
        updateConfiguration();
      }
      return;
    }

    for (ConfigEntry c : config) {
      System.err.println("equals?");
      if (!c.srcRepoKey.get().equals(event.getProjectName())) {
        continue;
      }
      if (!c.srcRef.equals(event.getRefName())) {
        continue;
      }

      try {
        if (!hasDiff(repoManager, event.getProjectName(), event.getOldObjectId(), event.getNewObjectId(), c.xmlPath)) {
          continue;
        }
      } catch (IOException e) {
        log.error("ignoring hasDiff error for" + c.toString() + ":", e.toString());
      }

      try {
        update(c);
      } catch (IOException | GitAPIException e) {
        // Is this good enough? It would be nice if we could surface these in the UI.
        // Perhaps we could store these as commits in some special branch (but in what repo?).
        log.error("update for " + c.toString() + " failed: ", e);
      }
    }
  }

  private void update(ConfigEntry c) throws IOException, GitAPIException {
    Repository destRepo = repoManager.openRepository(c.destRepoKey);
    RepoCommand cmd =
        new RepoCommand(destRepo);

    // load commit author from event; cmd.setAuthor()?
    cmd.setTargetBranch(c.destBranch);

    Repository xmlRepo = repoManager.openRepository(c.srcRepoKey);
    InputStream manifestStream = new ByteArrayInputStream(readBlob(xmlRepo, c.srcRef + ":" + c.xmlPath));

    // TODO(hanwen): setRecordRemoteBranches()
    // TODO(hanwen): setRecordSubmoduleLabels()
    cmd.setInputStream(manifestStream);

    // TODO(hanwen): this is fishy; we'd silently swallow failures?
    cmd.setIgnoreRemoteFailures(true);

    // TODO(hanwen): is there any reason anyone would not want this?
    cmd.setRecommendShallow(true);

    // Is this the right URL?
    cmd.setURI(canonicalWebUrl);

    // TODO(hanwen): do we need to set this?
    // cmd.setRemoteReader(null); // ?

    // TODO(hanwen): set this up.
    // cmd.setIncludedFileReader(null);

    // TODO(hanwen): can/should we hook up a progress monitor somewhere?

    RevCommit commit = cmd.call();
    // TODO - check if there was a change?

    // TODO - what if we have subscription enabled as well. Can we get inconsistency due to
    // reordering of events?

   System.err.println("RESULT: " +  commit.toString()) ;
  }
}
