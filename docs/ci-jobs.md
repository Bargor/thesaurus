## CI jobs

Android CI runs lint, JVM tests, Firebase/tooling checks, release assembly and developer verification independently. A dedicated build job publishes debug and instrumentation APKs once. Three API 31 jobs download those APKs and run AndroidJUnitRunner shards; API 37 runs the same three shards daily or with run_api_37.

Each instrumentation job owns one Android emulator and its Firebase emulators. No Gradle build runs alongside them. The existing watchdog still bounds installation and execution and captures logs and visual evidence. Each shard publishes instrumentation output and JUnit XML in app/build/ci-instrumentation. A crashed, incomplete or empty run fails, as do reported test failures even when adb exits zero.

APK builds use a 4 GiB Gradle heap and at most two workers. Other Gradle jobs use a 2 GiB heap; JVM test processes on CI use one fork with a 768 MiB heap. These are starting limits to tune using CI measurements.

Branch protection must require the new job checks instead of Verify (API 31). Keep API 37 optional because it is skipped on ordinary PRs. Sharding reduces elapsed test time, but duplicated emulator startup can increase total runner minutes.
