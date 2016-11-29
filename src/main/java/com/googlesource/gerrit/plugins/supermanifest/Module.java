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

import com.google.common.collect.ImmutableList;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;

import com.google.gerrit.extensions.registration.DynamicSet;

import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.gitrepo.RepoCommand;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * This module will listen for changes to XML files in manifest repositories. When it finds such changes,
 * it will trigger an update of the associated superproject.
 */
public class Module extends AbstractModule {
  private static final Logger log =
      LoggerFactory.getLogger(Module.class);

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
    String repo;
    String branch;
    String xmlPath;
    Project.NameKey destRepoKey;
    String destBranch;

    // TODO - add groups?

    @Override
    public String toString() {
      return String.format("%s:%s:%s => %s:%s", repo, branch, xmlPath, destRepoKey, destBranch);
    }
  }

  private List<ConfigEntry> config = ImmutableList.of();

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

  private void parseConfiguration() {
    /* parse a list of ConfigEntry out of the global site configuration.
     Can we get at a list of strings in a plugin config section?

     Alternatively, we could store some config in one of the repos. However, since the config
     crosses repo boundaries, where should it live? What is a sensible choice here?
    */
  }

  private class RefUpdatedListener implements GitReferenceUpdatedListener {
    private boolean isConfigEvent(Event e) {
      return false;
    }

    @Override
    public void onGitReferenceUpdated(Event event) {
      if (isConfigEvent(event)) {
        // TODO: Reparse configuration.
        return;
      }

      for (ConfigEntry c : config) {
        if (c.repo.equals(event.getProjectName()) || c.branch.equals(event.getRefName())) {
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
