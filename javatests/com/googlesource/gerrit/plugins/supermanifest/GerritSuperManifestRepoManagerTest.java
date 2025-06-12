package com.googlesource.gerrit.plugins.supermanifest;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.entities.Project;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.testing.InMemoryRepositoryManager;
import com.google.gerrit.testing.NoGitRepositoryCheckIfClosed;
import java.io.IOException;
import org.eclipse.jgit.lib.Repository;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
@NoGitRepositoryCheckIfClosed
public class GerritSuperManifestRepoManagerTest {
  private static final String CANONICAL_WEB_URL = "https://example.com/gerrit/";

  SuperManifestRefUpdatedListener.GerritSuperManifestRepoManager superManifestRepoManager;

  @Before
  public void setUp() throws IOException {
    GitRepositoryManager repoManager = new InMemoryRepositoryManager();
    repoManager.createRepository(Project.nameKey("project/x"));
    superManifestRepoManager =
        new SuperManifestRefUpdatedListener.GerritSuperManifestRepoManager(
            repoManager, CANONICAL_WEB_URL, null);
  }

  @Test
  public void openByRepoName() throws Exception {
    Repository repo = superManifestRepoManager.openByName(Project.nameKey("project/x"));
    assertThat(repo).isNotNull();
  }

  @Test
  public void openByUri_canonical_repoNameExcludingCanonical() throws Exception {
    Repository repo = superManifestRepoManager.openByUri(CANONICAL_WEB_URL + "project/x");
    assertThat(repo).isNotNull();
  }

  @Test
  public void openByUri_nonCanonical_uriPathIsRepoName() throws IOException {
    // TODO(ifrade): This should be a RepositoryNotFound exception, but we are relying on opening
    // the wrong repo.
    Repository repo = superManifestRepoManager.openByUri("https://otherhost.com/project/x");
    assertThat(repo).isNotNull();
  }
}
