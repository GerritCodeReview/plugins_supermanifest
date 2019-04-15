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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.gerrit.acceptance.GitUtil;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.PushOneCommit.Result;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.extensions.api.projects.BranchApi;
import com.google.gerrit.extensions.config.DownloadScheme;
import com.google.gerrit.extensions.registration.DynamicMap;
import com.google.gerrit.extensions.registration.PrivateInternals_DynamicMapImpl;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.Project.NameKey;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.inject.Inject;
import com.google.inject.util.Providers;
import java.net.URI;
import java.util.Arrays;
import org.apache.commons.lang.RandomStringUtils;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.BlobBasedConfig;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Test;

@TestPlugin(
    name = "supermanifest",
    sysModule = "com.googlesource.gerrit.plugins.supermanifest.SuperManifestModule")
public class JiriSuperManifestIT extends LightweightPluginDaemonTest {
  NameKey[] testRepoKeys;

  @Inject private ProjectOperations projectOperations;
  @Inject private DynamicMap<DownloadScheme> downloadScheme;

  void setupTestRepos(String prefix) throws Exception {
    // Set up download schemes for test repos.
    PrivateInternals_DynamicMapImpl<DownloadScheme> downloadSchemeImpl =
        (PrivateInternals_DynamicMapImpl<DownloadScheme>) downloadScheme;
    String host = URI.create(canonicalWebUrl.get()).getHost();
    downloadSchemeImpl.put("supermanifest", "https", Providers.of(new TestDownloadScheme(host)));

    testRepoKeys = new NameKey[2];
    for (int i = 0; i < 2; i++) {
      testRepoKeys[i] =
          projectOperations
              .newProject()
              .name(RandomStringUtils.randomAlphabetic(8) + prefix + i)
              .create();

      TestRepository<InMemoryRepository> repo = cloneProject(testRepoKeys[i], admin);

      PushOneCommit push =
          pushFactory.create(admin.getIdent(), repo, "Subject", "file" + i, "file");
      push.to("refs/heads/master").assertOkStatus();
    }
  }

  void pushConfig(String config) throws Exception {
    // This will trigger a configuration reload.
    TestRepository<InMemoryRepository> allProjectRepo = cloneProject(allProjects, admin);
    GitUtil.fetch(allProjectRepo, RefNames.REFS_CONFIG + ":config");
    allProjectRepo.reset("config");
    PushOneCommit push =
        pushFactory.create(
            admin.getIdent(), allProjectRepo, "Subject", "supermanifest.config", config);
    PushOneCommit.Result res = push.to("refs/meta/config");
    res.assertOkStatus();
  }

