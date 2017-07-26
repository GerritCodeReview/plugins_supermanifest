package com.googlesource.gerrit.plugins.supermanifest;

import static com.google.gerrit.reviewdb.client.RefNames.REFS_HEADS;
import static com.google.gerrit.reviewdb.client.RefNames.REFS_TAGS;

import com.googlesource.gerrit.plugins.supermanifest.SuperManifestRefUpdatedListener.GerritRemoteReader;
import java.net.URI;
import java.text.MessageFormat;
import java.util.Map;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.errors.ConcurrentRefUpdateException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.gitrepo.internal.RepoText;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
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
      Repository repo, String targetRef, JiriProjects projects, GerritRemoteReader reader)
      throws Exception {

    DirCache index = DirCache.newInCore();
    DirCacheBuilder builder = index.builder();
    ObjectInserter inserter = repo.newObjectInserter();
    try (RevWalk rw = new RevWalk(repo)) {
      Config cfg = new Config();
      projects.sortByPath();
      String parent = null;
      for (Project proj : projects.getProjects()) {
        String path = proj.getPath();
        String nameUri = proj.getRemote();
        if (parent != null) {
          String p1 = StringUtil.stripAndaddCharsAtEnd(path, "/");
          String p2 = StringUtil.stripAndaddCharsAtEnd(parent, "/");
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
          if (ref.isEmpty()) {
            ref = "master";
          }
          objectId = reader.sha1(nameUri, ref);
          if (objectId == null) {
            // Run ls-remote
            LsRemoteCommand ls = new LsRemoteCommand(repo);
            ls.setRemote(nameUri);
            Map<String, Ref> refs = ls.callAsMap();
            if (refs.containsKey(REFS_HEADS + ref)) {
              objectId = refs.get(REFS_HEADS + ref).getObjectId();
            } else if (refs.containsKey(REFS_TAGS + ref)) {
              objectId = refs.get(REFS_TAGS + ref).getObjectId();
            } else {
              warn("failed to get ref '%s' for '%s', skipping", ref, nameUri);
              continue;
            }
          }
        }

        // can be branch or tag
        cfg.setString(
            "submodule",
            path,
            "branch", 
            ref);

        if (proj.getHistorydepth() > 0) {
          cfg.setBoolean("submodule", path, "shallow", true); // $NON-NLS-1$ //$NON-NLS-2$
          if (proj.getHistorydepth() != 1) {
            warn(
                "Project %s(%s) has historydepth other than 1. Submodule only support shallow of depth 1.",
                proj.getName(), proj.getPath());
          }
        }

        URI submodUrl = URI.create(nameUri);
        cfg.setString("submodule", path, "path", path); // $NON-NLS-1$ //$NON-NLS-2$
        cfg.setString("submodule", path, "url", submodUrl.toString()); // $NON-NLS-1$ //$NON-NLS-2$

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
      ObjectId headId = repo.resolve(targetRef + "^{commit}"); // $NON-NLS-1$
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(treeId);
      if (headId != null) commit.setParentIds(headId);
      commit.setAuthor(serverIdent);
      commit.setCommitter(serverIdent);
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

      rw.parseCommit(commitId);
    }
  }

  @Override
  public void update(GerritRemoteReader reader, ConfigEntry c, String srcRef) throws Exception {
    Repository srcRepo = reader.openRepository(c.getSrcRepoKey().toString());
    Repository destRepo = reader.openRepository(c.getDestRepoKey().toString());
    JiriProjects projects = JiriManifestParser.GetProjects(srcRepo, srcRef, c.getXmlPath());
    String targetRef = c.getDestBranch().equals("*")? srcRef: REFS_HEADS + c.getDestBranch();
    updateSubmodules(destRepo, targetRef, projects, reader);
  }
}
