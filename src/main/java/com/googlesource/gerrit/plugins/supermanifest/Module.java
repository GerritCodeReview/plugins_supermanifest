// Copyright (C) 2013 The Android Open Source Project
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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;

import com.google.gerrit.extensions.registration.DynamicSet;

import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.gitrepo.RepoCommand;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * This module will listen for changes to XML files in manifest repositories. When it finds such changes,
 * it will trigger an update of the associated superproject.
 */
public class Module extends AbstractModule {
  private static final Logger log =
      LoggerFactory.getLogger(Module.class);

  private static String ALL_PROJECTS_NAME = "All-Projects";
  private static String NAME = "SuperManifest";

  private static String SECTION_NAME = "manifest";

  private final PluginConfigFactory cfgFactory;
  private final GitRepositoryManager repoManager;

  @Inject
  Module(
      PluginConfigFactory cfgFactory,
      GitRepositoryManager repoManager) {
    this.cfgFactory = cfgFactory;
    this.repoManager = repoManager;
  }

  private static class ConfigEntry {
    String srcRepo;
    String srcBranch;
    String xmlPath;
    Project.NameKey destRepoKey;
    String destBranch;

    // TODO - add groups?

    @Override
    public String toString() {
      return String.format("%s:%s:%s => %s:%s", srcRepo, srcBranch, xmlPath, destRepoKey, destBranch);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      ConfigEntry that = (ConfigEntry) o;

      if (!srcRepo.equals(that.srcRepo)) return false;
      if (!srcBranch.equals(that.srcBranch)) return false;
      if (!xmlPath.equals(that.xmlPath)) return false;
      if (!destRepoKey.equals(that.destRepoKey)) return false;
      return destBranch.equals(that.destBranch);
    }

    @Override
    public int hashCode() {
      int result = srcRepo.hashCode();
      result = 31 * result + srcBranch.hashCode();
      result = 31 * result + xmlPath.hashCode();
      result = 31 * result + destRepoKey.hashCode();
      result = 31 * result + destBranch.hashCode();
      return result;
    }
  }

  private Set<ConfigEntry> config = ImmutableSet.of();

  private boolean hasDiff(String repoName, String oldId, String newId, String path) throws IOException {
    Project.NameKey projectName = new Project.NameKey(repoName);

    try (Repository repo =
             repoManager.openRepository(projectName);
        RevWalk rw = new RevWalk(repo)) {
        RevCommit c1 = rw.lookupCommit(ObjectId.fromString(oldId));
        RevCommit c2 = rw.lookupCommit(ObjectId.fromString(newId));

        try (TreeWalk tw = TreeWalk.forPath(
            repo, path, c1.getTree().getId(),
            c2.getTree().getId())) {
          return !tw.getObjectId(0).equals(tw.getObjectId(1));
        }
      }
  }

  /*
      [manifest "bla"]
         srcRepo = platforms/manifest
         srcBranch = nyc
         srcPath = manifest.xml
         destRepo = submodules
         destBranch = nyc

      so section = manifest, subsection = bla.
   */
  private void parseConfiguration() {
    Project.NameKey all = new Project.NameKey(ALL_PROJECTS_NAME);

    Config cfg = null;
    try {
      cfg = cfgFactory.getProjectPluginConfig(all, NAME);
    } catch (NoSuchProjectException e) {
      Preconditions.checkState(false);
    }

    Set<ConfigEntry> newConf = new HashSet<>();
    for (String name : cfg.getSubsections(SECTION_NAME)) {
      try {
        ConfigEntry e = newConfigEntry(cfg, name);
        newConf.add(e);
      } catch (ConfigException e) {
        // how to report?
      }
    }

    config = newConf; // TODO synchronized.
  }

  public class ConfigException extends Exception {
    String missing;
    ConfigException(String m) { missing = m; }
  }

  private interface checked<F, T> {
    T apply(F var1) throws ConfigException;
  }

  private ConfigEntry newConfigEntry(Config cfg, String name) throws ConfigException {
    checked<String, String> get = (nm) -> {
      String s = cfg.getString(SECTION_NAME, name, nm);
      if (s == null) {
        throw new ConfigException(nm);
      }
      return s;
    };
    ConfigEntry e = new ConfigEntry();
    e.srcRepo = get.apply("srcRepo");
    e.srcBranch = get.apply("srcBranch");
    e.xmlPath = get.apply("srcPath");
    e.destRepoKey = new Project.NameKey(get.apply("destRepo"));
    e.destBranch = get.apply("destBranch");
    return e;
  }

  private class RefUpdatedListener implements GitReferenceUpdatedListener {
    private boolean isConfigEvent(Event e) {
      return e.getProjectName().equals(ALL_PROJECTS_NAME) && e.getRefName().equals("refs/heads/master");
    }

    @Override
    public void onGitReferenceUpdated(Event event) {
      if (isConfigEvent(event)) {
        parseConfiguration();
        return;
      }

      for (ConfigEntry c : config) {
        if (c.srcRepo.equals(event.getProjectName()) || c.srcBranch.equals(event.getRefName())) {
          continue;
        }

        try {
          if (!hasDiff(event.getProjectName(), event.getOldObjectId(), event.getNewObjectId(), c.xmlPath)) {
            continue;
          }
        } catch (IOException e) {
          log.error("ignoring hasDiff error for" + c.toString() + ":", e.toString());
        }

        try {
          update(c);
        } catch (IOException | GitAPIException e) {
          log.error("update for " + c.toString()+ " failed: ", e);
        }
      }
    }

    private void update(ConfigEntry c) throws IOException, GitAPIException {
      RepoCommand cmd =
          new RepoCommand(repoManager.openRepository(c.destRepoKey));

      // load commit author from event; cmd.setAuthor()
      cmd.setBranch(c.destBranch); // difference with setTargetBranch ?

      // load XML, and convert to stream. Or use setPath ?
      cmd.setInputStream(null);

      cmd.setIgnoreRemoteFailures(false);

      cmd.setRecommendShallow(true);

      cmd.setURI(""); // ?
      cmd.setRemoteReader(null); // ?
      cmd.setIncludedFileReader(null); // ?

      RevCommit commit = cmd.call();
      // TODO - check if there was a change?

      // TODO - what if we have subscription enabled as well. Can we get inconsistency due to
      // reordering of events?
    }
  }

  protected void configure() {
    DynamicSet.bind(binder(), GitReferenceUpdatedListener.class)
      .to(RefUpdatedListener.class);
  }

}
