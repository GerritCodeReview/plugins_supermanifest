package com.googlesource.gerrit.plugins.supermanifest;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;

import com.google.gerrit.entities.Project;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.testing.InMemoryRepositoryManager;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.gitrepo.RepoCommand;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class GerritRemoteReaderTest {

  private static final String MASTER = "refs/heads/master";
  private static final String CANONICAL_WEB_URL = "https://example.com/gerrit";

  private static final String PROJECT_REPONAME = "project/x";
  private static final String PROJECT_URL = CANONICAL_WEB_URL + "/" + PROJECT_REPONAME;

  private GitRepositoryManager repoManager = new InMemoryRepositoryManager();
  SuperManifestRefUpdatedListener.GerritSuperManifestRepoManager superManifestRepoManager =
      new SuperManifestRefUpdatedListener.GerritSuperManifestRepoManager(
          repoManager, CANONICAL_WEB_URL, null);
  private SuperManifestRefUpdatedListener.GerritRemoteReader reader =
      new SuperManifestRefUpdatedListener.GerritRemoteReader(
          superManifestRepoManager, CANONICAL_WEB_URL);
  private Repository projectX;
  private RevCommit projectXTip;

  @Before
  public void setUp() throws Exception {
    repoManager.createRepository(Project.nameKey(PROJECT_REPONAME));
    projectX = repoManager.openRepository(Project.nameKey(PROJECT_REPONAME));
    try (TestRepository<Repository> git = new TestRepository<>(projectX)) {
      projectXTip = git.branch(MASTER).commit().add("test_file", "test_contents").create();
    }
  }

  @Test
  public void sha1_inCanonical_opens() throws Exception {
    ObjectId objectId = reader.sha1(PROJECT_URL, "refs/heads/master");
    assertThat(objectId).isEqualTo(projectXTip);
  }

  @Test
  public void sha1_outterHost_null() throws Exception {
    ObjectId objectId = reader.sha1("https://other-host.com/gerrit/project/x", "refs/heads/master");
    assertThat(objectId).isNull();
  }

  @Test
  public void sha1_repoName_opens() throws GitAPIException {
    // When remote has relative fetch (e.g. "..") supermanifest returns repoNames as uri
    ObjectId objectId = reader.sha1(PROJECT_REPONAME, "refs/heads/master");
    assertThat(objectId).isEqualTo(projectXTip);
  }

  @Test
  public void sha1_repoName_nonExisting_null() throws GitAPIException {
    ObjectId objectId = reader.sha1("unexisting/project", "refs/heads/master");
    assertThat(objectId).isNull();
  }

  @Test
  public void sha1_forObjectId_returnIt() throws Exception {
    String sha1 = "91f2c8cb366e21c20544f531be710fdfa5eb3afb";
    ObjectId oid = ObjectId.fromString(sha1);
    assertThat(reader.sha1("http://this.is.not.read/", sha1)).isEqualTo(oid);
  }

  @Test
  public void readWithFileMode_uriInCanonical_reads() throws Exception {
    RepoCommand.RemoteFile remoteFile =
        reader.readFileWithMode(PROJECT_URL, "refs/heads/master", "test_file");
    assertThat(new String(remoteFile.getContents())).isEqualTo("test_contents");
  }

  @Test
  public void readWithFileMode_repoName_reads() throws Exception {
    RepoCommand.RemoteFile remoteFile =
        reader.readFileWithMode(PROJECT_REPONAME, "refs/heads/master", "test_file");
    assertThat(new String(remoteFile.getContents())).isEqualTo("test_contents");
  }

  @Test
  public void readWithFileMode_outerHostUri_throws() {
    assertThrows(
        RepositoryNotFoundException.class,
        () ->
            reader.readFileWithMode(
                "https://somwhere.com/project/x", "refs/heads/master", "test_file"));
  }

  @Test
  public void readWithFileMode_repoName_nonexisting_throws() {
    assertThrows(
        RepositoryNotFoundException.class,
        () -> reader.readFileWithMode("unexisting/project", "refs/heads/master", "test_file"));
  }

  @Test
  public void openRepository_byRepoName_ok() throws Exception {
    Repository repo = reader.openRepository(PROJECT_REPONAME);
    assertThat(repo).isNotNull();
  }

  @Test
  public void openRepository_byUri_doesNotOpen() throws Exception {
    assertThrows(RepositoryNotFoundException.class, () -> reader.openRepository(PROJECT_URL));
  }
}
