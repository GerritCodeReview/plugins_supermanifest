package com.googlesource.gerrit.plugins.supermanifest;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.acceptance.GitUtil;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.PushOneCommit.Result;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gson.stream.JsonReader;
import com.google.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.lang3.RandomStringUtils;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.junit.Test;

@TestPlugin(
    name = "supermanifest",
    sysModule = "com.googlesource.gerrit.plugins.supermanifest.SuperManifestModule")
public class SuperManifestBranchConfIT extends LightweightPluginDaemonTest {
  Project.NameKey[] testRepoKeys;
  String[] testRepoCommits;
  Project.NameKey manifestKey;
  TestRepository<InMemoryRepository> manifestRepo;
  Project.NameKey superKey;

  @Inject private ProjectOperations projectOperations;

  void setupTestRepos(String prefix) throws Exception {
    testRepoKeys = new Project.NameKey[2];
    testRepoCommits = new String[2];
    for (int i = 0; i < 2; i++) {
      testRepoKeys[i] =
          projectOperations
              .newProject()
              .name(RandomStringUtils.randomAlphabetic(8) + prefix + i)
              .create();

      TestRepository<InMemoryRepository> repo = cloneProject(testRepoKeys[i], admin);

      PushOneCommit push =
          pushFactory.create(admin.newIdent(), repo, "Subject", "file" + i, "file");

      Result r = push.to("refs/heads/master");
      r.assertOkStatus();
      testRepoCommits[i] = r.getCommit().getName();
    }

    // Make sure the manifest exists so the configuration loads successfully.
    manifestKey = projectOperations.newProject().name(name("manifest")).create();
    manifestRepo = cloneProject(manifestKey, admin);

    superKey = projectOperations.newProject().name(name("superproject")).create();
    cloneProject(superKey, admin);
  }

  void pushConfig(String config) throws Exception {
    // This will trigger a configuration reload.
    TestRepository<InMemoryRepository> allProjectRepo = cloneProject(allProjects, admin);
    GitUtil.fetch(allProjectRepo, RefNames.REFS_CONFIG + ":config");
    allProjectRepo.reset("config");
    PushOneCommit push =
        pushFactory.create(
            admin.newIdent(), allProjectRepo, "Subject", "supermanifest.config", config);
    PushOneCommit.Result res = push.to("refs/meta/config");
    res.assertOkStatus();
  }

  public void pushManifestToRef(String ref) throws Exception {
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
    Result manifestPush =
        pushFactory.create(admin.newIdent(), manifestRepo, "Subject", "default.xml", xml).to(ref);
    manifestPush.assertOkStatus();
  }

  @Test
  public void check_onlyAdmin() throws Exception {
    setupTestRepos("project");
    pushManifestToRef("refs/heads/srcbranch");

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

    RestResponse r = userRestSession.get("/projects/" + manifestKey + "/branches/srcbranch/conf");
    r.assertForbidden();
  }

  @Test
  public void check_oneone_match() throws Exception {
    setupTestRepos("project");
    pushManifestToRef("refs/heads/srcbranch");

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

    RestResponse r = adminRestSession.get("/projects/" + manifestKey + "/branches/srcbranch/conf");
    r.assertOK();
    List<String> msgs = parseBody(r);
    assertThat(msgs)
        .contains(
            String.format(
                "MATCH: %s:refs/heads/srcbranch:default.xml (Repo) => %s:destbranch",
                manifestKey.get(), superKey.get()));
  }

  @Test
  public void check_star_match() throws Exception {
    setupTestRepos("project");
    pushManifestToRef("refs/heads/srcbranch");

    // Push config after XML. Needs a manual trigger to create the destination.
    pushConfig(
        "[superproject \""
            + superKey.get()
            + ":refs/heads/*\"]\n"
            + "  srcRepo = "
            + manifestKey.get()
            + "\n"
            + "  srcPath = default.xml\n");

    RestResponse r = adminRestSession.get("/projects/" + manifestKey + "/branches/srcbranch/conf");
    r.assertOK();
    List<String> msgs = parseBody(r);
    assertThat(msgs)
        .contains(
            String.format(
                "MATCH: %s:*:default.xml (Repo) => %s:*", manifestKey.get(), superKey.get()));
  }

  @Test
  public void check_overlapping() throws Exception {
    setupTestRepos("project");
    pushManifestToRef("refs/heads/main-x-release");

    // Configure supermanifest to same superproject for main-* and *-release.
    // An update to main-x-release triggers an overlapping write
    pushConfig(
        "[superproject \""
            + superKey.get()
            + ":refs/heads/main-*\"]\n"
            + "  srcRepo = "
            + manifestKey.get()
            + "\n"
            + "  srcRef = blablabla\n"
            + "  srcPath = default.xml\n"
            + "\n"
            + "[superproject \""
            + superKey.get()
            + ":refs/heads/*-release\"]\n"
            + "  srcRepo = "
            + manifestKey.get()
            + "\n"
            + "  srcRef = blablabla\n"
            + "  srcPath = default.xml\n");

    RestResponse r =
        adminRestSession.get("/projects/" + manifestKey + "/branches/main-x-release/conf");
    r.assertOK();
    List<String> msgs = parseBody(r);
    assertThat(msgs).hasSize(5);
    assertThat(startingWith("CONF", msgs)).hasSize(2);
    assertThat(startingWith("MATCH:", msgs)).hasSize(1);
    assertThat(startingWith("SKIP:", msgs)).hasSize(1);
  }

  private List<String> startingWith(String prefix, List<String> entries) {
    return entries.stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
  }

  private List<String> parseBody(RestResponse r) throws IOException {
    try (JsonReader jsonReader = new JsonReader(r.getReader())) {
      jsonReader.setLenient(true);
      return newGson().fromJson(jsonReader, ArrayList.class);
    }
  }
}
