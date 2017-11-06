load("//tools/bzl:junit.bzl", "junit_tests")
load("//tools/bzl:plugin.bzl", "gerrit_plugin")

gerrit_plugin(
    name = "supermanifest",
    srcs = glob(["src/main/java/**/*.java"]),
    manifest_entries = [
        "Gerrit-PluginName: supermanifest",
        "Gerrit-Module: com.googlesource.gerrit.plugins.supermanifest.SuperManifestModule",
        "Implementation-Title: Supermanifest plugin",
        "Implementation-URL: https://gerrit-review.googlesource.com/#/todo",
    ],
    resources = glob(["src/main/**/*"]),
)

junit_tests(
    name = "supermanifest_tests",
    size = "large",
    srcs = glob(["src/test/java/**/*IT.java"]),
    resources = glob(["src/test/resources/**"]),
    tags = [
        "supermanifest-plugin",
    ],
    visibility = ["//visibility:public"],
    deps = [
        ":supermanifest__plugin",
        "//java/com/google/gerrit/acceptance:lib",
        "//lib/bouncycastle:bcprov",
        "//lib/jetty:http",
        "//plugins:plugin-lib",
    ],
)
