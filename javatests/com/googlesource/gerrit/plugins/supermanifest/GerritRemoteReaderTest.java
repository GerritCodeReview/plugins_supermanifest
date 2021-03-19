package com.googlesource.gerrit.plugins.supermanifest;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;

import com.google.gerrit.entities.Project;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.testing.InMemoryRepositoryManager;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.gitrepo.RepoCommand;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Test;

public class GerritRemoteReaderTest {

  private static final String MASTER = "refs/heads/master";
  private static final String CANONICAL_WEB_URL = "https://example.com/gerrit";

  GitRepositoryManager repoManager = new InMemoryRepositoryManager();
  SuperManifestRefUpdatedListener.GerritSuperManifestRepoManager superManifestRepoManager =
      new SuperManifestRefUpdatedListener.GerritSuperManifestRepoManager(
          repoManager, CANONICAL_WEB_URL);
  SuperManifestRefUpdatedListener.GerritRemoteReader reader =
      new SuperManifestRefUpdatedListener.GerritRemoteReader(
          superManifestRepoManager, CANONICAL_WEB_URL);

  @Test
  public void sha1_inCanonical_repoNameWithoutCanonicalPrefix() throws Exception {
    repoManager.createRepository(Project.nameKey("project/x"));
    Repository repository = repoManager.openRepository(Project.nameKey("project/x"));
    RevCommit projectXTip;
    try (TestRepository<Repository> git = new TestRepository<>(repository)) {
      projectXTip = git.branch(MASTER).commit().add("x", "xxx").create();
    }

    ObjectId objectId = reader.sha1(CANONICAL_WEB_URL + "/project/x", "refs/heads/master");
    assertThat(objectId).isEqualTo(projectXTip);
  }

  @Test
  public void sha1_outterHost_throws() throws Exception {
    // This tries to open "gerrit/project/x" which (unfortunately) works
    assertThrows(
        InvalidRemoteException.class,
        () -> reader.sha1("https://other-host.com/gerrit/project/x", "refs/heads/master"));
  }

  @Test
  public void sha1_forObjectId_returnIt() throws Exception {
    String sha1 = "91f2c8cb366e21c20544f531be710fdfa5eb3afb";
    ObjectId oid = ObjectId.fromString(sha1);
    assertThat(reader.sha1("http://this.is.not.read/", sha1)).isEqualTo(oid);
  }

  @Test
  public void readWithFileMode_readsContents() throws Exception {
    repoManager.createRepository(Project.nameKey("project/x"));
    Repository repository = repoManager.openRepository(Project.nameKey("project/x"));
    try (TestRepository<Repository> git = new TestRepository<>(repository)) {
      git.branch(MASTER).commit().add("x", "contents").create();
    }

    // This tries to open "gerrit/project/x" which (unfortunately) works
    RepoCommand.RemoteFile remoteFile =
        reader.readFileWithMode(CANONICAL_WEB_URL + "/project/x", "refs/heads/master", "x");
    assertThat(new String(remoteFile.getContents())).isEqualTo("contents");
  }

  @Test
  public void openRepository_byRepoName_ok() throws Exception {
    repoManager.createRepository(Project.nameKey("project/x"));
    Repository repository = repoManager.openRepository(Project.nameKey("project/x"));
    try (TestRepository<Repository> git = new TestRepository<>(repository)) {
      git.branch(MASTER).commit().add("x", "contents").create();
    }

    Repository repo = reader.openRepository("project/x");
    assertThat(repo).isNotNull();
  }

  @Test
  public void openRepository_byUri_doesNotOpen() throws Exception {
    repoManager.createRepository(Project.nameKey("project/x"));
    Repository repository = repoManager.openRepository(Project.nameKey("project/x"));
    try (TestRepository<Repository> git = new TestRepository<>(repository)) {
      git.branch(MASTER).commit().add("x", "contents").create();
    }

    // This tries to open "gerrit/project/x" which (unfortunately) works
    assertThrows(
        RepositoryNotFoundException.class,
        () -> reader.openRepository(CANONICAL_WEB_URL + "/project/x"));
  }
}
