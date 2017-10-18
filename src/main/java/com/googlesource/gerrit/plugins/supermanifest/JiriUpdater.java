package com.googlesource.gerrit.plugins.supermanifest;

import static com.google.gerrit.reviewdb.client.RefNames.REFS_HEADS;

import com.googlesource.gerrit.plugins.supermanifest.SuperManifestRefUpdatedListener.GerritRemoteReader;
import java.io.IOException;
import java.net.URI;
import java.text.MessageFormat;
import java.util.Date;
import java.util.StringJoiner;
import org.eclipse.jgit.api.errors.ConcurrentRefUpdateException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.gitrepo.internal.RepoText;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class JiriUpdater implements SubModuleUpdater {
  PersonIdent serverIdent;
  URI canonicalWebUrl;

  public JiriUpdater(PersonIdent serverIdent, URI canonicalWebUrl) {
    this.serverIdent = serverIdent;
    this.canonicalWebUrl = canonicalWebUrl;
  }

  private static final Logger log = LoggerFactory.getLogger(SuperManifestRefUpdatedListener.class);

  private void warn(String formatStr, Object... args) {
    // The docs claim that log.warn() uses format strings, but it doesn't seem to work, so we do it
    // explicitly.
    log.warn(canonicalWebUrl + " : " + String.format(formatStr, args));
  }

  private void updateSubmodules(
      Repository repo,
      String targetRef,
      URI targetURI,
      JiriProjects projects,
      GerritRemoteReader reader)
      throws IOException, GitAPIException {
    DirCache index = DirCache.newInCore();
    DirCacheBuilder builder = index.builder();
    ObjectInserter inserter = repo.newObjectInserter();
    try (RevWalk rw = new RevWalk(repo)) {
      Config cfg = new Config();
      projects.sortByPath();
      String parent = null;
      for (JiriProjects.Project proj : projects.getProjects()) {
        String path = proj.getPath();
        String nameUri = proj.getRemote();
        if (parent != null) {
          String p1 = StringUtil.stripAndAddCharsAtEnd(path, "/");
          String p2 = StringUtil.stripAndAddCharsAtEnd(parent, "/");
          if (p1.startsWith(p2)) {
            warn(
                "Skipping project %s(%s) as git doesn't support nested submodules",
                proj.getName(), path);
            continue;
          }
        }

        ObjectId objectId;
        String ref = proj.getRef();

        if (ObjectId.isId(ref)) {
          objectId = ObjectId.fromString(ref);
        } else {
          objectId = reader.sha1(nameUri, ref);
          if (objectId == null) {
            warn("failed to get ref '%s' for '%s', skipping", ref, nameUri);
            continue;
          }
        }

        // can be branch or tag
        cfg.setString("submodule", path, "branch", ref);

        if (proj.getHistorydepth() > 0) {
          cfg.setBoolean("submodule", path, "shallow", true);
          if (proj.getHistorydepth() != 1) {
            warn(
                "Project %s(%s) has historydepth other than 1. Submodule only support shallow of depth 1.",
                proj.getName(), proj.getPath());
          }
        }

        URI submodUrl = URI.create(nameUri);

        //check if repo exists locally then relativize its URL
        try {
          String repoName = submodUrl.getPath();
          while (repoName.startsWith("/")) {
            repoName = repoName.substring(1);
          }
          reader.openRepository(repoName);
          submodUrl = relativize(targetURI, URI.create(repoName));
        } catch (RepositoryNotFoundException e) {
        }
        cfg.setString("submodule", path, "path", path);
        cfg.setString("submodule", path, "url", submodUrl.toString());

        // create gitlink
        DirCacheEntry dcEntry = new DirCacheEntry(path);
        dcEntry.setObjectId(objectId);
        dcEntry.setFileMode(FileMode.GITLINK);
        builder.add(dcEntry);
        parent = path;
      }

      String content = cfg.toText();

      // create a new DirCacheEntry for .gitmodules file.
      final DirCacheEntry dcEntry = new DirCacheEntry(Constants.DOT_GIT_MODULES);
      ObjectId objectId =
          inserter.insert(Constants.OBJ_BLOB, content.getBytes(Constants.CHARACTER_ENCODING));
      dcEntry.setObjectId(objectId);
      dcEntry.setFileMode(FileMode.REGULAR_FILE);
      builder.add(dcEntry);

      builder.finish();
      ObjectId treeId = index.writeTree(inserter);

      // Create a Commit object, populate it and write it
      ObjectId headId = repo.resolve(targetRef + "^{commit}");
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(treeId);
      if (headId != null) commit.setParentIds(headId);
      PersonIdent author =
          new PersonIdent(
              serverIdent.getName(),
              serverIdent.getEmailAddress(),
              new Date(),
              serverIdent.getTimeZone());
      commit.setAuthor(author);
      commit.setCommitter(author);
      commit.setMessage(RepoText.get().repoCommitMessage);

      ObjectId commitId = inserter.insert(commit);
      inserter.flush();

      RefUpdate ru = repo.updateRef(targetRef);
      ru.setNewObjectId(commitId);
      ru.setExpectedOldObjectId(headId != null ? headId : ObjectId.zeroId());
      Result rc = ru.update(rw);

      switch (rc) {
        case NEW:
        case FORCED:
        case FAST_FORWARD:
          // Successful. Do nothing.
          break;
        case REJECTED:
        case LOCK_FAILURE:
          throw new ConcurrentRefUpdateException(
              MessageFormat.format(JGitText.get().cannotLock, targetRef), ru.getRef(), rc);
        default:
          throw new JGitInternalException(
              MessageFormat.format(
                  JGitText.get().updatingRefFailed, targetRef, commitId.name(), rc));
      }
    }
  }

  private static final String SLASH = "/";
  /*
   * Copied from https://github.com/eclipse/jgit/blob/e9fb111182b55cc82c530d82f13176c7a85cd958/org.eclipse.jgit/src/org/eclipse/jgit/gitrepo/RepoCommand.java#L729
   */
  static URI relativize(URI current, URI target) {
    // We only handle bare paths for now.
    if (!target.toString().equals(target.getPath())) {
      return target;
    }
    if (!current.toString().equals(current.getPath())) {
      return target;
    }

    String cur = current.normalize().getPath();
    String dest = target.normalize().getPath();

    if (cur.startsWith(SLASH) != dest.startsWith(SLASH)) {
      return target;
    }

    while (cur.startsWith(SLASH)) {
      cur = cur.substring(1);
    }
    while (dest.startsWith(SLASH)) {
      dest = dest.substring(1);
    }

    if (cur.indexOf('/') == -1 || dest.indexOf('/') == -1) {
      // Avoid having to special-casing in the next two ifs.
      String prefix = "prefix/";
      cur = prefix + cur;
      dest = prefix + dest;
    }

    if (!cur.endsWith(SLASH)) {
      // The current file doesn't matter.
      int lastSlash = cur.lastIndexOf('/');
      cur = cur.substring(0, lastSlash);
    }
    String destFile = "";
    if (!dest.endsWith(SLASH)) {
      // We always have to provide the destination file.
      int lastSlash = dest.lastIndexOf('/');
      destFile = dest.substring(lastSlash + 1, dest.length());
      dest = dest.substring(0, dest.lastIndexOf('/'));
    }

    String[] cs = cur.split(SLASH);
    String[] ds = dest.split(SLASH);

    int common = 0;
    while (common < cs.length && common < ds.length && cs[common].equals(ds[common])) {
      common++;
    }

    StringJoiner j = new StringJoiner(SLASH);
    for (int i = common; i < cs.length; i++) {
      j.add("..");
    }
    for (int i = common; i < ds.length; i++) {
      j.add(ds[i]);
    }

    j.add(destFile);
    return URI.create(j.toString());
  }

  @Override
  public void update(GerritRemoteReader reader, ConfigEntry c, String srcRef)
      throws IOException, GitAPIException, ConfigInvalidException {
    Repository destRepo = reader.openRepository(c.getDestRepoKey().toString());
    JiriProjects projects =
        JiriManifestParser.getProjects(
            reader, c.getSrcRepoKey().toString(), srcRef, c.getXmlPath());
    String targetRef = c.getDestBranch().equals("*") ? srcRef : REFS_HEADS + c.getDestBranch();
    updateSubmodules(
        destRepo, targetRef, URI.create(c.getDestRepoKey().toString() + "/"), projects, reader);
  }
}