  @Test
  public void basicFunctionalityWorks() throws Exception {
    setupTestRepos("project");

    // Make sure the manifest exists so the configuration loads successfully.
    NameKey manifestKey = projectOperations.newProject().name(name("manifest")).create();
    TestRepository<InMemoryRepository> manifestRepo = cloneProject(manifestKey, admin);

    NameKey superKey = projectOperations.newProject().name(name("superproject")).create();
    cloneProject(superKey, admin);

    pushConfig(
        "[superproject \""
            + superKey.get()
            + ":refs/heads/destbranch\"]\n"
            + "  srcRepo = "
            + manifestKey.get()
            + "\n"
            + "  srcRef = refs/heads/srcbranch\n"
            + "  srcPath = default\n"
            + "  toolType = jiri\n");

    // XML change will trigger commit to superproject.
    String xml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<manifest>\n<projects>\n"
            + "<project name=\""
            + testRepoKeys[0].get()
            + "\" remote=\""
            + canonicalWebUrl.get()
            + testRepoKeys[0].get()
            + "\" path=\"project1\" />\n"
            + "</projects>\n</manifest>\n";

    pushFactory
        .create(admin.getIdent(), manifestRepo, "Subject", "default", xml)
        .to("refs/heads/srcbranch")
        .assertOkStatus();

    BranchApi branch = gApi.projects().name(superKey.get()).branch("refs/heads/destbranch");
    assertThat(branch.file("project1").getContentType()).isEqualTo("x-git/gitlink; charset=UTF-8");
    try {
      branch.file("project2");
      fail("wanted exception");
    } catch (ResourceNotFoundException e) {
      // all fine.
    }

    xml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<manifest>\n<projects>\n"
            + "  <project name=\""
            + testRepoKeys[0].get()
            + "\" remote=\""
            + canonicalWebUrl.get()
            + testRepoKeys[0].get()
            + "\" path=\"project1\" />\n"
            + "  <project name=\""
            + testRepoKeys[1].get()
            + "\" remote=\""
            + canonicalWebUrl.get()
            + testRepoKeys[1].get()
            + "\" path=\"project2\" />\n"
            + "</projects>\n</manifest>\n";

    pushFactory
        .create(admin.getIdent(), manifestRepo, "Subject", "default", xml)
        .to("refs/heads/srcbranch")
        .assertOkStatus();

    branch = gApi.projects().name(superKey.get()).branch("refs/heads/destbranch");
    assertThat(branch.file("project2").getContentType()).isEqualTo("x-git/gitlink; charset=UTF-8");

    // Make sure config change gets picked up.
    pushConfig(
        "[superproject \""
            + superKey.get()
            + ":refs/heads/other\"]\n"
            + "  srcRepo = "
            + manifestKey.get()
            + "\n"
            + "  srcRef = refs/heads/srcbranch\n"
            + "  srcPath = default\n"
            + "  toolType = jiri\n");

    // Push another XML change; this should trigger a commit using the new config.
    xml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<manifest>\n<projects>\n"
            + "  <project name=\""
            + testRepoKeys[1].get()
            + "\" remote=\""
            + canonicalWebUrl.get()
            + testRepoKeys[1].get()
            + "\" path=\"project3\" />\n"
            + "</projects>\n</manifest>\n";

    pushFactory
        .create(admin.getIdent(), manifestRepo, "Subject", "default", xml)
        .to("refs/heads/srcbranch")
        .assertOkStatus();

    branch = gApi.projects().name(superKey.get()).branch("refs/heads/other");
    assertThat(branch.file("project3").getContentType()).isEqualTo("x-git/gitlink; charset=UTF-8");
  }

  @Test
  public void ImportTagWorks() throws Exception {
    setupTestRepos("project");

    // Make sure the manifest exists so the configuration loads successfully.
    NameKey manifest1Key = projectOperations.newProject().name(name("manifest1")).create();
    TestRepository<InMemoryRepository> manifest1Repo = cloneProject(manifest1Key, admin);

    NameKey manifest2Key = projectOperations.newProject().name(name("manifest2")).create();
    TestRepository<InMemoryRepository> manifest2Repo = cloneProject(manifest2Key, admin);

    NameKey superKey = projectOperations.newProject().name(name("superproject")).create();
    cloneProject(superKey, admin);

    pushConfig(
        "[superproject \""
            + superKey.get()
            + ":refs/heads/destbranch\"]\n"
            + "  srcRepo = "
            + manifest1Key.get()
            + "\n"
            + "  srcRef = refs/heads/srcbranch\n"
            + "  srcPath = default\n"
            + "  toolType = jiri\n");

    // XML change will trigger commit to superproject.
    String xml1 =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<manifest>\n<imports>\n"
            + "<import name=\""
            + manifest2Key.get()
            + "\" manifest=\"default\" remote=\""
            + canonicalWebUrl.get()
            + manifest2Key.get()
            + "\" />\n</imports>"
            + "<projects>\n"
            + "<project name=\""
            + testRepoKeys[0].get()
            + "\" remote=\""
            + canonicalWebUrl.get()
            + testRepoKeys[0].get()
            + "\" path=\"project1\" />\n"
            + "</projects>\n</manifest>\n";

    String xml2 =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<manifest>\n<projects>\n"
            + "<project name=\""
            + manifest2Key.get()
            + "\" remote=\""
            + canonicalWebUrl.get()
            + manifest2Key.get()
            + "\" path=\"manifest2\" />\n"
            + "</projects>\n</manifest>\n";
    pushFactory
        .create(admin.getIdent(), manifest2Repo, "Subject", "default", xml2)
        .to("refs/heads/master")
        .assertOkStatus();
    pushFactory
        .create(admin.getIdent(), manifest1Repo, "Subject", "default", xml1)
        .to("refs/heads/srcbranch")
        .assertOkStatus();

    BranchApi branch = gApi.projects().name(superKey.get()).branch("refs/heads/destbranch");
    assertThat(branch.file("project1").getContentType()).isEqualTo("x-git/gitlink; charset=UTF-8");
    assertThat(branch.file("manifest2").getContentType()).isEqualTo("x-git/gitlink; charset=UTF-8");
  }

