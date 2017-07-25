package com.googlesource.gerrit.plugins.supermanifest.repo;

import static com.google.gerrit.reviewdb.client.RefNames.REFS_HEADS;

import com.googlesource.gerrit.plugins.supermanifest.ConfigEntry;
import com.googlesource.gerrit.plugins.supermanifest.SubModuleUpdater;
import com.googlesource.gerrit.plugins.supermanifest.SuperManifestRefUpdatedListener.GerritRemoteReader;
import com.googlesource.gerrit.plugins.supermanifest.Utils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import org.eclipse.jgit.gitrepo.ManifestParser;
import org.eclipse.jgit.gitrepo.RepoCommand;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;

public class RepoUpdater implements SubModuleUpdater {
  PersonIdent serverIdent;
  URI canonicalWebUrl;

  public RepoUpdater(PersonIdent serverIdent, URI canonicalWebUrl) {
    this.serverIdent = serverIdent;
    this.canonicalWebUrl = canonicalWebUrl;
  }

  @Override
  public void update(GerritRemoteReader reader, ConfigEntry c, String srcRef) throws Exception {
    Repository destRepo = reader.openRepository(c.getDestRepoKey().toString());
    Repository srcRepo = reader.openRepository(c.getSrcRepoKey().toString());

    RepoCommand cmd = new RepoCommand(destRepo);

    if (c.getDestBranch().equals("*")) {
      cmd.setTargetBranch(srcRef.substring(REFS_HEADS.length()));
    } else {
      cmd.setTargetBranch(c.getDestBranch());
    }

    InputStream manifestStream =
        new ByteArrayInputStream(Utils.readBlob(srcRepo, srcRef + ":" + c.getXmlPath()));

    cmd.setAuthor(serverIdent)
        .setRecordRemoteBranch(true)
        .setRecordSubmoduleLabels(c.isRecordSubmoduleLabels())
        .setInputStream(manifestStream)
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
