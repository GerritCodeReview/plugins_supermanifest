package com.googlesource.gerrit.plugins.supermanifest;

import com.google.gerrit.entities.Project;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.testing.InMemoryRepositoryManager;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Test;

import java.io.IOException;

import static com.google.common.truth.Truth.assertThat;

public class GerritSuperManifestRepoManagerTest {
  private static final String MASTER = "refs/heads/master";
  private static final String CANONICAL_WEB_URL = "https://example.com/gerrit/";

  GitRepositoryManager repoManager = new InMemoryRepositoryManager();
  SuperManifestRefUpdatedListener.GerritSuperManifestRepoManager superManifestRepoManager =
      new SuperManifestRefUpdatedListener.GerritSuperManifestRepoManager(repoManager, CANONICAL_WEB_URL);

  @Test
  public void openByRepoName() throws Exception {
    repoManager.createRepository(Project.nameKey("project/x"));
    Repository repo = superManifestRepoManager.openByName(Project.nameKey("project/x"));
    assertThat(repo).isNotNull();
  }

  @Test
  public void openByUri_canonical_repoNameExcludingCanonical() throws Exception {
    repoManager.createRepository(Project.nameKey("project/x"));
    Repository repo = superManifestRepoManager.openByUri(CANONICAL_WEB_URL + "project/x");
    assertThat(repo).isNotNull();
  }

  @Test
  public void openByUri_nonCanonical_uriPathIsRepoName() throws Exception {
    repoManager.createRepository(Project.nameKey("project/x"));
    Repository repo = superManifestRepoManager.openByUri("https://otherhost.com/project/x");
    assertThat(repo).isNotNull();
  }
}
