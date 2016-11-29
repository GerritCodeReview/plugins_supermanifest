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
import static org.junit.Assert.fail;

import com.google.gerrit.acceptance.GitUtil;
import com.google.gerrit.acceptance.PluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.extensions.api.projects.BranchApi;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RefNames;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.junit.Test;

@TestPlugin(
  name = "supermanifest",
  sysModule = "com.googlesource.gerrit.plugins.supermanifest.SuperManifestModule"
)
public class SuperManifestIT extends PluginDaemonTest {
  @Test
  public void basicFunctionalityWorks() throws Exception {
    Project.NameKey testRepoKeys[] = new Project.NameKey[2];
    for (int i = 0; i < 2; i++) {
      testRepoKeys[i] = createProject("project" + i);

      TestRepository<InMemoryRepository> repo = cloneProject(testRepoKeys[i], admin);

      PushOneCommit push =
          pushFactory.create(db, admin.getIdent(), repo, "Subject", "file" + i, "file");
      push.to("refs/heads/master").assertOkStatus();
    }

    // Make sure the manifest exists so the configuration loads successfully.
    Project.NameKey manifestKey = createProject("manifest");
    TestRepository<InMemoryRepository> manifestRepo = cloneProject(manifestKey, admin);

    Project.NameKey superKey = createProject("superproject");

    TestRepository<InMemoryRepository> superRepo = cloneProject(superKey, admin);

    String config =
        "[superproject \""
            + superKey.get()
            + ":destbranch\"]\n"
            + "  srcRepo = "
            + manifestKey.get()
            + "\n"
            + "  srcRef = refs/heads/srcbranch\n"
            + "  srcPath = default.xml\n";

    // This will trigger a configuration reload.
    TestRepository<InMemoryRepository> allProjectRepo = cloneProject(allProjects, admin);
    GitUtil.fetch(allProjectRepo, RefNames.REFS_CONFIG + ":config");
    allProjectRepo.reset("config");
    PushOneCommit push =
        pushFactory.create(
            db, admin.getIdent(), allProjectRepo, "Subject", "supermanifest.config", config);
    PushOneCommit.Result res = push.to("refs/meta/config");
    res.assertOkStatus();

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
        .to("refs/heads/srcbranch")
        .assertOkStatus();

    System.err.println("\n\ngit clone " + canonicalWebUrl.get() + superKey.get());

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
            + originXml
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
    config =
        "[superproject \""
            + superKey.get()
            + ":other\"]\n"
            + "  srcRepo = "
            + manifestKey.get()
            + "\n"
            + "  srcRef = refs/heads/srcbranch\n"
            + "  srcPath = default.xml\n";

    pushFactory
        .create(db, admin.getIdent(), allProjectRepo, "Subject", "supermanifest.config", config)
        .to("refs/meta/config")
        .assertOkStatus();

    // Push another XML change; this should trigger a commit using the new config.
    xml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<manifest>\n"
            + remoteXml
            + originXml
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
  public void wildcardDestBranchWorks() throws Exception {
    Project.NameKey testRepoKeys[] = new Project.NameKey[2];
    for (int i = 0; i < 2; i++) {
      testRepoKeys[i] = createProject("project" + i);

      TestRepository<InMemoryRepository> repo = cloneProject(testRepoKeys[i], admin);

      PushOneCommit push =
          pushFactory.create(db, admin.getIdent(), repo, "Subject", "file" + i, "file");
      push.to("refs/heads/master").assertOkStatus();
    }

    // Make sure the manifest exists so the configuration loads successfully.
    Project.NameKey manifestKey = createProject("manifest");
    TestRepository<InMemoryRepository> manifestRepo = cloneProject(manifestKey, admin);

    Project.NameKey superKey = createProject("superproject");
    TestRepository<InMemoryRepository> superRepo = cloneProject(superKey, admin);

    String config =
        "[superproject \""
            + superKey.get()
            + ":*\"]\n"
            + "  srcRepo = "
            + manifestKey.get()
            + "\n"
            + "  srcRef = blablabla\n"
            + "  srcPath = default.xml\n";

    // This will trigger a configuration reload.
    TestRepository<InMemoryRepository> allProjectRepo = cloneProject(allProjects, admin);
    GitUtil.fetch(allProjectRepo, RefNames.REFS_CONFIG + ":config");
    allProjectRepo.reset("config");
    PushOneCommit push =
        pushFactory.create(
            db, admin.getIdent(), allProjectRepo, "Subject", "supermanifest.config", config);
    PushOneCommit.Result res = push.to("refs/meta/config");
    res.assertOkStatus();

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

    System.err.println("\n\ngit clone " + canonicalWebUrl.get() + superKey.get());

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

  // TODO - should add tests for all the error handling in configuration parsing?
}
