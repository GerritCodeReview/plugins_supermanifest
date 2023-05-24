// Copyright (C) 2017 The Android Open Source Project
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

import static com.google.gerrit.entities.RefNames.REFS_HEADS;

import com.googlesource.gerrit.plugins.supermanifest.SuperManifestRefUpdatedListener.GerritRemoteReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.gitrepo.ManifestParser;
import org.eclipse.jgit.gitrepo.RepoCommand;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;

class RepoUpdater implements SubModuleUpdater {

  static final String SUPERMANIFEST_STAMP = ".supermanifest";

  PersonIdent serverIdent;

  public RepoUpdater(PersonIdent serverIdent) {
    this.serverIdent = serverIdent;
  }

  @Override
  public void update(GerritRemoteReader reader, ConfigEntry c, String srcRef)
      throws IOException, GitAPIException {
    Repository destRepo = reader.openRepository(c.getDestRepoKey().toString());
    Repository srcRepo = reader.openRepository(c.getSrcRepoKey().toString());

    RepoCommand cmd = new RepoCommand(destRepo);
    cmd.setTargetBranch(c.getActualDestBranch(srcRef));

    InputStream manifestStream =
        new ByteArrayInputStream(Utils.readBlob(srcRepo, srcRef + ":" + c.getXmlPath()));

    cmd.setAuthor(serverIdent)
        .setGroups(c.getGroupsParameter())
        .setRecordRemoteBranch(true)
        .setRecordSubmoduleLabels(c.isRecordSubmoduleLabels())
        .setIgnoreRemoteFailures(c.ignoreRemoteFailures)
        .setInputStream(manifestStream)
        .addToDestination(
            SUPERMANIFEST_STAMP,
            String.format("%s %s %s", c.getSrcRepoKey(), srcRef, srcRepo.resolve(srcRef).getName()))
        .setRecommendShallow(true)
        .setRemoteReader(reader)
        .setTargetURI(c.getDestRepoKey().toString())
        .setURI(c.getBaseUri().toString());

    // Must setup a included file reader; the default is to read the file from the filesystem
    // otherwise, which would leak data from the serving machine.
    cmd.setIncludedFileReader(new GerritIncludeReader(srcRepo, srcRef));

    cmd.call();
  }

  private static class GerritIncludeReader implements ManifestParser.IncludedFileReader {
    private final Repository repo;
    private final String ref;

    GerritIncludeReader(Repository repo, String ref) {
      this.repo = repo;
      this.ref = ref;
    }

    @Override
    public InputStream readIncludeFile(String path) throws IOException {
      String blobRef = ref + ":" + path;
      return new ByteArrayInputStream(Utils.readBlob(repo, blobRef));
    }
  }
}
