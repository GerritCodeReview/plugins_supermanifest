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
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.extensions.api.projects.BranchApi;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RefNames;
import java.net.URI;
import java.util.Arrays;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.BlobBasedConfig;
import org.eclipse.jgit.lib.Config;
import org.junit.Test;

@TestPlugin(
  name = "supermanifest",
  sysModule = "com.googlesource.gerrit.plugins.supermanifest.SuperManifestModule"
)
public class RepoSuperManifestIT extends LightweightPluginDaemonTest {
  Project.NameKey[] testRepoKeys;
  String[] testRepoCommits;

  void setupTestRepos(String prefix) throws Exception {
    testRepoKeys = new Project.NameKey[2];
    testRepoCommits = new String[2];
    for (int i = 0; i < 2; i++) {
      testRepoKeys[i] = createProject(prefix + i);

      TestRepository<InMemoryRepository> repo = cloneProject(testRepoKeys[i], admin);

      PushOneCommit push =
          pushFactory.create(db, admin.getIdent(), repo, "Subject", "file" + i, "file");

      Result r = push.to("refs/heads/master");
      r.assertOkStatus();
      testRepoCommits[i] = r.getCommit().getName();
    }
  }

  void pushConfig(String config) throws Exception {
    // This will trigger a configuration reload.
    TestRepository<InMemoryRepository> allProjectRepo = cloneProject(allProjects, admin);
    GitUtil.fetch(allProjectRepo, RefNames.REFS_CONFIG + ":config");
    allProjectRepo.reset("config");
    PushOneCommit push =
        pushFactory.create(
            db, admin.getIdent(), allProjectRepo, "Subject", "supermanifest.config", config);
    PushOneCommit.Result res = push.to("refs/meta/config");
    res.assertOkStatus();
  }

  @Test
  public void basicFunctionalityWorks() throws Exception {
    setupTestRepos("project");

    // Make sure the manifest exists so the configuration loads successfully.
    Project.NameKey manifestKey = createProject("manifest");
    TestRepository<InMemoryRepository> manifestRepo = cloneProject(manifestKey, admin);

    Project.NameKey superKey = createProject("superproject");
    cloneProject(superKey, admin);

    pushConfig(
        "[superproject \""
            + superKey.get()
            + ":refs/heads/destbranch\"]\n"
            + "  srcRepo = "
            + manifestKey.get()
            + "\n"
            + "  srcRef = refs/heads/srcbranch\n"
            + "  srcPath = default.xml\n");

    String remoteXml = "  <remote name=\"origin\" fetch=\"" + canonicalWebUrl.get() + "\" />\n";
    String defaultXml = "  <default remote=\"origin\" revision=\"refs/heads/master\" />\n";
    // XML change will trigger commit to superproject.
    String xml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<manifest>\n"
            + remoteXml
            + defaultXml
            + "  <project name=\""
            + testRepoKeys[0].get()
            + "\" path=\"project1\" />\n"
            + "</manifest>\n";

    pushFactory
        .create(db, admin.getIdent(), manifestRepo, "Subject", "default.xml", xml)
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
            + "<manifest>\n"
            + remoteXml
            + defaultXml
            + "  <project name=\""
            + testRepoKeys[0].get()
            + "\" path=\"project1\" />\n"
            + "  <project name=\""
            + testRepoKeys[1].get()
            + "\" path=\"project2\" />\n"
            + "</manifest>\n";

    pushFactory
        .create(db, admin.getIdent(), manifestRepo, "Subject", "default.xml", xml)
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
            + "  srcPath = default.xml\n");

    // Push another XML change; this should trigger a commit using the new config.
    xml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<manifest>\n"
            + remoteXml
            + defaultXml
            + "  <project name=\""
            + testRepoKeys[1].get()
            + "\" path=\"project3\" />\n"
            + "</manifest>\n";

    pushFactory
        .create(db, admin.getIdent(), manifestRepo, "Subject", "default.xml", xml)
        .to("refs/heads/srcbranch")
        .assertOkStatus();

