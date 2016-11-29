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

import com.google.inject.AbstractModule;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.gitrepo.RepoCommand;
import org.eclipse.jgit.revwalk.RevCommit;

import java.util.List;

/**
 * This module will listen for changes to XML files in manifest repositories. When it finds such changes,
 * it will trigger an update of the associated superproject is configured.
 */
public class Module extends AbstractModule {

  private static class ConfigEntry {
    String repo;
    String branch;
    String xmlPath;
    String destRepo;
    String destBranch;
  }

  private List<ConfigEntry> config = ImmutableList.of();

  private static boolean hasDiff(String repo, String oldId, String newId, String path) {
    // stub for: get commits, Get trees, do treewalk and see if path is touched.
    return false;
  }

  private void parseConfiguration() {
    /* parse a list of ConfigEntry out of the global site configuration.
     Can we get at a list of strings in a plugin config section?

     Alternatively, we could store some config in one of the repos. However, since the config
     crosses repo boundaries, where should it live? What is a sensible choice here?
    */
  }

  private class RefUpdatedListener implements GitReferenceUpdatedListener{
    private boolean isConfigEvent(Event e) {
      return false;
    }


    public void onGitReferenceUpdated(Event event) {
      if (isConfigEvent(event)) {
        // TODO: Reparse configuration.
        return;
      }

      for (ConfigEntry c : config) {
        if (c.repo != event.getProjectName() || c.branch != event.getRefName()) {
          continue;
        }

        if (!hasDiff(event.getProjectName(), event.getOldObjectId(), event.getNewObjectId(), c.xmlPath)) {
          continue;
        }


        RepoCommand cmd =
            new RepoCommand(null);
        // TODO(hanwen): set cmd parameters
        try {
          RevCommit commit = cmd.call();
        } catch (GitAPIException e) {
          // log error.
        }
      }
    }
  }


  @Override
  protected void configure() {
    DynamicSet.bind(binder(), GitReferenceUpdatedListener.class)
      .to(RefUpdatedListener.class);
  }

}