  @Test
  public void ImportTagWithRevisionWorks() throws Exception {
    setupTestRepos("project");

    // Make sure the manifest exists so the configuration loads successfully.
    NameKey manifest1Key = projectOperations.newProject().name(name("manifest1")).create();
    TestRepository<InMemoryRepository> manifest1Repo = cloneProject(manifest1Key, admin);

    NameKey manifest2Key = projectOperations.newProject().name(name("manifest2")).create();
    TestRepository<InMemoryRepository> manifest2Repo = cloneProject(manifest2Key, admin);

    NameKey superKey = projectOperations.newProject().name(name("superproject")).create();
    cloneProject(superKey, admin);

    pushConfig(
        "[superproject \""
            + superKey.get()
            + ":refs/heads/destbranch\"]\n"
            + "  srcRepo = "
            + manifest1Key.get()
            + "\n"
            + "  srcRef = refs/heads/srcbranch\n"
            + "  srcPath = default\n"
            + "  toolType = jiri\n");

    String xml2 =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<manifest>\n<projects>\n"
            + "<project name=\""
            + manifest2Key.get()
            + "\" remote=\""
            + canonicalWebUrl.get()
            + manifest2Key.get()
            + "\" path=\"manifest2\" />\n"
            + "</projects>\n</manifest>\n";
    Result c =
        pushFactory
            .create(admin.getIdent(), manifest2Repo, "Subject", "default", xml2)
            .to("refs/heads/master");
    c.assertOkStatus();
    RevCommit commit = c.getCommit();

    // Add new project, that should not be imported
    xml2 =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<manifest>\n<projects>\n"
            + "<project name=\""
            + manifest2Key.get()
            + "\" remote=\""
            + canonicalWebUrl.get()
            + manifest2Key.get()
            + "\" path=\"manifest2\" />\n"
            + "<project name=\""
            + testRepoKeys[1].get()
            + "\" remote=\""
            + canonicalWebUrl.get()
            + testRepoKeys[1].get()
            + "\" path=\"project2\" />\n"
            + "</projects>\n</manifest>\n";

    pushFactory
        .create(admin.getIdent(), manifest2Repo, "Subject", "default", xml2)
        .to("refs/heads/master")
        .assertOkStatus();

    // XML change will trigger commit to superproject.
    String xml1 =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<manifest>\n<imports>\n"
            + "<import name=\""
            + manifest2Key.get()
            + "\" manifest=\"default\" remote=\""
            + canonicalWebUrl.get()
            + manifest2Key.get()
            + "\" revision=\""
            + commit.name()
            + "\"/>\n</imports>"
            + "<projects>\n"
            + "<project name=\""
            + testRepoKeys[0].get()
            + "\" remote=\""
            + canonicalWebUrl.get()
            + testRepoKeys[0].get()
            + "\" path=\"project1\" />\n"
            + "</projects>\n</manifest>\n";

    pushFactory
        .create(admin.getIdent(), manifest1Repo, "Subject", "default", xml1)
        .to("refs/heads/srcbranch")
        .assertOkStatus();

    BranchApi branch = gApi.projects().name(superKey.get()).branch("refs/heads/destbranch");
    assertThat(branch.file("project1").getContentType()).isEqualTo("x-git/gitlink; charset=UTF-8");
    assertThat(branch.file("manifest2").getContentType()).isEqualTo("x-git/gitlink; charset=UTF-8");
    assertThat(branch.file("manifest2").asString()).contains(commit.name());
    try {
      branch.file("project2");
      fail("wanted exception");
    } catch (ResourceNotFoundException e) {
      // all fine.
    }
  }

