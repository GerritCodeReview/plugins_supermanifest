This plugin updates a submodule superproject based on a manifest repository.

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