    branch = gApi.projects().name(superKey.get()).branch("refs/heads/other");
    assertThat(branch.file("project3").getContentType()).isEqualTo("x-git/gitlink; charset=UTF-8");
  }

  @Test
  public void httpEndpoint() throws Exception {
    setupTestRepos("project");

    // Make sure the manifest exists so the configuration loads successfully.
    Project.NameKey manifestKey = createProject("manifest");
    TestRepository<InMemoryRepository> manifestRepo = cloneProject(manifestKey, admin);

    Project.NameKey superKey = createProject("superproject");
    cloneProject(superKey, admin);

    String remoteXml = "  <remote name=\"origin\" fetch=\"" + canonicalWebUrl.get() + "\" />\n";
    String defaultXml = "  <default remote=\"origin\" revision=\"refs/heads/master\" />\n";
    String xml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<manifest>\n"
            + remoteXml
            + defaultXml
            + "  <project name=\""
            + testRepoKeys[0].get()
            + "\" path=\"project1\" />\n"
            + "</manifest>\n";

    pushFactory
        .create(db, admin.getIdent(), manifestRepo, "Subject", "default.xml", xml)
        .to("refs/heads/srcbranch")
        .assertOkStatus();

    // Push config after XML. Needs a manual trigger to create the destination.
    pushConfig(
        "[superproject \""
            + superKey.get()
            + ":refs/heads/destbranch\"]\n"
            + "  srcRepo = "
            + manifestKey.get()
            + "\n"
            + "  srcRef = refs/heads/srcbranch\n"
            + "  srcPath = default.xml\n");

    RestResponse r =
        userRestSession.post("/projects/" + manifestKey + "/branches/srcbranch/update_manifest");
    r.assertForbidden();
    r = adminRestSession.post("/projects/" + manifestKey + "/branches/srcbranch/update_manifest");
    r.assertNoContent();

    BranchApi branch = gApi.projects().name(superKey.get()).branch("refs/heads/destbranch");
    assertThat(branch.file("project1").getContentType()).isEqualTo("x-git/gitlink; charset=UTF-8");
  }

  @Test
  public void rawSha1Ref() throws Exception {
    setupTestRepos("project");

    // Make sure the manifest exists so the configuration loads successfully.
    Project.NameKey manifestKey = createProject("manifest");
    TestRepository<InMemoryRepository> manifestRepo = cloneProject(manifestKey, admin);

    Project.NameKey superKey = createProject("superproject");
    cloneProject(superKey, admin);

    String remoteXml = "  <remote name=\"origin\" fetch=\"" + canonicalWebUrl.get() + "\" />\n";
    String defaultXml = "  <default remote=\"origin\" revision=\"refs/heads/master\" />\n";
    String xml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<manifest>\n"
            + remoteXml
            + defaultXml
            + "  <project name=\""
            + testRepoKeys[0].get()
            + "\" path=\"project1\""
            + " revision=\""
            + testRepoCommits[0]
            + "\" />\n"
            + "</manifest>\n";

    pushFactory
        .create(db, admin.getIdent(), manifestRepo, "Subject", "default.xml", xml)
        .to("refs/heads/srcbranch")
        .assertOkStatus();

    // Push config after XML. Needs a manual trigger to create the destination.
    pushConfig(
        "[superproject \""
            + superKey.get()
            + ":refs/heads/destbranch\"]\n"
            + "  srcRepo = "
            + manifestKey.get()
            + "\n"
            + "  srcRef = refs/heads/srcbranch\n"
            + "  srcPath = default.xml\n"
            + "  ignoreRemoteFailures = true\n"
            + "");

    { // Advance head, but the manifest refers to the latest one.
      TestRepository<InMemoryRepository> repo = cloneProject(testRepoKeys[0], admin);
      PushOneCommit push =
          pushFactory.create(db, admin.getIdent(), repo, "Subject", "file3", "file");

      Result r = push.to("refs/heads/master");
      r.assertOkStatus();
    }

    adminRestSession
        .post("/projects/" + manifestKey + "/branches/srcbranch/update_manifest")
        .assertNoContent();

    BranchApi branch = gApi.projects().name(superKey.get()).branch("refs/heads/destbranch");
    assertThat(branch.file("project1").getContentType()).isEqualTo("x-git/gitlink; charset=UTF-8");
    assertThat(branch.file("project1").asString()).isEqualTo(testRepoCommits[0]);
  }

  @Test
  public void testIgnoreRemoteFailure() throws Exception {
    setupTestRepos("project");

    // Make sure the manifest exists so the configuration loads successfully.
    Project.NameKey manifestKey = createProject("manifest");
    TestRepository<InMemoryRepository> manifestRepo = cloneProject(manifestKey, admin);

    Project.NameKey superKey = createProject("superproject");
    cloneProject(superKey, admin);

    String remoteXml = "  <remote name=\"origin\" fetch=\"" + canonicalWebUrl.get() + "\" />\n";
    String defaultXml = "  <default remote=\"origin\" revision=\"refs/heads/master\" />\n";
    String xml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<manifest>\n"
            + remoteXml
            + " <remote fetch=\"https://example.invalid/\" name=\"invalid\" /> "
            + defaultXml
            + "  <project name=\""
            + testRepoKeys[0].get()
            + "\" path=\"project1\" />\n"
            + "  <project name=\"unavailable\" remote=\"invalid\" path=\"invalid\" />"
            + "</manifest>\n";

    pushFactory
        .create(db, admin.getIdent(), manifestRepo, "Subject", "default.xml", xml)
        .to("refs/heads/srcbranch")
        .assertOkStatus();

    // Push config after XML. Needs a manual trigger to create the destination.
    pushConfig(
        "[superproject \""
            + superKey.get()
            + ":refs/heads/destbranch\"]\n"
            + "  srcRepo = "
            + manifestKey.get()
            + "\n"
            + "  srcRef = refs/heads/srcbranch\n"
            + "  srcPath = default.xml\n");

    RestResponse r =
        adminRestSession.post("/projects/" + manifestKey + "/branches/srcbranch/update_manifest");
    r.assertStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);

    pushConfig(
        "[superproject \""
            + superKey.get()
            + ":refs/heads/destbranch\"]\n"
            + "  srcRepo = "
            + manifestKey.get()
            + "\n"
            + "  srcRef = refs/heads/srcbranch\n"
            + "  srcPath = default.xml\n"
            + "  ignoreRemoteFailures = true\n");

    r = adminRestSession.post("/projects/" + manifestKey + "/branches/srcbranch/update_manifest");
    r.assertNoContent();

    BranchApi branch = gApi.projects().name(superKey.get()).branch("refs/heads/destbranch");
    assertThat(branch.file("project1").getContentType()).isEqualTo("x-git/gitlink; charset=UTF-8");
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
    Project.NameKey manifestKey = createProject("manifest");
    TestRepository<InMemoryRepository> manifestRepo = cloneProject(manifestKey, admin);

    Project.NameKey superKey = createProject("superproject");
    cloneProject(superKey, admin);

    pushConfig(
        "[superproject \""
            + superKey.get()
            + ":refs/heads/*\"]\n"
            + "  srcRepo = "
            + manifestKey.get()
            + "\n"
            + "  srcRef = blablabla\n"
            + "  srcPath = default.xml\n");

    String remoteXml = "  <remote name=\"origin\" fetch=\"" + canonicalWebUrl.get() + "\" />\n";
    String originXml = "  <default remote=\"origin\" revision=\"refs/heads/master\" />\n";

    // XML change will trigger commit to superproject.
    String xml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<manifest>\n"
            + remoteXml
            + originXml
            + "  <project name=\""
            + testRepoKeys[0].get()
            + "\" path=\"project1\" />\n"
            + "</manifest>\n";

    pushFactory
        .create(db, admin.getIdent(), manifestRepo, "Subject", "default.xml", xml)
        .to("refs/heads/src1")
        .assertOkStatus();

    xml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<manifest>\n"
            + remoteXml
            + originXml
            + "  <project name=\""
            + testRepoKeys[1].get()
            + "\" path=\"project2\" />\n"
            + "</manifest>\n";

    pushFactory
        .create(db, admin.getIdent(), manifestRepo, "Subject", "default.xml", xml)
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
  public void manifestIncludesOtherManifest() throws Exception {
    setupTestRepos("project");

    String remoteXml = "  <remote name=\"origin\" fetch=\"" + canonicalWebUrl.get() + "\" />\n";
    String originXml = "  <default remote=\"origin\" revision=\"refs/heads/master\" />\n";
    String xml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<manifest>\n"
            + remoteXml
            + originXml
            + "  <project name=\""
            + testRepoKeys[0].get()
            + "\" path=\"project1\" />\n"
            + "</manifest>\n";

    Project.NameKey manifestKey = createProject("manifest");
    TestRepository<InMemoryRepository> manifestRepo = cloneProject(manifestKey, admin);
    pushFactory
        .create(db, admin.getIdent(), manifestRepo, "Subject", "default.xml", xml)
        .to("refs/heads/master")
        .assertOkStatus();

    String superXml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<manifest>"
            + "  <include name=\"default.xml\"/>"
            + "</manifest>";

    pushFactory
        .create(db, admin.getIdent(), manifestRepo, "Subject", "super.xml", superXml)
        .to("refs/heads/master")
        .assertOkStatus();

    Project.NameKey superKey = createProject("superproject");
    cloneProject(superKey, admin);

    pushConfig(
        "[superproject \""
            + superKey.get()
            + ":refs/heads/master\"]\n"
            + "  srcRepo = "
            + manifestKey.get()
            + "\n"
            + "  srcRef = refs/heads/master\n"
            + "  srcPath = super.xml\n");

    // Push a change to the source branch. We intentionally change the included XML file
    // (rather than the one mentioned in srcPath), to double check that we don't try to be too
    // smart about eliding nops.
    pushFactory
        .create(db, admin.getIdent(), manifestRepo, "Subject", "default.xml", xml + " ")
        .to("refs/heads/master")
        .assertOkStatus();

    BranchApi branch = gApi.projects().name(superKey.get()).branch("refs/heads/master");
    assertThat(branch.file("project1").getContentType()).isEqualTo("x-git/gitlink; charset=UTF-8");
  }

  @Test
  public void relativeFetch() throws Exception {
    // Test the setup that Android uses, where the "fetch" field is relative to the location of the
    // manifest repo.
    setupTestRepos("platform/project");

    // The test framework adds more cruft to the prefix.
    String realPrefix = testRepoKeys[0].get().split("/")[0];

    Project.NameKey manifestKey = createProject(realPrefix + "/manifest");
    TestRepository<InMemoryRepository> manifestRepo = cloneProject(manifestKey, admin);

    Project.NameKey superKey = createProject("superproject");
    pushConfig(
        "[superproject \""
            + superKey.get()
            + ":refs/heads/destbranch\"]\n"
            + "  srcRepo = "
            + manifestKey.get()
            + "\n"
            + "  srcRef = refs/heads/srcbranch\n"
            + "  srcPath = default.xml\n");

    String url = canonicalWebUrl.get();
    String remoteXml = "  <remote name=\"origin\" fetch=\"..\" review=\"" + url + "\" />\n";
    String defaultXml = "  <default remote=\"origin\" revision=\"refs/heads/master\" />\n";

    String xml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<manifest>\n"
            + remoteXml
            + defaultXml
            + "  <project name=\""
            + testRepoKeys[0].get()
            + "\" path=\"path1\" />\n"
            + "</manifest>\n";
    pushFactory
        .create(db, admin.getIdent(), manifestRepo, "Subject", "default.xml", xml)
        .to("refs/heads/srcbranch")
        .assertOkStatus();

    BranchApi branch = gApi.projects().name(superKey.get()).branch("refs/heads/destbranch");
    assertThat(branch.file("path1").getContentType()).isEqualTo("x-git/gitlink; charset=UTF-8");

    Config base = new Config();
    BlobBasedConfig cfg =
        new BlobBasedConfig(base, branch.file(".gitmodules").asString().getBytes(UTF_8));

    String subUrl = cfg.getString("submodule", "path1", "url");

    // URL is valid.
    URI.create(subUrl);

    // The suburls must be interpreted as relative to the parent project as a directory, i.e.
    // to go from superproject/ to platform/project0, you have to do ../platform/project0

    // URL is clean.
    assertThat(subUrl).isEqualTo("../" + realPrefix + "/project0");
  }

  @Test
  public void testToRepoKey() {
    URI base = URI.create("https://gerrit-review.googlesource.com");
    assertThat(
            SuperManifestRefUpdatedListener.urlToRepoKey(
                base, "https://gerrit-review.googlesource.com/repo"))
        .isEqualTo("repo");
    assertThat(SuperManifestRefUpdatedListener.urlToRepoKey(base, "repo")).isEqualTo("repo");
    assertThat(
            SuperManifestRefUpdatedListener.urlToRepoKey(
                URI.create("https://gerrit-review.googlesource.com/"),
                "https://gerrit-review.googlesource.com/repo"))
        .isEqualTo("repo");
  }

  // TODO - should add tests for all the error handling in configuration parsing?
}