  @Test
  public void ImportTagWithRemoteBranchWorks() throws Exception {
    setupTestRepos("project");

    // Make sure the manifest exists so the configuration loads successfully.
    NameKey manifest1Key = projectOperations.newProject().name(name("manifest1")).create();
    TestRepository<InMemoryRepository> manifest1Repo = cloneProject(manifest1Key, admin);

    NameKey manifest2Key = projectOperations.newProject().name(name("manifest2")).create();
    TestRepository<InMemoryRepository> manifest2Repo = cloneProject(manifest2Key, admin);

    NameKey superKey = projectOperations.newProject().name(name("superproject")).create();
    cloneProject(superKey, admin);

    pushConfig(
        "[superproject \""
            + superKey.get()
            + ":refs/heads/destbranch\"]\n"
            + "  srcRepo = "
            + manifest1Key.get()
            + "\n"
            + "  srcRef = refs/heads/srcbranch\n"
            + "  srcPath = default\n"
            + "  toolType = jiri\n");

    String xml2 =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<manifest>\n<projects>\n"
            + "<project name=\""
            + manifest2Key.get()
            + "\" remote=\""
            + canonicalWebUrl.get()
            + manifest2Key.get()
            + "\" path=\"manifest2\" />\n"
            + "</projects>\n</manifest>\n";
    pushFactory
        .create(admin.getIdent(), manifest2Repo, "Subject", "default", xml2)
        .to("refs/heads/b1")
        .assertOkStatus();

    // Add new project, that should not be imported
    xml2 =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<manifest>\n<projects>\n"
            + "<project name=\""
            + manifest2Key.get()
            + "\" remote=\""
            + canonicalWebUrl.get()
            + manifest2Key.get()
            + "\" path=\"manifest2\" />\n"
            + "<project name=\""
            + testRepoKeys[1].get()
            + "\" remote=\""
            + canonicalWebUrl.get()
            + testRepoKeys[1].get()
            + "\" path=\"project2\" />\n"
            + "</projects>\n</manifest>\n";

    pushFactory
        .create(admin.getIdent(), manifest2Repo, "Subject", "default", xml2)
        .to("refs/heads/master")
        .assertOkStatus();

    // XML change will trigger commit to superproject.
    String xml1 =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<manifest>\n<imports>\n"
            + "<import name=\""
            + manifest2Key.get()
            + "\" manifest=\"default\" remote=\""
            + canonicalWebUrl.get()
            + manifest2Key.get()
            + "\" remotebranch=\"b1\" />\n</imports>"
            + "<projects>\n"
            + "<project name=\""
            + testRepoKeys[0].get()
            + "\" remote=\""
            + canonicalWebUrl.get()
            + testRepoKeys[0].get()
            + "\" path=\"project1\" />\n"
            + "</projects>\n</manifest>\n";

    pushFactory
        .create(admin.getIdent(), manifest1Repo, "Subject", "default", xml1)
        .to("refs/heads/srcbranch")
        .assertOkStatus();

    BranchApi branch = gApi.projects().name(superKey.get()).branch("refs/heads/destbranch");
    assertThat(branch.file("project1").getContentType()).isEqualTo("x-git/gitlink; charset=UTF-8");
    assertThat(branch.file("manifest2").getContentType()).isEqualTo("x-git/gitlink; charset=UTF-8");
    try {
      branch.file("project2");
      fail("wanted exception");
    } catch (ResourceNotFoundException e) {
      // all fine.
    }
  }

  private void outer() throws Exception {
    inner();
  }

  private void inner() {
    throw new IllegalStateException();
  }

