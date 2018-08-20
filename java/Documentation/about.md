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

It should be configured by adding `supermanifest.config` to the
`All-Projects` project. The format for configuration is as follows:


```
[superproject "submodules:refs/heads/nyc"]
   srcRepo = platforms/manifest
   srcRef = refs/heads/nyc
   srcPath = manifest.xml
   toolType = repo
```

this configures a repository called `submodules` to have a branch
`nyc`, for which the contents corresponds to the manifest file
`manifest.xml` on branch `refs/heads/nyc` in project `platforms/manifest`.

valid value(s) for `toolType` right now is `repo`. It can be left blank to
default to `repo`.

The plugin supports the following options:

*  `groups` (defaults to `default`). Sets the groups setting for JGit's
   RepoCommand

*  `recordSubmoduleLabels` (defaults to false). Sets recordSubmoduleLabels
   setting for JGit's RepoCommand

*  `ignoreRemoteFailures = true` (defaults to false). Sets ignoreRemoteFailures.
   Setting it true will cause repos that are not accessible to be ignored.


For the destination branch, you may also specify `*` to copy all
branches in the manifest repository.

```
[superproject "submodules:*"]
   srcRepo = platforms/manifest
   srcPath = manifest.xml
```

This plugin bypasses visibility restrictions, so edits to the manifest
repo can be used to reveal existence of hidden repositories or
branches.


MANUAL TRIGGER
==============

Users that have the `administrateServer` permission may simulate an update to
the manifest repository. This is useful for debugging, and provides diagnostics
that are otherwise written into the server logs. To do so, issue the following
call

```sh
curl -X POST
  http://HOST/a/projects/platform%2Fmanifest/branches/master/update_manifest
```


JIRI
====

There is also support for the Jiri manifest file used in the Fuchsia OS.

```
[superproject "submodules:refs/heads/master"]
   srcRepo = platforms/manifest
   srcRef = refs/heads/master
   srcPath = manifest.xml
   toolType = jiri
```

TODO(anmittal): provide more documentation.
