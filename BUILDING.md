# Building SaveWatch

## Windows: one-click build

1. Download the repository using **Code → Download ZIP**, or clone it with Git.
2. Extract the repository.
3. Double-click `build.bat`.

The first run checks for a full JDK 21 installation. It searches:

- `JAVA_HOME`
- Java available through `PATH`
- Common Eclipse Adoptium, Microsoft, and Oracle Java directories
- Common Prism Launcher runtime directories
- The project-local `.tools/jdk-21-temurin` directory

Only a JDK whose Java and compiler both report major version 21 is accepted. Java 17, 22, 25, and a JRE without `javac` are not used for this build.

If no suitable JDK is found, the builder downloads the latest Eclipse Temurin JDK 21 archive from the official Adoptium API. It verifies the archive against the SHA-256 checksum returned by that API before extracting it to `.tools/jdk-21-temurin`. No administrator rights are required, and the system-wide Java configuration is not changed.

The included Gradle Wrapper downloads the project's pinned Gradle distribution and the required Minecraft/NeoForge development dependencies automatically.

The resulting mod is placed in:

```
build\libs\savewatch-0.1.0.jar
```

The exact filename may change when the mod version changes. Sources JARs are not the file to install in Minecraft.

## Optional Gradle arguments

You can pass Gradle tasks to the builder:

```bat
build.bat clean build
build.bat tasks
build.bat runClient
```

The builder always adds `--no-daemon` for a predictable one-shot build.

## Requirements and downloads

- Windows PowerShell 5.1 (included with supported Windows versions)
- Internet access on the first build, for JDK/Gradle/Minecraft dependencies as needed
- Enough disk space for the JDK, Gradle caches, and NeoForge development dependencies

The builder uses the official Eclipse Adoptium distribution and the repository's Gradle Wrapper. It does not install software system-wide, edit the Windows registry, or require administrator privileges.

## Troubleshooting

- If the JDK download fails, check internet access to `api.adoptium.net` and the Adoptium package host, then run `build.bat` again.
- If Gradle dependency resolution fails, check access to the Gradle distribution service, Maven Central, and NeoForge's Maven repository.
- Build output and errors are printed in the console. The detailed Gradle output is retained in the terminal.
- To use a different JDK 21, set `JAVA_HOME` before launching `build.bat`.