  private void innerTest() throws Exception {
    try {
      outer();
      fail("should throw");
    } catch (IllegalStateException e) {
      StackTraceElement[] trimmed =
          SuperManifestRefUpdatedListener.trimStack(
              e.getStackTrace(), Thread.currentThread().getStackTrace()[1]);
      String str = Arrays.toString(trimmed);
      assertThat(str).doesNotContain("trimStackTrace");
      assertThat(str).contains("innerTest");
    }
  }

  @Test
  public void trimStackTrace() throws Exception {
    innerTest();
  }

  @Test
  public void wildcardDestBranchWorks() throws Exception {
    setupTestRepos("project");

    // Make sure the manifest exists so the configuration loads successfully.
    NameKey manifestKey = projectOperations.newProject().name(name("manifest")).create();
    TestRepository<InMemoryRepository> manifestRepo = cloneProject(manifestKey, admin);

    NameKey superKey = projectOperations.newProject().name(name("superproject")).create();
    cloneProject(superKey, admin);

    pushConfig(
        "[superproject \""
            + superKey.get()
            + ":refs/heads/*\"]\n"
            + "  srcRepo = "
            + manifestKey.get()
            + "\n"
            + "  srcRef = blablabla\n"
            + "  srcPath = default\n"
            + "  toolType = jiri\n");

    // XML change will trigger commit to superproject.
    String xml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<manifest>\n<projects>\n"
            + "  <project name=\""
            + testRepoKeys[0].get()
            + "\" remote=\""
            + canonicalWebUrl.get()
            + testRepoKeys[0].get()
            + "\" path=\"project1\" />\n"
            + "</projects>\n</manifest>\n";

    pushFactory
        .create(admin.getIdent(), manifestRepo, "Subject", "default", xml)
        .to("refs/heads/src1")
        .assertOkStatus();

    xml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<manifest>\n<projects>\n"
            + "  <project name=\""
            + testRepoKeys[1].get()
            + "\" remote=\""
            + canonicalWebUrl.get()
            + testRepoKeys[1].get()
            + "\" path=\"project2\" />\n"
            + "</projects>\n</manifest>\n";

    pushFactory
        .create(admin.getIdent(), manifestRepo, "Subject", "default", xml)
        .to("refs/heads/src2")
        .assertOkStatus();

    BranchApi branch1 = gApi.projects().name(superKey.get()).branch("refs/heads/src1");
    assertThat(branch1.file("project1").getContentType()).isEqualTo("x-git/gitlink; charset=UTF-8");
    try {
      branch1.file("project2");
      fail("wanted exception");
    } catch (ResourceNotFoundException e) {
      // all fine.
    }

    BranchApi branch2 = gApi.projects().name(superKey.get()).branch("refs/heads/src2");
    assertThat(branch2.file("project2").getContentType()).isEqualTo("x-git/gitlink; charset=UTF-8");
    try {
      branch2.file("project1");
      fail("wanted exception");
    } catch (ResourceNotFoundException e) {
      // all fine.
    }
  }

