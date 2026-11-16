# SPDX-License-Identifier: GPL-3.0-only
{
  runCommand,
  lib,
  kotlin,
  jdk21_headless,
  zip,
  appimageContents,
  hostSources,
  spotifySources,
  providersSource,
  version,
}:

# ALPHA-1 predates desktop session recovery. Compile the affected JVM code
# against that release's own dependencies, then replace it in the extracted
# AppImage. No Gradle cache, generated build output, credentials or local JDK
# enters the package. Remove this overlay when the pinned release contains it.
assert lib.assertMsg (kotlin.version == "2.4.21")
  "Valen ALPHA-1's recovery overlay requires Kotlin 2.4.21; review it when updating nixpkgs.";
runCommand "valen-${version}-contents" {
  nativeBuildInputs = [ kotlin jdk21_headless zip ];
  JAVA_HOME = jdk21_headless;
} ''
  cp -r ${appimageContents} "$out"
  chmod -R u+w "$out"

  # These are the two application jars named by the release's launcher config.
  # Keep their filenames so the original JVM, native launcher and UI are reused.
  hostJar="$out/lib/app/desktop-ee935fd6ede771be17a4b339c0a5b9.jar"
  appJar="$out/lib/app/app-desktop-e3d54524fdb03d3b9ee3ea77874f4e9.jar"
  test -f "$hostJar"
  test -f "$appJar"

  classpath=""
  for dependency in "$out"/lib/app/*.jar; do
    if [ "$dependency" != "$hostJar" ]; then
      classpath="$classpath''${classpath:+:}$dependency"
    fi
  done

  mapfile -d "" hostFiles < <(find ${hostSources} -name '*.kt' -print0)
  hostFiles+=("${spotifySources}/AdBlock.kt")
  kotlinc -jvm-target 21 -no-stdlib -no-reflect \
    -module-name Valen.host_desktop -classpath "$classpath" \
    -d host-classes "''${hostFiles[@]}"
  jar --create --file "$hostJar" --date=1980-01-01T00:00:02Z -C host-classes .

  # Providers.kt has no Compose or serialization compiler-plugin dependency.
  # Preserve the basename: Kotlin derives ProvidersKt from it, whereas a single
  # Nix store file has a hash-prefixed name that would break the release's calls.
  cp ${providersSource} Providers.kt
  kotlinc -jvm-target 21 -no-stdlib -no-reflect \
    -module-name Valen_app-desktop -classpath "$hostJar:$classpath" \
    -d provider-classes Providers.kt
  zip -q -d "$appJar" \
    'app/valen/desktop/ProvidersKt*.class' \
    'app/valen/desktop/ProviderSet*.class' \
    'app/valen/desktop/RetryingProvider*.class' \
    'app/valen/desktop/Builder*.class'
  # Preserve the release's module metadata for its other, unchanged source files.
  jar --update --file "$appJar" --date=1980-01-01T00:00:02Z -C provider-classes app

  # Fail the build if the recovery classes were not included in the actual jars.
  jar --list --file "$hostJar" | grep -Fx 'app/valen/host/desktop/BrowserSession.class'
  javap -classpath "$hostJar" app.valen.host.desktop.DesktopHosts \
    | grep -F 'ensureBackground('
  javap -classpath "$appJar" app.valen.desktop.RetryingProvider > /dev/null
  javap -classpath "$appJar" app.valen.desktop.ProvidersKt \
    | grep -F 'buildProviders('
  printf '%s\n' '${version}' > "$out/valen-package-version"
''
