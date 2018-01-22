
SUMMARY
=======

The SuperManifest plugin is for projects that use the 'repo' tool to piece
together a tree of git repositories. The repo tool reads an XML file that
describes the layout of the tree.

The file is usually called `default.xml`, and the repository is usually called
`manifest`.

Notable projects that use 'repo' include the Android Open Source Project.

The plugin will update a superproject to contain the submodules defined in the
manifest XML file whenever the manifest repo is changed.


CONFIGURATION
=============

The plugin should be enabled in `gerrit.config`, and then configured with a
`supermanifest.config` configuration file in the `refs/meta/config` branch. The
`supermanifest.config` lists the mappings from source, eg.

```
    [superproject "codesearch:refs/heads/stable"]
      srcRepo = manifest
      srcPath = default.xml
      srcBranch = stable
```

If all branches from the manifest repo should be mirrored into the
`codesearch` repository, you can specify `*` as the destination
branch, e.g.,

```
    [superproject "codesearch:refs/heads/*"]
      srcRepo = manifest
      srcPath = default.xml
```

MANUAL TRIGGER
==============

Users that have the `administrateServer` permission may simulate an update to
the manifest repository. This is useful for debugging, and provides diagnostics
that are otherwise written into the server logs. To do so, issue the following
call

```sh
curl -X POST
  http://HOST/a/projects/codesearch/branches/master/update_manifest
```

JIRI
====

There is also support for the Jiri manifest file used in the Fuchsia OS.

TODO(anmittal): provide more documentation.