  @Test
  public void relativeFetch() throws Exception {
    // Test that first party gerrit repos are represented by relative URLs in supermanifest and
    // external repos by their absolute URLs.
    setupTestRepos("platform/project");

    String realPrefix = testRepoKeys[0].get().split("/")[0];

    Project.NameKey manifestKey =
        projectOperations.newProject().name(name(realPrefix + "/manifest")).create();
    TestRepository<InMemoryRepository> manifestRepo = cloneProject(manifestKey, admin);

    Project.NameKey superKey = projectOperations.newProject().name(name("superproject")).create();
    pushConfig(
        "[superproject \""
            + superKey.get()
            + ":refs/heads/destbranch\"]\n"
            + "  srcRepo = "
            + manifestKey.get()
            + "\n"
            + "  srcRef = refs/heads/srcbranch\n"
            + "  srcPath = default\n"
            + "  toolType = jiri\n");

    // XML change will trigger commit to superproject.
    String xml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<manifest>\n<projects>\n"
            + "<project name=\""
            + testRepoKeys[0].get()
            + "\" remote=\""
            + canonicalWebUrl.get()
            + testRepoKeys[0].get()
            + "\" path=\"project1\" />\n"
            + "<project name=\"external1\""
            + " remote=\"https://external/repo\""
            + " revision=\"c438d02cdf08a08fe29550cb11cb6ae8190919f1\""
            + " path=\"project2\" />\n"
            + "<project name=\"external2\""
            + " remote=\"https://external/"
            + testRepoKeys[1].get()
            + "\""
            + " revision=\"c438d02cdf08a08fe29550cb11cb6ae8190919f1\""
            + " path=\"project3\" />\n"
            + "</projects>\n</manifest>\n";

    pushFactory
        .create(admin.getIdent(), manifestRepo, "Subject", "default", xml)
        .to("refs/heads/srcbranch")
        .assertOkStatus();

    BranchApi branch = gApi.projects().name(superKey.get()).branch("refs/heads/destbranch");
    assertThat(branch.file("project1").getContentType()).isEqualTo("x-git/gitlink; charset=UTF-8");
    assertThat(branch.file("project2").getContentType()).isEqualTo("x-git/gitlink; charset=UTF-8");

    Config base = new Config();
    BlobBasedConfig cfg =
        new BlobBasedConfig(base, branch.file(".gitmodules").asString().getBytes(UTF_8));

    String subUrl = cfg.getString("submodule", "project1", "url");

    // URL is valid.
    URI.create(subUrl);

    // The suburl must be interpreted as relative to the parent project as a directory, i.e.
    // to go from superproject/ to platform/project0, you have to do ../platform/project0

    // URL is clean.
    assertThat(subUrl).isEqualTo("../" + realPrefix + "/project0");

    subUrl = cfg.getString("submodule", "project2", "url");

    // URL is valid.
    URI.create(subUrl);

    // The suburl must be absolute as this is external repo

    assertThat(subUrl).isEqualTo("https://external/repo");

    subUrl = cfg.getString("submodule", "project3", "url");

    // URL is valid.
    URI.create(subUrl);

    // Though the this project has the same name as a local repo, the subUrl must be absolute
    // as this is an external repo.
    assertThat(subUrl).isEqualTo("https://external/" + testRepoKeys[1].get());
  }

  @Test
  public void manifestIncludesOtherManifest() throws Exception {
    setupTestRepos("project");

    String xml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<manifest>\n<projects>\n"
            + "<project name=\""
            + testRepoKeys[0].get()
            + "\" remote=\""
            + canonicalWebUrl.get()
            + testRepoKeys[0].get()
            + "\" path=\"project1\" />\n"
            + "</projects>\n</manifest>\n";

    NameKey manifestKey = projectOperations.newProject().name(name("manifest")).create();
    TestRepository<InMemoryRepository> manifestRepo = cloneProject(manifestKey, admin);
    pushFactory
        .create(admin.getIdent(), manifestRepo, "Subject", "default", xml)
        .to("refs/heads/master")
        .assertOkStatus();

    String superXml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<manifest>\n<imports>\n"
            + "  <localimport file=\"default\"/>"
            + "</imports>\n</manifest>";

    pushFactory
        .create(admin.getIdent(), manifestRepo, "Subject", "super", superXml)
        .to("refs/heads/master")
        .assertOkStatus();

    NameKey superKey = projectOperations.newProject().name(name("superproject")).create();
    cloneProject(superKey, admin);

    pushConfig(
        "[superproject \""
            + superKey.get()
            + ":refs/heads/master\"]\n"
            + "  srcRepo = "
            + manifestKey.get()
            + "\n"
            + "  srcRef = refs/heads/master\n"
            + "  srcPath = super\n"
            + "  toolType = jiri\n");

    // Push a change to the source branch. We intentionally change the included XML file
    // (rather than the one mentioned in srcPath), to double check that we don't try to be too
    // smart about eluding nops.
    pushFactory
        .create(admin.getIdent(), manifestRepo, "Subject", "default", xml + " ")
        .to("refs/heads/master")
        .assertOkStatus();

    BranchApi branch = gApi.projects().name(superKey.get()).branch("refs/heads/master");
    assertThat(branch.file("project1").getContentType()).isEqualTo("x-git/gitlink; charset=UTF-8");
  }

