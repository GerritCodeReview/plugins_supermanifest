load('//tools/bzl:junit.bzl', 'junit_tests')
load('//tools/bzl:plugin.bzl', 'gerrit_plugin')

gerrit_plugin(
  name = 'supermanifest',
  srcs = glob(['src/main/java/**/*.java']),
  resources = glob(['src/main/**/*']),
  manifest_entries = [
    'Gerrit-PluginName: supermanifest',
    'Gerrit-Module: com.googlesource.gerrit.plugins.supermanifest.Module',
    'Implementation-Title: Supermanifest plugin',
    'Implementation-URL: https://gerrit-review.googlesource.com/#/todo',
  ],
)

# For the reasons, how the plugin tests are implemented
# they cannot be run hermetically.
junit_tests(
  name = 'supermanifest_tests',
  srcs = glob(['src/test/java/**/*IT.java']),
  size = 'large',
  tags = ['supermanifest-plugin', 'local'],
  deps = [
    ':supermanifest__plugin',
    '//gerrit-acceptance-framework:lib',
    '//gerrit-plugin-api:lib',
  ],
  visibility = ['//visibility:public'],
)
