load("//tools/bzl:maven_jar.bzl", "maven_jar")

def external_plugin_deps():
  maven_jar(
    name = 'jaxb-api',
    artifact = 'javax.xml.bind:jaxb-api:2.3.1',
  )