  @Test
  public void remoteImportFails() throws Exception {
    setupTestRepos("project");

    String xml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<manifest>\n<projects>\n"
            + "<project name=\""
            + testRepoKeys[0].get()
            + "\" remote=\""
            + canonicalWebUrl.get()
            + testRepoKeys[0].get()
            + "\" path=\"project1\" />\n"
            + "</projects>\n</manifest>\n";

    NameKey manifestKey = projectOperations.newProject().name(name("manifest")).create();
    NameKey superKey = projectOperations.newProject().name(name("superproject")).create();

    cloneProject(superKey, admin);

    pushConfig(
        "[superproject \""
            + superKey.get()
            + ":refs/heads/master\"]\n"
            + "  srcRepo = "
            + manifestKey.get()
            + "\n"
            + "  srcRef = refs/heads/master\n"
            + "  srcPath = super\n"
            + "  toolType = jiri\n");
    TestRepository<InMemoryRepository> manifestRepo = cloneProject(manifestKey, admin);
    pushFactory
        .create(admin.getIdent(), manifestRepo, "Subject", "default", xml)
        .to("refs/heads/master")
        .assertOkStatus();

    String superXml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<manifest>\n<imports>\n"
            + "<import manifest=\""
            + manifestKey.get()
            + "\" name=\"default\" remote=\""
            + canonicalWebUrl.get()
            + manifestKey.get()
            + "\"/>"
            + "</imports>\n</manifest>";

    pushFactory
        .create(admin.getIdent(), manifestRepo, "Subject", "super", superXml)
        .to("refs/heads/master")
        .assertOkStatus();

    BranchApi branch = gApi.projects().name(superKey.get()).branch("refs/heads/master");
    try {
      branch.file("project1");
      fail("wanted exception");
    } catch (ResourceNotFoundException e) {
      // all fine.
    }
  }

  /*
   * Copied test from https://github.com/eclipse/jgit/blob/e9fb111182b55cc82c530d82f13176c7a85cd958/org.eclipse.jgit.test/tst/org/eclipse/jgit/gitrepo/RepoCommandTest.java#L1105
   */
  void testRelative(String a, String b, String want) throws Exception {
    String got = JiriUpdater.relativize(URI.create(a), URI.create(b)).toString();

    if (!got.equals(want)) {
      fail(String.format("relative('%s', '%s') = '%s', want '%s'", a, b, got, want));
    }
  }

  @Test
  public void relative() throws Exception {
    testRelative("a/b/", "a/", "../");
    // Normalization:
    testRelative("a/p/..//b/", "a/", "../");
    testRelative("a/b", "a/", "");
    testRelative("a/", "a/b/", "b/");
    testRelative("a/", "a/b", "b");
    testRelative("/a/b/c", "/b/c", "../../b/c");
    testRelative("/abc", "bcd", "bcd");
    testRelative("abc", "def", "def");
    testRelative("abc", "/bcd", "/bcd");
    testRelative("http://a", "a/b", "a/b");
    testRelative("http://base.com/a/", "http://child.com/a/b", "http://child.com/a/b");
  }

  // Modified from com.google.gerrit.acceptance.api.accounts.GeneralPreferencesIT.TestDownloadScheme
  private static class TestDownloadScheme extends DownloadScheme {

    private String host;

    public TestDownloadScheme(String host) {
      this.host = host;
    }

    @Override
    public String getUrl(String project) {
      return "https://" + this.host + "/" + project;
    }

    @Override
    public boolean isAuthRequired() {
      return false;
    }

    @Override
    public boolean isAuthSupported() {
      return false;
    }

    @Override
    public boolean isEnabled() {
      return true;
    }
  }
}
