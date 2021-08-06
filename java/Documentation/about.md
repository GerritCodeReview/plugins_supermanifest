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


For the destination branch, you may also specify `*` to copy all branches in the
manifest repository. In this case the `srcRef` field is not required (it will be
ignored if present). Specific branches can be excluded with the `exclude`
option. `exclude` value is a comma-separated list of fully qualified refs
(i.e. with the `refs/heads/` prefix).

```
[superproject "submodules:*"]
   srcRepo = platforms/manifest
   srcPath = manifest.xml
   exclude = refs/heads/test,refs/heads/ignoreme
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
  http://[host]/a/projects/[url-encoded repository name]/branches/[branch name]/update_manifest
```

If you are working within Google and therefore talking to gerrit-on-borg you'll
want to use go/gob-curl to properly authenticate.

A successful request will return `204 No Content` if there were no errors.

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
